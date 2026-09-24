package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.ordering.api.OrderAmendmentApplied;
import uz.horecaos.platform.ordering.api.OrderAmendmentProposed;
import uz.horecaos.platform.ordering.api.OrderAmendmentRejected;
import uz.horecaos.platform.ordering.api.OrderCallbackRequested;
import uz.horecaos.platform.ordering.api.OrderCallbackResolved;
import uz.horecaos.platform.ordering.api.OrderRevisionCreated;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.AmendmentStatus;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore.AmendmentRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.CustomerSnapshotPatch;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderFieldPatch;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionRow;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Amending an order without editing it (ADR 0039).
 *
 * <p>ADR 0019 made an order immutable and gave its reasons: mutating financial
 * history cascades into payment, fiscal receipts, inventory and the POS export.
 * Nothing here takes that back. An applied amendment appends a revision carrying
 * its own complete total; revision N−1 is left byte-identical, and there is never
 * a second order for one meal the customer ordered once.
 *
 * <p>Five of the now-twelve commands are carried out. Three change no money — the
 * kitchen note, the callback flag and the change-due figure — which is
 * deliberately the order ADR 0039's rollout puts them in: they exercise the
 * revision machinery with nothing at risk. ADR 0113 (wave P10) adds two more of
 * the same non-financial shape, the courier note and the internal note, once the
 * three had proved the machinery out. Every other command is refused by name
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

    private static final Logger log = LoggerFactory.getLogger(OrderAmendmentService.class);

    /**
     * The ADR 0039 §3.11 default cut point, used only when the configured value
     * fails to resolve to a real, non-terminal {@link OrderStatus} — a bad
     * tenant override must fail toward the documented default, not toward
     * refusing every amendment or, worse, permitting one past every stage.
     */
    private static final OrderStatus DEFAULT_CUT_POINT = OrderStatus.READY;

    /** ADR 0039: the one cash-tender method code ordering itself ever names. */
    private static final String CASH_METHOD_CODE = "CASH";

    private final JdbcOrderStore orders;
    private final JdbcOrderAmendmentStore amendments;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PosExportStatus posExports;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate independently;
    private final CartPricingPort pricing;
    private final QuoteAcceptancePort quoteAcceptance;
    private final InventoryReservationPort inventory;
    private final ApprovalService approvals;
    private final PaymentIntentPort payments;
    private final FieldProtection protection;
    private final ConfigurationResolver configuration;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrderAmendmentService(
            JdbcOrderStore orders,
            JdbcOrderAmendmentStore amendments,
            AuditRecorder audit,
            ObjectMapper objectMapper,
            Clock clock,
            PosExportStatus posExports,
            ApplicationEventPublisher events,
            TransactionTemplate unitOfWork,
            CartPricingPort pricing,
            QuoteAcceptancePort quoteAcceptance,
            InventoryReservationPort inventory,
            ApprovalService approvals,
            PaymentIntentPort payments,
            FieldProtection protection,
            ConfigurationResolver configuration) {
        this.orders = orders;
        this.amendments = amendments;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.posExports = posExports;
        this.events = events;
        this.pricing = pricing;
        this.quoteAcceptance = quoteAcceptance;
        this.inventory = inventory;
        this.approvals = approvals;
        this.payments = payments;
        this.protection = protection;
        this.configuration = configuration;
        // A second template for the one write that has to outlive the exception it
        // accompanies, exactly as PaymentAttemptService needs for the same reason:
        // apply() settles an expired amendment and then refuses the application, and
        // both of those happen inside apply()'s own transaction, so the settlement
        // rolled back with the refusal and the amendment stayed proposed forever.
        this.independently = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Proposes an amendment and, where nothing stands in the way, applies it.
     *
     * <p>The three built commands raise no total, so ADR 0039's
     * {@code PRICED -> APPLIED} edge is the one they take: there is no increase to
     * confirm and no incremental payment to wait for. The states in between exist
     * for the financial commands and are not skipped for them. See {@link
     * ProposeCommand#applyOnPrice} for what governs whether this call also applies.
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
        requireBeforeCutPoint(
                order, command.commands().stream().anyMatch(c -> c.type().financial()));

        // One open amendment per order. The partial unique index is the authority;
        // this read exists only so the second operator is told who has it rather
        // than being handed a constraint violation.
        amendments.findOpen(tenantId, orderId).ifPresent(open -> {
            if (open.expiresAt().isAfter(now)) {
                throw new AmendmentInProgressException(open.id(), open.createdByActorId(), open.expiresAt());
            }
        });

        UUID amendmentId = UUID.randomUUID();

        // Priced once, here, against live data — never again in {@link #apply},
        // which trusts this amendment's own stored quote id exactly the way
        // ADR 0019 checkout prices once and accepts later. A quote nobody ever
        // applies simply expires on the ordinary ADR 0018 TTL; it is never a
        // second charge or a second reservation.
        DecodedAmendment decoded = decode(typedPayloadsOfIssued(command.commands()));
        long deltaTotalMinor = 0L;
        boolean requiresApproval = false;
        UUID quoteId = null;
        if (decoded.basket().needsReprice()) {
            QuoteSnapshot quote = repriceFor(order, decoded.basket(), command.idempotencyKey() + ":quote");
            quoteId = quote.quoteId();
            deltaTotalMinor = quote.totalMinor() - order.totalMinor();

            if (deltaTotalMinor > 0 && !"NOT_REQUIRED".equals(order.paymentStatusProjection())) {
                throw new AmendmentRefusedException(
                        "INCREMENTAL_PAYMENT_NOT_SUPPORTED",
                        ("This order's payment already runs through a provider. Collecting an "
                                        + "additional %d %s needs an incremental-payment path this build does "
                                        + "not have; cancel and take a new order instead.")
                                .formatted(deltaTotalMinor, order.currency()));
            }
            if (deltaTotalMinor < 0) {
                requiresApproval = requiresDecreaseApproval(
                        order,
                        amendmentId,
                        deltaTotalMinor,
                        command.commands(),
                        command.actorType(),
                        command.actorId(),
                        command.reason());
            }
        }
        if (decoded.paymentMethodCode() != null && !"NOT_REQUIRED".equals(order.paymentStatusProjection())) {
            throw new AmendmentRefusedException(
                    "PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND",
                    "This order's payment already runs through a provider. Changing its method needs "
                            + "voiding or refunding that intent, which this build's payments port does "
                            + "not expose; cancel and take a new order instead.");
        }

        amendments.insert(new JdbcOrderAmendmentStore.NewAmendment(
                amendmentId,
                tenantId,
                orderId,
                AmendmentStatus.PRICED,
                order.currentRevision(),
                quoteId,
                deltaTotalMinor,
                requiresApproval,
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

        events.publishEvent(new OrderAmendmentProposed(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                now,
                order.brandId(),
                order.locationId(),
                amendmentId,
                order.currentRevision(),
                command.commands().stream().map(c -> c.type().name()).toList(),
                proposed.status().name(),
                order.version()));

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
            @Nullable String correlationId) {

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
            // In its own transaction, because the refusal below rolls this one's
            // back. Settling the amendment and then throwing from inside the same
            // @Transactional method undid the settlement every time: the amendment
            // stayed open, its one-per-order index stayed held, and the next apply
            // repeated the cycle. The event is published in here for the same
            // reason — BEFORE_COMMIT fires on this inner transaction, so a consumer
            // is told about a rejection the database has actually kept.
            independently.executeWithoutResult(ignored -> {
                if (amendments.markRejected(tenantId, amendmentId, "EXPIRED", now)) {
                    publishRejected(tenantId, amendment, "EXPIRED", now);
                }
            });
            throw new AmendmentExpiredException(amendment.expiresAt());
        }
        // Enforced in the database as well, on the applied row. Stated twice on
        // purpose: charging more than the customer agreed to is the failure this
        // prevents, and a guard that lives only in one service is a guard the next
        // call path can walk around.
        if (amendment.deltaTotalMinor() > 0 && amendment.confirmationAttestedAt() == null) {
            throw new CustomerConfirmationRequiredException(amendment.deltaTotalMinor());
        }

        requirePosExportSettled(tenantId, orderId);

        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));
        if (order.version() != expectedOrderVersion) {
            throw new OrderStateService.StaleOrderException(expectedOrderVersion, order.version());
        }

        var commands = amendments.commands(tenantId, amendmentId);
        requireBeforeCutPoint(
                order, commands.stream().anyMatch(c -> c.commandType().financial()));
        DecodedAmendment decoded = decode(typedPayloadsOfStored(commands));
        List<OrderLineRow> liveLines = orders.lines(tenantId, orderId);

        // Re-derived rather than trusted from the stored column: an approval is
        // consumed here, in this transaction, exactly once — never at propose,
        // which may have run minutes or days earlier in a transaction of its
        // own. requireApproval's own idempotent lookup is what lets this ask
        // again safely; a second identical ask before a decision is made finds
        // the same Pending request rather than raising a duplicate one.
        UUID consumedApprovalId = null;
        if (amendment.requiresApproval()) {
            ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                    ApprovalAction.ORDERING_AMENDMENT_DECREASE.code(),
                    decreaseApprovalHash(
                            amendment.id(),
                            amendment.deltaTotalMinor(),
                            commands.stream().map(c -> c.commandType().name()).toList()),
                    ResourceScope.brand(tenantId, order.brandId()),
                    actorRefOf(actorType, actorId),
                    reason == null || reason.isBlank() ? "Amendment decrease approval" : reason,
                    ApprovalRequestCommand.DEFAULT_VALIDITY));
            switch (outcome) {
                case ApprovalOutcome.NotRequired ignored -> {}
                case ApprovalOutcome.Approved approved -> {
                    outcome.consume();
                    consumedApprovalId = approved.requestId();
                }
                case ApprovalOutcome.Pending pending ->
                    throw new AmendmentRefusedException(
                            "AMENDMENT_PENDING_APPROVAL",
                            "This amendment needs a second signature (request %s) before it can apply"
                                    .formatted(pending.requestId()));
                case ApprovalOutcome.Declined declined ->
                    throw new AmendmentRefusedException(
                            "AMENDMENT_APPROVAL_DECLINED",
                            "The approval for this amendment was declined: %s".formatted(declined.reason()));
            }
        }

        int newRevision = order.currentRevision() + 1;
        RevisionRow previous = orders.revisions(tenantId, orderId).stream()
                .filter(row -> row.revision() == order.currentRevision())
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Order " + orderId + " has no revision " + order.currentRevision()));

        long deltaTotalMinor = amendment.deltaTotalMinor();
        boolean fiscalCorrectionRequired = false;
        UUID quoteId = previous.pricingQuoteId();
        String contextHash = previous.pricingContextHash();
        long subtotalMinor = previous.subtotalMinor();
        long taxMinor = previous.taxMinor();
        long discountMinor = previous.discountMinor();
        long feeMinor = previous.feeMinor();
        long totalMinor = previous.totalMinor();

        if (decoded.basket().needsReprice()) {
            UUID storedQuoteId = Objects.requireNonNull(
                    amendment.quoteId(), "A repricing amendment always priced a quote at propose time");
            QuoteSnapshot quote = quoteAcceptance
                    .quoteSnapshot(tenantId, storedQuoteId)
                    .orElseThrow(() -> new AmendmentRefusedException(
                            "AMENDMENT_QUOTE_EXPIRED",
                            "The priced quote behind this amendment is gone; re-propose it"));
            QuoteAcceptance acceptance = quoteAcceptance.acceptQuote(tenantId, storedQuoteId, quote.contextHash());
            if (!acceptance.isAccepted()) {
                throw new AmendmentRefusedException(
                        "AMENDMENT_QUOTE_EXPIRED",
                        "The priced quote behind this amendment is %s; re-propose the amendment"
                                .formatted(acceptance.outcome()));
            }
            if (decoded.basket().address() != null && !quote.isDeliveryFeeUsable()) {
                throw new AmendmentRefusedException(
                        quote.deliveryOutcome() == DeliveryFeeOutcome.OUT_OF_ZONE
                                ? "DELIVERY_ADDRESS_OUT_OF_ZONE"
                                : "DELIVERY_ADDRESS_NOT_SERVICEABLE",
                        "The new address cannot be delivered to: %s".formatted(quote.deliveryOutcome()));
            }

            reserveIncrease(tenantId, order, storedQuoteId, quote, liveLines, decoded.basket());
            writeLineChanges(tenantId, orderId, liveLines, decoded.basket(), quote, newRevision);

            quoteId = quote.quoteId();
            contextHash = quote.contextHash();
            subtotalMinor = quote.subtotalMinor();
            taxMinor = quote.taxMinor();
            discountMinor = quote.discountMinor();
            feeMinor = quote.feeMinor();
            totalMinor = quote.totalMinor();
            deltaTotalMinor = totalMinor - previous.totalMinor();
            fiscalCorrectionRequired = true;
        }

        String paymentProjectionPatch = null;
        if (decoded.paymentMethodCode() != null) {
            paymentProjectionPatch = applyPaymentMethodChange(tenantId, order, amendment, decoded.paymentMethodCode());
            fiscalCorrectionRequired = true;
        }

        writeSnapshotChange(tenantId, orderId, decoded.snapshot());

        OrderFieldPatch patch = new OrderFieldPatch(
                decoded.orderFields().kitchenNote(),
                decoded.orderFields().callbackRequested(),
                decoded.orderFields().cashTenderedExpectedMinor(),
                decoded.orderFields().promisedAt(),
                decoded.orderFields().promiseBasis(),
                paymentProjectionPatch,
                decoded.basket().needsReprice()
                        ? new JdbcOrderStore.RevisionTotals(
                                subtotalMinor, taxMinor, discountMinor, feeMinor, totalMinor)
                        : null);

        // A non-repricing amendment carries its predecessor's quote and totals
        // forward unchanged and the delta stays zero — re-accepting the quote
        // would be a second acceptance of one price, and recomputing the totals
        // would risk them differing from the ones the customer agreed to for no
        // reason at all. A repricing one carries the freshly accepted quote's
        // own totals instead, computed above.
        orders.insertRevision(new JdbcOrderStore.NewRevision(
                orderId,
                newRevision,
                tenantId,
                "AMENDMENT",
                amendmentId,
                quoteId,
                contextHash,
                previous.currency(),
                subtotalMinor,
                taxMinor,
                discountMinor,
                feeMinor,
                totalMinor,
                deltaTotalMinor,
                fiscalCorrectionRequired,
                actorType,
                actorId,
                now));

        int orderVersion = orders.applyRevision(
                        tenantId, orderId, expectedOrderVersion, newRevision, patch, actorId, now)
                .orElseThrow(() -> new OrderStateService.StaleOrderException(
                        expectedOrderVersion,
                        orders.find(tenantId, orderId).map(OrderRow::version).orElse(0)));

        int amendmentVersion = amendments
                .markApplied(tenantId, amendmentId, amendment.version(), newRevision, consumedApprovalId, now)
                .orElseThrow(
                        () -> new AmendmentNotPermittedException("The amendment settled while it was being applied"));

        List<String> warnings = warningsFor(order, patch);

        events.publishEvent(new OrderAmendmentApplied(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                now,
                order.brandId(),
                order.locationId(),
                amendmentId,
                newRevision,
                commands.stream().map(c -> c.commandType().name()).toList(),
                deltaTotalMinor,
                orderVersion));

        events.publishEvent(new OrderRevisionCreated(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                now,
                order.brandId(),
                order.locationId(),
                newRevision,
                amendmentId,
                previous.currency(),
                previous.totalMinor(),
                deltaTotalMinor,
                orderVersion));

        // ADR 0039: SET_CALLBACK_REQUESTED both raises and clears the flag, so the
        // one field on the patch tells the two facts apart rather than needing an
        // eleventh command.
        if (patch.callbackRequested() != null) {
            if (patch.callbackRequested()) {
                events.publishEvent(new OrderCallbackRequested(
                        UUID.randomUUID(),
                        new TenantId(tenantId),
                        orderId,
                        now,
                        order.brandId(),
                        order.locationId(),
                        amendmentId,
                        orderVersion));
            } else {
                events.publishEvent(new OrderCallbackResolved(
                        UUID.randomUUID(),
                        new TenantId(tenantId),
                        orderId,
                        now,
                        order.brandId(),
                        order.locationId(),
                        amendmentId,
                        orderVersion));
            }
        }

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
                        "amendmentVersion",
                        amendmentVersion,
                        "revision",
                        newRevision,
                        "commands",
                        commands.stream().map(c -> c.commandType().name()).toList(),
                        "deltaTotalMinor",
                        deltaTotalMinor),
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
        AmendmentRow amendment =
                amendments.find(tenantId, amendmentId).orElseThrow(() -> new AmendmentNotFoundException(amendmentId));
        Instant now = clock.instant();
        if (!amendments.markRejected(tenantId, amendmentId, reasonCode, now)) {
            throw new AmendmentNotFoundException(amendmentId);
        }

        publishRejected(tenantId, amendment, reasonCode, now);
    }

    /**
     * One rejection fact for both paths that produce one — an operator withdrawing
     * an amendment, and {@code apply} finding it past its TTL.
     *
     * <p>The amendment's own foreign key guarantees the order it names still
     * exists, so this is the same orElseThrow every other method here uses rather
     * than a defensive null.
     */
    private void publishRejected(UUID tenantId, AmendmentRow amendment, String reasonCode, Instant now) {
        OrderRow order = orders.find(tenantId, amendment.orderId())
                .orElseThrow(() -> new OrderStateService.OrderNotFoundException(amendment.orderId()));
        events.publishEvent(new OrderAmendmentRejected(
                UUID.randomUUID(),
                new TenantId(tenantId),
                amendment.orderId(),
                now,
                order.brandId(),
                order.locationId(),
                amendment.id(),
                amendment.baseRevision(),
                reasonCode));
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

    /**
     * The free-text note a kitchen/courier/internal-note command carries, for
     * the amendment history view (orders.md §3.6) — {@code null} for every
     * other command type.
     *
     * <p>{@code SET_COURIER_NOTE} and {@code SET_INTERNAL_NOTE} (ADR 0113,
     * wave P10) have no order column to be read back from the way {@code
     * kitchenNote} is on {@code OrderDetailResponse} — this method, and the
     * history view built on it, is their only read path. {@code
     * SET_KITCHEN_NOTE} answers here too, for the same history, even though
     * its current value also has a column: the two are not required to agree
     * mid-amendment, and a history entry should show what that command
     * actually said rather than the order's value as of the read.
     */
    public @Nullable String noteOf(JdbcOrderAmendmentStore.CommandRow command) {
        if (command.commandType() != AmendmentCommandType.SET_KITCHEN_NOTE
                && command.commandType() != AmendmentCommandType.SET_COURIER_NOTE
                && command.commandType() != AmendmentCommandType.SET_INTERNAL_NOTE) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(command.payloadJson(), Map.class);
        Object note = payload.get("note");
        return note == null ? null : String.valueOf(note);
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
     * Refuses an amendment while the till may already be cooking the order.
     *
     * <p>The failure being prevented is a kitchen holding two tickets for one
     * order and cooking the first. This read the ordering process table until
     * the POS export became automatic. Nothing ever wrote a {@code
     * POS_ORDER_EXPORT} row there — the export has always tracked its own state
     * in {@code integration.pos_order_exports} — so {@code ifPresent} never fired
     * and the guard passed for every order in the platform's history. It looked
     * like protection and was not, which is worse than an absent guard, because
     * the amendment path was written by somebody who believed this stopped them.
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
     * Resolves the ADR 0039 §3.11 financial amendment cut point for this
     * order's own scope, floored to a non-terminal status. A bad or
     * unrecognised tenant override fails toward {@link #DEFAULT_CUT_POINT}
     * rather than toward refusing every amendment or permitting one past
     * every stage.
     */
    private OrderStatus cutPointStatus(UUID tenantId, UUID brandId, UUID locationId) {
        String configured = configuration.value(
                OrderingConfigurationKeys.AMENDMENT_CUT_POINT_STATUS,
                ResourceScope.location(tenantId, brandId, locationId));
        if (configured == null) {
            return DEFAULT_CUT_POINT;
        }
        try {
            OrderStatus status = OrderStatus.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            return status.terminal() ? DEFAULT_CUT_POINT : status;
        } catch (IllegalArgumentException badValue) {
            return DEFAULT_CUT_POINT;
        }
    }

    /**
     * ADR 0039 §3.11: "Financial commands stop at a cut point ... past it the
     * answer to 'add a dessert' is a second order." {@link OrderStatus} is
     * declared in the order the ADR 0019 machine actually moves through for
     * every non-terminal status, so comparing ordinals is comparing position
     * in that flow — checked again in {@link #apply} because the order may
     * have moved on since {@link #propose} priced it.
     */
    private void requireBeforeCutPoint(OrderRow order, boolean anyFinancial) {
        if (!anyFinancial) {
            return;
        }
        OrderStatus cutPoint = cutPointStatus(order.tenantId(), order.brandId(), order.locationId());
        if (order.status().ordinal() >= cutPoint.ordinal()) {
            throw new AmendmentRefusedException(
                    "AMENDMENT_PAST_CUT_POINT",
                    ("This order is %s, at or past the %s cut point (ADR 0039 §3.11). "
                                    + "Take a second order instead of amending this one.")
                            .formatted(order.status(), cutPoint));
        }
    }

    /**
     * ADR 0039 §3.11: "A decrease above a configured amount needs ADR 0027
     * four-eyes approval." The request is raised here, at propose, rather
     * than deferred to apply — a Pending outcome must reach the checker's
     * queue the moment the size is known, not only when somebody happens to
     * retry applying the amendment.
     */
    private boolean requiresDecreaseApproval(
            OrderRow order,
            UUID amendmentId,
            long deltaTotalMinor,
            List<AmendmentCommand> commands,
            String actorType,
            String actorId,
            String reason) {
        long threshold = Objects.requireNonNull(
                configuration.value(
                        OrderingConfigurationKeys.AMENDMENT_DECREASE_APPROVAL_THRESHOLD_MINOR,
                        ResourceScope.brand(order.tenantId(), order.brandId())),
                "ordering.amendment_decrease_approval_threshold_minor declares a code default and never "
                        + "terminates on explicit null");
        if (-deltaTotalMinor < threshold) {
            return false;
        }
        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.ORDERING_AMENDMENT_DECREASE.code(),
                decreaseApprovalHash(
                        amendmentId,
                        deltaTotalMinor,
                        commands.stream().map(c -> c.type().name()).toList()),
                ResourceScope.brand(order.tenantId(), order.brandId()),
                actorRefOf(actorType, actorId),
                reason == null || reason.isBlank() ? "Amendment decrease approval" : reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        return !(outcome instanceof ApprovalOutcome.NotRequired);
    }

    /**
     * What a signature on a decreasing amendment covers: which amendment, by
     * how much, and which commands produced it — never the actor, the
     * idempotency key or the correlation id, for the same reasons {@code
     * OrderRemedyService#refundApprovalHash} excludes them. Must resolve
     * identically whether called from {@link #propose} (which raises the
     * request) or {@link #apply} (which spends it, possibly in a separate
     * transaction, possibly minutes or days later) — every component here is
     * a value stored on the amendment row itself, never derived fresh.
     */
    private record DecreaseApprovalParameters(UUID amendmentId, long deltaTotalMinor, List<String> commandTypes) {}

    private String decreaseApprovalHash(UUID amendmentId, long deltaTotalMinor, List<String> commandTypes) {
        return ApprovalParameters.of(new DecreaseApprovalParameters(amendmentId, deltaTotalMinor, commandTypes))
                .hash();
    }

    private ActorRef actorRefOf(@Nullable String actorType, @Nullable String actorId) {
        return switch (actorType == null ? "SERVICE" : actorType) {
            case "USER" -> ActorRef.user(actorId == null ? "unknown-user" : actorId, null);
            case "SYSTEM_JOB" -> ActorRef.systemJob(actorId == null ? "ordering" : actorId);
            default -> ActorRef.service(actorId == null ? "ordering" : actorId);
        };
    }

    // ---------------------------------------------- wave 10: decoding a batch

    /** One command's type and payload, whichever of the two shapes it came from. */
    private record TypedPayload(AmendmentCommandType type, Map<String, Object> payload) {}

    private static List<TypedPayload> typedPayloadsOfIssued(List<AmendmentCommand> commands) {
        return commands.stream()
                .map(c -> new TypedPayload(c.type(), c.payload()))
                .toList();
    }

    private List<TypedPayload> typedPayloadsOfStored(List<JdbcOrderAmendmentStore.CommandRow> rows) {
        return rows.stream()
                .map(row -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = objectMapper.readValue(row.payloadJson(), Map.class);
                    return new TypedPayload(row.commandType(), payload);
                })
                .toList();
    }

    /** One line {@link AmendmentCommandType#ADD_LINES} adds, matched back to its priced quote by key. */
    private record NewLine(String lineKey, UUID variantId, int quantity) {}

    /**
     * What a batch of commands asks for in the quote and the basket — decoded
     * once and used both to build the {@link CartPricingPort} request in
     * {@link #propose} and to decide which {@code order_lines} rows to close
     * or insert in {@link #apply}.
     */
    private record FinancialIntent(
            List<NewLine> addedLines,
            Map<UUID, Integer> changedQuantities,
            @Nullable DeliveryDestination address) {

        boolean needsReprice() {
            return !addedLines.isEmpty() || !changedQuantities.isEmpty() || address != null;
        }
    }

    /** What one amendment batch changes, decoded once from its commands (ADR 0039). */
    private record DecodedAmendment(
            OrderFieldPatch orderFields,
            @Nullable SnapshotChange snapshot,
            FinancialIntent basket,
            @Nullable String paymentMethodCode) {}

    /**
     * The plaintext {@code order_customer_snapshots} fields one amendment
     * changes, before {@link #writeSnapshotChange} encrypts them. Never held
     * longer than the one transaction that produced it and never logged —
     * see {@link DeliveryDestination#toString()} for why the address itself
     * prints nothing.
     */
    private record SnapshotChange(
            @Nullable String recipientName,
            @Nullable String recipientPhone,
            @Nullable DeliveryDestination address,
            @Nullable String deliveryInstructions) {}

    /**
     * Folds the commands into the order-level fields, the customer snapshot
     * and the basket they change. Later commands of one type win, which
     * matters only for a repeated command inside one amendment and is the
     * same answer the operator would expect from typing into the box twice.
     */
    private DecodedAmendment decode(List<TypedPayload> commands) {
        String kitchenNote = null;
        Boolean callbackRequested = null;
        Long cashTendered = null;
        Instant promisedAt = null;
        String promiseBasis = null;
        String recipientName = null;
        String recipientPhone = null;
        DeliveryDestination address = null;
        String deliveryInstructions = null;
        String paymentMethodCode = null;
        List<NewLine> addedLines = new ArrayList<>();
        Map<UUID, Integer> changedQuantities = new LinkedHashMap<>();
        int newLineSequence = 0;

        for (TypedPayload command : commands) {
            Map<String, Object> payload = command.payload();
            switch (command.type()) {
                case SET_KITCHEN_NOTE -> kitchenNote = String.valueOf(payload.getOrDefault("note", ""));
                case SET_CALLBACK_REQUESTED -> callbackRequested = Boolean.TRUE.equals(payload.get("requested"));
                case SET_CASH_TENDERED ->
                    cashTendered = ((Number) Objects.requireNonNull(
                                    payload.get("amountMinor"), "cashTendered command has no amount"))
                            .longValue();
                // ADR 0113 (wave P10): neither touches an order field or the
                // basket. Falling into this switch at all, rather than being
                // filtered out before the loop, is deliberate: it keeps this
                // switch exhaustive over every built command type, the same
                // guarantee the default branch below enforces.
                case SET_COURIER_NOTE, SET_INTERNAL_NOTE -> {
                    // Intentionally no-op.
                }
                case CHANGE_CONTACT -> {
                    recipientName = asStringOrNull(payload.get("recipientName"));
                    recipientPhone = asStringOrNull(payload.get("recipientPhone"));
                }
                case CHANGE_FULFILLMENT_TIME -> {
                    promisedAt = Instant.parse(String.valueOf(Objects.requireNonNull(
                            payload.get("promisedAt"), "CHANGE_FULFILLMENT_TIME command has no promisedAt")));
                    promiseBasis = "SCHEDULED_SLOT";
                }
                case CHANGE_PAYMENT_METHOD ->
                    paymentMethodCode = asStringOrNull(Objects.requireNonNull(
                            payload.get("paymentMethodCode"), "CHANGE_PAYMENT_METHOD command has no target method"));
                case CHANGE_DELIVERY_ADDRESS -> {
                    address = new DeliveryDestination(
                            asStringOrEmpty(payload.get("line1")),
                            asStringOrEmpty(payload.get("line2")),
                            asStringOrEmpty(payload.get("city")),
                            asStringOrEmpty(payload.get("district")),
                            asStringOrEmpty(payload.get("postalCode")),
                            asStringOrEmpty(payload.get("entrance")),
                            asStringOrEmpty(payload.get("floor")),
                            asStringOrEmpty(payload.get("apartment")),
                            asStringOrEmpty(payload.get("landmark")),
                            asDouble(Objects.requireNonNull(
                                    payload.get("latitude"), "CHANGE_DELIVERY_ADDRESS command has no latitude")),
                            asDouble(Objects.requireNonNull(
                                    payload.get("longitude"), "CHANGE_DELIVERY_ADDRESS command has no longitude")));
                    deliveryInstructions = asStringOrNull(payload.get("deliveryInstructions"));
                    recipientName = asStringOrNull(payload.get("recipientName"));
                    recipientPhone = asStringOrNull(payload.get("recipientPhone"));
                }
                case ADD_LINES -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> lines = (List<Map<String, Object>>)
                            Objects.requireNonNull(payload.get("lines"), "ADD_LINES command has no lines");
                    for (Map<String, Object> line : lines) {
                        if (!asUuidList(line.get("modifierOptionIds")).isEmpty()) {
                            throw new AmendmentRefusedException(
                                    "LINE_MODIFIERS_NOT_SUPPORTED",
                                    "Adding a line with modifiers is not built in this release; add the "
                                            + "base item and note the modifier for the kitchen instead");
                        }
                        addedLines.add(new NewLine(
                                "amend-new:" + newLineSequence++,
                                asUuid(Objects.requireNonNull(line.get("variantId"), "A new line needs a variant")),
                                asInt(Objects.requireNonNull(line.get("quantity"), "A new line needs a quantity"))));
                    }
                }
                case CHANGE_LINE_QUANTITY ->
                    changedQuantities.put(
                            asUuid(Objects.requireNonNull(
                                    payload.get("orderLineId"), "CHANGE_LINE_QUANTITY command has no orderLineId")),
                            asInt(Objects.requireNonNull(
                                    payload.get("quantity"), "CHANGE_LINE_QUANTITY command has no quantity")));
                default -> throw new IllegalStateException("No built handler for " + command.type());
            }
        }

        OrderFieldPatch orderFields =
                new OrderFieldPatch(kitchenNote, callbackRequested, cashTendered, promisedAt, promiseBasis, null, null);
        SnapshotChange snapshot = recipientName != null || recipientPhone != null || address != null
                ? new SnapshotChange(recipientName, recipientPhone, address, deliveryInstructions)
                : null;
        return new DecodedAmendment(
                orderFields,
                snapshot,
                new FinancialIntent(List.copyOf(addedLines), Map.copyOf(changedQuantities), address),
                paymentMethodCode);
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }

    private static double asDouble(Object value) {
        return ((Number) value).doubleValue();
    }

    private static @Nullable String asStringOrNull(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * {@link DeliveryDestination}'s structured fields carry no {@code
     * @Nullable} of their own — {@link DeliveryDestination#addressLine()}
     * already treats a blank component as absent — so an operator who leaves
     * подъезд/этаж/квартира/ориентир empty gets {@code ""}, never a null that
     * would fail the record's own constructor.
     */
    private static String asStringOrEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> asUuidList(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Object>) value)
                .stream().map(OrderAmendmentService::asUuid).toList();
    }

    // ----------------------------------------------- wave 10: repricing lines

    /**
     * Reprices the whole live basket through the identical {@link
     * CartPricingPort} entry point {@code CartService#price} and operator
     * checkout both use, so an amendment, a storefront cart and an
     * operator-placed order can never price the same basket two different
     * ways. Called exactly once, from {@link #propose}; {@link #apply} trusts
     * the stored quote id rather than pricing a second time — see that
     * method's own doc.
     */
    private QuoteSnapshot repriceFor(OrderRow order, FinancialIntent intent, String idempotencyKey) {
        List<OrderLineRow> liveLines = orders.lines(order.tenantId(), order.orderId());
        Map<UUID, List<UUID>> modifiersByLine = orders.lineModifiers(order.tenantId(), order.orderId()).stream()
                .collect(Collectors.groupingBy(
                        JdbcOrderStore.OrderModifierRow::orderLineId,
                        Collectors.mapping(JdbcOrderStore.OrderModifierRow::sourceOptionId, Collectors.toList())));

        List<CartPricingPort.PricingCommand.Item> items = new ArrayList<>();
        for (OrderLineRow line : liveLines) {
            int quantity = intent.changedQuantities().getOrDefault(line.lineId(), line.quantity());
            if (intent.changedQuantities().containsKey(line.lineId()) && quantity <= line.quantity()) {
                throw new AmendmentRefusedException(
                        "QUANTITY_DECREASE_NOT_SUPPORTED",
                        ("Line %s cannot be reduced: releasing already-committed stock has no ADR "
                                        + "0017 primitive in this build yet (a return-to-stock or write-off "
                                        + "movement, not one of hold/commit/release). Withdraw this amendment "
                                        + "and place a new order for the corrected quantity.")
                                .formatted(line.lineId()));
            }
            items.add(new CartPricingPort.PricingCommand.Item(
                    line.lineId().toString(),
                    line.sourceVariantId(),
                    quantity,
                    modifiersByLine.getOrDefault(line.lineId(), List.of())));
        }
        for (UUID targeted : intent.changedQuantities().keySet()) {
            if (liveLines.stream().noneMatch(line -> line.lineId().equals(targeted))) {
                throw new AmendmentRefusedException(
                        "ORDER_LINE_NOT_FOUND", "Line " + targeted + " is not a live line on this order");
            }
        }
        for (NewLine added : intent.addedLines()) {
            items.add(new CartPricingPort.PricingCommand.Item(
                    added.lineKey(), added.variantId(), added.quantity(), List.of()));
        }

        if (intent.address() != null && order.fulfillmentMode() != FulfillmentMode.DELIVERY) {
            throw new AmendmentRefusedException(
                    "DELIVERY_ADDRESS_NOT_APPLICABLE",
                    "A " + order.fulfillmentMode() + " order has nowhere to deliver to");
        }
        GeoPoint destinationPoint = intent.address() != null
                ? new GeoPoint(intent.address().latitude(), intent.address().longitude())
                : currentDestinationPoint(order);
        CartPricingPort.PricingCommand.Delivery delivery = destinationPoint == null
                ? null
                : new CartPricingPort.PricingCommand.Delivery(destinationPoint, PricingAuthority.HORECAOS);

        try {
            return pricing.priceCart(new CartPricingPort.PricingCommand(
                    order.tenantId(),
                    order.brandId(),
                    order.locationId(),
                    order.customerAccountId(),
                    order.channelCode(),
                    items,
                    idempotencyKey,
                    null,
                    delivery));
        } catch (CartPricingPort.PricingRefusedException refused) {
            throw new AmendmentRefusedException(
                    refused.code(), Objects.requireNonNullElse(refused.getMessage(), refused.code()));
        }
    }

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";

    /** The order's current delivery point, decrypted for repricing only — never logged, never returned whole. */
    private @Nullable GeoPoint currentDestinationPoint(OrderRow order) {
        if (order.fulfillmentMode() != FulfillmentMode.DELIVERY) {
            return null;
        }
        var snapshot =
                orders.customerSnapshot(order.tenantId(), order.orderId()).orElse(null);
        if (snapshot == null || snapshot.addressEncrypted() == null) {
            return null;
        }
        String json = protection.reveal(
                order.tenantId(),
                uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(snapshot.addressEncrypted()),
                new RecordRef(SNAPSHOT_TABLE, "address_encrypted", order.orderId()),
                "AMENDMENT_REPRICE");
        DeliveryDestination destination = objectMapper.readValue(json, DeliveryDestination.class);
        return new GeoPoint(destination.latitude(), destination.longitude());
    }

    /**
     * Reserves the increase only — never the whole repriced basket, which
     * would double-count stock a still-live line already holds. Nothing to
     * reserve (a fulfilment-time or address-only reprice) is a no-op, never a
     * call with an empty map.
     */
    private void reserveIncrease(
            UUID tenantId,
            OrderRow order,
            UUID quoteId,
            QuoteSnapshot quote,
            List<OrderLineRow> liveLines,
            FinancialIntent intent) {
        Map<UUID, Integer> increaseByVariant = new LinkedHashMap<>();
        Map<UUID, Integer> liveQuantityByLine =
                liveLines.stream().collect(Collectors.toMap(OrderLineRow::lineId, OrderLineRow::quantity));
        Map<UUID, UUID> liveVariantByLine =
                liveLines.stream().collect(Collectors.toMap(OrderLineRow::lineId, OrderLineRow::sourceVariantId));

        intent.changedQuantities().forEach((lineId, newQuantity) -> {
            int delta = newQuantity - liveQuantityByLine.getOrDefault(lineId, 0);
            if (delta > 0) {
                UUID variantId = liveVariantByLine.get(lineId);
                increaseByVariant.merge(variantId, delta, Integer::sum);
            }
        });
        for (NewLine added : intent.addedLines()) {
            increaseByVariant.merge(added.variantId(), added.quantity(), Integer::sum);
        }
        if (increaseByVariant.isEmpty()) {
            return;
        }

        ReservationResult reservation = inventory.reserveForQuote(
                tenantId, order.brandId(), order.locationId(), quoteId, quote.expiresAt(), increaseByVariant);
        if (!reservation.isHeld()) {
            throw new AmendmentRefusedException(
                    "INVENTORY_UNAVAILABLE", "The added quantity is not available: " + reservation.refusal());
        }
        if (!inventory.commit(tenantId, quoteId)) {
            throw new AmendmentRefusedException(
                    "INVENTORY_UNAVAILABLE", "The reservation for this amendment settled before it could be committed");
        }
    }

    /**
     * ADR 0039 {@code CHANGE_PAYMENT_METHOD}: {@code CASH} at either end is
     * carried out directly. Anything else on the <em>current</em> side needs
     * voiding or refunding an intent {@link PaymentIntentPort} does not
     * expose, and is refused before this is ever called (see {@link
     * #propose}). Anything else on the <em>new</em> side opens a fresh
     * intent — nothing to void, because nothing was ever captured.
     *
     * @return the payment projection to patch onto the order, or null when
     *         switching to {@code CASH} leaves the already-{@code
     *         NOT_REQUIRED} projection alone
     */
    private @Nullable String applyPaymentMethodChange(
            UUID tenantId, OrderRow order, AmendmentRow amendment, String paymentMethodCode) {
        if (CASH_METHOD_CODE.equalsIgnoreCase(paymentMethodCode)) {
            return null;
        }
        if (!payments.canAcceptPayment(tenantId, order.locationId(), paymentMethodCode)) {
            throw new AmendmentRefusedException(
                    "PAYMENT_METHOD_NOT_ACCEPTED", "This location cannot accept " + paymentMethodCode);
        }
        payments.createIntent(
                tenantId,
                order.orderId(),
                order.totalMinor(),
                order.currency(),
                paymentMethodCode,
                amendment.idempotencyKey() + ":payment-method");
        boolean paymentFirst = payments.paymentRequiredBeforeConfirmation(tenantId, order.orderId(), paymentMethodCode);
        return paymentFirst ? "PENDING" : "NOT_REQUIRED";
    }

    /**
     * Closes and appends {@code order_lines} rows for {@code ADD_LINES}/{@code
     * CHANGE_LINE_QUANTITY} — never edits one in place, exactly as V0022's own
     * comment on the table describes for a future quantity change. Matched
     * back to the amendment's own already-accepted {@code quote} by the
     * identical line key {@link #repriceFor} priced it under, so what gets
     * written is what was actually quoted, not a second, independent
     * computation of it. A no-op for a batch that never touched the basket.
     */
    private void writeLineChanges(
            UUID tenantId,
            UUID orderId,
            List<OrderLineRow> liveLines,
            FinancialIntent intent,
            QuoteSnapshot quote,
            int newRevision) {
        if (intent.addedLines().isEmpty() && intent.changedQuantities().isEmpty()) {
            return;
        }
        Map<String, QuoteSnapshot.Line> quotedByKey =
                quote.lines().stream().collect(Collectors.toMap(QuoteSnapshot.Line::lineKey, l -> l));
        Map<UUID, OrderLineRow> liveById = liveLines.stream().collect(Collectors.toMap(OrderLineRow::lineId, l -> l));
        int nextNumber = orders.nextLineNumber(tenantId, orderId);

        for (Map.Entry<UUID, Integer> changed : intent.changedQuantities().entrySet()) {
            UUID lineId = changed.getKey();
            OrderLineRow original =
                    Objects.requireNonNull(liveById.get(lineId), "Already validated as a live line by repriceFor");
            QuoteSnapshot.Line quoted = Objects.requireNonNull(
                    quotedByKey.get(lineId.toString()), "The accepted quote always prices every changed line");
            if (!orders.closeLine(tenantId, orderId, lineId, newRevision)) {
                throw new AmendmentRefusedException(
                        "ORDER_LINE_NOT_FOUND", "Line " + lineId + " is no longer live on this order");
            }
            orders.insertLine(
                    UUID.randomUUID(),
                    tenantId,
                    orderId,
                    nextNumber++,
                    original.sourceProductId(),
                    original.sourceVariantId(),
                    quoted.descriptionSnapshot(),
                    original.variantName(),
                    original.sku(),
                    quoted.quantity(),
                    quoted.unitAmountMinor(),
                    quoted.baseAmountMinor(),
                    quoted.finalAmountMinor(),
                    quoted.taxAmountMinor(),
                    null);
        }

        for (NewLine added : intent.addedLines()) {
            QuoteSnapshot.Line quoted = Objects.requireNonNull(
                    quotedByKey.get(added.lineKey()), "The accepted quote always prices every added line");
            orders.insertLine(
                    UUID.randomUUID(),
                    tenantId,
                    orderId,
                    nextNumber++,
                    // No catalog lookup here (unlike checkout's CheckoutOrderWriter):
                    // an amendment has no cart to carry a product id, and the quote's
                    // own snapshot description is what the customer is actually shown.
                    // The same fallback CheckoutOrderWriter already takes for a variant
                    // its own catalog lookup could not describe.
                    null,
                    quoted.variantId(),
                    quoted.descriptionSnapshot(),
                    null,
                    null,
                    quoted.quantity(),
                    quoted.unitAmountMinor(),
                    quoted.baseAmountMinor(),
                    quoted.finalAmountMinor(),
                    quoted.taxAmountMinor(),
                    null);
        }
    }

    /** Encrypts and writes {@link SnapshotChange}, or does nothing when the batch never touched it. */
    private void writeSnapshotChange(UUID tenantId, UUID orderId, @Nullable SnapshotChange change) {
        if (change == null) {
            return;
        }
        orders.updateCustomerSnapshot(
                tenantId,
                orderId,
                new CustomerSnapshotPatch(
                        protectOrNull(tenantId, orderId, "display_name_encrypted", change.recipientName()),
                        protectOrNull(tenantId, orderId, "contact_encrypted", change.recipientPhone()),
                        change.address() == null
                                ? null
                                : protectOrNull(
                                        tenantId,
                                        orderId,
                                        "address_encrypted",
                                        objectMapper.writeValueAsString(change.address())),
                        change.address() == null
                                ? null
                                : protectOrNull(
                                        tenantId,
                                        orderId,
                                        "delivery_instructions_encrypted",
                                        change.deliveryInstructions())));
    }

    /**
     * Re-encrypts a plaintext snapshot field under this order's own ADR 0029
     * associated data — never copied ciphertext, so a value moved here fails
     * to decrypt rather than revealing the wrong order's words, exactly the
     * rule {@code CheckoutOrderWriter#reEncryptNote} follows.
     */
    private @Nullable String protectOrNull(UUID tenantId, UUID orderId, String column, @Nullable String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(SNAPSHOT_TABLE, column, orderId), plaintext)
                .serialize();
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
            @Nullable String actorType,
            @Nullable String actorId,
            @Nullable String reason,
            int version,
            Map<String, Object> changed,
            @Nullable String correlationId,
            Instant now) {

        ActorRef actor =
                switch (actorType == null ? "SERVICE" : actorType) {
                    case "USER" -> ActorRef.user(actorId == null ? "unknown-user" : actorId, null);
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
         * Records the change-due the customer is expected to hand over.
         *
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

        /** ADR 0113 (wave P10): operator to courier. Never rendered to the customer. */
        public static AmendmentCommand courierNote(String note) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("note", note == null ? "" : note);
            return new AmendmentCommand(AmendmentCommandType.SET_COURIER_NOTE, payload);
        }

        /** ADR 0113 (wave P10): operator to operator. Same shape as {@link #courierNote}. */
        public static AmendmentCommand internalNote(String note) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("note", note == null ? "" : note);
            return new AmendmentCommand(AmendmentCommandType.SET_INTERNAL_NOTE, payload);
        }

        // -------------------------------------------------- wave 10: financial

        /** ADR 0039: correcting who answers at the door. ADR 0029 protected, never audited by value. */
        public static AmendmentCommand changeContact(String recipientName, String recipientPhone) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "recipientName", Objects.requireNonNull(recipientName, "A contact change needs a recipient name"));
            payload.put(
                    "recipientPhone",
                    Objects.requireNonNull(recipientPhone, "A contact change needs a recipient phone"));
            return new AmendmentCommand(AmendmentCommandType.CHANGE_CONTACT, payload);
        }

        /**
         * ADR 0039: reschedules the promise. Never reprices in this build — no
         * price plane in this codebase varies by time of day or day of week
         * (see {@code OrderAmendmentService#repriceIfNeeded}'s own doc) — so the
         * matrix's "reprice if the time crosses a price plane" cell is
         * vacuously satisfied rather than skipped.
         */
        public static AmendmentCommand changeFulfillmentTime(Instant promisedAt) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "promisedAt",
                    Objects.requireNonNull(promisedAt, "A fulfilment time change needs a new time")
                            .toString());
            return new AmendmentCommand(AmendmentCommandType.CHANGE_FULFILLMENT_TIME, payload);
        }

        /**
         * ADR 0039: {@code CASH} at either end is carried out directly; anything
         * else on the current side needs a void/refund {@link PaymentIntentPort}
         * does not expose today and is refused by
         * {@code OrderAmendmentService#applyPaymentMethodChange}.
         */
        public static AmendmentCommand changePaymentMethod(String paymentMethodCode) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "paymentMethodCode",
                    Objects.requireNonNull(paymentMethodCode, "A payment method change needs a target method"));
            return new AmendmentCommand(AmendmentCommandType.CHANGE_PAYMENT_METHOD, payload);
        }

        /**
         * ADR 0039: re-resolves the ADR 0037 delivery fee through the same
         * quoting path {@code CartService#price} uses. Refused when the new
         * point leaves every zone.
         */
        public static AmendmentCommand changeDeliveryAddress(
                DeliveryDestination destination,
                @Nullable String deliveryInstructions,
                String recipientName,
                String recipientPhone) {
            Objects.requireNonNull(destination, "A delivery address change needs a destination");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("line1", destination.line1());
            payload.put("line2", destination.line2());
            payload.put("city", destination.city());
            payload.put("district", destination.district());
            payload.put("postalCode", destination.postalCode());
            payload.put("entrance", destination.entrance());
            payload.put("floor", destination.floor());
            payload.put("apartment", destination.apartment());
            payload.put("landmark", destination.landmark());
            payload.put("latitude", destination.latitude());
            payload.put("longitude", destination.longitude());
            payload.put("deliveryInstructions", deliveryInstructions);
            payload.put(
                    "recipientName",
                    Objects.requireNonNull(recipientName, "A delivery address change needs a recipient name"));
            payload.put(
                    "recipientPhone",
                    Objects.requireNonNull(recipientPhone, "A delivery address change needs a recipient phone"));
            return new AmendmentCommand(AmendmentCommandType.CHANGE_DELIVERY_ADDRESS, payload);
        }

        /**
         * ADR 0039: reprices through {@link CartPricingPort}, reserves the added
         * quantity through {@link InventoryReservationPort}, and fails the whole
         * amendment — no reservation, no revision — if any line cannot be
         * reserved.
         */
        public static AmendmentCommand addLines(List<LineRequest> lines) {
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("ADD_LINES needs at least one line");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "lines",
                    lines.stream()
                            .map(line -> {
                                Map<String, Object> one = new LinkedHashMap<>();
                                one.put(
                                        "variantId",
                                        Objects.requireNonNull(line.variantId(), "A new line needs a variant"));
                                one.put("quantity", line.quantity());
                                one.put("modifierOptionIds", line.modifierOptionIds());
                                return one;
                            })
                            .toList());
            return new AmendmentCommand(AmendmentCommandType.ADD_LINES, payload);
        }

        /**
         * ADR 0039: reprices the whole order fresh and reserves the increase.
         * Built for an increase only — a decrease has no ADR 0017 primitive to
         * carry it out yet (see {@code OrderAmendmentService#repriceIfNeeded}'s
         * own doc) and is refused by name with {@code
         * QUANTITY_DECREASE_NOT_SUPPORTED}.
         */
        public static AmendmentCommand changeLineQuantity(UUID orderLineId, int quantity) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "orderLineId", Objects.requireNonNull(orderLineId, "A quantity change needs the line it targets"));
            payload.put("quantity", quantity);
            return new AmendmentCommand(AmendmentCommandType.CHANGE_LINE_QUANTITY, payload);
        }

        /** One line {@link #addLines} carries: a variant, a quantity, and its modifiers. */
        public record LineRequest(UUID variantId, int quantity, List<UUID> modifierOptionIds) {

            public LineRequest {
                modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
            }
        }
    }

    /**
     * A proposal to amend an order, and whether to apply it in the same call.
     *
     * @param applyOnPrice apply in the same transaction once priced. Refused when
     *                     the amendment raises the total or needs approval, so it
     *                     can never become a way past the customer's agreement
     */
    public record ProposeCommand(
            int expectedOrderVersion,
            List<AmendmentCommand> commands,
            boolean applyOnPrice,
            String idempotencyKey,
            String reason,
            String actorType,
            String actorId,
            @Nullable String correlationId) {}

    /**
     * The outcome of a propose or apply call.
     *
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

    /**
     * A financial amendment refused for a reason specific enough to carry a
     * stable code (ADR 0031) — the cut point, an out-of-zone address, a
     * quantity decrease with no inventory primitive to carry it out, a
     * payment-method change this build cannot settle, or an increase on an
     * order with no incremental-payment path.
     */
    public static class AmendmentRefusedException extends RuntimeException {

        private final String code;

        public AmendmentRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
