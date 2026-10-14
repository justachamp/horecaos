package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.loyalty.api.LoyaltySyncDiscrepancyOpened;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * The till-balance sync's operations Telegram trigger (ADR 0171 Decision 6, ADR 0058): a difference between the loyalty
 * ledger and a till that a person has to look at, told to the brand's chats subscribed to {@code
 * LOYALTY_SYNC_DISCREPANCY}.
 *
 * <p>Same placement as {@link ReviewsOperationsAlertTrigger}: {@code loyalty} does not depend on {@code notifications},
 * so a listener here importing the signal from {@code loyalty.api} is a clean one-way edge. The signal is in-process only,
 * never a catalogued event.
 *
 * <p>Two things keep a chat from being flooded. Only the kinds that need a person are told ({@code TILL_ONLY} and {@code
 * PLATFORM_ONLY} are ordinary: most of a till's customers have never used the platform), and what is told is one alert per
 * binding, per kind, per UTC day, whatever number of differences a pass found: the report in the console lists them all.
 * The message carries ids and a kind and nothing else (ADR 0029): no customer, no phone, no balance.
 *
 * <p>The sync runs outside any transaction, so the listener runs where it is published when none is open ({@code
 * fallbackExecution}) and after the commit when one is: an alert about a difference never rolls the difference back.
 */
@Component
public class LoyaltyOperationsAlertTrigger {

    /** The semantic template key and event class a tenant authors this alert's wording against. */
    public static final String LOYALTY_SYNC_DISCREPANCY = "LOYALTY_SYNC_DISCREPANCY";

    static final String SUBJECT_TYPE = "PosSyncBinding";

    /** The kinds that ask a person to look. The rest are counted in the report and not announced. */
    static final Set<String> ALERTING_KINDS = Set.of(
            "TILL_HIGHER",
            "TILL_LOWER",
            "SPEND_EXCEEDS_BALANCE",
            "CREDIT_HELD",
            "AMBIGUOUS_MATCH",
            "RATIO_MISMATCH",
            "NOT_CONSENTED",
            "UNVERIFIABLE");

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;
    private final Clock clock;

    public LoyaltyOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            // A difference waits for a finance person, not for a cook mid-service: it stays worth a chat's attention
            // for a day.
            @Value("${horecaos.notifications.telegram.loyalty-sync-alert-expiry:P1D}") Duration expiry,
            Clock clock) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDiscrepancyOpened(LoyaltySyncDiscrepancyOpened event) {
        if (!ALERTING_KINDS.contains(event.kind())) {
            return;
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("bindingId", event.bindingId().toString());
        variables.put("discrepancyId", event.discrepancyId().toString());
        variables.put("discrepancyKind", event.kind());
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                // A binding belongs to the brand: the chats bound to the brand as a whole hear it.
                null,
                LOYALTY_SYNC_DISCREPANCY,
                LOYALTY_SYNC_DISCREPANCY,
                SUBJECT_TYPE,
                event.bindingId(),
                null,
                "%s:%s:%s:%s:%s"
                        .formatted(
                                LOYALTY_SYNC_DISCREPANCY,
                                SUBJECT_TYPE,
                                event.bindingId(),
                                event.kind(),
                                clock.instant().atZone(ZoneOffset.UTC).toLocalDate()),
                variables,
                expiry);
    }
}
