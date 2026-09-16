package io.github.susongyan.redisops.worker.protocol;

import io.github.susongyan.redisops.sync.contract.SyncCommandCapabilities;
import io.github.susongyan.redisops.sync.contract.SyncCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Whole-unit scope checks happen before individual command transformation. Not a source marker executor. */
public final class SourceTransactionPlanner {
    private final KeyFilter filter;
    private final boolean cluster;
    private final SyncCommandPolicy policy;
    private final int taskDatabase;

    public record Result(CommandPlan plan, int sourceDatabase) {
    }

    public SourceTransactionPlanner(KeyFilter filter, boolean cluster, SyncCommandPolicy policy, int taskDatabase) {
        this.filter = filter;
        this.cluster = cluster;
        this.policy = policy;
        this.taskDatabase = taskDatabase;
    }

    public Result plan(SourceTransactionAssembler.Unit unit, int initialDatabase) {
        if (policy.additionallyBlocks("MULTI") || policy.additionallyBlocks("EXEC"))
            return blocked("BLOCKED_COMMAND_POLICY", initialDatabase);
        if (!unit.transaction())
            return blocked("BLOCKED_TRANSACTION_STRUCTURE", initialDatabase);
        int database = initialDatabase;
        boolean insideWrite = false, outsideWrite = false, outsideRead = false;
        int slot = -1;
        boolean crossSlot = false;
        for (var command : unit.commands()) {
            var args = command.arguments();
            if (command.name().equals("SELECT")) {
                try {
                    if (args.size() != 2 || args.get(1).length == 0 || args.get(1).length > 10)
                        throw new IllegalArgumentException();
                    for (byte b : args.get(1))
                        if (b < '0' || b > '9')
                            throw new IllegalArgumentException();
                    database = Integer.parseInt(new String(args.get(1), StandardCharsets.US_ASCII));
                } catch (IllegalArgumentException invalid) {
                    return blocked("BLOCKED_COMMAND_ARGUMENTS", initialDatabase);
                }
                continue;
            }
            String name = command.name();
            if (policy.additionallyBlocks(name))
                return blocked("BLOCKED_COMMAND_POLICY", initialDatabase);
            if (!(SyncCommandCapabilities.singleKey(name) || SyncCommandCapabilities.safeSplit(name)
                    || policy.supportsMultiKey() && SyncCommandCapabilities.conditionalMultiKey(name)))
                return blocked("BLOCKED_UNSUPPORTED_COMMAND", initialDatabase);
            final CommandKeySemantics.Description description;
            try {
                description = CommandKeySemantics.describe(name, args).orElseThrow();
            } catch (IllegalArgumentException invalid) {
                return blocked("BLOCKED_COMMAND_ARGUMENTS", initialDatabase);
            }
            if (description.destinationDatabase() != null && description.destinationDatabase() != database)
                return blocked("BLOCKED_CROSS_DATABASE", initialDatabase);
            for (var argument : description.keys()) {
                byte[] key = args.get(argument.index());
                if (reserved(key))
                    return blocked("BLOCKED_RESERVED_NAMESPACE", initialDatabase);
                boolean inside = database == taskDatabase && filter.accepts(key);
                if (argument.role() != CommandKeySemantics.Role.READ) {
                    insideWrite |= inside;
                    outsideWrite |= !inside;
                }
                if (argument.role() != CommandKeySemantics.Role.WRITE)
                    outsideRead |= !inside;
                int currentSlot = RedisSlot.of(key);
                crossSlot |= slot >= 0 && slot != currentSlot;
                slot = currentSlot;
            }
        }
        if (!insideWrite)
            return new Result(CommandPlan.skip(), database);
        if (outsideWrite || outsideRead)
            return blocked("BLOCKED_TRANSACTION_MIXED_SCOPE", initialDatabase);
        if (cluster && crossSlot)
            return blocked("BLOCKED_TRANSACTION_CROSS_SLOT", initialDatabase);
        var planner = new CommandPlanner(filter, cluster, null, policy, taskDatabase);
        var planned = new ArrayList<CommandPlan.PlannedCommand>();
        for (var command : unit.commands()) {
            if (command.name().equals("SELECT"))
                continue;
            var result = planner.plan(command);
            if (result.disposition() == CommandPlan.Disposition.BLOCK)
                return new Result(result, initialDatabase);
            if (result.disposition() != CommandPlan.Disposition.APPLY)
                return blocked("BLOCKED_TRANSACTION_MIXED_SCOPE", initialDatabase);
            planned.addAll(result.commands());
        }
        return new Result(new CommandPlan(CommandPlan.Disposition.APPLY, planned, null), database);
    }

    private Result blocked(String code, int database) {
        return new Result(CommandPlan.block(code), database);
    }
    private boolean reserved(byte[] key) {
        byte[] prefix = "__redis_ops_sync_".getBytes(StandardCharsets.US_ASCII);
        if (key.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++)
            if (key[i] != prefix[i])
                return false;
        return true;
    }
}
