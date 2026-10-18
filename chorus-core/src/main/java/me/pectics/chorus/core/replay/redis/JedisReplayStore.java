/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/RedisReplayGuard.java
 * sync: Register with SET NX PX, consume with DEL, and never retry an unknown command outcome.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.UUID;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.SslVerifyMode;
import redis.clients.jedis.params.SetParams;

/**
 * Blocking standalone-Redis storage owned by one guard. Opening validates connectivity, TLS, and
 * ACL; every command timeout or connection loss is an unknown outcome and is never retried.
 */
final class JedisReplayStore implements RedisReplayGuard.Store {
    private final RedisClient client;

    JedisReplayStore(ReplayGuardConfiguration.Redis settings) throws IOException {
        RedisClient created = null;
        try {
            var config =
                    DefaultJedisClientConfig.builder()
                            .connectionTimeoutMillis((int) settings.timeouts().connect().toMillis())
                            .socketTimeoutMillis((int) settings.timeouts().command().toMillis())
                            .database(settings.database())
                            .user(settings.username())
                            .password(readPassword(settings.passwordPath()))
                            .clientSetInfoConfig(ClientSetInfoConfig.DISABLED);
            if (settings.tls().mode() == ReplayGuardConfiguration.TlsMode.VERIFY_FULL) {
                config.sslOptions(sslOptions(settings.tls().caCert()));
            }
            var pool = new ConnectionPoolConfig();
            pool.setMaxTotal(settings.pool().size());
            pool.setMaxIdle(settings.pool().size());
            pool.setMinIdle(1);
            pool.setBlockWhenExhausted(true);
            pool.setMaxWait(Duration.ofMillis(settings.timeouts().acquire().toMillis()));
            pool.setTestOnBorrow(false);
            pool.setTestWhileIdle(true);
            created =
                    RedisClient.builder()
                            .hostAndPort(settings.host(), settings.port())
                            .clientConfig(config.build())
                            .poolConfig(pool)
                            .build();
            created.ping();
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (created != null) {
                try {
                    created.close();
                } catch (RuntimeException close) {
                    failure.addSuppressed(close);
                }
            }
            // Driver/configuration exceptions may contain connection details; never attach them.
            throw new IOException(
                    "Unable to initialize Redis connection settings or credential/CA files");
        }
        this.client = created;
    }

    @Override
    public void register(String issuerNodeId, String jti, long ttlMillis) {
        String reply =
                client.set(
                        ReplayKey.encode(issuerNodeId, jti),
                        "1",
                        SetParams.setParams().nx().px(ttlMillis));
        if (!"OK".equals(reply)) {
            throw new IllegalStateException("Redis replay registration already exists");
        }
    }

    @Override
    public ReplayDecision consume(String issuerNodeId, String jti) {
        try {
            return client.del(ReplayKey.encode(issuerNodeId, jti)) == 1
                    ? ReplayDecision.ACCEPTED
                    : ReplayDecision.REPLAYED;
        } catch (RuntimeException failure) {
            return ReplayDecision.UNAVAILABLE;
        }
    }

    private static SslOptions sslOptions(Path caCert) throws IOException, GeneralSecurityException {
        var builder = SslOptions.builder().sslVerifyMode(SslVerifyMode.FULL);
        if (caCert == null) {
            return builder.build();
        }
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        int index = 0;
        try (var input = Files.newInputStream(caCert)) {
            for (var certificate :
                    CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                store.setCertificateEntry("ca-" + index++, certificate);
            }
        }
        if (index == 0) {
            throw new GeneralSecurityException("Empty CA bundle");
        }
        char[] password = UUID.randomUUID().toString().toCharArray();
        var encoded = new ByteArrayOutputStream();
        store.store(encoded, password);
        byte[] bytes = encoded.toByteArray();
        return builder.truststore(() -> new ByteArrayInputStream(bytes), password)
                .trustStoreType("PKCS12")
                .build();
    }

    private static String readPassword(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(65537);
        }
        if (bytes.length > 65536) {
            throw new IOException("Redis password file exceeds size limit");
        }
        String value =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    public void close() {
        client.close();
    }
}
