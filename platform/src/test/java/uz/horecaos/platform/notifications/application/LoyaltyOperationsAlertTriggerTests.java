package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.integration.provider.telegram.TelegramEventClass;
import uz.horecaos.platform.loyalty.api.LoyaltySyncDiscrepancyOpened;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;

/**
 * The chat alert for a till-sync difference (ADR 0171 Decision 6, ADR 0058): only the kinds that ask a person to look, one
 * per binding per kind per day, and made of ids and a kind alone.
 */
class LoyaltyOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.fromString("018fd187-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd187-5000-7000-8000-0000000000b1");
    private static final UUID BINDING = UUID.fromString("018fd187-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_BINDING = UUID.fromString("018fd187-5000-7000-8000-0000000000c2");
    private static final UUID ACCOUNT = UUID.fromString("018fd187-5000-7000-8000-0000000000d1");

    private OperationsAlertPort alerts;
    private Instant now;
    private LoyaltyOperationsAlertTrigger trigger;

    @BeforeEach
    void setUp() {
        alerts = mock(OperationsAlertPort.class);
        now = Instant.parse("2026-10-14T10:00:00Z");
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        trigger = new LoyaltyOperationsAlertTrigger(alerts, Duration.ofDays(1), clock);
    }

    private static LoyaltySyncDiscrepancyOpened opened(UUID binding, String kind) {
        return new LoyaltySyncDiscrepancyOpened(TENANT, BRAND, binding, UUID.randomUUID(), ACCOUNT, kind);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TILL_HIGHER",
                "TILL_LOWER",
                "SPEND_EXCEEDS_BALANCE",
                "CREDIT_HELD",
                "AMBIGUOUS_MATCH",
                "RATIO_MISMATCH",
                "NOT_CONSENTED",
                "UNVERIFIABLE"
            })
    @DisplayName("a difference that asks a person to look is told to the brand's chats, by ids and its kind alone")
    void aDifferenceThatNeedsAPersonIsTold(String kind) {
        LoyaltySyncDiscrepancyOpened event = opened(BINDING, kind);

        trigger.onDiscrepancyOpened(event);

        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts)
                .fanOut(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(BRAND),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq("LOYALTY_SYNC_DISCREPANCY"),
                        org.mockito.ArgumentMatchers.eq("LOYALTY_SYNC_DISCREPANCY"),
                        org.mockito.ArgumentMatchers.eq("PosSyncBinding"),
                        org.mockito.ArgumentMatchers.eq(BINDING),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(
                                "LOYALTY_SYNC_DISCREPANCY:PosSyncBinding:" + BINDING + ":" + kind + ":2026-10-14"),
                        variables.capture(),
                        org.mockito.ArgumentMatchers.eq(Duration.ofDays(1)));
        assertThat(variables.getValue())
                .as("no customer, no phone, no balance: ids and a kind")
                .containsOnlyKeys("bindingId", "discrepancyId", "discrepancyKind")
                .containsEntry("bindingId", BINDING.toString())
                .containsEntry("discrepancyId", event.discrepancyId().toString())
                .containsEntry("discrepancyKind", kind);
        assertThat(variables.getValue().values()).noneMatch(value -> value.contains(ACCOUNT.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TILL_ONLY", "PLATFORM_ONLY"})
    @DisplayName(
            "the ordinary kinds are counted in the report and never announced: most of a till's customers never used the platform")
    void theOrdinaryKindsAreNotTold(String kind) {
        trigger.onDiscrepancyOpened(opened(BINDING, kind));

        verify(alerts, never())
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("a hundred differences of one kind in one day are one key, a new day and a new kind are new ones")
    void theKeyIsPerBindingKindAndDay() {
        trigger.onDiscrepancyOpened(opened(BINDING, "UNVERIFIABLE"));
        trigger.onDiscrepancyOpened(opened(BINDING, "UNVERIFIABLE"));
        trigger.onDiscrepancyOpened(opened(BINDING, "TILL_HIGHER"));
        trigger.onDiscrepancyOpened(opened(OTHER_BINDING, "UNVERIFIABLE"));
        now = now.plus(Duration.ofDays(1));
        trigger.onDiscrepancyOpened(opened(BINDING, "UNVERIFIABLE"));

        ArgumentCaptor<String> keys = ArgumentCaptor.captor();
        verify(alerts, times(5))
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        keys.capture(),
                        any(),
                        any());
        assertThat(keys.getAllValues()).hasSize(5);
        assertThat(keys.getAllValues().stream().distinct().toList())
                .as(
                        "the first two are the same key, so the fan-out's own idempotency collapses them to one alert per chat")
                .hasSize(4);
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(4)).endsWith(":2026-10-15");
    }

    @Test
    @DisplayName("a fault in raising the alert does not fail the sync pass that found the difference")
    void aFailureToTellDoesNotFailThePass() {
        org.mockito.Mockito.doThrow(new IllegalStateException("the subscription lookup is down"))
                .when(alerts)
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());

        org.assertj.core.api.Assertions.assertThatCode(() -> trigger.onDiscrepancyOpened(opened(BINDING, "TILL_HIGHER")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the alert's variables are declared by the OPERATIONS_ALERT catalogue and its event class exists")
    void theVariablesAndTheClassAreDeclared() {
        trigger.onDiscrepancyOpened(opened(BINDING, "TILL_LOWER"));
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts)
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        variables.capture(),
                        any());

        var declared = NotificationVariableCatalog.forClass(NotificationClass.OPERATIONS_ALERT).stream()
                .map(NotificationVariableCatalog.Variable::name)
                .toList();
        assertThat(declared).containsAll(variables.getValue().keySet());
        assertThat(TelegramEventClass.valueOf(LoyaltyOperationsAlertTrigger.LOYALTY_SYNC_DISCREPANCY))
                .isEqualTo(TelegramEventClass.LOYALTY_SYNC_DISCREPANCY);
    }
}
