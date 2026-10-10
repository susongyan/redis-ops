package io.github.susongyan.redisops.platform.domain.operation;

import java.util.*;

/** Pure policy calculation; never probes Redis or guesses an unknown command's access mode. */
public final class CommandTreePolicy {
    private CommandTreePolicy() {
    }

    public record Decision(OperationCommand definition, String action, String fingerprint) {
    }

    public static Decision resolve(List<OperationCommand> nodes, String command, List<String> arguments) {
        if (nodes.size() > 4096 || command == null || !command.matches("[A-Za-z][A-Za-z0-9_.-]{0,31}"))
            throw invalid("COMMAND_NOT_ALLOWED");
        String name = command.toUpperCase(Locale.ROOT);
        Map<String, OperationCommand> names = new HashMap<>();
        Map<Long, OperationCommand> ids = new HashMap<>();
        for (OperationCommand node : nodes) {
            if (ids.put(node.id(), node) != null || names.put(node.commandName(), node) != null)
                throw invalid("AMBIGUOUS_COMMAND_TREE");
        }
        String exact = name + " "
                + ((arguments == null || arguments.isEmpty()) ? "" : arguments.get(0)).toUpperCase(Locale.ROOT);
        OperationCommand selected = names.get(exact);
        if (selected == null)
            selected = names.get(name);
        if (selected == null || !Set.of("COMMAND", "SUBCOMMAND").contains(selected.nodeKind()) || !selected.enabled())
            throw invalid("COMMAND_NOT_ALLOWED");
        String action = selected.approvalPolicy();
        StringBuilder fingerprint = new StringBuilder().append(selected.id()).append(':').append(selected.version())
                .append('/');
        if (action == null || "DENY".equals(action))
            throw invalid("COMMAND_NOT_ALLOWED");
        if (!Set.of("DIRECT", "CONFIRM", "DANGER_CONFIRM", "APPROVAL").contains(action))
            throw invalid("INVALID_COMMAND_POLICY");
        // Risk/access requirements are floors; a permissive inherited action cannot weaken them.
        if ("HIGH".equals(selected.riskLevel()) || "MANAGE".equals(selected.accessMode())
                || "WILDCARD".equals(selected.nodeKind()) || "APPROVAL".equals(action))
            action = "DANGER_CONFIRM";
        else if (!"READ".equals(selected.accessMode()) && "DIRECT".equals(action))
            action = "CONFIRM";
        return new Decision(selected, action, fingerprint.toString());
    }

    public static void validatePlacement(OperationCommand candidate, List<OperationCommand> nodes) {
        if (!Set.of("CATEGORY", "FAMILY", "COMMAND", "SUBCOMMAND", "WILDCARD").contains(candidate.nodeKind()))
            throw invalid("INVALID_NODE_KIND");
        Map<Long, OperationCommand> ids = new HashMap<>();
        for (var node : nodes)
            ids.put(node.id(), node);
        var parent = candidate.parentId() == null ? null : ids.get(candidate.parentId());
        if (candidate.parentId() != null && parent == null)
            throw invalid("PARENT_NOT_FOUND");
        if (!Set.of("CATEGORY", "COMMAND", "SUBCOMMAND").contains(candidate.nodeKind()))
            throw invalid("INVALID_NODE_KIND");
        if (parent != null && !"CATEGORY".equals(parent.nodeKind()))
            throw invalid("CATEGORY_PARENT_REQUIRED");
        if ("CATEGORY".equals(candidate.nodeKind()) && parent != null)
            throw invalid("CATEGORY_PARENT_REQUIRED");
        if (!"CATEGORY".equals(candidate.nodeKind())
                && !candidate.commandName().matches("[A-Z][A-Z0-9_.-]{0,31}( [A-Z][A-Z0-9_.-]{0,31})?"))
            throw invalid("INVALID_COMMAND_NAME");
        Set<Long> seen = new HashSet<>();
        if (candidate.id() != null)
            seen.add(candidate.id());
        while (parent != null) {
            if (!seen.add(parent.id()) || seen.size() > 16)
                throw invalid("INVALID_COMMAND_TREE");
            Long id = parent.parentId();
            parent = id == null ? null : ids.get(id);
            if (id != null && parent == null)
                throw invalid("INVALID_COMMAND_TREE");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
