package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.ExportProbe;
import uz.horecaos.platform.pos.application.port.PosAdapter.ExportResult;
import uz.horecaos.platform.pos.application.port.PosAdapter.OrderExport;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.application.port.PosAdapter.RecoveryRead;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.domain.ExportCandidate;
import uz.horecaos.platform.pos.domain.ExportState;
import uz.horecaos.platform.pos.domain.ExportStateMachine;
import uz.horecaos.platform.pos.domain.LineFingerprint;
import uz.horecaos.platform.pos.domain.UncertainExportResolver;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * Exports one order to a till, and settles the ones whose outcome is unknown
 * (ADR 0011).
 *
 * <p>The design of this class is dominated by a single fact about the provider it
 * was written for: <strong>there is no idempotency mechanism of any kind.</strong>
 * No key, no header, no documented repeat semantics, no dedupe window. Its own
 * documentation, in the section about retries, tells integrators to check the
 * server state before re-sending a non-idempotent request. So this service never
 * re-sends an order whose outcome it does not know, and there is no code path
 * that could.
 *
 * <p>Three places carry that rule, and all three are needed because any one of
 * them alone can be worked around.
 *
 * <ol>
 *   <li>The {@link ExportStateMachine} has no edge from {@code UNCERTAIN} back to
 *       {@code SENT}, so nothing in this module can express a blind retry.</li>
 *   <li>The route beneath this has no redelivery at all, so no infrastructure can
 *       repeat the call underneath us.</li>
 *   <li>{@link JdbcPosExportStore#claimForAttempt} is a conditional update, so
 *       two workers that both decide to send produce one send.</li>
 * </ol>
 *
 * <p>What replaces the retry is a person. {@link #discoverOutcome} reads the
 * provider and attaches what it found; unless the provider handed back our own
 * correlation reference, it moves the export to {@code AWAITING_OPERATOR} and
 * stops. That is a real operational cost and it is the honest price of the
 * missing key — see {@link UncertainExportResolver} for why the obvious automatic
 * rules are all wrong.
 */
@Service
public class PosOrderExportService {

    private static final Logger log = LoggerFactory.getLogger(PosOrderExportService.class);

    /** Recorded on every reveal of the customer's contact details (ADR 0029). */
    private static final String REVEAL_PURPOSE = "pos.order.export";

    /** The ADR 0026 mapping entity type an order line resolves through. */
    private static final String VARIANT_ENTITY = "VARIANT";

    private static final String MODIFIER_ENTITY = "MODIFIER";

    /**
     * How far either side of the export request a candidate order may have been
     * created and still be a candidate.
     *
     * <p>Wide enough to survive a slow till and a clock that disagrees with ours
     * by a few minutes; narrow enough that yesterday's identical order from the
     * same customer is not offered to an operator as a possibility.
     */
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(30);

    private final PosAdapterRegistry adapters;
    private final ProviderInstallationLookup installations;
    private final ProviderEntityMappingLookup mappings;
    private final JdbcPosBindingConfiguration configuration;
    private final JdbcPosExportStore exports;
    private final JdbcPosCapabilityStore capabilities;
    private final PosOrderSource orders;
    private final Clock clock;

    public PosOrderExportService(
            PosAdapterRegistry adapters,
            ProviderInstallationLookup installations,
            ProviderEntityMappingLookup mappings,
            JdbcPosBindingConfiguration configuration,
            JdbcPosExportStore exports,
            JdbcPosCapabilityStore capabilities,
            PosOrderSource orders,
            Clock clock) {
        this.adapters = adapters;
        this.installations = installations;
        this.mappings = mappings;
        this.configuration = configuration;
        this.exports = exports;
        this.capabilities = capabilities;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Opens the export row for an order, or returns the one that exists.
     *
     * <p>Separate from sending on purpose. The row is the platform's own
     * idempotency: with it, a duplicated command, a redelivered event and an
     * operator pressing a button twice all converge on one export in one state,
     * and without it each of them would be a fresh decision to send.
     */
    @Transactional
    public Optional<UUID> open(UUID tenantId, UUID orderId) {
        // ADR 0024. Skipped rather than refused, because the empty Optional this
        // method already returns is a truthful answer during an import: no export
        // row was opened, and an order with no export is the state every branch
        // without a POS binding is in. Nothing downstream then has an export to
        // send, which is the effect ADR 0024 actually names.
        if (ImportSuppression.suppress(ExternalEffect.POS_ORDER_EXPORT, "Order", orderId)) {
            return Optional.empty();
        }

        Optional<PosOrderSource.ExportableOrder> found = orders.find(tenantId, orderId, REVEAL_PURPOSE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PosOrderSource.ExportableOrder order = found.get();

        Optional<BindingRef> binding = installations.primaryBinding(
                tenantId, order.brandId(), order.locationId(), PosCapability.ORDER_EXPORT.code());
        if (binding.isEmpty()) {
            // Not an error. A branch with no POS binding takes its orders the way
            // it did before there was one, and saying so is the whole point of
            // capability resolution.
            log.debug("No POS binding provides {} at location {}", PosCapability.ORDER_EXPORT, order.locationId());
            return Optional.empty();
        }

        Map<String, String> config = configuration.resolve(binding.get()).orElse(Map.of());
        List<LineFingerprint.Line> fingerprintLines = fingerprintLines(binding.get(), order);

        UUID exportId = exports.open(new JdbcPosExportStore.NewExport(
                UUID.randomUUID(),
                tenantId,
                orderId,
                binding.get().bindingId(),
                binding.get().installationId(),
                // The customer-facing order number, which a clerk can read out
                // loud. It is unique per location per day, which is exactly the
                // scope a recovery read searches, so it is also a usable
                // correlation value if the provider honours the field at all.
                truncate(order.publicOrderNumber(), 20),
                LineFingerprint.of(fingerprintLines),
                LineFingerprint.phoneHash(order.customerPhone()),
                config.getOrDefault("clopos.venueId", venueOf(config)),
                clock.instant()));

        return Optional.of(exportId);
    }

    /**
     * Sends the order, once.
     *
     * <p>The claim is conditional on the export being in a state a send may leave
     * from, and there are exactly two: {@code PENDING}, meaning nothing has been
     * sent, and {@code RESOLVED_ABSENT}, meaning somebody established that the
     * previous attempt did not land. Any other state returns without touching the
     * provider.
     */
    public ProviderOutcome send(UUID tenantId, UUID exportId) {
        // The outbound half, refused rather than skipped. An export row can only
        // exist here if it predates the import or if open() was bypassed, and
        // either way putting it on the wire prints a ticket in a live kitchen.
        ImportSuppression.refuse(ExternalEffect.POS_PROVIDER_CALL, "send a POS order export");

        Optional<JdbcPosExportStore.ExportRow> row = exports.find(tenantId, exportId);
        if (row.isEmpty()) {
            return ProviderOutcome.rejected("EXPORT_UNKNOWN", "No such export");
        }
        JdbcPosExportStore.ExportRow export = row.get();

        if (!ExportStateMachine.permits(export.state(), ExportState.SENT)) {
            return ProviderOutcome.rejected(
                    "EXPORT_NOT_SENDABLE", "An export in %s is not sent again".formatted(export.state()));
        }

        Optional<Integer> attempt = exports.claimForAttempt(tenantId, exportId, export.state(), clock.instant());
        if (attempt.isEmpty()) {
            // Somebody else claimed it between the read and the update. Doing
            // nothing is the correct response and the only safe one.
            return ProviderOutcome.rejected("EXPORT_CLAIMED_ELSEWHERE", "Another worker is sending this export");
        }
        int attemptNumber = attempt.get();
        Instant startedAt = clock.instant();

        Prepared prepared;
        try {
            prepared = prepare(tenantId, export);
        } catch (ExportNotPossible refusal) {
            exports.recordAttempt(
                    tenantId,
                    exportId,
                    attemptNumber,
                    "REJECTED",
                    refusal.code(),
                    refusal.getMessage(),
                    startedAt,
                    clock.instant());
            exports.settle(
                    tenantId,
                    exportId,
                    ExportState.REJECTED,
                    null,
                    refusal.code(),
                    refusal.getMessage(),
                    clock.instant());
            return ProviderOutcome.rejected(refusal.code(), refusal.getMessage());
        }

        ExportResult result = prepared.adapter().exportOrder(prepared.context(), prepared.order());
        ProviderOutcome outcome = result.outcome();
        Instant finishedAt = clock.instant();

        exports.recordAttempt(
                tenantId,
                exportId,
                attemptNumber,
                outcome.status().name(),
                outcome.errorCode(),
                outcome.detail(),
                startedAt,
                finishedAt);

        ExportState next =
                switch (outcome.status()) {
                    case SUCCESS -> ExportState.ACCEPTED;
                    case REJECTED -> ExportState.REJECTED;
                    // Both of the remaining ones become UNCERTAIN, and the RETRYABLE case
                    // is the interesting one. The gateway has already upgraded every
                    // ambiguous retryable on an unkeyed create; what survives as RETRYABLE
                    // here is the circuit breaker refusing before anything was sent. That
                    // is genuinely safe to send again — but it is sent again by a caller
                    // deciding to, not by this method looping.
                    case UNCERTAIN -> ExportState.UNCERTAIN;
                    case RETRYABLE ->
                        "CIRCUIT_OPEN".equals(outcome.errorCode())
                                ? ExportState.RESOLVED_ABSENT
                                : ExportState.UNCERTAIN;
                };

        exports.settle(
                tenantId,
                exportId,
                next,
                result.externalOrderId(),
                outcome.errorCode(),
                outcome.detail(),
                clock.instant());

        if (next == ExportState.UNCERTAIN) {
            // Logged at warn because somebody has to look at it, and without a
            // customer identifier of any kind: ADR 0029 keeps an order's contact
            // details out of every log line, and the export id is enough to find
            // the row.
            log.warn(
                    "POS export {} is uncertain after attempt {} ({}); it will not be re-sent",
                    exportId,
                    attemptNumber,
                    outcome.errorCode());
        }
        return outcome;
    }

    /**
     * Reads the provider to find out what an uncertain export actually did.
     *
     * <p>Bounded, side-effect free, and never conclusive on its own. What it
     * establishes is written to the export; what it cannot establish becomes an
     * operator's decision with the candidates attached as evidence.
     */
    public ProviderOutcome discoverOutcome(UUID tenantId, UUID exportId) {
        Optional<JdbcPosExportStore.ExportRow> row = exports.find(tenantId, exportId);
        if (row.isEmpty()) {
            return ProviderOutcome.rejected("EXPORT_UNKNOWN", "No such export");
        }
        JdbcPosExportStore.ExportRow export = row.get();
        if (export.state() != ExportState.UNCERTAIN) {
            return ProviderOutcome.rejected(
                    "EXPORT_NOT_UNCERTAIN", "An export in %s has nothing to discover".formatted(export.state()));
        }

        Prepared prepared;
        try {
            prepared = prepare(tenantId, export);
        } catch (ExportNotPossible refusal) {
            // The export cannot even be described any more — a mapping was retired
            // between the send and now. That is a person's problem rather than a
            // machine's, and it is exactly where an operator queue belongs.
            exports.resolve(
                    tenantId,
                    exportId,
                    ExportState.UNCERTAIN,
                    ExportState.AWAITING_OPERATOR,
                    null,
                    null,
                    refusal.getMessage(),
                    null,
                    clock.instant());
            return ProviderOutcome.uncertain(refusal.code(), refusal.getMessage());
        }

        RecoveryRead read = prepared.adapter()
                .findExportedOrder(
                        prepared.context(),
                        new ExportProbe(
                                export.correlationReference(),
                                prepared.order().customer().phone(),
                                export.lineFingerprint(),
                                prepared.fingerprintLines(),
                                export.requestedAt().minus(RECOVERY_WINDOW),
                                export.requestedAt().plus(RECOVERY_WINDOW)));

        if (read.outcome().status() != ProviderOutcome.Status.SUCCESS) {
            // The provider could not be read. The export stays UNCERTAIN so the
            // read can be tried again; it does not become "absent", which would be
            // a licence to send the order a second time on the strength of an
            // outage.
            return read.outcome();
        }

        exports.replaceCandidates(
                tenantId, exportId, read.candidates(), Math.max(1, export.attemptCount()), clock.instant());

        UncertainExportResolver.Decision decision =
                UncertainExportResolver.decide(read.candidates(), idempotencyOf(tenantId, export.installationId()));

        return switch (decision.outcome()) {
            case LANDED -> {
                exports.resolve(
                        tenantId,
                        exportId,
                        ExportState.UNCERTAIN,
                        ExportState.RESOLVED_LANDED,
                        "CORRELATION_ECHOED",
                        decision.externalOrderId(),
                        decision.reason(),
                        // Attributed to the adapter version rather than to a
                        // person, because the database requires an author and
                        // "the machine, using this rule" is the honest one.
                        prepared.adapter().providerType(),
                        clock.instant());
                yield ProviderOutcome.success(
                        Map.of("externalOrderId", decision.externalOrderId()), decision.externalOrderId());
            }
            case RETRY_UNDER_KEY -> {
                exports.resolve(
                        tenantId,
                        exportId,
                        ExportState.UNCERTAIN,
                        ExportState.RESOLVED_ABSENT,
                        "CORRELATION_ECHOED",
                        null,
                        decision.reason(),
                        prepared.adapter().providerType(),
                        clock.instant());
                yield ProviderOutcome.retryable("EXPORT_SAFE_TO_RESEND", decision.reason(), null);
            }
            case OPERATOR -> {
                exports.resolve(
                        tenantId,
                        exportId,
                        ExportState.UNCERTAIN,
                        ExportState.AWAITING_OPERATOR,
                        null,
                        null,
                        decision.reason(),
                        prepared.adapter().providerType(),
                        clock.instant());
                yield ProviderOutcome.uncertain("EXPORT_NEEDS_OPERATOR", decision.reason());
            }
        };
    }

    /**
     * A person's decision about an export nothing could settle.
     *
     * @param landedExternalId the provider order the operator identified as ours,
     *                         required when they say it landed. Without it the
     *                         fiscal write-back and every later reconciliation
     *                         would have nothing to key on
     */
    @Transactional
    public boolean settleByOperator(
            UUID tenantId,
            UUID exportId,
            OperatorDecision decision,
            String landedExternalId,
            String reason,
            String operator) {

        ExportState to =
                switch (decision) {
                    case LANDED -> ExportState.RESOLVED_LANDED;
                    case ABSENT -> ExportState.RESOLVED_ABSENT;
                    case ABANDON -> ExportState.ABANDONED;
                };
        String kind =
                switch (decision) {
                    case LANDED -> "OPERATOR_CONFIRMED_LANDED";
                    case ABSENT -> "OPERATOR_CONFIRMED_ABSENT";
                    case ABANDON -> "OPERATOR_ABANDONED";
                };
        if (decision == OperatorDecision.LANDED && (landedExternalId == null || landedExternalId.isBlank())) {
            throw new IllegalArgumentException(
                    "Confirming that an export landed requires the provider order it landed as");
        }

        return exports.resolve(
                tenantId,
                exportId,
                ExportState.AWAITING_OPERATOR,
                to,
                kind,
                landedExternalId,
                reason,
                operator,
                clock.instant());
    }

    public List<ExportCandidate> candidates(UUID tenantId, UUID exportId) {
        return exports.candidates(tenantId, exportId);
    }

    public List<JdbcPosExportStore.ExportRow> awaitingOperator(UUID tenantId, int limit) {
        return exports.awaitingOperator(tenantId, limit);
    }

    // ------------------------------------------------------------------

    private Prepared prepare(UUID tenantId, JdbcPosExportStore.ExportRow export) {
        PosOrderSource.ExportableOrder order = orders.find(tenantId, export.orderId(), REVEAL_PURPOSE)
                .orElseThrow(
                        () -> new ExportNotPossible("ORDER_UNKNOWN", "The order behind this export could not be read"));

        BindingRef binding = installations
                .primaryBinding(tenantId, order.brandId(), order.locationId(), PosCapability.ORDER_EXPORT.code())
                .filter(candidate -> candidate.bindingId().equals(export.bindingId()))
                .orElseThrow(() -> new ExportNotPossible(
                        "BINDING_CHANGED",
                        "The binding this order was exported through no longer provides order export"));

        PosAdapter adapter = adapters.forProvider(binding.providerType())
                .orElseThrow(() -> new ExportNotPossible(
                        "NO_ADAPTER", "No POS adapter is registered for " + binding.providerType()));

        Map<String, String> config = configuration.resolve(binding).orElse(Map.of());

        List<OrderExport.Line> lines = new ArrayList<>();
        List<LineFingerprint.Line> fingerprintLines = new ArrayList<>();
        for (PosOrderSource.ExportableOrder.Line line : order.lines()) {
            String externalId = mappings.externalIdFor(binding.bindingId(), VARIANT_ENTITY, line.sourceVariantId())
                    .orElseThrow(() -> new ExportNotPossible(
                            "LINE_UNMAPPED",
                            // Named rather than guessed. ADR 0012's rule is that a
                            // mapping is never inferred from a mutable product
                            // name, and an export is exactly where inferring one
                            // would send the kitchen the wrong dish.
                            "Order line %s has no provider mapping, and a provider product ".formatted(line.lineId())
                                    + "must never be guessed from a name"));

            List<String> modifiers = line.modifierOptionIds().stream()
                    .map(optionId -> mappings.externalIdFor(binding.bindingId(), MODIFIER_ENTITY, optionId)
                            .orElseThrow(() -> new ExportNotPossible(
                                    "MODIFIER_UNMAPPED",
                                    "A modifier on line %s has no provider mapping".formatted(line.lineId()))))
                    .toList();

            lines.add(new OrderExport.Line(
                    externalId, displayName(line), line.quantity(), line.unitAmountMinor(), modifiers));
            fingerprintLines.add(new LineFingerprint.Line(externalId, line.quantity(), line.unitAmountMinor()));
        }

        if (lines.isEmpty()) {
            throw new ExportNotPossible(
                    "ORDER_EMPTY",
                    "An order with no lines is a kitchen ticket somebody has to walk over and ask about");
        }

        // Clopos and every till like it require their own customer identifier on
        // the create. Where the platform has no mapping for this customer, the
        // export cannot be built — and that is stated as a refusal rather than
        // papered over with a blank, because an order exported without a
        // telephone number is an order the courier cannot complete.
        String externalCustomerId = order.customerAccountId() == null
                ? null
                : mappings.externalIdFor(binding.bindingId(), "CUSTOMER", order.customerAccountId())
                        .orElse(null);

        OrderExport command = new OrderExport(
                order.orderId(),
                export.correlationReference(),
                order.publicOrderNumber(),
                new OrderExport.Customer(
                        externalCustomerId, order.customerName(), order.customerPhone(), order.customerAddress()),
                List.copyOf(lines),
                order.totalMinor(),
                config.getOrDefault("clopos.currency", order.currency()),
                order.fulfillmentMode(),
                // A restaurant-approval order asks the till to decide. An
                // auto-confirmed order tells it. Which one this is was settled
                // when the order was placed and is not re-decided here.
                //
                // But an order that has ALREADY been approved is telling, not
                // asking. Reading the acceptance mode alone was harmless while
                // export was an operator pressing a button after the decision;
                // now that a confirmed order exports itself, it would send every
                // restaurant-approval ticket to the till with
                // auto_order_accept=false — so the ticket would sit there unprinted
                // waiting for a clerk to accept an order the restaurant accepted
                // a moment ago, on the other screen.
                "RESTAURANT_APPROVAL".equals(order.acceptanceMode()) && !"CONFIRMED".equals(order.status()),
                order.placedAt());

        PosContext context = new PosContext(
                tenantId,
                binding.installationId(),
                binding.bindingId(),
                export.externalVenueReference(),
                config,
                export.id().toString());

        return new Prepared(adapter, context, command, List.copyOf(fingerprintLines));
    }

    private List<LineFingerprint.Line> fingerprintLines(BindingRef binding, PosOrderSource.ExportableOrder order) {
        List<LineFingerprint.Line> lines = new ArrayList<>();
        for (PosOrderSource.ExportableOrder.Line line : order.lines()) {
            String externalId = mappings.externalIdFor(binding.bindingId(), VARIANT_ENTITY, line.sourceVariantId())
                    // The fingerprint is computed at open time as well as at send
                    // time, and at open time an unmapped line is not yet fatal.
                    // The HorecaOS id keeps the value stable and different from any
                    // other line's, which is all the fingerprint needs.
                    .orElse(line.sourceVariantId().toString());
            lines.add(new LineFingerprint.Line(externalId, line.quantity(), line.unitAmountMinor()));
        }
        return lines;
    }

    /**
     * What repeating this export would do at the provider, as this installation
     * was actually observed to behave.
     *
     * <p>Read from the stored ADR 0011 snapshot rather than by probing. Probing
     * here would put several provider calls on the resolution path of an export
     * that has already timed out once — spending the brand's shared rate-limit
     * budget at exactly the moment it is least likely to be healthy — and it
     * would answer a question discovery has already answered and recorded.
     *
     * <p>The default when nothing is recorded is {@code NONE}, which forbids a
     * retry rather than permitting one. An installation nobody has run discovery
     * against is not an installation to assume idempotency about.
     */
    private IdempotencyBehaviour idempotencyOf(UUID tenantId, UUID installationId) {
        return capabilities
                .readSnapshot(tenantId, installationId)
                .flatMap(snapshot -> snapshot.entry(PosCapability.ORDER_EXPORT))
                .map(entry -> entry.idempotency())
                .orElse(IdempotencyBehaviour.NONE);
    }

    private static String displayName(PosOrderSource.ExportableOrder.Line line) {
        if (line.variantNameSnapshot() == null || line.variantNameSnapshot().isBlank()) {
            return line.productNameSnapshot();
        }
        return line.productNameSnapshot() + " " + line.variantNameSnapshot();
    }

    private static String venueOf(Map<String, String> config) {
        return config.get("venueId");
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** What an operator decided about an export nothing else could settle. */
    public enum OperatorDecision {

        /** The order is at the till. Requires the provider order it landed as. */
        LANDED,

        /** It is not, and the order may be exported again. */
        ABSENT,

        /** It will not be exported. The branch takes it another way. */
        ABANDON
    }

    /** The export cannot be built, which is a rejection and never an uncertainty. */
    private static final class ExportNotPossible extends RuntimeException {

        private final String code;

        ExportNotPossible(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private record Prepared(
            PosAdapter adapter, PosContext context, OrderExport order, List<LineFingerprint.Line> fingerprintLines) {}
}
