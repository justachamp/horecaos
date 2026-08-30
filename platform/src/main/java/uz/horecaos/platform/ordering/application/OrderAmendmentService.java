package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.AmendmentStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore.AmendmentRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderFieldPatch;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionRow;

/**
 * Amending an order without editing it (ADR 0039).
 *
 * <p>ADR 0019 made an order immutable and gave its reasons: mutating financial
 * history cascades into payment, fiscal receipts, inventory and the POS export.
 * Nothing here takes that back. An applied amendment appends a revision carrying
 * its own complete total; revision N−1 is left byte-identical, and there is never
 * a second order for one meal the customer ordered once.
 *
 * <p>Three of the ten commands are carried out. They are the three that change no
 * money — the kitchen note, the callback flag and the change-due figure — which
 * is deliberately the order ADR 0039's rollout puts them in: they exercise the
 * revision machinery with nothing at risk. Every other command is refused by name
 * rather than accepted and half-performed, because a command carried out in the
 * quote and forgotten in the fiscal receipt is the failure the whole design
 * exists to prevent.
 */
@Service
public class OrderAmendmentService {

    /**
     * The ADR 0018 quote TTL.
     *
     * <p>An amendment holding an inventory reservation against an unpriceable
     * quote is the problem that TTL already solves, so the amendment borrows it
     * rather than inventing a second deadline that could disagree with it.
     */
    public static final Duration TTL = Duration.ofMinutes(15);

    /**
     * The POS export process, watched rather than driven.
     *
     * <p>ADR 0011 owns it and nothing writes these rows today. The gate is here
     * anyway: the failure it prevents is a kitchen holding two tickets for one
     * order and cooking the first, and a guard added after the exporter ships is a
     * guard added after the first double ticket.
     */
    private static final Logger log = LoggerFactory.getLogger(OrderAmendmentService.class);

    private final JdbcOrderStore orders;
    private final JdbcOrderAmendmentStore amendments;
    private final JdbcOrderProcessStore processes;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PosExportStatus posExports;

    public OrderAmendmentService(
            JdbcOrderStore orders,
            JdbcOrderAmendmentStore amendments,
            JdbcOrderProcessStore processes,
            AuditRecorder audit,
            ObjectMapper objectMapper,
            Clock clock,
            PosExportStatus posExports) {
        this.orders = orders;
        this.amendments = amendments;
        this.processes = processes;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.posExports = posExports;
    }

    /**
     * Proposes an amendment and, where nothing stands in the way, applies it.
     *
     * <p>The three built commands raise no total, so ADR 0039's
     * {@code PRICED -> APPLIED} edge is the one they take: there is no increase to
     * confirm and no incremental payment to wait for. The states in between exist
     * for the financial commands and are not skipped for them.
     *
     * @param applyOnPrice apply in the same transaction once priced. Refused when
     *                     the amendment raises the total or needs approval, so it
     *                     can never become a way past the customer's agreement
     */
    @Transactional
    public AmendmentResult propose(UUID tenantId, UUID orderId, ProposeCommand command) {
        Instant now = clock.instant();

        // A repeat of one operator's click. Returning what the first one produced
        // rather than proposing again is what makes a retried request harmless,
        // and it is checked before the open-amendment guard so a retry is not
        // mistaken for a second operator.
        Optional<AmendmentRow> replay = amendments.findByIdempotencyKey(tenantId, command.idempotencyKey());
        if (replay.isPresent()) {
            AmendmentRow existing = replay.get();
            return new AmendmentResult(
                    existing,
                    orders.find(tenantId, orderId).map(OrderRow::version).orElse(0),
                    List.of(),
                    true);
        }

        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));

        if (order.version() != command.expectedOrderVersion()) {
            throw new OrderStateService.StaleOrderException(command.expectedOrderVersion(), order.version());
        }
        if (order.status().terminal()) {
            throw new AmendmentNotPermittedException(
                    "An order that is %s has ended. A change to it is a new order.".formatted(order.status()));
        }

        requireBuilt(command.commands());
        requirePosExportSettled(tenantId, orderId);

        // One open amendment per order. The partial unique index is the authority;
        // this read exists only so the second operator is told who has it rather
        // than being handed a constraint violation.
        amendments.findOpen(tenantId, orderId).ifPresent(open -> {
            if (open.expiresAt().isAfter(now)) {
                throw new AmendmentInProgressException(open.id(), open.createdByActorId(), open.expiresAt());
            }
        });

        UUID amendmentId = UUID.randomUUID();
        amendments.insert(new JdbcOrderAmendmentStore.NewAmendment(
                amendmentId,
                tenantId,
                orderId,
                AmendmentStatus.PRICED,
                order.currentRevision(),
                null,
                0L,
                false,
                null,
                command.idempotencyKey(),
                now.plus(TTL),
                command.actorType(),
                command.actorId(),
                now));

        int sequence = 0;
        for (AmendmentCommand issued : command.commands()) {
            sequence++;
            amendments.insertCommand(
                    amendmentId, tenantId, sequence, issued.type(), objectMapper.writeValueAsString(issued.payload()));
        }

        recordAudit(
                order,
                "ordering.order.amendment-proposed",
                command.actorType(),
                command.actorId(),
                command.reason(),
                order.version(),
                Map.of(
                        "amendmentId",
                        amendmentId.toString(),
                        "commands",
                        command.commands().stream().map(c -> c.type().name()).toList(),
                        "baseRevision",
                        order.currentRevision()),
                command.correlationId(),
                now);

        AmendmentRow proposed = amendments.find(tenantId, amendmentId).orElseThrow();
        if (!command.applyOnPrice()) {
            return new AmendmentResult(proposed, order.version(), List.of(), false);
        }
        return apply(
                tenantId,
                orderId,
                amendmentId,
                order.version(),
                command.actorType(),
                command.actorId(),
                command.reason(),
                command.correlationId());
    }

    /**
     * Applies a priced amendment, appending the revision it produces.
     *
     * <p>Both writes are compare-and-set. The order moves only if it is still at
     * the version the caller read, and the amendment moves only if it is still
     * {@code PRICED} — so two operators applying at the same instant produce one
     * revision and one {@code STALE_VERSION}, rather than two revisions for one
     * change.
     */
    @Transactional
    public AmendmentResult apply(
            UUID tenantId,
            UUID orderId,
            UUID amendmentId,
            int expectedOrderVersion,
            String actorType,
            String actorId,
            String reason,
            String correlationId) {

        Instant now = clock.instant();
        AmendmentRow amendment =
                amendments.find(tenantId, amendmentId).orElseThrow(() -> new AmendmentNotFoundException(amendmentId));

        if (!amendment.orderId().equals(orderId)) {
            throw new AmendmentNotFoundException(amendmentId);
        }
        if (amendment.status().terminal()) {
            // Already settled. The caller is told what happened rather than being
            // allowed to apply on top, exactly as a duplicate approval decision is.
            return new AmendmentResult(
                    amendment,
                    orders.find(tenantId, orderId).map(OrderRow::version).orElse(0),
                    List.of(),
                    true);
        }
        if (!amendment.expiresAt().isAfter(now)) {
            amendments.markRejected(tenantId, amendmentId, "EXPIRED", now);
            throw new AmendmentExpiredException(amendment.expiresAt());
        }
        // Enforced in the database as well, on the applied row. Stated twice on
        // purpose: charging more than the customer agreed to is the failure this
        // prevents, and a guard that lives only in one service is a guard the next
        // call path can walk around.
        if (amendment.deltaTotalMinor() > 0 && amendment.confirmationAttestedAt() == null) {
            throw new CustomerConfirmationRequiredException(amendment.deltaTotalMinor());
        }
        if (amendment.requiresApproval() && amendment.approvalRequestId() == null) {
            throw new AmendmentNotPermittedException(
                    "This amendment needs an ADR 0027 approval before it can be applied");
        }

        requirePosExportSettled(tenantId, orderId);

        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));
        if (order.version() != expectedOrderVersion) {
            throw new OrderStateService.StaleOrderException(expectedOrderVersion, order.version());
        }

        var commands = amendments.commands(tenantId, amendmentId);
        OrderFieldPatch patch = patchOf(commands);

        int newRevision = order.currentRevision() + 1;
        RevisionRow previous = orders.revisions(tenantId, orderId).stream()
                .filter(row -> row.revision() == order.currentRevision())
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Order " + orderId + " has no revision " + order.currentRevision()));

        // None of the built commands touches the basket, so the revision carries
        // its predecessor's quote and totals forward unchanged and the delta is
        // zero. Re-accepting the quote would be a second acceptance of one price;
        // recomputing the totals would risk them differing from the ones the
        // customer agreed to for no reason at all.
        orders.insertRevision(new JdbcOrderStore.NewRevision(
                orderId,
                newRevision,
                tenantId,
                "AMENDMENT",
                amendmentId,
                previous.pricingQuoteId(),
                previous.pricingContextHash(),
                previous.currency(),
                previous.subtotalMinor(),
                previous.taxMinor(),
                previous.discountMinor(),
                previous.feeMinor(),
                previous.totalMinor(),
                amendment.deltaTotalMinor(),
                actorType,
                actorId,
                now));

        int orderVersion = orders.applyRevision(
                        tenantId, orderId, expectedOrderVersion, newRevision, patch, actorId, now)
                .orElseThrow(() -> new OrderStateService.StaleOrderException(
                        expectedOrderVersion,
                        orders.find(tenantId, orderId).map(OrderRow::version).orElse(0)));

        amendments
                .markApplied(tenantId, amendmentId, amendment.version(), newRevision, now)
                .orElseThrow(
                        () -> new AmendmentNotPermittedException("The amendment settled while it was being applied"));

        List<String> warnings = warningsFor(order, patch);

        recordAudit(
                order,
                "ordering.order.amendment-applied",
                actorType,
                actorId,
                reason,
                orderVersion,
                Map.of(
                        "amendmentId",
                        amendmentId.toString(),
                        "revision",
                        newRevision,
                        "commands",
                        commands.stream().map(c -> c.commandType().name()).toList(),
                        "deltaTotalMinor",
                        amendment.deltaTotalMinor()),
                correlationId,
                now);

        log.info("Amendment {} applied to order {} as revision {}", amendmentId, orderId, newRevision);

        return new AmendmentResult(amendments.find(tenantId, amendmentId).orElseThrow(), orderVersion, warnings, false);
    }

    /** Records the customer's agreement to an increase, attested by the operator. */
    @Transactional
    public int attestConfirmation(
            UUID tenantId, UUID amendmentId, int expectedVersion, String attestedBy, String channel) {
        Instant now = clock.instant();
        AmendmentRow amendment =
                amendments.find(tenantId, amendmentId).orElseThrow(() -> new AmendmentNotFoundException(amendmentId));

        return amendments
                .attestConfirmation(tenantId, amendmentId, expectedVersion, attestedBy, channel, now)
                .orElseThrow(() -> new OrderStateService.StaleOrderException(expectedVersion, amendment.version()));
    }

    /** Withdraws an open amendment. The row stays: it is evidence of what was tried. */
    @Transactional
    public void withdraw(UUID tenantId, UUID amendmentId, String reasonCode) {
        if (!amendments.markRejected(tenantId, amendmentId, reasonCode, clock.instant())) {
            throw new AmendmentNotFoundException(amendmentId);
        }
    }

    /** Scheduled, not on the request path. */
    @Transactional
    public int expireOverdue(int batchSize) {
        int expired = amendments.expireOverdue(clock.instant(), batchSize);
        if (expired > 0) {
            log.info("Expired {} amendments past their quote TTL", expired);
        }
        return expired;
    }

    public List<AmendmentRow> forOrder(UUID tenantId, UUID orderId) {
        return amendments.forOrder(tenantId, orderId);
    }

    public List<JdbcOrderAmendmentStore.CommandRow> commands(UUID tenantId, UUID amendmentId) {
        return amendments.commands(tenantId, amendmentId);
    }

    // ------------------------------------------------------------------ rules

    private void requireBuilt(List<AmendmentCommand> issued) {
        if (issued.isEmpty()) {
            throw new IllegalArgumentException("An amendment carries at least one command");
        }
        for (AmendmentCommand command : issued) {
            if (!command.type().built()) {
                throw new AmendmentNotPermittedException(("%s is declared by ADR 0039 and not "
                                + "built. It reprices, re-reserves, re-charges or re-fiscalizes, and "
                                + "carrying out part of that would leave state nobody could "
                                + "reconstruct. Place a second order instead.")
                        .formatted(command.type()));
            }
        }
    }

    /**
     * Refuses to amend underneath an export the POS has not acknowledged.
     *
     * <p>The failure being prevented is a kitchen holding two tickets for one
     * order and cooking the first. Nothing writes these process rows yet, so the
     * gate passes for every order today — which is the honest state and is why the
     * check reads the table rather than assuming it is empty.
     */
    /**
     * Refuses an amendment while the till may already be cooking the order.
     *
     * <p>This read the ordering process table until the POS export became
     * automatic. Nothing ever wrote a {@code POS_ORDER_EXPORT} row there — the
     * export has always tracked its own state in {@code integration.pos_order_exports}
     * — so {@code ifPresent} never fired and the guard passed for every order in
     * the platform's history. It looked like protection and was not, which is
     * worse than an absent guard, because the amendment path was written by
     * somebody who believed this stopped them.
     *
     * <p>It now asks the export's own table through {@link PosExportStatus}. No
     * export at all still means yes: a location with no till exports nothing, and
     * refusing amendments there would break the ordinary case to guard one that
     * cannot arise.
     */
    private void requirePosExportSettled(UUID tenantId, UUID orderId) {
        if (!posExports.settledFor(tenantId, orderId)) {
            throw new PosExportUnacknowledgedException(
                    posExports.stateOf(tenantId, orderId).orElse("UNKNOWN"));
        }
    }

    /**
     * Folds the commands into the order-level fields they change.
     *
     * <p>Later commands of one type win, which matters only for a repeated command
     * inside one amendment and is the same answer the operator would expect from
     * typing into the box twice.
     */
    private OrderFieldPatch patchOf(List<JdbcOrderAmendmentStore.CommandRow> commands) {
        String kitchenNote = null;
        Boolean callbackRequested = null;
        Long cashTendered = null;

        for (JdbcOrderAmendmentStore.CommandRow command : commands) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(command.payloadJson(), Map.class);
            switch (command.commandType()) {
                case SET_KITCHEN_NOTE -> kitchenNote = String.valueOf(payload.getOrDefault("note", ""));
                case SET_CALLBACK_REQUESTED -> callbackRequested = Boolean.TRUE.equals(payload.get("requested"));
                case SET_CASH_TENDERED -> cashTendered = ((Number) payload.get("amountMinor")).longValue();
                default -> throw new IllegalStateException("No built handler for " + command.commandType());
            }
        }
        return new OrderFieldPatch(kitchenNote, callbackRequested, cashTendered);
    }

    /**
     * What the operator should be told but not blocked by.
     *
     * <p>ADR 0039: change-due short of the total raises
     * {@code CASH_TENDERED_INSUFFICIENT}, which the operator acknowledges rather
     * than being refused — the customer can hand over more, and a hard refusal
     * here would stop an order over a figure that is a hint rather than money.
     */
    private List<String> warningsFor(OrderRow order, OrderFieldPatch patch) {
        List<String> warnings = new ArrayList<>();
        if (patch.cashTenderedExpectedMinor() != null && patch.cashTenderedExpectedMinor() < order.totalMinor()) {
            warnings.add("CASH_TENDERED_INSUFFICIENT");
        }
        return warnings;
    }

    private void recordAudit(
            OrderRow order,
            String actionCode,
            String actorType,
            String actorId,
            String reason,
            int version,
            Map<String, Object> changed,
            String correlationId,
            Instant now) {

        ActorRef actor =
                switch (actorType == null ? "SERVICE" : actorType) {
                    case "USER" -> ActorRef.user(actorId, null);
                    case "SYSTEM_JOB" -> ActorRef.systemJob(actorId == null ? "ordering" : actorId);
                    default -> ActorRef.service(actorId == null ? "ordering" : actorId);
                };

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .targetVersion((long) version)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because(reason)
                .changed(changed)
                .correlatedBy(correlationId == null ? order.orderId().toString() : correlationId)
                .occurredAt(now)
                .build());
    }

    // --------------------------------------------------------------- commands

    /** One issued command and the payload its type declares. */
    public record AmendmentCommand(AmendmentCommandType type, Map<String, Object> payload) {

        public static AmendmentCommand kitchenNote(String note) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("note", note == null ? "" : note);
            return new AmendmentCommand(AmendmentCommandType.SET_KITCHEN_NOTE, payload);
        }

        public static AmendmentCommand callback(boolean requested) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requested", requested);
            return new AmendmentCommand(AmendmentCommandType.SET_CALLBACK_REQUESTED, payload);
        }

        /**
         * @param amountMinor whole som per ADR 0018. Never a decimal figure: a
         *                    formatter that divides change-due by a hundred shows
         *                    a customer the wrong money
         */
        public static AmendmentCommand cashTendered(long amountMinor) {
            if (amountMinor < 0) {
                throw new IllegalArgumentException("Change-due cannot be negative");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amountMinor", amountMinor);
            return new AmendmentCommand(AmendmentCommandType.SET_CASH_TENDERED, payload);
        }
    }

    public record ProposeCommand(
            int expectedOrderVersion,
            List<AmendmentCommand> commands,
            boolean applyOnPrice,
            String idempotencyKey,
            String reason,
            String actorType,
            String actorId,
            String correlationId) {}

    /**
     * @param replayed whether this call found an already-settled amendment rather
     *                 than doing the work, so a retried request gives the same
     *                 answer as the first
     */
    public record AmendmentResult(AmendmentRow amendment, int orderVersion, List<String> warnings, boolean replayed) {}

    public static class AmendmentNotFoundException extends RuntimeException {
        public AmendmentNotFoundException(UUID amendmentId) {
            super("No amendment " + amendmentId + " on this order");
        }
    }

    /** The command is declared by ADR 0039 and is not carried out in this release. */
    public static class AmendmentNotPermittedException extends RuntimeException {
        public AmendmentNotPermittedException(String message) {
            super(message);
        }
    }

    /** Another operator already has this order open. */
    public static class AmendmentInProgressException extends RuntimeException {

        private final UUID amendmentId;

        public AmendmentInProgressException(UUID amendmentId, String heldBy, Instant expiresAt) {
            super("This order is being amended by %s until %s"
                    .formatted(heldBy == null ? "another operator" : heldBy, expiresAt));
            this.amendmentId = amendmentId;
        }

        public UUID amendmentId() {
            return amendmentId;
        }
    }

    public static class AmendmentExpiredException extends RuntimeException {
        public AmendmentExpiredException(Instant expiredAt) {
            super("The amendment lapsed at %s and applied nothing".formatted(expiredAt));
        }
    }

    public static class CustomerConfirmationRequiredException extends RuntimeException {
        public CustomerConfirmationRequiredException(long deltaMinor) {
            super(("This amendment raises the total by %d and cannot commit until the customer's "
                            + "agreement is recorded")
                    .formatted(deltaMinor));
        }
    }

    /** ADR 0039: never apply while a POS export attempt is unacknowledged. */
    public static class PosExportUnacknowledgedException extends RuntimeException {
        public PosExportUnacknowledgedException(String processStatus) {
            super(("The POS has not acknowledged this order's export (%s). Amending underneath it "
                            + "leaves the kitchen holding two tickets for one order.")
                    .formatted(processStatus));
        }
    }
}
