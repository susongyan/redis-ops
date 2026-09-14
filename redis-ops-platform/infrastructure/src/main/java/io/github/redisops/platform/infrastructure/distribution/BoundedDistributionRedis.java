package io.github.redisops.platform.infrastructure.distribution;

import io.github.redisops.platform.domain.asset.*;
import io.github.redisops.platform.domain.distribution.DistributionScanPort;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class BoundedDistributionRedis implements DistributionScanPort {
    private final RedisConnectionProfileProvider profiles;
    private final DistributionReceiveLimits limits;
    public BoundedDistributionRedis(RedisConnectionProfileProvider profiles) {
        this(profiles, DistributionReceiveLimits.defaults());
    }
    @Autowired
    public BoundedDistributionRedis(RedisConnectionProfileProvider profiles, DistributionReceiveLimits limits) {
        this.profiles = profiles;
        this.limits = limits;
    }
    @Override
    public Topology topology(long clusterId, int database) {
        return topology(clusterId, database, Long.MAX_VALUE);
    }
    @Override
    public Topology topology(long clusterId, int database, long deadlineNanos) {
        try (var profile = profiles.get(clusterId)) {
            if (database < 0 || database > 15 || profile.mode() == ClusterMode.CLUSTER && database != 0)
                throw new IllegalArgumentException("INVALID_DISTRIBUTION_DATABASE");
            String endpoint = profile.seedEndpoints().get(0);
            if (profile.mode() == ClusterMode.SENTINEL) {
                try (Wire sentinel = new Wire(endpoint, profile, false, 0, deadlineNanos)) {
                    Object result = sentinel.command("SENTINEL", "get-master-addr-by-name",
                            profile.sentinelMasterName());
                    if (!(result instanceof List<?> values) || values.size() != 2)
                        throw new IOException("SCAN_SENTINEL_UNAVAILABLE");
                    String host = text(values.get(0));
                    endpoint = (host.contains(":") ? "[" + host + "]" : host) + ":" + text(values.get(1));
                }
            }
            try (Wire wire = new Wire(endpoint, profile, profile.mode() != ClusterMode.CLUSTER, database,
                    deadlineNanos)) {
                if (profile.mode() != ClusterMode.CLUSTER) {
                    String info = text(wire.command("INFO", "server"));
                    String runId = Arrays.stream(info.split("\\r?\\n")).filter(s -> s.startsWith("run_id:")).findFirst()
                            .orElseThrow();
                    return new Topology(hash(endpoint + runId), List.of(new Shard("default", endpoint)));
                }
                String nodes = text(wire.command("CLUSTER", "NODES"));
                List<Shard> shards = new ArrayList<>();
                List<String> signatures = new ArrayList<>();
                BitSet coveredSlots = new BitSet(16384);
                for (String line : nodes.split("\\r?\\n")) {
                    String[] fields = line.trim().split(" +");
                    if (fields.length < 8)
                        throw new IOException("SCAN_INVALID_TOPOLOGY");
                    Set<String> flags = new HashSet<>(Arrays.asList(fields[2].split(",")));
                    if (flags.contains("fail") || flags.contains("fail?") || flags.contains("handshake")
                            || flags.contains("noaddr") || !"connected".equals(fields[7]))
                        throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                    if (!flags.contains("master"))
                        continue;
                    if (Arrays.stream(fields).skip(8).anyMatch(s -> s.startsWith("[")))
                        throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                    for (int i = 8; i < fields.length; i++) {
                        if (!fields[i].matches("[0-9]{1,5}(?:-[0-9]{1,5})?"))
                            throw new IOException("SCAN_INVALID_TOPOLOGY");
                        String[] range = fields[i].split("-");
                        int from = Integer.parseInt(range[0]), to = Integer.parseInt(range[range.length - 1]);
                        if (from < 0 || to < from || to >= 16384
                                || coveredSlots.nextSetBit(from) >= 0 && coveredSlots.nextSetBit(from) <= to)
                            throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                        coveredSlots.set(from, to + 1);
                    }
                    String address = fields[1].split("[@,]", 2)[0];
                    shards.add(new Shard(fields[0], address));
                    signatures.add(fields[0] + "|" + address + "|"
                            + String.join(" ", Arrays.copyOfRange(fields, 8, fields.length)));
                    if (shards.size() > limits.shards())
                        throw new IOException("SCAN_SHARD_LIMIT");
                }
                shards.sort(Comparator.comparing(Shard::id));
                if (coveredSlots.cardinality() != 16384)
                    throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                Collections.sort(signatures);
                return new Topology(hash(String.join("\n", signatures)), shards);
            }
        } catch (Exception e) {
            if (System.nanoTime() >= deadlineNanos)
                throw new IllegalStateException("SCAN_BUDGET_LIMIT");
            throw safe(e);
        }
    }
    @Override
    public Page scan(long clusterId, int database, Shard shard, String cursor, int count) {
        return scan(clusterId, database, shard, cursor, count, Long.MAX_VALUE);
    }
    @Override
    public Page scan(long clusterId, int database, Shard shard, String cursor, int count, long deadlineNanos) {
        if (!cursor.matches("[0-9]{1,20}") || count < 1 || count > 200)
            throw new IllegalArgumentException("INVALID_SCAN_ARGUMENT");
        try (var profile = profiles.get(clusterId);
                Wire wire = new Wire(shard.endpoint(), profile, true, database, deadlineNanos)) {
            wire.send("SCAN", cursor, "COUNT", String.valueOf(Math.min(count, limits.scanCount())));
            return wire.reader().scan();
        } catch (Exception e) {
            if (System.nanoTime() >= deadlineNanos)
                throw new IllegalStateException("SCAN_BUDGET_LIMIT");
            throw safe(e);
        }
    }
    private static IllegalStateException safe(Exception error) {
        String code = error instanceof SocketTimeoutException ? "SCAN_TIMEOUT" : error.getMessage();
        if (code == null || !code.matches("SCAN_[A-Z_]+"))
            code = "SCAN_CONNECTION_FAILED";
        return new IllegalStateException(code); // Do not attach exceptions carrying addresses, replies or credentials.
    }
    private static String text(Object value) throws IOException {
        if (value instanceof byte[] bytes)
            return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof String s)
            return s;
        throw new IOException("SCAN_INVALID_METADATA");
    }
    private static String hash(String text) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private final class Wire implements AutoCloseable {
        private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "distribution-socket-deadline");
            t.setDaemon(true);
            return t;
        });
        static {
            DEADLINES.setRemoveOnCancelPolicy(true);
        }
        private final Socket socket = new Socket();
        private InputStream input;
        private OutputStream output;
        private long deadline;
        private int timeoutMillis;
        private final long outerDeadline;
        private ScheduledFuture<?> closeDeadline;
        private ScheduledFuture<?> cancellation;
        Wire(String endpoint, RedisConnectionProfile profile, boolean master, int database, long outerDeadline)
                throws IOException {
            this.outerDeadline = outerDeadline;
            try {
                Thread caller = Thread.currentThread();
                cancellation = DEADLINES.scheduleWithFixedDelay(() -> {
                    if (caller.isInterrupted()) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                }, 20, 20, TimeUnit.MILLISECONDS);
                armDeadline(limits.connectMillis());
                URI uri = URI.create("redis://" + endpoint);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() < 1 || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !uri.getPath().isEmpty())
                    throw new IOException("SCAN_INVALID_ENDPOINT");
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), limits.connectMillis());
                input = new BufferedInputStream(new FilterInputStream(socket.getInputStream()) {
                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        try {
                            return in.read(b, off, len);
                        } catch (IOException e) {
                            if (System.nanoTime() >= deadline)
                                throw new SocketTimeoutException("SCAN_TIMEOUT");
                            throw e;
                        }
                    }
                }, 8192);
                output = socket.getOutputStream();
                if (profile.password() != null && profile.password().length > 0) {
                    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(profile.password()));
                    byte[] password = new byte[encoded.remaining()];
                    encoded.get(password);
                    try {
                        List<byte[]> args = new ArrayList<>();
                        args.add("AUTH".getBytes(StandardCharsets.US_ASCII));
                        if (profile.username() != null && !profile.username().isBlank())
                            args.add(profile.username().getBytes(StandardCharsets.UTF_8));
                        args.add(password);
                        write(args);
                        if (!"OK".equals(reader().metadata()))
                            throw new IOException("SCAN_AUTH_FAILED");
                    } finally {
                        Arrays.fill(password, (byte) 0);
                        if (encoded.hasArray())
                            Arrays.fill(encoded.array(), (byte) 0);
                    }
                }
                if (master) {
                    Object role = command("ROLE");
                    if (!(role instanceof List<?> values) || values.isEmpty() || !"master".equals(text(values.get(0))))
                        throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                    if (database != 0 && !"OK".equals(command("SELECT", String.valueOf(database))))
                        throw new IOException("SCAN_DATABASE_UNAVAILABLE");
                }
            } catch (Exception e) {
                close();
                if (e instanceof IOException io)
                    throw io;
                throw new IOException("SCAN_CONNECTION_FAILED");
            }
        }
        Object command(String... values) throws IOException {
            send(values);
            return reader().metadata();
        }
        void send(String... values) throws IOException {
            if (!Set.of("SCAN", "ROLE", "INFO", "CLUSTER", "SENTINEL", "SELECT").contains(values[0]))
                throw new IOException("SCAN_COMMAND_DENIED");
            List<byte[]> args = Arrays.stream(values).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
            write(args);
        }
        void write(List<byte[]> args) throws IOException {
            armDeadline(limits.commandMillis());
            socket.setSoTimeout(3000);
            timeoutMillis = 3000;
            output.write(("*" + args.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (byte[] arg : args) {
                if (arg.length > 4096)
                    throw new IOException("SCAN_ARGUMENT_LIMIT");
                output.write(("$" + arg.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(arg);
                output.write(new byte[]{13, 10});
            }
            output.flush();
        }
        private void armDeadline(int millis) throws IOException {
            if (closeDeadline != null)
                closeDeadline.cancel(false);
            deadline = Math.min(outerDeadline, System.nanoTime() + millis * 1_000_000L);
            if (deadline <= System.nanoTime())
                throw new SocketTimeoutException("SCAN_TIMEOUT");
            closeDeadline = DEADLINES.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        BoundedResp reader() {
            return new BoundedResp(input, () -> {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("SCAN_TIMEOUT");
                try {
                    int timeout = (int) Math.max(1, Math.min(3000, remaining / 1_000_000));
                    if (timeout != timeoutMillis) {
                        socket.setSoTimeout(timeout);
                        timeoutMillis = timeout;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("SCAN_CONNECTION_FAILED");
                }
            }, limits);
        }
        @Override
        public void close() throws IOException {
            if (cancellation != null)
                cancellation.cancel(false);
            if (closeDeadline != null)
                closeDeadline.cancel(false);
            socket.close();
        }
    }
}
