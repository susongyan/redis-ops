package io.github.susongyan.redisops.platform.domain.operation;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandTreePolicyTest {
    OperationCommand node(long id, String name, String kind, Long parent, boolean enabled,
            String access, String risk, String action, long version) {
        return new OperationCommand(id, name, 1, "TEST", access, risk, enabled, "[]", 0, "NO_KEY", action,
                4096, "[\"key\"]", "CREATE_ALLOWED", false, "test", "user:1", version,
                Instant.EPOCH, Instant.EPOCH, null, kind, parent);
    }
    OperationCommand family(boolean enabled) {
        return node(1, "CLUSTER", "FAMILY", null, enabled, "MANAGE", "HIGH", "DANGER_CONFIRM", 0);
    }
    OperationCommand wildcard() {
        return node(2, "CLUSTER *", "WILDCARD", 1L, true, "MANAGE", "HIGH", "DANGER_CONFIRM", 0);
    }
    OperationCommand info(boolean enabled) {
        return node(3, "CLUSTER INFO", "SUBCOMMAND", 1L, enabled, "READ", "LOW", "DIRECT", 0);
    }
    @Test
    void aFamilyAloneDoesNotAuthorizeItsChildren() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandTreePolicy.resolve(List.of(family(true)), "CLUSTER", List.of("INFO")));
    }
    @Test
    void exactPolicyWinsOverWildcardAndIsCaseInsensitive() {
        var d = CommandTreePolicy.resolve(List.of(family(true), wildcard(), info(true)), "cluster", List.of("info"));
        assertEquals("CLUSTER INFO", d.definition().commandName());
        assertEquals("DIRECT", d.action());
    }
    @Test
    void exactDisabledRuleNeverFallsBackToWildcard() {
        assertThrows(IllegalArgumentException.class, () -> CommandTreePolicy
                .resolve(List.of(family(true), wildcard(), info(false)), "CLUSTER", List.of("INFO")));
    }
    @Test
    void disabledAncestorOverridesBothExactAndWildcard() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandTreePolicy.resolve(List.of(family(false), info(true)), "CLUSTER", List.of("INFO")));
        assertThrows(IllegalArgumentException.class,
                () -> CommandTreePolicy.resolve(List.of(family(false), wildcard()), "CLUSTER", List.of("MEET")));
    }
    @Test
    void explicitWildcardIsAlwaysDangerous() {
        var d = CommandTreePolicy.resolve(List.of(family(true), wildcard()), "CLUSTER", List.of("MEET"));
        assertEquals("DANGER_CONFIRM", d.action());
    }
    @Test
    void writeCannotInheritReadDirectAndParentVersionIsPartOfFingerprint() {
        var category = node(1, "STRINGS", "CATEGORY", null, true, "READ", "LOW", "DIRECT", 4);
        var set = node(2, "SET", "COMMAND", 1L, true, "WRITE", "LOW", "INHERIT", 2);
        var d = CommandTreePolicy.resolve(List.of(category, set), "SET", List.of("key", "value"));
        assertEquals("CONFIRM", d.action());
        assertEquals("2:2/1:4/", d.fingerprint());
    }
    @Test
    void missingPolicyUnknownAndMalformedCommandsAreRejected() {
        var c = node(1, "GET", "COMMAND", null, true, "READ", "LOW", "INHERIT", 0);
        for (String s : List.of("GET", "UNKNOWN", "GET key", "GET\r\nSET"))
            assertThrows(IllegalArgumentException.class, () -> CommandTreePolicy.resolve(List.of(c), s, List.of()));
    }
    @Test
    void cyclesMissingParentsAndDuplicateRulesFailClosed() {
        var a = node(1, "GET", "COMMAND", 1L, true, "READ", "LOW", "DIRECT", 0);
        assertThrows(IllegalArgumentException.class, () -> CommandTreePolicy.resolve(List.of(a), "GET", List.of()));
        var b = node(2, "GET", "COMMAND", null, true, "READ", "LOW", "DIRECT", 0);
        assertThrows(IllegalArgumentException.class, () -> CommandTreePolicy.resolve(List.of(a, b), "GET", List.of()));
        assertThrows(IllegalArgumentException.class, () -> CommandTreePolicy.validatePlacement(a, List.of(a)));
    }
    @Test
    void childMustBelongToItsNamedFamily() {
        var wrong = node(3, "CONFIG GET", "SUBCOMMAND", 1L, true, "READ", "LOW", "DIRECT", 0);
        assertThrows(IllegalArgumentException.class,
                () -> CommandTreePolicy.validatePlacement(wrong, List.of(family(true))));
        assertDoesNotThrow(() -> CommandTreePolicy.validatePlacement(info(true), List.of(family(true))));
    }
}
