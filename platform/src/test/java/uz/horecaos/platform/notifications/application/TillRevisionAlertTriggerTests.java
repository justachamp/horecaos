package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.provider.telegram.TelegramEventClass;
import uz.horecaos.platform.ordering.api.TillRevisionNoticeOpened;

/**
 * The branch's chat hears once that a weighed total needs the till changed (ADR 0058, ADR 0137, row 4.2c), and
 * what it is told is money and a public order number only.
 */
class TillRevisionAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID NOTICE = UUID.randomUUID();

    private final OperationsAlertFanoutService alerts = mock(OperationsAlertFanoutService.class);

    @Test
    @DisplayName("an opened notice fans out one alert about the order, keyed by the notice")
    void anOpenedNoticeFansOutOnceKeyedByTheNotice() {
        new TillRevisionAlertTrigger(alerts)
                .onNoticeOpened(
                        new TillRevisionNoticeOpened(TENANT, BRAND, LOCATION, ORDER, NOTICE, "UZS", 215_000, 208_000));

        verify(alerts)
                .fanOut(
                        eq(TENANT),
                        eq(BRAND),
                        eq(LOCATION),
                        eq("TILL_REVISION_NEEDED"),
                        eq("TILL_REVISION_NEEDED"),
                        eq("Order"),
                        eq(ORDER),
                        isNull(),
                        eq("TILL_REVISION_NEEDED:OrderTillRevisionNotice:" + NOTICE),
                        any(),
                        eq(Duration.ofHours(12)));
    }

    @Test
    @DisplayName("the only variable it adds is the total the till holds: no customer, no line, no address")
    void theVariablesAreMoneyOnly() {
        Map<String, String> variables = TillRevisionAlertTrigger.alertVariables("UZS", 215_000);

        assertThat(variables).containsOnlyKeys("tillAmount");
        assertThat(variables.get("tillAmount"))
                .as("formatted exactly as the order's own amount is, so the two read alike")
                .isEqualTo("215000");
    }

    @Test
    @DisplayName("the event class is one a chat can subscribe to")
    void theEventClassIsOffered() {
        assertThat(TelegramEventClass.valueOf(TillRevisionAlertTrigger.TILL_REVISION_NEEDED))
                .isEqualTo(TelegramEventClass.TILL_REVISION_NEEDED);
    }
}
