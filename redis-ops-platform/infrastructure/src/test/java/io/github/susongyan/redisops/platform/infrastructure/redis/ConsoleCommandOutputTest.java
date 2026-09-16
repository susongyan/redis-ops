package io.github.susongyan.redisops.platform.infrastructure.redis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleCommandOutputTest {
    @Test
    void scalarAndArrayResponsesAreNotCommandSpecific() {
        var text = new ConsoleCommandOutput();
        text.setSingle(ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8)));
        assertEquals("OK", text.result());
        var integer = new ConsoleCommandOutput();
        integer.set(12L);
        assertEquals(12L, integer.result());
        var nil = new ConsoleCommandOutput();
        nil.set((ByteBuffer) null);
        assertNull(nil.result());
        var array = new ConsoleCommandOutput();
        array.multi(2);
        array.set(1L);
        array.complete(1);
        array.set(2L);
        array.complete(1);
        array.complete(0);
        assertEquals(List.of(1L, 2L), array.result());
    }
    @Test
    void checksAllocationBudgetsBeforeAcceptingResponse() {
        assertThrows(IllegalArgumentException.class, () -> new ConsoleCommandOutput().multi(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ConsoleCommandOutput().set(ByteBuffer.allocate(65537)));
        var output = new ConsoleCommandOutput();
        output.multi(2048);
        assertThrows(IllegalArgumentException.class, () -> output.multi(1));
        var deep = new ConsoleCommandOutput();
        for (int i = 0; i < 16; i++)
            deep.multi(1);
        assertThrows(IllegalArgumentException.class, () -> deep.multi(1));
    }
    @Test
    void dispatchAcceptsAnUnlistedModuleCommandWithoutNameSwitch() {
        var commands = mock(io.lettuce.core.api.sync.BaseRedisCommands.class);
        when(commands.dispatch(any(), any(), any())).thenAnswer(call -> {
            var keyword = (io.lettuce.core.protocol.ProtocolKeyword) call.getArgument(0);
            assertEquals("EXAMPLE.MODULE", keyword.name());
            assertEquals("EXAMPLE.MODULE", new String(keyword.getBytes(), StandardCharsets.US_ASCII));
            ((ConsoleCommandOutput) call.getArgument(1)).set(9L);
            return List.of(9L);
        });
        var result = LettuceRedisOperationAdapter.run(commands, "example.module", List.of("key", "value"), 1);
        assertTrue(result.success());
        assertEquals("9", result.value());
    }
}
