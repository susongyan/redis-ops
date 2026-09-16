package io.github.susongyan.redisops.worker.protocol;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CommandKeySemanticsTest {
    private List<byte[]> args(String... values) {
        return Arrays.stream(values).map(x -> x.getBytes(StandardCharsets.UTF_8)).toList();
    }
    @Test
    void bitopAndMsetNxRolesDoNotGrantAdmission() {
        var d = CommandKeySemantics.describe("BITOP", args("BITOP", "AND", "dest", "a", "b")).orElseThrow();
        assertEquals(CommandKeySemantics.Role.WRITE, d.keys().get(0).role());
        assertEquals(2, d.keys().get(0).index());
        assertEquals(CommandKeySemantics.Role.READ, d.keys().get(1).role());
        assertFalse(
                CommandKeySemantics.describe("MSETNX", args("MSETNX", "a", "1", "b", "2")).orElseThrow().splittable());
        assertTrue(io.github.susongyan.redisops.sync.contract.SyncCommandCapabilities.hardBlocked("BITOP"));
    }
    @Test
    void subcommandKeyPositionAndBinaryKeyArePreserved() {
        var input = args("XGROUP", "CREATE", "unused", "group", "$");
        input = new ArrayList<>(input);
        input.set(2, new byte[]{(byte) 0xff, 0});
        assertEquals(2, CommandKeySemantics.describe("XGROUP", input).orElseThrow().keys().get(0).index());
        assertArrayEquals(new byte[]{(byte) 0xff, 0}, input.get(2));
        assertThrows(IllegalArgumentException.class,
                () -> CommandKeySemantics.describe("XGROUP", args("XGROUP", "UNKNOWN", "key", "group")));
        assertThrows(IllegalArgumentException.class,
                () -> CommandKeySemantics.describe("MSET", args("MSET", "a", "1", "b")));
        assertTrue(CommandKeySemantics.describe("CUSTOM", args("CUSTOM", "a")).isEmpty());
    }
}
