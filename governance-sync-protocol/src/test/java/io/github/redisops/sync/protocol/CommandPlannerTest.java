package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandPlannerTest {
    private static byte[] b(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    @Test
    void splitsMsetByTargetSlotAndFilter() {
        var planner = new CommandPlanner(new KeyFilter(List.of("order:*"), List.of("order:debug:*")), true);
        var plan = planner.plan(new ReplicationCommand("MSET",
                List.of(b("MSET"), b("order:{1}:a"), b("a"), b("order:{2}:b"), b("b"), b("other"), b("c")), 0, 10));
        assertEquals(CommandPlan.Disposition.APPLY, plan.disposition());
        assertEquals(2, plan.commands().size());
    }
    @Test
    void blocksCrossModeSemanticCommand() {
        var planner = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), true);
        assertEquals(CommandPlan.Disposition.BLOCK, planner.plan(new ReplicationCommand("MSETNX",
                List.of(b("MSETNX"), b("a"), b("1"), b("b"), b("2")), 0, 1)).disposition());
    }
}
