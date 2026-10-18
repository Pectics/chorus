/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/redisIntegrationTest/java/me/pectics/chorus/core/replay/redis/RedisReplayGuardIntegrationTest.java
 * sync: Drop an executed command's response to verify unknown outcomes without client retries.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Sequential RESP test proxy; drops one reply only after Redis has executed the command. */
final class RedisReplyDroppingProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final Thread worker;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> drop = new AtomicReference<>();
    private final AtomicInteger sets = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private volatile boolean closed;
    private volatile IOException failure;

    RedisReplyDroppingProxy(int upstreamPort) throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        worker = new Thread(() -> forward(upstreamPort), "chorus-test-redis-proxy");
        worker.setDaemon(true);
        worker.start();
    }

    int port() {
        return listener.getLocalPort();
    }

    void dropNext(String command) {
        drop.set(command);
    }

    int setCalls() {
        return sets.get();
    }

    int deleteCalls() {
        return deletes.get();
    }

    private void forward(int upstreamPort) {
        while (!closed) {
            try (var client = listener.accept();
                    var upstream = new Socket("localhost", upstreamPort)) {
                sockets.add(client);
                sockets.add(upstream);
                try {
                    if (closed) return;
                    exchange(client, upstream);
                } finally {
                    sockets.remove(client);
                    sockets.remove(upstream);
                }
            } catch (EOFException disconnected) {
                // A pool may close an idle connection before the proxy itself closes.
            } catch (IOException error) {
                if (!closed) failure = error;
                return;
            }
        }
    }

    private void exchange(Socket client, Socket upstream) throws IOException {
        while (!closed) {
            byte[] request;
            try {
                request = frame(client.getInputStream());
            } catch (SocketException disconnected) {
                // Jedis may reset a pooled socket during normal client shutdown.
                return;
            }
            String command = new String(request, StandardCharsets.US_ASCII).split("\r\n", 4)[2];
            if (command.equals("SET")) sets.incrementAndGet();
            if (command.equals("DEL")) deletes.incrementAndGet();
            upstream.getOutputStream().write(request);
            upstream.getOutputStream().flush();
            byte[] reply = frame(upstream.getInputStream());
            String expected = drop.get();
            if (command.equals(expected) && drop.compareAndSet(expected, null)) return;
            client.getOutputStream().write(reply);
            client.getOutputStream().flush();
        }
    }

    private static byte[] frame(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream();
        readFrame(input, output);
        return output.toByteArray();
    }

    private static void readFrame(InputStream input, ByteArrayOutputStream output)
            throws IOException {
        int marker = input.read();
        if (marker < 0) throw new EOFException();
        output.write(marker);
        var line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            output.write(value);
            if (previous == '\r' && value == '\n') break;
            line.write(value);
            previous = value;
        }
        boolean bulk = marker == '$' || marker == '=' || marker == '!';
        boolean map = marker == '%' || marker == '|';
        if (!bulk && !map && marker != '*' && marker != '~' && marker != '>') return;
        int length = Integer.parseInt(line.toString(StandardCharsets.US_ASCII).strip());
        if (length < 0) return;
        if (bulk) {
            byte[] payload = input.readNBytes(length + 2);
            if (payload.length != length + 2) throw new EOFException();
            output.writeBytes(payload);
        } else {
            for (int i = 0; i < (map ? length * 2 : length); i++) readFrame(input, output);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException problem = failure;
        try {
            listener.close();
        } catch (IOException error) {
            problem = error;
        }
        for (var socket : sockets) {
            try {
                socket.close();
            } catch (IOException error) {
                if (problem == null) problem = error;
                else problem.addSuppressed(error);
            }
        }
        try {
            worker.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted closing the Redis test proxy", interrupted);
        }
        if (worker.isAlive()) throw new IOException("Redis test proxy did not stop");
        if (problem != null) throw problem;
    }
}
