package io.github.susongyan.redisops.platform.infrastructure.distribution;

import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Selection is per primary shard; unavailable replicas only cause a startup fallback. */
final class ReplicaScanSelection {
    interface Reader {
        Object read(String endpoint, String... command) throws Exception;
    }
    static Topology select(String endpoint, boolean cluster, Reader reader) throws Exception {
        if (!cluster) {
            var role = list(reader.read(endpoint, "ROLE"));
            if (!"master".equals(text(role.get(0))))
                throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
            Shard primary = target("default", endpoint, "master", "default", reader);
            var candidates = new ArrayList<String>();
            for (Object item : list(role.get(2))) {
                var replica = list(item);
                candidates.add(address(text(replica.get(0)), text(replica.get(1))));
            }
            Collections.sort(candidates);
            Shard selected = primary;
            for (String candidate : candidates) {
                var found = replica("default", candidate, "default", reader);
                if (found != null) {
                    selected = found;
                    break;
                }
            }
            return topology(List.of(primary.toString(), selected.toString()), List.of(selected));
        }
        String nodes = text(reader.read(endpoint, "CLUSTER", "NODES"));
        List<String[]> parsed = new ArrayList<>();
        for (String line : nodes.split("\\r?\\n")) {
            if (line.isBlank())
                continue;
            String[] f = line.trim().split(" +");
            if (f.length < 8)
                throw new IOException("SCAN_INVALID_TOPOLOGY");
            parsed.add(f);
        }
        parsed.sort(Comparator.comparing(f -> f[0]));
        List<Shard> shards = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        BitSet slots = new BitSet(16384);
        for (var f : parsed) {
            if (!flags(f).contains("master"))
                continue;
            if (!healthy(f) || Arrays.stream(f).skip(8).anyMatch(s -> s.startsWith("[")))
                throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
            if (f.length == 8)
                continue; // no slots, no data shard
            for (int i = 8; i < f.length; i++) {
                if (!f[i].matches("[0-9]{1,5}(?:-[0-9]{1,5})?"))
                    throw new IOException("SCAN_INVALID_TOPOLOGY");
                String[] range = f[i].split("-");
                int from = Integer.parseInt(range[0]), to = Integer.parseInt(range[range.length - 1]);
                int overlap = slots.nextSetBit(from);
                if (to < from || to >= 16384 || overlap >= 0 && overlap <= to)
                    throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
                slots.set(from, to + 1);
            }
            Shard primary = target(f[0], endpoint(f), "master", f[0], reader);
            Shard selected = primary;
            for (var replica : parsed) {
                if (!f[0].equals(replica[3]) || !healthy(replica) || !flags(replica).contains("slave"))
                    continue;
                Shard found = replica(f[0], endpoint(replica), replica[0], reader);
                if (found != null) {
                    selected = found;
                    break;
                }
            }
            shards.add(selected);
            signatures.add(primary + "|" + String.join(" ", Arrays.copyOfRange(f, 8, f.length)) + "|" + selected);
            if (shards.size() > 256)
                throw new IOException("SCAN_SHARD_LIMIT");
        }
        if (slots.cardinality() != 16384)
            throw new IOException("SCAN_TOPOLOGY_UNSTABLE");
        return topology(signatures, shards);
    }
    private static Shard replica(String id, String endpoint, String nodeId, Reader reader) {
        try {
            var role = list(reader.read(endpoint, "ROLE"));
            if (!"slave".equals(text(role.get(0))) || role.size() < 4 || !"connected".equals(text(role.get(3))))
                return null;
            var info = info(text(reader.read(endpoint, "INFO", "replication")));
            if (!"up".equals(info.get("master_link_status")) || !"0".equals(info.get("master_sync_in_progress")))
                return null;
            return target(id, endpoint, "replica", nodeId, reader);
        } catch (Exception unavailable) {
            return null;
        }
    }
    private static Shard target(String id, String endpoint, String role, String nodeId, Reader reader)
            throws Exception {
        String runId = info(text(reader.read(endpoint, "INFO", "server"))).get("run_id");
        if (runId == null || !runId.matches("[a-fA-F0-9]{40}"))
            throw new IOException("SCAN_INVALID_METADATA");
        return new Shard(id, endpoint, role, runId, nodeId);
    }
    static Map<String, String> info(String text) {
        Map<String, String> result = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int i = line.indexOf(':');
            if (i > 0)
                result.put(line.substring(0, i), line.substring(i + 1));
        }
        return result;
    }
    private static Topology topology(List<String> signatures, List<Shard> shards) throws Exception {
        signatures = new ArrayList<>(signatures);
        Collections.sort(signatures);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", signatures).getBytes(StandardCharsets.UTF_8)));
        return new Topology(hash, shards);
    }
    static String text(Object value) throws IOException {
        if (value instanceof byte[] b)
            return new String(b, StandardCharsets.UTF_8);
        if (value instanceof String || value instanceof Number)
            return value.toString();
        throw new IOException("SCAN_INVALID_METADATA");
    }
    private static List<?> list(Object value) throws IOException {
        if (value instanceof List<?> l && !l.isEmpty())
            return l;
        if (value instanceof List<?> l)
            return l;
        throw new IOException("SCAN_INVALID_METADATA");
    }
    private static Set<String> flags(String[] f) {
        return new HashSet<>(Arrays.asList(f[2].split(",")));
    }
    private static boolean healthy(String[] f) {
        var flags = flags(f);
        return "connected".equals(f[7]) && Collections.disjoint(flags, Set.of("fail", "fail?", "handshake", "noaddr"));
    }
    private static String endpoint(String[] f) {
        return f[1].split("[@,]", 2)[0];
    }
    private static String address(String host, String port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
