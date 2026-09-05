package uz.horecaos.platform.voice.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.RecentOrder;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Assembles the ADR 0064 screen-pop card: "the card the inbox and the order
 * board already know how to render" is aspirational — no shared card assembly
 * exists yet anywhere in this codebase — so this is that assembly, built new,
 * for this one caller.
 *
 * <p>An unknown number is not an error: it is the ordinary answer for a first-
 * time caller, and the card says so plainly so the operations app can offer
 * create-customer prefilled with the number already on the card.
 */
@Service
public class ScreenPopQueryService {

    private static final int RECENT_ORDERS_LIMIT = 5;
    private static final String CALL_EVENTS_TABLE = "voice.call_events";
    private static final String CALLER_NUMBER_COLUMN = "caller_number_encrypted";

    private final JdbcVoiceStore store;
    private final CustomerPhoneLookup customers;
    private final OrderDirectory orders;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public ScreenPopQueryService(
            JdbcVoiceStore store,
            CustomerPhoneLookup customers,
            OrderDirectory orders,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.customers = customers;
        this.orders = orders;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Card> current(UUID tenantId, UUID brandId, UUID locationId) {
        return store.currentScreenPop(tenantId, locationId).map(row -> assemble(tenantId, brandId, row));
    }

    private Card assemble(UUID tenantId, UUID brandId, JdbcVoiceStore.ScreenPopRow row) {
        if (row.resolvedCustomerAccountId() == null) {
            return Card.unknownCaller(row);
        }
        UUID accountId = row.resolvedCustomerAccountId();
        String displayName = customers
                .cardProfile(tenantId, accountId)
                .map(CustomerPhoneLookup.CardProfile::displayName)
                .orElse(null);
        List<RecentOrder> recentOrders = orders.recentForCustomer(tenantId, brandId, accountId, RECENT_ORDERS_LIMIT);
        return Card.knownCaller(row, accountId, displayName, recentOrders);
    }

    /**
     * The one deliberate reveal this module performs: an unknown caller's own
     * number, so the create-customer form the screen-pop card offers is
     * genuinely prefilled rather than asking the operator to retype what they
     * just heard.
     *
     * <p>Refused outright for a resolved caller — a known customer's number is
     * revealed through their own record and its own capability
     * ({@code CUSTOMER_PII_REVEAL}), never as a side effect of this one. Audited
     * before the value is decrypted, the same ordering {@code
     * CustomerProfileService.revealContactPoints} uses, so a decryption failure
     * cannot leave a reveal that happened with no record of it.
     */
    @Transactional
    public String revealUnknownCallerNumber(
            UUID tenantId, UUID brandId, UUID locationId, UUID callEventId, ActorRef actor, String capabilityUsed) {
        JdbcVoiceStore.CallerNumberRow row = store.callerNumberForReveal(tenantId, callEventId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such call"));

        if (row.resolvedCustomerAccountId() != null) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This caller is a known customer; reveal their number through the customer record instead");
        }
        String encrypted = row.callerNumberEncrypted();
        if (encrypted == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No caller number was recorded for this call");
        }

        audit.record(AuditFact.of("voice.screen_pop.caller_number_revealed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("VoiceCallEvent", callEventId)
                .because("Prefilling create-customer from an unknown caller's screen-pop card")
                .usingCapability(capabilityUsed)
                .changed(Map.of())
                .correlatedBy(callEventId.toString())
                .occurredAt(clock.instant())
                .build());

        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(encrypted),
                new RecordRef(CALL_EVENTS_TABLE, CALLER_NUMBER_COLUMN, callEventId),
                "voice.screen_pop.create_customer_prefill");
    }

    @Transactional
    public void acknowledge(UUID tenantId, UUID callEventId, String operatorPrincipalId) {
        boolean claimed = store.acknowledge(tenantId, callEventId, operatorPrincipalId, clock.instant());
        if (!claimed) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This call is no longer ringing, or another operator already has it");
        }
    }

    /**
     * The screen-pop card, assembled fresh on every poll.
     *
     * @param unknownCaller       true when no customer account matched — the
     *                            operations app should offer create-customer,
     *                            prefilled with {@code maskedCallerNumber}'s
     *                            un-masked source, which the operator already
     *                            heard the caller say
     * @param customerAccountId   null exactly when {@code unknownCaller}
     * @param recentOrders        newest first, capped; empty for an unknown
     *                            caller or a known one with no order history
     * @param acknowledgedBy      null while the card is still unclaimed
     */
    public record Card(
            UUID callEventId,
            @Nullable String lineDid,
            @Nullable String maskedCallerNumber,
            Instant occurredAt,
            boolean unknownCaller,
            @Nullable UUID customerAccountId,
            @Nullable String customerDisplayName,
            List<RecentOrder> recentOrders,
            @Nullable String acknowledgedBy,
            @Nullable Instant acknowledgedAt) {

        static Card unknownCaller(JdbcVoiceStore.ScreenPopRow row) {
            return new Card(
                    row.callEventId(),
                    row.lineDid(),
                    row.callerNumberMasked(),
                    row.occurredAt(),
                    true,
                    null,
                    null,
                    List.of(),
                    row.acknowledgedByPrincipalId(),
                    row.acknowledgedAt());
        }

        static Card knownCaller(
                JdbcVoiceStore.ScreenPopRow row,
                UUID customerAccountId,
                @Nullable String displayName,
                List<RecentOrder> recentOrders) {
            return new Card(
                    row.callEventId(),
                    row.lineDid(),
                    row.callerNumberMasked(),
                    row.occurredAt(),
                    false,
                    customerAccountId,
                    displayName,
                    recentOrders,
                    row.acknowledgedByPrincipalId(),
                    row.acknowledgedAt());
        }
    }
}
