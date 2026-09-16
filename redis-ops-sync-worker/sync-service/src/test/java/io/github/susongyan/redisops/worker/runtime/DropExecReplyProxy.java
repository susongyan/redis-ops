package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Single-connection fault injector for an isolated Redis, never a production endpoint. */
final class DropExecReplyProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<?> result;
    DropExecReplyProxy(int targetPort, int dropExec, boolean beforeSend) throws IOException {
        this(targetPort, dropExec, beforeSend, true, () -> {
        });
    }
    DropExecReplyProxy(int targetPort, int selectedExec, Runnable beforeExec) throws IOException {
        this(targetPort, selectedExec, false, false, beforeExec);
    }
    private DropExecReplyProxy(int targetPort, int dropExec, boolean beforeSend, boolean dropReply,
            Runnable beforeExec) throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        result = executor.submit(() -> {
            try (var inbound = listener.accept(); var upstream = new Socket("127.0.0.1", targetPort)) {
                inbound.setSoTimeout(10000);
                upstream.setSoTimeout(10000);
                var downstream = new RespCodec(inbound.getInputStream(), inbound.getOutputStream());
                var target = new RespCodec(upstream.getInputStream(), upstream.getOutputStream());
                int execs = 0;
                while (true) {
                    RespValue.Array request = (RespValue.Array) downstream.read();
                    byte[][] args = request.values().stream().map(v -> ((RespValue.Bulk) v).value())
                            .toArray(byte[][]::new);
                    boolean cut = new String(args[0], StandardCharsets.US_ASCII).equals("EXEC") && ++execs == dropExec;
                    if (cut)
                        beforeExec.run();
                    if (cut && beforeSend)
                        return;
                    target.writeCommand(args);
                    RespValue reply = target.read();
                    if (cut && dropReply)
                        return;
                    write(reply, inbound.getOutputStream());
                    inbound.getOutputStream().flush();
                }
            } catch (EOFException expected) {
                // Caller closed a normally completed connection.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
    int port() {
        return listener.getLocalPort();
    }
    private static void write(RespValue value, OutputStream out) throws IOException {
        if (value == RespValue.NullValue.INSTANCE) {
            out.write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (value instanceof RespValue.Bulk bulk) {
            line(out, "$" + bulk.value().length);
            out.write(bulk.value());
            line(out, "");
        } else if (value instanceof RespValue.Simple simple)
            line(out, "+" + simple.value());
        else if (value instanceof RespValue.Error error)
            line(out, "-" + error.value());
        else if (value instanceof RespValue.IntegerValue integer)
            line(out, ":" + integer.value());
        else if (value instanceof RespValue.Array array) {
            line(out, "*" + array.values().size());
            for (var item : array.values())
                write(item, out);
        } else
            throw new IOException("unexpected fixture reply");
    }
    private static void line(OutputStream out, String value) throws IOException {
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    public void close() throws Exception {
        try {
            listener.close();
            result.get(12, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
