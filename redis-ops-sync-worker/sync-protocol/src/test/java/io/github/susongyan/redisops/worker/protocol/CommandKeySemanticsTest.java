package io.github.susongyan.redisops.worker.protocol;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CommandKeySemanticsTest {
    @Test
    void parsesCopyDatabaseWithoutTreatingOptionsAsKeys() {
        var copy = CommandKeySemantics.describe("COPY", args("COPY", "source", "target", "REPLACE", "DB", "3"))
                .orElseThrow();
        assertEquals(3, copy.destinationDatabase());
        assertEquals(List.of(new CommandKeySemantics.KeyArgument(1, CommandKeySemantics.Role.READ),
                new CommandKeySemantics.KeyArgument(2, CommandKeySemantics.Role.WRITE)), copy.keys());
        assertNull(CommandKeySemantics.describe("COPY", args("COPY", "a", "b")).orElseThrow().destinationDatabase());
    }
    @Test
    void sortedSetOptionsAndWeightsAreNotKeys() {
        for (String name : List.of("ZUNIONSTORE", "ZINTERSTORE")) {
            var d = CommandKeySemantics
                    .describe(name, args(name, "out", "2", "a", "b", "WEIGHTS", "-2.5", "1e2", "AGGREGATE", "MAX"))
                    .orElseThrow();
            assertEquals(List.of(1, 3, 4), d.keys().stream().map(CommandKeySemantics.KeyArgument::index).toList());
            assertEquals(
                    List.of(CommandKeySemantics.Role.WRITE, CommandKeySemantics.Role.READ,
                            CommandKeySemantics.Role.READ),
                    d.keys().stream().map(CommandKeySemantics.KeyArgument::role).toList());
        }
        assertEquals(3, CommandKeySemantics.describe("ZDIFFSTORE", args("ZDIFFSTORE", "out", "2", "a", "b"))
                .orElseThrow().keys().size());
    }
    @Test
    void rejectsMalformedOrUnrecognizedShapesWithFixedNonSensitiveReason() {
        List<List<byte[]>> invalid = List.of(
                args("RENAME", "a", "b", "extra"), args("SMOVE", "a", "b"),
                args("LMOVE", "a", "b", "UP", "LEFT"), args("RPOPLPUSH", "a"),
                args("COPY", "a", "b", "DB"), args("COPY", "a", "b", "DB", "-1"),
                args("COPY", "a", "b", "DB", "2147483648"), args("COPY", "a", "b", "DB", "0", "DB", "1"),
                args("COPY", "a", "b", "REPLACE", "REPLACE"), args("COPY", "a", "b", "UNKNOWN"),
                args("ZUNIONSTORE", "out", "0", "a"), args("ZUNIONSTORE", "out", "2147483647", "a"),
                args("ZUNIONSTORE", "out", "2", "a"), args("ZUNIONSTORE", "out", "1", "a", "WEIGHTS"),
                args("ZUNIONSTORE", "out", "1", "a", "WEIGHTS", "secret-value"),
                args("ZUNIONSTORE", "out", "1", "a", "WEIGHTS", "NaN"),
                args("ZUNIONSTORE", "out", "1", "a", "WEIGHTS", "1", "WEIGHTS", "2"),
                args("ZINTERSTORE", "out", "1", "a", "AGGREGATE", "COUNT"),
                args("ZINTERSTORE", "out", "1", "a", "AGGREGATE", "SUM", "AGGREGATE", "MAX"),
                args("ZDIFFSTORE", "out", "1", "a", "WEIGHTS", "1"));
        for (var input : invalid) {
            String name = new String(input.get(0), StandardCharsets.US_ASCII);
            var error = assertThrows(IllegalArgumentException.class, () -> CommandKeySemantics.describe(name, input));
            assertEquals("INVALID_COMMAND_KEY_ARGUMENTS", error.getMessage());
        }
    }
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
