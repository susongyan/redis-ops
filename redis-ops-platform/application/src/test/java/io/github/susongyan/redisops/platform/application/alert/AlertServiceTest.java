package io.github.susongyan.redisops.platform.application.alert;

import io.github.susongyan.redisops.platform.domain.alert.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertServiceTest {

    @Test
    void hasUnresolvedFollowsTheFirstEnabledRuleOfTheType() {
        Fixture fixture = new Fixture();
        when(fixture.alerts.rules()).thenReturn(List.of(
                fixture.rule(1, "OTHER_TYPE", true),
                fixture.rule(2, "LARGE_KEY_FOUND", true)));
        when(fixture.alerts.findEventByKey(2L, "SYNC_TASK", "8"))
                .thenReturn(Optional.of(fixture.event(AlertStatus.OPEN)));
        assertTrue(fixture.service.hasUnresolved("LARGE_KEY_FOUND", "SYNC_TASK", "8"));
        verify(fixture.alerts, never()).findEventByKey(eq(1L), anyString(), anyString());
    }

    @Test
    void resolvedOrMissingEventsDoNotSuppressAndDisabledRulesAreSkipped() {
        Fixture fixture = new Fixture();
        when(fixture.alerts.rules()).thenReturn(List.of(
                fixture.rule(1, "LARGE_KEY_FOUND", false),
                fixture.rule(2, "LARGE_KEY_FOUND", true)));
        when(fixture.alerts.findEventByKey(2L, "SYNC_TASK", "8"))
                .thenReturn(Optional.of(fixture.event(AlertStatus.RESOLVED)))
                .thenReturn(Optional.empty());
        assertFalse(fixture.service.hasUnresolved("LARGE_KEY_FOUND", "SYNC_TASK", "8"));
        assertFalse(fixture.service.hasUnresolved("LARGE_KEY_FOUND", "SYNC_TASK", "8"));
    }

    @Test
    void acknowledgedStillCountsAsUnresolvedAndUnknownTypeIsFalse() {
        Fixture fixture = new Fixture();
        when(fixture.alerts.rules()).thenReturn(List.of(fixture.rule(2, "LARGE_KEY_FOUND", true)));
        when(fixture.alerts.findEventByKey(2L, "SYNC_TASK", "8"))
                .thenReturn(Optional.of(fixture.event(AlertStatus.ACKNOWLEDGED)));
        assertTrue(fixture.service.hasUnresolved("LARGE_KEY_FOUND", "SYNC_TASK", "8"));
        assertFalse(fixture.service.hasUnresolved("NO_SUCH_TYPE", "SYNC_TASK", "8"));
    }

    private static final class Fixture {
        private final AlertRepository alerts = mock(AlertRepository.class);
        private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        private final AlertService service = new AlertService(alerts, deliveries);

        private AlertRule rule(long id, String type, boolean enabled) {
            return new AlertRule(id, "rule-" + id, type, AlertSeverity.P2, enabled, null, 0, null, 0,
                    Instant.now(), Instant.now());
        }

        private AlertEvent event(AlertStatus status) {
            return new AlertEvent(99L, 2L, "SYNC_TASK", "8", status, AlertSeverity.P2, "rule-2", "{}",
                    Instant.now(), Instant.now(), null, null, null, null, 0);
        }
    }
}
