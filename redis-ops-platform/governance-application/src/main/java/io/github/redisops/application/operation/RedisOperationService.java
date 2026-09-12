package io.github.redisops.application.operation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.ClusterRepository;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.operation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedisOperationService {
    private final OperationRepository repo;
    private final ClusterRepository clusters;
    private final RedisOperationPort redis;
    private final ObjectMapper json;
    private final AuditRepository audits;
    public RedisOperationService(OperationRepository repo, ClusterRepository clusters, RedisOperationPort redis,
            ObjectMapper json, AuditRepository audits) {
        this.repo = repo;
        this.clusters = clusters;
        this.redis = redis;
        this.json = json;
        this.audits = audits;
    }
    public List<OperationCommand> commands(boolean writes, boolean includeDisabled) {
        return repo.commands(writes, includeDisabled);
    }
    @Transactional
    public OperationCommand updateCommand(long id, long version, boolean enabled, String riskLevel,
            String approvalPolicy, int maxValueBytes, List<String> allowedDataTypes, String missingKeyPolicy,
            boolean blockedByDefault, String changeReason, String operator) {
        var current = repo.commands(true, true).stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("COMMAND_NOT_FOUND"));
        if (maxValueBytes < 0 || maxValueBytes > 1024 * 1024)
            throw new IllegalArgumentException("INVALID_VALUE_LIMIT");
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(riskLevel)
                || !Set.of("DIRECT", "CONFIRM", "APPROVAL").contains(approvalPolicy)
                || !Set.of("CREATE_ALLOWED", "EXISTING_REQUIRED").contains(missingKeyPolicy))
            throw new IllegalArgumentException("INVALID_COMMAND_POLICY");
        if (enabled && blockedByDefault && !("HIGH".equals(riskLevel) && "APPROVAL".equals(approvalPolicy)))
            throw new IllegalArgumentException("DANGEROUS_COMMAND_REQUIRES_APPROVAL");
        String types = toJson(
                allowedDataTypes == null || allowedDataTypes.isEmpty() ? List.of("key") : allowedDataTypes);
        var updated = new OperationCommand(current.id(), current.commandName(), current.commandVersion(),
                current.category(), current.accessMode(), riskLevel, enabled, current.parameterSchemaJson(),
                current.keyPosition(), current.routingPolicy(), approvalPolicy, maxValueBytes, types, missingKeyPolicy,
                blockedByDefault, changeReason, operator, version, current.createdAt(), Instant.now());
        if (!repo.updateCommand(updated, version))
            throw new IllegalArgumentException("VERSION_CONFLICT");
        audits.append(operator, "OPERATION_COMMAND_UPDATE", "OPERATION_COMMAND", Long.toString(id), "SUCCESS");
        return repo.commands(true, true).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }
    public Preview preview(long clusterId, int db, String command, List<String> args) {
        var c = clusters.findById(clusterId).orElseThrow(() -> new IllegalArgumentException("CLUSTER_NOT_FOUND"));
        if (c.mode().name().equals("CLUSTER") && db != 0)
            throw new IllegalArgumentException("CLUSTER_DB_MUST_BE_ZERO");
        var d = definition(command);
        validate(d, args);
        validateType(clusterId, db, d, args);
        String action = d.approvalPolicy();
        return new Preview(d.commandName(), d.riskLevel(), action,
                args.isEmpty() ? null : args.get(d.keyPosition() - 1), "READY");
    }
    @Transactional
    public RedisOperation request(long clusterId, int db, String command, List<String> args, String operator) {
        var p = preview(clusterId, db, command, args);
        var d = definition(command);
        String a = toJson(args), digest = digest(a);
        String status = "READ".equals(d.accessMode())
                ? "EXECUTING"
                : ("APPROVAL".equals(d.approvalPolicy()) ? "PENDING_APPROVAL" : "PENDING_CONFIRMATION");
        var x = new RedisOperation(null, "OP-" + UUID.randomUUID(), clusterId, db, d.commandName(), a, digest,
                d.accessMode(), d.riskLevel(), status, json.valueToTree(p).toString(), null, operator, null, null, 0,
                Instant.now(), Instant.now());
        x = repo.save(x);
        if ("EXECUTING".equals(status))
            return execute(x, operator, args);
        return x;
    }
    @Transactional
    public RedisOperation confirm(long id, long version, String operator) {
        var x = get(id);
        if (!"PENDING_CONFIRMATION".equals(x.status()))
            throw new IllegalArgumentException("INVALID_OPERATION_STATE");
        return update(x, "APPROVED", operator, null, null, version);
    }
    @Transactional
    public RedisOperation approve(long id, long version, String operator, String note) {
        var x = get(id);
        if (!"PENDING_APPROVAL".equals(x.status()))
            throw new IllegalArgumentException("INVALID_OPERATION_STATE");
        return update(x, "APPROVED", operator, note, null, version);
    }
    @Transactional
    public RedisOperation execute(long id, long version, String operator, List<String> args) {
        var x = get(id);
        if (!Set.of("APPROVED", "PENDING_CONFIRMATION", "CONFIRMED").contains(x.status()))
            throw new IllegalArgumentException("OPERATION_NOT_APPROVED");
        if (!digest(toJson(args)).equals(x.argumentsDigest()))
            throw new IllegalArgumentException("ARGUMENT_DIGEST_MISMATCH");
        return execute(x, operator, args);
    }
    public RedisOperation cancel(long id, long version, String operator) {
        return update(get(id), "CANCELLED", operator, null, null, version);
    }
    public RedisOperation get(long id) {
        return repo.find(id).orElseThrow(() -> new IllegalArgumentException("OPERATION_NOT_FOUND"));
    }
    public List<RedisOperation> list(int page, int size) {
        return repo.list(page, size);
    }
    private RedisOperation execute(RedisOperation x, String operator, List<String> args) {
        var r = redis.execute(x.clusterId(), x.databaseNo(), x.commandName(), args);
        String result;
        try {
            result = json.writeValueAsString(r);
        } catch (Exception e) {
            result = "{}";
        }
        return update(x, r.success() ? "SUCCEEDED" : "FAILED", operator, null, result, x.version());
    }
    private RedisOperation update(RedisOperation x, String status, String operator, String note, String result,
            long version) {
        var y = new RedisOperation(x.id(), x.operationNo(), x.clusterId(), x.databaseNo(), x.commandName(),
                x.argumentsJson(), x.argumentsDigest(), x.accessMode(), x.riskLevel(), status, x.previewJson(),
                note == null ? x.approvalNote() : note, x.operatorName(),
                "APPROVED".equals(status) ? operator : x.approverName(), result == null ? x.resultJson() : result,
                version, x.createdAt(), Instant.now());
        if (!repo.update(y, version))
            throw new IllegalArgumentException("VERSION_CONFLICT");
        return get(x.id());
    }
    private OperationCommand definition(String name) {
        return repo.command(name.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("COMMAND_NOT_ALLOWED"));
    }
    private void validate(OperationCommand d, List<String> a) {
        if (!"SINGLE_KEY".equals(d.routingPolicy()))
            throw new IllegalArgumentException("ROUTING_NOT_SUPPORTED");
        try {
            var fields = json.readValue(d.parameterSchemaJson(), new TypeReference<List<Map<String, Object>>>() {
            });
            if (a.size() != fields.size())
                throw new IllegalArgumentException("INVALID_ARGUMENTS");
            for (int i = 0; i < fields.size(); i++) {
                if (Boolean.TRUE.equals(fields.get(i).get("required")) && a.get(i).isBlank())
                    throw new IllegalArgumentException("INVALID_ARGUMENTS");
                if ("VALUE".equals(fields.get(i).get("type"))
                        && a.get(i).getBytes(StandardCharsets.UTF_8).length > d.maxValueBytes())
                    throw new IllegalArgumentException("VALUE_TOO_LARGE");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException x)
                throw x;
            throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        }
    }
    private void validateType(long clusterId, int db, OperationCommand d, List<String> args) {
        try {
            String observed = redis.execute(clusterId, db, "TYPE", List.of(args.get(d.keyPosition() - 1))).value();
            if (observed == null || observed.isBlank())
                observed = "none";
            var allowed = json.readValue(d.allowedDataTypesJson(), new TypeReference<List<String>>() {
            });
            // Missing keys follow Redis command semantics (nil/0 or create); they are
            // not a policy error. A generic KEY constraint accepts every existing type.
            if ("none".equals(observed))
                return;
            if (!allowed.contains("key") && !allowed.contains(observed))
                throw new IllegalArgumentException("KEY_TYPE_NOT_ALLOWED");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("TYPE_CHECK_FAILED");
        }
    }
    private String toJson(Object x) {
        try {
            return json.writeValueAsString(x);
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_ARGUMENTS");
        }
    }
    private static String digest(String x) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    public record Preview(String command, String riskLevel, String action, String key, String status) {
    }
}
