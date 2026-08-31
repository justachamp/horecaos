package uz.horecaos.platform.ordering.application;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OrderOutcome;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.domain.TerminalOutcomeKind;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;

/**
 * Closing an order with a reason the tenant configured (ADR 0039).
 *
 * <p>This is the half of ADR 0039 that has to exist before amendment is worth
 * anything: without it, a cancelled order, a rejected one and an expired one are
 * one status string, and the cancellation funnel and the write-off report cannot
 * be computed at all — which is exactly where the legacy system is.
 *
 * <p>The reason decides the consequences, and the operator does not. ADR 0039
 * refuses an operator write-off checkbox by name, so the cancel dialog displays
 * what the chosen reason carries and cannot change it.
 *
 * <p>Everything runs inside the transaction that moves the order, because an
 * outcome written afterwards is one that can fail to be written at all.
 */
@Service
public class OrderOutcomeService {

    private final OrderStateService orderState;
    private final OrderOutcomeReasonService reasons;
    private final JdbcOrderStore orders;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    public OrderOutcomeService(
            OrderStateService orderState,
            OrderOutcomeReasonService reasons,
            JdbcOrderStore orders,
            FieldProtection protection,
            ObjectMapper objectMapper) {
        this.orderState = orderState;
        this.reasons = reasons;
        this.orders = orders;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    /**
     * Cancels an order under a reason from the registry.
     *
     * <p>Permitted after confirmation, which the reasonless path is not. The
     * difference is not a relaxation: what ADR 0019 refused to do was guess the
     * stock consequence and the liable party, and a registry reason is precisely
     * the thing that stops it being a guess.
     */
    @Transactional
    public OrderStateService.DecisionResult cancel(
            UUID tenantId, UUID orderId, int expectedVersion, CancelCommand command) {

        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));

        ReasonRow reason = reasons.find(tenantId, command.reasonId())
                .orElseThrow(() -> new OrderOutcomeReasonService.ReasonNotFoundException(command.reasonId()));
        if (reason.kind() != OutcomeReasonKind.CANCELLATION) {
            throw new IllegalArgumentException(
                    "%s is a completion reason and cannot cancel an order".formatted(reason.internalName()));
        }
        if (!"ACTIVE".equals(reason.status())) {
            // An archived reason still resolves for outcomes recorded under it,
            // but nothing new may cite one: an administrator retired it because it
            // was wrong, and a dropdown that still offers it makes the retirement
            // decorative.
            throw new IllegalArgumentException(
                    "%s has been archived and cannot be used".formatted(reason.internalName()));
        }

        // ADR 0017, closed here. Before the reservation is committed the
        // cancellation always releases and the reason's disposition is ignored;
        // after it, the disposition decides and the reservation is never reopened.
        boolean committed = order.confirmedAt() != null;
        StockDisposition disposition =
                committed ? StockDisposition.valueOf(reason.stockDisposition()) : StockDisposition.RELEASE;

        OrderOutcome outcome = new OrderOutcome(
                TerminalOutcomeKind.CANCELLED,
                OutcomeSystemCategory.valueOf(reason.systemCategory()),
                reason.id(),
                reason.version(),
                objectMapper.writeValueAsString(reasons.snapshotOf(reason)),
                disposition,
                LiabilityParty.valueOf(reason.liabilityParty()),
                CustomerRefund.valueOf(reason.customerRefund()),
                committed,
                encryptNote(tenantId, orderId, command.note()));

        return orderState.cancel(
                tenantId,
                orderId,
                expectedVersion,
                reason.systemCategory(),
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                outcome);
    }

    /**
     * Completes an order, naming how (ADR 0039).
     *
     * <p>The reason is validated against the order's fulfilment mode, because
     * without that «Самовывоз выполнен» lands on a delivery order and both the
     * courier SLA report and the external-logistics settlement quietly lose it.
     *
     * @param reasonId the operator's choice, or null to record the one the mode
     *                 implies
     */
    @Transactional
    public OrderStateService.DecisionResult complete(
            UUID tenantId,
            UUID orderId,
            int expectedVersion,
            @Nullable UUID reasonId,
            String actorType,
            String actorId,
            @Nullable String correlationId) {

        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));

        OrderOutcome outcome = null;
        String reasonCode = OutcomeSystemCategory.defaultCompletionFor(order.fulfillmentMode())
                .name();

        if (reasonId != null) {
            ReasonRow reason = reasons.find(tenantId, reasonId)
                    .orElseThrow(() -> new OrderOutcomeReasonService.ReasonNotFoundException(reasonId));
            if (reason.kind() != OutcomeReasonKind.COMPLETION) {
                throw new IllegalArgumentException(
                        "%s is a cancellation reason and cannot complete an order".formatted(reason.internalName()));
            }
            if (!"ACTIVE".equals(reason.status())) {
                throw new IllegalArgumentException(
                        "%s has been archived and cannot be used".formatted(reason.internalName()));
            }
            // A completion reason always names its modes (enforced at authoring
            // time by OrderOutcomeReasonService#validate); NullAway cannot see
            // that cross-field invariant, so it is restated here.
            if (!Objects.requireNonNull(
                            reason.allowedFulfillmentModes(), "a completion reason names its fulfilment modes")
                    .contains(order.fulfillmentMode().name())) {
                throw new IllegalArgumentException(("%s is not valid for a %s order. Recording it "
                                + "would drop the order out of the courier SLA report and the "
                                + "external-logistics settlement.")
                        .formatted(reason.internalName(), order.fulfillmentMode()));
            }
            reasonCode = reason.systemCategory();
            outcome = new OrderOutcome(
                    TerminalOutcomeKind.COMPLETED,
                    OutcomeSystemCategory.valueOf(reason.systemCategory()),
                    reason.id(),
                    reason.version(),
                    objectMapper.writeValueAsString(reasons.snapshotOf(reason)),
                    StockDisposition.NO_EFFECT,
                    null,
                    null,
                    true,
                    null);
        }

        return orderState.advance(
                tenantId,
                orderId,
                OrderStatus.COMPLETED,
                expectedVersion,
                reasonCode,
                actorType,
                actorId,
                correlationId,
                outcome);
    }

    /**
     * Encrypts the operator's free-text note.
     *
     * <p>ADR 0029 has no exception for text somebody promised would be innocuous,
     * and nothing stops an operator typing a customer's phone number into a
     * free-text box. The order id is the associated data, so a ciphertext copied
     * to another order fails to decrypt rather than revealing the wrong person's
     * words.
     */
    private @Nullable String encryptNote(UUID tenantId, UUID orderId, @Nullable String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("ordering.order_outcomes", "note_encrypted", orderId),
                        note)
                .serialize();
    }

    public record CancelCommand(
            UUID reasonId,
            @Nullable String note,
            String actorType,
            String actorId,
            @Nullable String correlationId) {}
}
