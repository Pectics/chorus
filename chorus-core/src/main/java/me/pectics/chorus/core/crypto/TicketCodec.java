/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.4, 3.7, and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/crypto/TicketCompression.java
 * sync: Keep the wire schema, strict parsing, and issuer-bound algorithm allowlist aligned.
 * @endcontext
 */
package me.pectics.chorus.core.crypto;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.impl.BaseJWSProvider;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ProfileProperty;
import me.pectics.chorus.core.model.TicketClaims;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.TrustedIssuerRegistry;

/** Bounded version-1 JWS with optional compression. Only approved local keys are used. */
public final class TicketCodec {
    private static final JOSEObjectType TYPE = new JOSEObjectType("chorus-ticket+jwt");
    private static final Set<String> CLAIM_FIELDS =
            Set.of("version", "jti", "iss", "aud", "iat", "nbf", "exp", "identity", "extensions");
    private final int maxPayloadBytes;
    private final int maxPropertyBytes;
    private final TicketCompression compression;

    /**
     * Limits are explicit configuration, not product defaults. Property size counts UTF-8 bytes.
     */
    public TicketCodec(int maxPayloadBytes, int maxPropertyBytes) {
        this(maxPayloadBytes, maxPropertyBytes, TicketCompression.NONE);
    }

    /**
     * Compression applies to the complete signed JWS. The payload limit includes its outer frame;
     * the decompressed JWS is limited to 64 KiB. Decoding accepts every supported encoding,
     * regardless of the outgoing compression choice. Instances are safe for concurrent calls.
     */
    public TicketCodec(int maxPayloadBytes, int maxPropertyBytes, TicketCompression compression) {
        if (maxPayloadBytes < 1
                || maxPayloadBytes > 5120
                || maxPropertyBytes < 1
                || maxPropertyBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("Invalid ticket or property size limit");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxPropertyBytes = maxPropertyBytes;
        this.compression = Objects.requireNonNull(compression, "compression");
    }

    public byte[] encode(TicketClaims claims, TicketSigner signer) {
        if (claims.version() != 1) {
            throw reject(TicketError.UNSUPPORTED_VERSION);
        }
        checkProperties(claims.identity());
        var identity = claims.identity();
        var properties = new ArrayList<Map<String, Object>>();
        for (var property : identity.properties()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("name", property.name());
            value.put("value", property.value());
            if (property.signature() != null) {
                value.put("signature", property.signature());
            }
            properties.add(value);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("version", claims.version());
        body.put("jti", claims.jti());
        body.put("iss", claims.issuerNodeId());
        body.put("aud", claims.targetNodeId());
        body.put("iat", seconds(claims.issuedAt()));
        body.put("nbf", seconds(claims.notBefore()));
        body.put("exp", seconds(claims.expiresAt()));
        body.put(
                "identity",
                Map.of(
                        "username",
                        identity.username(),
                        "authType",
                        identity.authType().name(),
                        "authProvider",
                        identity.authProvider(),
                        "providerSubject",
                        identity.providerSubject(),
                        "sourceUuid",
                        identity.sourceUuid().toString(),
                        "properties",
                        properties));
        body.put("extensions", claims.extensions());
        var jws =
                new JWSObject(
                        new JWSHeader.Builder(new JWSAlgorithm(signer.algorithm().jwsName()))
                                .type(TYPE)
                                .build(),
                        new Payload(body));
        try {
            jws.sign(new Signer(signer));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Ticket signing failed", e);
        }
        byte[] result = compression.encode(jws.serialize().getBytes(StandardCharsets.US_ASCII));
        if (result.length > maxPayloadBytes) {
            throw reject(TicketError.TICKET_TOO_LARGE);
        }
        return result;
    }

    /** Captures one approved binding for both signature and subsequent policy checks. */
    public record DecodedTicket(TicketClaims claims, TrustedIssuer issuer) {}

    public DecodedTicket decode(byte[] bytes, TrustedIssuerRegistry issuers) {
        if (bytes == null || bytes.length == 0) {
            throw reject(TicketError.COOKIE_MISSING);
        }
        if (bytes.length > maxPayloadBytes) {
            throw reject(TicketError.TICKET_TOO_LARGE);
        }
        try {
            bytes = TicketCompression.decode(bytes);
            for (byte b : bytes) {
                if (b < 0) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
            }
            String compact = new String(bytes, StandardCharsets.US_ASCII);
            String[] parts = compact.split("\\.", -1);
            if (parts.length != 3) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            for (String part : parts) {
                if (!part.matches("[A-Za-z0-9_-]+")
                        || !Base64URL.encode(new Base64URL(part).decode())
                                .toString()
                                .equals(part)) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
            }
            var header = parseObject(new Base64URL(parts[0]).decode());
            var body = parseObject(new Base64URL(parts[1]).decode());
            SignatureAlgorithm algorithm;
            try {
                algorithm = SignatureAlgorithm.fromJwsName((String) header.get("alg"));
            } catch (IllegalArgumentException | ClassCastException unsupported) {
                throw reject(TicketError.UNSUPPORTED_ALGORITHM);
            }
            if (!header.keySet().equals(Set.of("alg", "typ"))
                    || !TYPE.toString().equals(header.get("typ"))) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            String issuerId = string(body, "iss");
            var issuer =
                    issuers.findByIssuer(issuerId)
                            .orElseThrow(() -> reject(TicketError.UNKNOWN_ISSUER));
            if (!issuer.enabled()) {
                throw reject(TicketError.UNTRUSTED_KEY);
            }
            if (issuer.algorithm() != algorithm) {
                throw reject(TicketError.UNSUPPORTED_ALGORITHM);
            }
            var jws = JWSObject.parse(compact);
            boolean verified = false;
            for (var verifier : issuer.verifiers()) {
                if (jws.verify(new Verifier(verifier))) {
                    verified = true;
                    break;
                }
            }
            if (!verified) {
                throw reject(TicketError.INVALID_SIGNATURE);
            }
            if (!body.keySet().equals(CLAIM_FIELDS)) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            long version = integer(body, "version");
            if (version != 1) {
                throw reject(TicketError.UNSUPPORTED_VERSION);
            }
            var rawIdentity = object(body.get("identity"));
            if (!rawIdentity
                    .keySet()
                    .equals(
                            Set.of(
                                    "username",
                                    "authType",
                                    "authProvider",
                                    "providerSubject",
                                    "sourceUuid",
                                    "properties"))) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            if (!(rawIdentity.get("properties") instanceof List<?> rawProperties)
                    || rawProperties.size() > 1
                    || !object(body.get("extensions")).isEmpty()) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            var properties = new ArrayList<ProfileProperty>();
            for (var rawProperty : rawProperties) {
                var property = object(rawProperty);
                if (!property.keySet().equals(Set.of("name", "value"))
                        && !property.keySet().equals(Set.of("name", "value", "signature"))) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
                properties.add(
                        new ProfileProperty(
                                string(property, "name"),
                                string(property, "value"),
                                property.containsKey("signature")
                                        ? string(property, "signature")
                                        : null));
            }
            String uuid = string(rawIdentity, "sourceUuid");
            if (!UUID.fromString(uuid).toString().equals(uuid)) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            var identity =
                    new IdentityClaim(
                            string(rawIdentity, "username"),
                            AuthType.valueOf(string(rawIdentity, "authType")),
                            string(rawIdentity, "authProvider"),
                            string(rawIdentity, "providerSubject"),
                            UUID.fromString(uuid),
                            properties);
            checkProperties(identity);
            return new DecodedTicket(
                    new TicketClaims(
                            1,
                            string(body, "jti"),
                            issuerId,
                            string(body, "aud"),
                            Instant.ofEpochSecond(integer(body, "iat")),
                            Instant.ofEpochSecond(integer(body, "nbf")),
                            Instant.ofEpochSecond(integer(body, "exp")),
                            identity,
                            Map.of()),
                    issuer);
        } catch (TicketRejectedException e) {
            throw e;
        } catch (java.text.ParseException
                | com.nimbusds.jose.JOSEException
                | IllegalArgumentException
                | java.time.DateTimeException
                | java.nio.charset.CharacterCodingException e) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
    }

    private void checkProperties(IdentityClaim identity) {
        long bytes = 0;
        for (var property : identity.properties()) {
            bytes += property.name().getBytes(StandardCharsets.UTF_8).length;
            bytes += property.value().getBytes(StandardCharsets.UTF_8).length;
            if (property.signature() != null) {
                bytes += property.signature().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (bytes > maxPropertyBytes) {
            throw reject(TicketError.TICKET_TOO_LARGE);
        }
    }

    private static long seconds(Instant instant) {
        if (instant.getNano() != 0) {
            throw new IllegalArgumentException("Ticket timestamps require whole seconds");
        }
        return instant.getEpochSecond();
    }

    private static Map<String, Object> parseObject(byte[] bytes)
            throws java.text.ParseException, java.nio.charset.CharacterCodingException {
        String json =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        checkStructure(json);
        return JSONObjectUtils.parse(json, TicketCompression.MAX_DECOMPRESSED_BYTES);
    }

    private static final class Scope {
        private final boolean object;
        private final Set<String> keys = new HashSet<>();
        private boolean expectingKey;

        private Scope(boolean object) {
            this.object = object;
            this.expectingKey = object;
        }
    }

    // Bound nesting and reject duplicate names at every object depth before library parsing.
    private static void checkStructure(String json) throws java.text.ParseException {
        var scopes = new ArrayDeque<Scope>();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                int start = i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                if (i >= json.length()) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
                if (!scopes.isEmpty() && scopes.peek().object && scopes.peek().expectingKey) {
                    String key =
                            JSONObjectUtils.parse("{" + json.substring(start, i + 1) + ":0}")
                                    .keySet()
                                    .iterator()
                                    .next();
                    if (!scopes.peek().keys.add(key)) {
                        throw reject(TicketError.MALFORMED_TICKET);
                    }
                    scopes.peek().expectingKey = false;
                }
            } else if (c == '{' || c == '[') {
                scopes.push(new Scope(c == '{'));
                if (scopes.size() > 8) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
            } else if (c == '}' || c == ']') {
                if (scopes.isEmpty() || scopes.pop().object != (c == '}')) {
                    throw reject(TicketError.MALFORMED_TICKET);
                }
            } else if (c == ',' && !scopes.isEmpty() && scopes.peek().object) {
                scopes.peek().expectingKey = true;
            } else if (c == 'n') {
                // No version-1 field uses JSON null; optional signatures are omitted.
                throw reject(TicketError.MALFORMED_TICKET);
            }
        }
        if (!scopes.isEmpty()) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(k -> !(k instanceof String))) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof String value) || value.isEmpty()) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        return value;
    }

    private static long integer(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof Long value)) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        return value;
    }

    private static TicketRejectedException reject(TicketError code) {
        return new TicketRejectedException(code);
    }

    private static final class Signer extends BaseJWSProvider implements JWSSigner {
        private final TicketSigner signer;

        private Signer(TicketSigner signer) {
            super(Set.of(new JWSAlgorithm(signer.algorithm().jwsName())));
            this.signer = signer;
        }

        @Override
        public Base64URL sign(JWSHeader header, byte[] input) {
            return Base64URL.encode(signer.sign(input));
        }
    }

    private static final class Verifier extends BaseJWSProvider implements JWSVerifier {
        private final TicketVerifier verifier;

        private Verifier(TicketVerifier verifier) {
            super(Set.of(new JWSAlgorithm(verifier.algorithm().jwsName())));
            this.verifier = verifier;
        }

        @Override
        public boolean verify(JWSHeader header, byte[] input, Base64URL signature) {
            return verifier.verify(input, signature.decode());
        }
    }
}
