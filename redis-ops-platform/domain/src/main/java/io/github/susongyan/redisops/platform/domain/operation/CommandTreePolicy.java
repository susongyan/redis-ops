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
        OperationCommand root = names.get(name);
        if (root == null || !Set.of("COMMAND", "FAMILY").contains(root.nodeKind()))
            throw invalid("COMMAND_NOT_ALLOWED");
        OperationCommand selected = root;
        if ("FAMILY".equals(root.nodeKind())) {
            if (arguments == null || arguments.isEmpty() || arguments.get(0) == null)
                throw invalid("SUBCOMMAND_REQUIRED");
            String sub = arguments.get(0);
            if (!sub.matches("[A-Za-z][A-Za-z0-9_.-]{0,31}"))
                throw invalid("COMMAND_NOT_ALLOWED");
            selected = names.get(name + " " + sub.toUpperCase(Locale.ROOT));
            if (selected == null)
                selected = names.get(name + " *");
            if (selected == null || !Objects.equals(selected.parentId(), root.id())
                    || !Set.of("SUBCOMMAND", "WILDCARD").contains(selected.nodeKind()))
                throw invalid("COMMAND_NOT_ALLOWED");
        }
        String action = null;
        StringBuilder fingerprint = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        OperationCommand current = selected;
        while (current != null) {
            if (!seen.add(current.id()) || seen.size() > 16)
                throw invalid("INVALID_COMMAND_TREE");
            if (!current.enabled())
                throw invalid("COMMAND_NOT_ALLOWED");
            fingerprint.append(current.id()).append(':').append(current.version()).append('/');
            if (action == null && !"INHERIT".equals(current.approvalPolicy()))
                action = current.approvalPolicy();
            Long parent = current.parentId();
            current = parent == null ? null : ids.get(parent);
            if (parent != null && current == null)
                throw invalid("INVALID_COMMAND_TREE");
        }
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
        if (Set.of("SUBCOMMAND", "WILDCARD").contains(candidate.nodeKind())) {
            if (parent == null || !"FAMILY".equals(parent.nodeKind()))
                throw invalid("COMMAND_FAMILY_REQUIRED");
            String suffix = "WILDCARD".equals(candidate.nodeKind()) ? "\\*" : "[A-Z][A-Z0-9_.-]{0,31}";
            if (!candidate.commandName().matches(java.util.regex.Pattern.quote(parent.commandName()) + " " + suffix))
                throw invalid("INVALID_SUBCOMMAND_NAME");
        } else {
            if (parent != null && !"CATEGORY".equals(parent.nodeKind()))
                throw invalid("CATEGORY_PARENT_REQUIRED");
            if (!candidate.commandName().matches("[A-Z][A-Z0-9_.-]{0,31}"))
                throw invalid("INVALID_COMMAND_NAME");
        }
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
