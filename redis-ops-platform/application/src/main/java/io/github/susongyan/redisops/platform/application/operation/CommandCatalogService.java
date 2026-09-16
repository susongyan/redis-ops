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
        if (input.accessMode() == null || input.riskLevel() == null || input.approvalPolicy() == null
                || input.routingPolicy() == null)
            throw new IllegalArgumentException("INVALID_COMMAND_DEFINITION");
        String name = input.commandName() == null ? "" : input.commandName().trim().toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z][A-Z0-9_.-]{0,31}") || input.category() == null || input.category().isBlank()
                || input.category().length() > 32
                || !Set.of("READ", "WRITE").contains(input.accessMode())
                || !Set.of("LOW", "MEDIUM", "HIGH").contains(input.riskLevel())
                || !Set.of("DIRECT", "CONFIRM", "APPROVAL").contains(input.approvalPolicy())
                || !Set.of("SINGLE_KEY", "NO_KEY").contains(input.routingPolicy()) || input.maxValueBytes() < 0
                || input.maxValueBytes() > 1048576)
            throw new IllegalArgumentException("INVALID_COMMAND_DEFINITION");
        if (input.changeReason() == null || input.changeReason().isBlank() || input.changeReason().length() > 512)
            throw new IllegalArgumentException("CHANGE_REASON_REQUIRED");
        var fields = parameters(json, input.parameterSchemaJson());
        if (fields.size() > 32 || ("NO_KEY".equals(input.routingPolicy())
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
        if (current != null && !current.commandName().equals(name))
            throw new IllegalArgumentException("COMMAND_NAME_IMMUTABLE");
        if (id == null && repo.commands(true, true).stream().anyMatch(x -> x.commandName().equals(name)))
            throw new IllegalArgumentException("COMMAND_ALREADY_EXISTS");
        String schema;
        try {
            schema = json.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_ARGUMENT_SCHEMA");
        }
        var value = new OperationCommand(id, name, current == null ? 1 : current.commandVersion(), input.category(),
                input.accessMode(), input.riskLevel(), id != null && input.enabled(), schema, input.keyPosition(),
                input.routingPolicy(), input.approvalPolicy(), input.maxValueBytes(), "[\"key\"]", "CREATE_ALLOWED",
                false, input.changeReason(), operator, version, current == null ? Instant.now() : current.createdAt(),
                Instant.now());
        OperationCommand result;
        if (id == null)
            result = repo.createCommand(value);
        else {
            if (!repo.updateCommand(value, version))
                throw new IllegalArgumentException("VERSION_CONFLICT");
            result = get(id);
        }
        audits.append(operator, id == null ? "OPERATION_COMMAND_CREATE" : "OPERATION_COMMAND_UPDATE",
                "OPERATION_COMMAND", result.id().toString(), "SUCCESS", AuditDetails.commandChange(current, result));
        return result;
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
            String changeReason) {
    }
}
