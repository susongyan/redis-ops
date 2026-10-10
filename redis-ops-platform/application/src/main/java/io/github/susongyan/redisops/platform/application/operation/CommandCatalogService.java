package io.github.susongyan.redisops.platform.application.operation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.application.audit.AuditDetails;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.operation.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandCatalogService {
    private final OperationRepository repo;
    private final AuditRepository audits;
    private final ObjectMapper json;
    public CommandCatalogService(OperationRepository repo, AuditRepository audits, ObjectMapper json) {
        this.repo = repo;
        this.audits = audits;
        this.json = json;
    }
    public OperationCommand get(long id) {
        return repo.commands(true, true).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("COMMAND_NOT_FOUND"));
    }
    @Transactional
    public OperationCommand define(Long id, long version, Definition input, String operator) {
        var nodes = repo.commands(true, true);
        if ("CATEGORY".equals(input.nodeKind())) {
            if (input.commandName() == null || input.commandName().isBlank()
                    || input.commandName().trim().length() > 32)
                throw new IllegalArgumentException("INVALID_GROUP_NAME");
            input = new Definition(input.commandName().trim(), "CUSTOM", "READ", "LOW", true, "[]", 0,
                    "CONTAINER", "DIRECT", 4096, input.changeReason(), "CATEGORY", null);
        }
        if (id == null && nodes.size() >= 4096)
            throw new IllegalArgumentException("COMMAND_TREE_CAPACITY");
        if (input.accessMode() == null || input.riskLevel() == null || input.approvalPolicy() == null
                || input.routingPolicy() == null)
            throw new IllegalArgumentException("INVALID_COMMAND_DEFINITION");
        if (input.nodeKind() == null || !Set.of("CATEGORY", "COMMAND", "SUBCOMMAND").contains(input.nodeKind()))
            throw new IllegalArgumentException("INVALID_NODE_KIND");
        String name = input.commandName() == null ? "" : input.commandName().trim();
        if (!"CATEGORY".equals(input.nodeKind()))
            name = name.toUpperCase(Locale.ROOT);
        if ((!"CATEGORY".equals(input.nodeKind())
                && !name.matches("[A-Z][A-Z0-9_.-]{0,31}(?: (?:[A-Z][A-Z0-9_.-]{0,31}|\\*))?"))
                || input.category() == null
                || input.category().isBlank()
                || input.category().length() > 32
                || !Set.of("READ", "WRITE", "MANAGE").contains(input.accessMode())
                || !Set.of("LOW", "MEDIUM", "HIGH").contains(input.riskLevel())
                || !Set.of("DIRECT", "CONFIRM", "DANGER_CONFIRM", "DENY").contains(input.approvalPolicy())
                || !Set.of("SINGLE_KEY", "NO_KEY", "CONTAINER").contains(input.routingPolicy())
                || input.maxValueBytes() < 0
                || input.maxValueBytes() > 1048576)
            throw new IllegalArgumentException("INVALID_COMMAND_DEFINITION");
        if (input.changeReason() == null || input.changeReason().isBlank() || input.changeReason().length() > 512)
            throw new IllegalArgumentException("CHANGE_REASON_REQUIRED");
        if (name.isBlank() || name.length() > 96)
            throw new IllegalArgumentException("INVALID_COMMAND_NAME");
        if (!"CATEGORY".equals(input.nodeKind())
                && !input.nodeKind().equals(name.contains(" ") ? "SUBCOMMAND" : "COMMAND"))
            throw new IllegalArgumentException("INVALID_NODE_KIND");
        var fields = parameters(json, input.parameterSchemaJson());
        boolean container = Set.of("CATEGORY", "FAMILY").contains(input.nodeKind());
        if ("SUBCOMMAND".equals(input.nodeKind()) && (fields.isEmpty()
                || !Objects.equals(fields.get(0).get("literal"), name.substring(name.indexOf(' ') + 1))
                || !Boolean.TRUE.equals(fields.get(0).get("required"))))
            throw new IllegalArgumentException("SUBCOMMAND_LITERAL_REQUIRED");
        if ("WILDCARD".equals(input.nodeKind()) && (fields.isEmpty()
                || !"TEXT".equals(fields.get(0).get("type")) || fields.get(0).containsKey("literal")
                || !Boolean.TRUE.equals(fields.get(0).get("required"))))
            throw new IllegalArgumentException("SUBCOMMAND_ARGUMENT_REQUIRED");
        if (container != "CONTAINER".equals(input.routingPolicy()) || (container && !fields.isEmpty()))
            throw new IllegalArgumentException("INVALID_CONTAINER_DEFINITION");
        if ("WILDCARD".equals(input.nodeKind()) && (!"MANAGE".equals(input.accessMode())
                || !"HIGH".equals(input.riskLevel())
                || !Set.of("DANGER_CONFIRM", "DENY").contains(input.approvalPolicy())
                || !"NO_KEY".equals(input.routingPolicy())))
            throw new IllegalArgumentException("WILDCARD_REQUIRES_MANAGEMENT_CONFIRMATION");
        if (fields.size() > 32 || (!"SINGLE_KEY".equals(input.routingPolicy())
                ? input.keyPosition() != 0
                : input.keyPosition() < 1 || input.keyPosition() > fields.size()))
            throw new IllegalArgumentException("INVALID_KEY_POSITION");
        boolean optional = false;
        for (int i = 0; i < fields.size(); i++) {
            var f = fields.get(i);
            if (!(f.get("name") instanceof String label) || label.isBlank() || label.length() > 64
                    || !(f.get("type") instanceof String type)
                    || !Set.of("REDIS_KEY", "TEXT", "VALUE", "INTEGER", "DECIMAL").contains(type))
                throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
            if ((f.containsKey("required") && !(f.get("required") instanceof Boolean))
                    || (f.containsKey("variadic") && !(f.get("variadic") instanceof Boolean)))
                throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
            if (optional && Boolean.TRUE.equals(f.get("required")))
                throw new IllegalArgumentException("OPTIONAL_ARGUMENTS_MUST_BE_LAST");
            optional |= !Boolean.TRUE.equals(f.get("required"));
            if (Boolean.TRUE.equals(f.get("variadic")) && i != fields.size() - 1)
                throw new IllegalArgumentException("VARIADIC_ARGUMENT_MUST_BE_LAST");
            if ("REDIS_KEY".equals(f.get("type"))
                    && (i + 1 != input.keyPosition() || Boolean.TRUE.equals(f.get("variadic"))))
                throw new IllegalArgumentException("MULTI_KEY_ROUTING_NOT_SUPPORTED");
            if (f.containsKey("literal") && !(f.get("literal") instanceof String))
                throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        }
        if (input.keyPosition() > 0 && (!"REDIS_KEY".equals(fields.get(input.keyPosition() - 1).get("type"))
                || !Boolean.TRUE.equals(fields.get(input.keyPosition() - 1).get("required"))))
            throw new IllegalArgumentException("INVALID_KEY_POSITION");
        var current = id == null ? null : get(id);
        if (current != null && !"CATEGORY".equals(current.nodeKind()) && !current.commandName().equals(name))
            throw new IllegalArgumentException("COMMAND_NAME_IMMUTABLE");
        if (current != null && !current.nodeKind().equals(input.nodeKind()))
            throw new IllegalArgumentException("NODE_KIND_IMMUTABLE");
        for (var node : nodes)
            if (!Objects.equals(node.id(), id) && node.commandName().equalsIgnoreCase(name))
                throw new IllegalArgumentException("COMMAND_ALREADY_EXISTS");
        String schema;
        try {
            schema = json.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        }
        String category = "CUSTOM";
        if (!"CATEGORY".equals(input.nodeKind())) {
            category = "未分组";
            if (input.parentId() != null) {
                category = repo.lockGroup(input.parentId())
                        .orElseThrow(() -> new IllegalArgumentException("CATEGORY_PARENT_REQUIRED"));
            }
        }
        String action = input.approvalPolicy();
        if (!"DENY".equals(action)) {
            if ("HIGH".equals(input.riskLevel()) || "MANAGE".equals(input.accessMode()))
                action = "DANGER_CONFIRM";
            else if ("WRITE".equals(input.accessMode()) && "DIRECT".equals(action))
                action = "CONFIRM";
        }
        var value = new OperationCommand(id, name, current == null ? 1 : current.commandVersion(), category,
                input.accessMode(), input.riskLevel(), "CATEGORY".equals(input.nodeKind()) || input.enabled(), schema,
                input.keyPosition(),
                input.routingPolicy(), action, input.maxValueBytes(), "[\"key\"]", "CREATE_ALLOWED",
                false, input.changeReason(), operator, version, current == null ? Instant.now() : current.createdAt(),
                Instant.now(), null, input.nodeKind(), input.parentId());
        CommandTreePolicy.validatePlacement(value, nodes);
        OperationCommand result;
        if (id == null)
            result = repo.createCommand(value);
        else {
            if (!repo.updateCommand(value, version))
                throw new IllegalArgumentException("VERSION_CONFLICT");
            result = get(id);
        }
        if ("CATEGORY".equals(result.nodeKind()))
            repo.relabelGroup(result.id(), result.commandName());
        audits.append(operator, id == null ? "OPERATION_COMMAND_CREATE" : "OPERATION_COMMAND_UPDATE",
                "OPERATION_COMMAND", result.id().toString(), "SUCCESS", AuditDetails.commandChange(current, result));
        return result;
    }
    @Transactional
    public void delete(long id, long version, String reason, String operator) {
        if (reason == null || reason.isBlank() || reason.length() > 512)
            throw new IllegalArgumentException("CHANGE_REASON_REQUIRED");
        var current = get(id);
        if (!repo.deleteCommand(id, version, reason, operator))
            throw new IllegalArgumentException("VERSION_CONFLICT");
        if ("CATEGORY".equals(current.nodeKind()))
            repo.detachGroup(id);
        audits.append(operator, "OPERATION_COMMAND_DELETE", "OPERATION_COMMAND", Long.toString(id), "SUCCESS",
                AuditDetails.change("删除命令配置", AuditDetails.command(current), Map.of(), reason));
    }
    static List<Map<String, Object>> parameters(ObjectMapper json, String schema) {
        if (schema == null || schema.length() > 16384)
            throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        try {
            var fields = json.readValue(schema, new TypeReference<List<Map<String, Object>>>() {
            });
            if (fields == null || fields.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException();
            return fields;
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        }
    }
    public record Definition(String commandName, String category, String accessMode, String riskLevel, boolean enabled,
            String parameterSchemaJson, int keyPosition, String routingPolicy, String approvalPolicy, int maxValueBytes,
            String changeReason, String nodeKind, Long parentId) {
        public Definition {
            nodeKind = nodeKind == null ? "COMMAND" : nodeKind;
        }
        public Definition(String commandName, String category, String accessMode, String riskLevel, boolean enabled,
                String parameterSchemaJson, int keyPosition, String routingPolicy, String approvalPolicy,
                int maxValueBytes,
                String changeReason) {
            this(commandName, category, accessMode, riskLevel, enabled, parameterSchemaJson, keyPosition, routingPolicy,
                    approvalPolicy, maxValueBytes, changeReason, "COMMAND", null);
        }
    }
}
