package io.github.susongyan.redisops.platform.application.operation;
import io.github.susongyan.redisops.platform.application.audit.AuditDetails;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.operation.*;
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
    private final org.springframework.transaction.support.TransactionTemplate independent;
    @org.springframework.beans.factory.annotation.Autowired
    public RedisOperationService(OperationRepository repo, ClusterRepository clusters, RedisOperationPort redis,
            ObjectMapper json, AuditRepository audits,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.repo = repo;
        this.clusters = clusters;
        this.redis = redis;
        this.json = json;
        this.audits = audits;
        independent = new org.springframework.transaction.support.TransactionTemplate(manager);
        independent
                .setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public RedisOperationService(OperationRepository repo, ClusterRepository clusters, RedisOperationPort redis,
            ObjectMapper json, AuditRepository audits) {
        this.repo = repo;
        this.clusters = clusters;
        this.redis = redis;
        this.json = json;
        this.audits = audits;
        this.independent = null;
    }
    public List<OperationCommand> commands(boolean writes, boolean includeDisabled) {
        return repo.commands(writes, includeDisabled);
    }
    @Transactional
    public OperationCommand updateCommand(long id, long version, boolean enabled, String riskLevel,
            String approvalPolicy, int maxValueBytes, List<String> allowedDataTypes, String missingKeyPolicy,
            boolean blockedByDefault, String changeReason, String operator) {
        repo.lockCatalog();
        var current = repo.commands(true, true).stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("COMMAND_NOT_FOUND"));
        if (maxValueBytes < 0 || maxValueBytes > 1024 * 1024)
            throw new IllegalArgumentException("INVALID_VALUE_LIMIT");
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(riskLevel)
                || !Set.of("DIRECT", "CONFIRM", "DANGER_CONFIRM", "INHERIT", "DENY").contains(approvalPolicy)
                || !Set.of("CREATE_ALLOWED", "EXISTING_REQUIRED").contains(missingKeyPolicy))
            throw new IllegalArgumentException("INVALID_COMMAND_POLICY");
        if (enabled && (blockedByDefault || "WILDCARD".equals(current.nodeKind()))
                && !("HIGH".equals(riskLevel) && "DANGER_CONFIRM".equals(approvalPolicy)))
            throw new IllegalArgumentException("DANGEROUS_COMMAND_REQUIRES_APPROVAL");
        String types = toJson(
                allowedDataTypes == null || allowedDataTypes.isEmpty() ? List.of("key") : allowedDataTypes);
        var updated = new OperationCommand(current.id(), current.commandName(), current.commandVersion(),
                current.category(), current.accessMode(), riskLevel, enabled, current.parameterSchemaJson(),
                current.keyPosition(), current.routingPolicy(), approvalPolicy, maxValueBytes, types, missingKeyPolicy,
                blockedByDefault, changeReason, operator, version, current.createdAt(), Instant.now(),
                null, current.nodeKind(), current.parentId());
        if (!repo.updateCommand(updated, version))
            throw new IllegalArgumentException("VERSION_CONFLICT");
        audits.append(operator, "OPERATION_COMMAND_UPDATE", "OPERATION_COMMAND", Long.toString(id), "SUCCESS",
                AuditDetails.commandChange(current, updated));
        return repo.commands(true, true).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }
    public Preview preview(long clusterId, int db, String command, List<String> args) {
        if (db < 0 || db > 15)
            throw new IllegalArgumentException("INVALID_DATABASE");
        var c = clusters.findById(clusterId).orElseThrow(() -> new IllegalArgumentException("CLUSTER_NOT_FOUND"));
        if (!"ACTIVE".equals(c.status().name()))
            throw new IllegalArgumentException("CLUSTER_NOT_ACTIVE");
        if (c.mode().name().equals("CLUSTER") && db != 0)
            throw new IllegalArgumentException("CLUSTER_DB_MUST_BE_ZERO");
        var decision = definition(command, args);
        var d = decision.definition();
        if (c.mode().name().equals("CLUSTER") && d.keyPosition() == 0)
            throw new IllegalArgumentException("CLUSTER_NODE_TARGET_REQUIRED");
        validate(d, args);
        validateType(clusterId, db, d, args);
        return new Preview(d.commandName(), d.riskLevel(), decision.action(),
                null, "READY", d.id(), d.version(), decision.fingerprint(), c.version());
    }
    public RedisOperation request(long clusterId, int db, String command, List<String> args, String operator) {
        return request(clusterId, db, command, args, operator, UUID.randomUUID().toString());
    }
    public RedisOperation request(long clusterId, int db, String command, List<String> args, String operator,
            String key) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
        String number = "OP-" + digest(operator + "\n" + key).substring(0, 40);
        String argumentDigest = digest(toJson(args));
        var created = persisted(() -> {
            repo.lockCatalog();
            var existing = repo.findByNumber(number);
            if (existing.isPresent()) {
                var x = existing.get();
                if (x.clusterId() != clusterId || x.databaseNo() != db || !x.commandName().equalsIgnoreCase(command)
                        || !x.argumentsDigest().equals(argumentDigest) || !x.operatorName().equals(operator))
                    throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
                return new Created(x, false);
            }
            var p = preview(clusterId, db, command, args);
            var d = definition(command, args).definition();
            var x = new RedisOperation(null, number, clusterId, db, command.toUpperCase(Locale.ROOT), "[]",
                    argumentDigest,
                    d.accessMode(), d.riskLevel(), "DIRECT".equals(p.action()) ? "READY" : "PENDING_CONFIRMATION",
                    json.valueToTree(p).toString(), null, operator, null, null, 0, Instant.now(), Instant.now());
            return new Created(repo.save(x), true);
        });
        return created.fresh() && "READY".equals(created.operation().status())
                ? execute(created.operation(), operator, args)
                : created.operation();
    }
    private record Created(RedisOperation operation, boolean fresh) {
    }
    private <T> T persisted(java.util.function.Supplier<T> action) {
        return independent == null ? action.get() : independent.execute(status -> action.get());
    }
    @Transactional
    public RedisOperation confirm(long id, long version, String operator) {
        return confirm(id, version, operator, null, null);
    }
    @Transactional
    public RedisOperation confirm(long id, long version, String operator, String targetName, String reason) {
        var x = get(id);
        requireOwner(x, operator);
        if (!"PENDING_CONFIRMATION".equals(x.status()))
            throw new IllegalArgumentException("INVALID_OPERATION_STATE");
        try {
            if ("DANGER_CONFIRM".equals(json.readTree(x.previewJson()).path("action").asText())) {
                var cluster = clusters.findById(x.clusterId()).orElseThrow();
                if (!Objects.equals(cluster.name(), targetName) || reason == null || reason.isBlank()
                        || reason.length() > 512)
                    throw new IllegalArgumentException("DANGEROUS_CONFIRMATION_REQUIRED");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("INVALID_OPERATION_PREVIEW");
        }
        audits.append(operator, "REDIS_OPERATION_CONFIRM", "REDIS_OPERATION", Long.toString(id), "SUCCESS",
                "{\"clusterId\":" + x.clusterId() + ",\"databaseNo\":" + x.databaseNo() + ",\"command\":\""
                        + x.commandName() + "\"}");
        return update(x, "APPROVED", operator, reason, null, version);
    }
    @Transactional
    public RedisOperation approve(long id, long version, String operator, String note) {
        throw new IllegalArgumentException("APPROVAL_REPLACED_BY_CONFIRMATION");
    }
    public RedisOperation execute(long id, long version, String operator, List<String> args) {
        var x = get(id);
        requireOwner(x, operator);
        if (!Set.of("APPROVED", "CONFIRMED").contains(x.status()))
            throw new IllegalArgumentException("OPERATION_NOT_APPROVED");
        if (x.version() != version)
            throw new IllegalArgumentException("VERSION_CONFLICT");
        if (!digest(toJson(args)).equals(x.argumentsDigest()))
            throw new IllegalArgumentException("ARGUMENT_DIGEST_MISMATCH");
        return execute(x, operator, args);
    }
    public RedisOperation cancel(long id, long version, String operator) {
        var x = get(id);
        requireOwner(x, operator);
        if (!Set.of("READY", "PENDING_CONFIRMATION", "PENDING_APPROVAL", "APPROVED").contains(x.status()))
            throw new IllegalArgumentException("INVALID_OPERATION_STATE");
        return update(x, "CANCELLED", operator, null, null, version);
    }
    public RedisOperation get(long id) {
        return repo.find(id).orElseThrow(() -> new IllegalArgumentException("OPERATION_NOT_FOUND"));
    }
    public List<RedisOperation> list(int page, int size) {
        return repo.list(Math.max(1, page), Math.max(1, Math.min(100, size)));
    }
    private RedisOperation execute(RedisOperation x, String operator, List<String> args) {
        final RedisOperation requested = x;
        final OperationCommand[] selected = new OperationCommand[1];
        x = persisted(() -> {
            repo.lockCatalog();
            var fresh = get(requested.id());
            if (fresh.version() != requested.version()
                    || !Set.of("READY", "APPROVED", "CONFIRMED").contains(fresh.status()))
                throw new IllegalArgumentException("OPERATION_ALREADY_CLAIMED");
            selected[0] = validateExecution(fresh, args);
            return update(fresh, "EXECUTING", operator, null, null, fresh.version());
        });
        RedisOperationPort.OperationResult r;
        try {
            r = redis.execute(x.clusterId(), x.databaseNo(), x.commandName(), args, selected[0].keyPosition());
        } catch (Exception failure) {
            r = new RedisOperationPort.OperationResult(false, null, null, 0, -1, "EXECUTION_UNCERTAIN");
        }
        String result = toJson(r);
        var summary = new RedisOperationPort.OperationResult(r.success(), r.type(), null, r.valueLength(),
                r.ttlSeconds(), r.errorCode());
        String status = r.success() ? "SUCCEEDED" : "UNKNOWN";
        final RedisOperation claimed = x;
        var saved = persisted(() -> {
            var done = update(claimed, status, operator, null, toJson(summary), claimed.version());
            audits.append(operator, "REDIS_OPERATION_EXECUTE", "REDIS_OPERATION", Long.toString(done.id()), status,
                    "{\"clusterId\":" + done.clusterId() + ",\"databaseNo\":" + done.databaseNo() + ",\"command\":\""
                            + done.commandName() + "\"}");
            return done;
        });
        return new RedisOperation(saved.id(), saved.operationNo(), saved.clusterId(), saved.databaseNo(),
                saved.commandName(),
                saved.argumentsJson(), saved.argumentsDigest(), saved.accessMode(), saved.riskLevel(), saved.status(),
                saved.previewJson(), saved.approvalNote(), saved.operatorName(), saved.approverName(), result,
                saved.version(),
                saved.createdAt(), saved.updatedAt(), saved.operatorSnapshot(), saved.approverSnapshot(),
                saved.executorName(), saved.executorSnapshot());
    }
    private OperationCommand validateExecution(RedisOperation x, List<String> args) {
        var decision = definition(x.commandName(), args);
        var d = decision.definition();
        try {
            var saved = json.readTree(x.previewJson());
            if (saved == null || !saved.has("definitionVersion")
                    || saved.path("definitionVersion").asLong() != d.version()
                    || saved.path("definitionId").asLong() != d.id()
                    || !decision.fingerprint().equals(saved.path("policyFingerprint").asText())
                    || !decision.action().equals(saved.path("action").asText())
                    || !saved.has("clusterVersion") || saved.path("clusterVersion").asLong() != clusters
                            .findById(x.clusterId()).orElseThrow().version())
                throw new IllegalArgumentException("COMMAND_CHANGED_RECREATE_OPERATION");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("COMMAND_CHANGED_RECREATE_OPERATION");
        }
        preview(x.clusterId(), x.databaseNo(), x.commandName(), args);
        return d;
    }
    private RedisOperation update(RedisOperation x, String status, String operator, String note, String result,
            long version) {
        var y = new RedisOperation(x.id(), x.operationNo(), x.clusterId(), x.databaseNo(), x.commandName(),
                x.argumentsJson(), x.argumentsDigest(), x.accessMode(), x.riskLevel(), status, x.previewJson(),
                note == null ? x.approvalNote() : note, x.operatorName(),
                "APPROVED".equals(status) ? operator : x.approverName(), result == null ? x.resultJson() : result,
                version, x.createdAt(), Instant.now(), x.operatorSnapshot(), x.approverSnapshot(),
                result != null ? operator : null, null);
        if (!repo.update(y, version))
            throw new IllegalArgumentException("VERSION_CONFLICT");
        return get(x.id());
    }
    private CommandTreePolicy.Decision definition(String name, List<String> args) {
        return CommandTreePolicy.resolve(repo.commands(true, true), name, args);
    }
    private static void requireOwner(RedisOperation x, String operator) {
        if (!Objects.equals(x.operatorName(), operator))
            throw new IllegalArgumentException("OPERATION_OWNER_REQUIRED");
    }
    private void validate(OperationCommand d, List<String> a) {
        if (!Set.of("SINGLE_KEY", "NO_KEY").contains(d.routingPolicy()))
            throw new IllegalArgumentException("ROUTING_NOT_SUPPORTED");
        var fields = CommandCatalogService.parameters(json, d.parameterSchemaJson());
        if (a == null || a.size() > 128 || fields.size() > 32)
            throw new IllegalArgumentException("INVALID_ARGUMENTS");
        boolean variadic = !fields.isEmpty() && Boolean.TRUE.equals(fields.get(fields.size() - 1).get("variadic"));
        long required = fields.stream().filter(f -> Boolean.TRUE.equals(f.get("required"))).count();
        if (a.size() < required || (!variadic && a.size() > fields.size()) || (fields.isEmpty() && !a.isEmpty()))
            throw new IllegalArgumentException("INVALID_ARGUMENTS");
        int bytes = 0;
        for (int i = 0; i < a.size(); i++) {
            var f = fields.get(Math.min(i, fields.size() - 1));
            String value = a.get(i);
            if (value == null)
                throw new IllegalArgumentException("INVALID_ARGUMENTS");
            int length = value.getBytes(StandardCharsets.UTF_8).length;
            bytes += length;
            if (bytes > 1048576 || length > 1048576)
                throw new IllegalArgumentException("ARGUMENTS_TOO_LARGE");
            if ("VALUE".equals(f.get("type")) && length > d.maxValueBytes())
                throw new IllegalArgumentException("VALUE_TOO_LARGE");
            String compared = i == 0 && "SUBCOMMAND".equals(d.nodeKind()) ? value.toUpperCase(Locale.ROOT) : value;
            if (f.containsKey("literal") && !Objects.equals(f.get("literal"), compared))
                throw new IllegalArgumentException("ARGUMENT_LITERAL_MISMATCH");
            try {
                if ("INTEGER".equals(f.get("type")))
                    Long.parseLong(value);
                if ("DECIMAL".equals(f.get("type")) && !Double.isFinite(Double.parseDouble(value)))
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("INVALID_NUMERIC_ARGUMENT");
            }
        }
        if (d.keyPosition() < 0 || d.keyPosition() > a.size()
                || ("SINGLE_KEY".equals(d.routingPolicy()) && d.keyPosition() == 0))
            throw new IllegalArgumentException("INVALID_KEY_POSITION");
    }
    private void validateType(long clusterId, int db, OperationCommand d, List<String> args) {
        if (d.keyPosition() == 0)
            return;
        try {
            var allowed = json.readValue(d.allowedDataTypesJson(), new TypeReference<List<String>>() {
            });
            if (allowed.contains("key"))
                return;
            var typeResult = redis.execute(clusterId, db, "TYPE", List.of(args.get(d.keyPosition() - 1)));
            if (!typeResult.success())
                throw new IllegalArgumentException("TYPE_CHECK_FAILED");
            String observed = typeResult.value();
            if (observed == null || observed.isBlank())
                observed = "none";

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
    public record Preview(String command, String riskLevel, String action, String key, String status, Long definitionId,
            long definitionVersion, String policyFingerprint, long clusterVersion) {
    }
}
