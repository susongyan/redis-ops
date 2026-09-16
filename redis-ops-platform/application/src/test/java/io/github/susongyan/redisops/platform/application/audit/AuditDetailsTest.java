package io.github.susongyan.redisops.platform.application.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.operation.OperationCommand;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditDetailsTest {
    @Test
    void onlyChangedFieldsWithNullAndBooleanAreKept() throws Exception {
        var value = new ObjectMapper()
                .readTree(AuditDetails.change("修改", AuditDetails.fields("启用", false, "原样", 1, "名称", null),
                        AuditDetails.fields("启用", true, "原样", 1, "名称", "test"), "approved"));
        assertEquals(2, value.get("changes").size());
        assertFalse(value.get("changes").get(0).get("before").asBoolean());
        assertTrue(value.get("changes").get(0).get("after").asBoolean());
        assertTrue(value.get("changes").get(1).get("before").isNull());
        assertEquals("approved", value.get("reason").asText());
    }
    @Test
    void schemaLiteralsNeverReachAuditAndLongValuesAreMarked() {
        var now = Instant.now();
        var command = new OperationCommand(1L, "GET", 1, "STRING", "READ", "LOW", true,
                "[{\"literal\":\"secret-key-content\"}]", 1, "SINGLE_KEY", "DIRECT", 4096, "[\"key\"]",
                "CREATE_ALLOWED", false, "approved", "user:1", 0, now, now);
        var details = AuditDetails.commandChange(null, command);
        assertFalse(details.contains("secret-key-content"));
        assertTrue(details.contains("内容不记录"));
        assertTrue(AuditDetails.change("test", Map.of(), AuditDetails.fields("名称", "x".repeat(1000)), null)
                .contains("已省略"));
    }
    @Test
    void excessiveDetailsFailClosed() {
        var fields = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < 100; i++)
            fields.put("field" + i, "x".repeat(512));
        assertThrows(IllegalArgumentException.class, () -> AuditDetails.change("test", Map.of(), fields, null));
    }
}
