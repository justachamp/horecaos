package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.ordering.api.MarkingConfigurationKeys;
import uz.horecaos.platform.ordering.api.OrderMarksCompleted;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.domain.MarkCaptureStage;
import uz.horecaos.platform.ordering.domain.MarkEntryMethod;
import uz.horecaos.platform.ordering.domain.MarkReleaseReason;
import uz.horecaos.platform.ordering.domain.MarkingCodes;
import uz.horecaos.platform.ordering.domain.MarkingStructureCheck;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore.LiveLine;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore.LiveMark;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore.NewMark;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Capture, removal and reading of the marking codes of a marked order (ADR 0161, gap map row X.35).
 *
 * <p>One mark per physical unit of a marked order line, captured at the pass. The service is the whole
 * server-side rule:
 *
 * <ul>
 *   <li><strong>One lock.</strong> A capture, a removal and the handover guard all take the order row's
 *       lock ({@link JdbcOrderMarkStore#lockOrder}) before they read the status or count anything, so a
 *       scan, a removal and a status change on one order serialise and an order can never leave the pass
 *       with fewer live marks than units. The alternative -- bumping the order's version on every scan --
 *       would make the version the operator's screen holds stale after each scan.
 *   <li><strong>The requirement is read live</strong> from the catalog classification, not snapshotted onto
 *       the line: three code paths insert order lines and a snapshot written by two of them would be a
 *       guard that silently skips the third. A line that already holds a live mark stays marked whatever
 *       the catalog later says.
 *   <li><strong>The server is the authority.</strong> The same normalisation the browser runs is run here;
 *       then the line, the status, the structure (per policy), the GTIN against the classification's
 *       barcode, and uniqueness among live marks. Nothing cryptographic is verified: the tail is carried,
 *       never interpreted.
 *   <li><strong>No code outside the protected row.</strong> The code is an ADR 0029 {@code FINANCIAL}
 *       envelope bound to its row; it is never in a log, a metric label, an error, an audit change
 *       document, an event or a response. This class decrypts nothing: only {@code payments}, for a
 *       receipt.
 * </ul>
 *
 * <p>Nothing here is reachable until {@code feature.marked_goods} is on for the tenant: the capture
 * endpoints answer {@code FEATURE_DISABLED}, the guard is skipped, and an order read carries no marking.
 */
@Service
public class OrderMarkService implements OrderMarksGuard, OrderMarksPort {

    /** The ADR 0029 lookup domain of the per-tenant keyed hash that makes a live code unique. */
    static final String LOOKUP_DOMAIN = "marking-code";

    /** The limiter bucket of a scanning principal: 240 captures a minute (ADR 0033). */
    public static final String CAPTURE_OPERATION = "marking.capture";

    private static final RateLimiter.Policy CAPTURE_LIMIT = RateLimiter.Policy.perMinute(240);

    private final JdbcOrderMarkStore marks;
    private final JdbcOrderStore orders;
    private final FiscalNodeFacts classification;
    private final FieldProtection protection;
    private final ConfigurationResolver configuration;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final RateLimiter limiter;
    private final MarkingMetrics metrics;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrderMarkService(
            JdbcOrderMarkStore marks,
            JdbcOrderStore orders,
            FiscalNodeFacts classification,
            FieldProtection protection,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            RateLimiter limiter,
            MarkingMetrics metrics,
            Clock clock) {
        this.marks = marks;
        this.orders = orders;
        this.classification = classification;
        this.protection = protection;
        this.configuration = configuration;
        this.audit = audit;
        this.events = events;
        this.limiter = limiter;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- capture

    /**
     * @param rawCode what the scanner read, un-normalised; never logged and never echoed
     */
    public record CaptureCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID lineId,

            @Classified(value = DataClass.FINANCIAL, reason = "a marking code identifies one physical unit")
            String rawCode,

            MarkEntryMethod entryMethod,
            String actorId) {}

    /** @param gtin the product the code claims, a product identifier and not a secret */
    public record CaptureResult(
            UUID markId,
            int sequence,
            String gtin,
            int captured,
            int required,
            boolean orderMarksComplete,
            boolean gtinChecked) {}

    /** A capture and the stage it happened at, which only the metric wants. */
    private record Captured(CaptureResult result, String stage) {}

    @Transactional
    public CaptureResult capture(CaptureCommand command) {
        try {
            Captured captured = captureUnder(command);
            metrics.accepted(captured.stage());
            return captured.result();
        } catch (MarkRefusedException refused) {
            metrics.refused(refused.stageForMetrics());
            throw refused;
        }
    }

    private Captured captureUnder(CaptureCommand command) {
        UUID tenantId = command.tenantId();
        requireEnabled(tenantId);

        RateLimiter.Decision decision = limiter.check(
                new RateLimiter.Key(CAPTURE_OPERATION, tenantId.toString(), command.actorId()), CAPTURE_LIMIT);
        if (!decision.allowed()) {
            throw new MarkRefusedException(
                    Refusal.RATE_LIMITED,
                    "Too many scans in a minute. Wait a moment and try again.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())),
                    "none");
        }

        if (command.entryMethod() == MarkEntryMethod.KEYED
                && !Boolean.TRUE.equals(
                        configuration.value(MarkingConfigurationKeys.ALLOW_KEYED_ENTRY, scopeOf(tenantId)))) {
            throw new MarkRefusedException(
                    Refusal.MARKING_KEYED_ENTRY_REFUSED,
                    "A code typed or pasted by hand is not accepted: scan the package",
                    Map.of(),
                    "none");
        }

        String canonical = MarkingCodes.normalise(command.rawCode())
                .orElseThrow(() -> malformed("The scan is not a marking code the scanner read whole"));
        MarkingStructureCheck policy = structureCheck(tenantId);
        MarkingCodes.ClaimedGtin claimed = MarkingCodes.claimedGtin(canonical, policy)
                .orElseThrow(() -> malformed("The scan does not have the shape of a marking code"));

        // The lock first: everything below reads state a concurrent removal or hand-over can change.
        if (!marks.lockOrder(tenantId, command.orderId())) {
            throw new MarkNotFoundException("No such order");
        }
        OrderRow order = orders.find(tenantId, command.orderId())
                .filter(found -> found.locationId().equals(command.locationId()))
                .orElseThrow(() -> new MarkNotFoundException("No such order"));
        MarkCaptureStage stage = MarkCaptureStage.forStatus(order.status())
                .orElseThrow(() -> new MarkRefusedException(
                        Refusal.ORDER_NOT_MARKABLE,
                        "An order that is %s can no longer have a code captured against it".formatted(order.status()),
                        Map.of("status", order.status().name()),
                        "none"));

        LiveLine line = marks.findLiveLine(tenantId, command.orderId(), command.lineId())
                .orElseThrow(() -> new MarkNotFoundException("No such order line"));
        Map<UUID, Integer> counts = marks.liveCounts(tenantId, command.orderId());
        int held = counts.getOrDefault(line.lineId(), 0);
        Map<UUID, FiscalNodeFacts.MarkedNode> markedNodes =
                classification.markedNodeDetails(tenantId, order.brandId(), Set.of(line.variantId()));
        FiscalNodeFacts.MarkedNode node = markedNodes.get(line.variantId());
        if (node == null && held == 0) {
            throw refusal(Refusal.LINE_NOT_MARKED, "This line is not a marked good", stage);
        }
        int required = line.units();
        if (held >= required) {
            throw refusal(Refusal.LINE_MARKS_COMPLETE, "This line already has every code it needs", stage);
        }

        boolean gtinChecked = false;
        Optional<String> expected = node == null ? Optional.empty() : MarkingCodes.barcodeAsGtin14(node.barcode());
        if (expected.isPresent() && claimed.readable()) {
            if (!expected.get().equals(claimed.gtin())) {
                throw refusal(Refusal.MARKING_CODE_WRONG_PRODUCT, "This code belongs to a different product", stage);
            }
            gtinChecked = true;
        }

        String hash = protection.lookupHash(tenantId, LOOKUP_DOMAIN, canonical);
        Optional<JdbcOrderMarkStore.LiveCodeOwner> owner = marks.liveOwnerOfCode(tenantId, hash);
        if (owner.isPresent()) {
            throw alreadyCaptured(owner.get().lineId().equals(line.lineId()), stage);
        }

        int sequence = marks.lowestFreeSequence(tenantId, line.lineId(), required)
                .orElseThrow(
                        () -> refusal(Refusal.LINE_MARKS_COMPLETE, "This line already has every code it needs", stage));
        UUID markId = Ids.newId();
        String envelope = protection
                .protect(
                        tenantId,
                        DataClass.FINANCIAL,
                        new RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, markId),
                        canonical)
                .serialize();
        Instant now = clock.instant();
        boolean written = marks.insert(new NewMark(
                markId,
                tenantId,
                command.orderId(),
                line.lineId(),
                sequence,
                claimed.gtin(),
                envelope,
                hash,
                stage.name(),
                command.entryMethod().name(),
                gtinChecked,
                "USER",
                command.actorId(),
                now));
        if (!written) {
            // Another order took the unit between the check above and this insert (the lock is per order, so
            // nothing serialises two orders). The unique index on live codes is the last line of defence and it
            // answered without aborting the transaction, so the question can be asked here; the answer is the
            // same as the check above. The owner may have been released since, in which case the next scan
            // of the unit goes through.
            throw alreadyCaptured(
                    marks.liveOwnerOfCode(tenantId, hash)
                            .map(found -> found.lineId().equals(line.lineId()))
                            .orElse(false),
                    stage);
        }

        recordCaptured(order, line, sequence, stage, command, claimed.gtin(), gtinChecked, now);

        Progress progress = progressOf(tenantId, order.brandId(), command.orderId());
        boolean complete = progress.complete();
        if (complete) {
            events.publishEvent(new OrderMarksCompleted(
                    Ids.newId(),
                    new TenantId(tenantId),
                    command.orderId(),
                    now,
                    order.brandId(),
                    order.locationId(),
                    progress.markedLineCount(),
                    progress.markedUnitCount(),
                    now));
        }
        return new Captured(
                new CaptureResult(markId, sequence, claimed.gtin(), held + 1, required, complete, gtinChecked),
                stage.name());
    }

    // ---------------------------------------------------------------- removal

    public record RemovalResult(int captured, int required) {}

    /**
     * Releases one scanned unit -- never deletes it -- while the order is still at the pass. A damaged
     * symbol is answered by scanning another unit, or the order does not leave the pass: this is the way
     * to take a wrong scan back, not a bypass.
     */
    @Transactional
    public RemovalResult remove(
            UUID tenantId, UUID locationId, UUID orderId, UUID lineId, UUID markId, String reasonCode, String actorId) {
        requireEnabled(tenantId);
        MarkReleaseReason reason = MarkReleaseReason.parseOperatorReason(reasonCode)
                .orElseThrow(() ->
                        new IllegalArgumentException("reasonCode must be one of MISSCAN, UNIT_REPLACED, UNIT_DAMAGED"));

        if (!marks.lockOrder(tenantId, orderId)) {
            throw new MarkNotFoundException("No such order");
        }
        OrderRow order = orders.find(tenantId, orderId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new MarkNotFoundException("No such order"));
        if (MarkCaptureStage.forStatus(order.status()).isEmpty()) {
            throw new MarkRefusedException(
                    Refusal.ORDER_NOT_MARKABLE,
                    "An order that is %s has left the pass and its codes can no longer be taken back"
                            .formatted(order.status()),
                    Map.of("status", order.status().name()),
                    "none");
        }
        LiveMark mark = marks.findLiveMark(tenantId, orderId, markId)
                .filter(found -> found.lineId().equals(lineId))
                .orElseThrow(() -> new MarkNotFoundException("No such scanned unit"));
        Instant now = clock.instant();
        marks.release(tenantId, markId, reason.name(), now);
        audit.record(AuditFact.of("ordering.order.line_mark_removed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorId, null))
                .at(ResourceScope.location(tenantId, order.brandId(), order.locationId()))
                .target("ordering.order", orderId)
                .because(reason.name())
                .usingCapability(Capability.ORDER_ADVANCE.code())
                .changed(ChangeDocuments.diff(
                        Map.of("orderLineId", lineId.toString(), "sequence", mark.sequence(), "released", false),
                        Map.of("orderLineId", lineId.toString(), "sequence", mark.sequence(), "released", true)))
                .correlatedBy(orderId.toString())
                .occurredAt(now)
                .build());

        LiveLine line = marks.findLiveLine(tenantId, orderId, lineId).orElseThrow();
        int captured = marks.liveCounts(tenantId, orderId).getOrDefault(lineId, 0);
        return new RemovalResult(captured, line.units());
    }

    // ---------------------------------------------------------------- reads

    /** One marked line's progress and the units scanned against it; never a code. */
    public record LineProgress(
            UUID lineId, int lineNumber, String productName, int required, int captured, List<LiveMark> marks) {}

    @Transactional(readOnly = true)
    public List<LineProgress> read(UUID tenantId, UUID locationId, UUID orderId) {
        requireEnabled(tenantId);
        OrderRow order = orders.find(tenantId, orderId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new MarkNotFoundException("No such order"));
        List<LiveLine> lines = marks.liveLines(tenantId, orderId);
        Map<UUID, Integer> counts = marks.liveCounts(tenantId, orderId);
        Set<UUID> markedVariants = markedVariantsOf(tenantId, order.brandId(), lines);
        Map<UUID, List<LiveMark>> byLine = new LinkedHashMap<>();
        marks.liveMarks(tenantId, orderId)
                .forEach(mark -> byLine.computeIfAbsent(mark.lineId(), ignored -> new ArrayList<>())
                        .add(mark));
        List<LineProgress> progress = new ArrayList<>();
        for (LiveLine line : lines) {
            int held = counts.getOrDefault(line.lineId(), 0);
            if (markedVariants.contains(line.variantId()) || held > 0) {
                progress.add(new LineProgress(
                        line.lineId(),
                        line.lineNumber(),
                        line.productName(),
                        line.units(),
                        held,
                        byLine.getOrDefault(line.lineId(), List.of())));
            }
        }
        return progress;
    }

    /** What an order read shows against each marked line: {@code required} and {@code captured}, nothing else. */
    public record LineMarking(int required, int captured) {}

    /**
     * The marking counts of an order's lines, keyed by line, for the order detail.
     *
     * <p>Empty for a tenant with the flag off (an order read pays nothing for marking) and for an order
     * with no marked line.
     */
    @Transactional(readOnly = true)
    public Map<UUID, LineMarking> markingOf(UUID tenantId, UUID brandId, UUID orderId) {
        if (!enabled(tenantId)) {
            return Map.of();
        }
        Map<UUID, LineMarking> result = new LinkedHashMap<>();
        for (LineMarkCount count : markCountsFor(tenantId, brandId, orderId)) {
            result.put(count.lineId(), new LineMarking(count.required(), count.captured()));
        }
        return result;
    }

    // ---------------------------------------------------------------- guard

    @Override
    public List<UUID> incompleteLines(UUID tenantId, UUID brandId, UUID orderId) {
        if (!enabled(tenantId)) {
            return List.of();
        }
        List<LiveLine> lines = marks.liveLines(tenantId, orderId);
        Set<UUID> markedVariants = markedVariantsOf(tenantId, brandId, lines);
        // Ordinary orders never lock: one indexed catalog read and one indexed marks read decide it.
        if (markedVariants.isEmpty() && !marks.hasLiveMarks(tenantId, orderId)) {
            return List.of();
        }
        // Whoever holds the order row first wins: a removal that committed first leaves one fewer to count;
        // a guard that holds it first makes the removal wait and then find the order handed over.
        marks.lockOrder(tenantId, orderId);
        Map<UUID, Integer> counts = marks.liveCounts(tenantId, orderId);
        List<UUID> incomplete = new ArrayList<>();
        for (LiveLine line : lines) {
            int held = counts.getOrDefault(line.lineId(), 0);
            boolean marked = markedVariants.contains(line.variantId()) || held > 0;
            if (marked && held < line.units()) {
                incomplete.add(line.lineId());
            }
        }
        return incomplete;
    }

    @Override
    public void releaseForCancellation(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, Instant now) {
        if (!marks.hasLiveMarks(tenantId, orderId)) {
            return;
        }
        int released = marks.releaseAll(tenantId, orderId, MarkReleaseReason.ORDER_CANCELLED.name(), now);
        audit.record(AuditFact.of("ordering.order.marks_released", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("ordering"))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("ordering.order", orderId)
                .changed(ChangeDocuments.created(Map.of("released", released, "reason", "ORDER_CANCELLED")))
                .correlatedBy(orderId.toString())
                .occurredAt(now)
                .build());
    }

    // ---------------------------------------------------------------- port

    @Override
    @Transactional(readOnly = true)
    public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
        if (!enabled(tenantId)) {
            return List.of();
        }
        Optional<OrderRow> order = orders.find(tenantId, orderId);
        return order.map(found -> markCountsFor(tenantId, found.brandId(), orderId))
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new MarkNotFoundException("No such order"));
        List<OrderLineRow> lines = orders.lines(tenantId, orderId);
        Map<UUID, List<MarkRef>> refs = new LinkedHashMap<>();
        for (JdbcOrderMarkStore.ProtectedMark mark : marks.protectedLiveMarks(tenantId, orderId)) {
            refs.computeIfAbsent(mark.lineId(), ignored -> new ArrayList<>())
                    .add(new MarkRef(mark.markId(), mark.sequence(), mark.gtin(), mark.codeEncrypted()));
        }
        Set<UUID> markedVariants = enabled(tenantId)
                ? classification.markedNodes(
                        tenantId,
                        order.brandId(),
                        lines.stream().map(OrderLineRow::sourceVariantId).collect(Collectors.toSet()))
                : Set.of();
        List<ReceiptLine> receiptLines = new ArrayList<>();
        for (OrderLineRow line : lines) {
            List<MarkRef> held = refs.getOrDefault(line.lineId(), List.of());
            boolean marked = markedVariants.contains(line.sourceVariantId()) || !held.isEmpty();
            receiptLines.add(new ReceiptLine(
                    line.lineId(),
                    line.sourceVariantId(),
                    line.productName(),
                    line.quantity(),
                    line.finalAmountMinor(),
                    line.taxAmountMinor(),
                    marked,
                    marked ? unitsOf(line.quantity()) : 0,
                    held));
        }
        return new ReceiptSource(order.brandId(), order.currency(), List.copyOf(receiptLines));
    }

    // ---------------------------------------------------------------- internals

    private boolean enabled(UUID tenantId) {
        return Boolean.TRUE.equals(configuration.value(FeatureFlagKeys.MARKED_GOODS, scopeOf(tenantId)));
    }

    private void requireEnabled(UUID tenantId) {
        if (!enabled(tenantId)) {
            throw new MarkRefusedException(
                    Refusal.FEATURE_DISABLED, "Marked-goods capture is not enabled", Map.of(), "none");
        }
    }

    private MarkingStructureCheck structureCheck(UUID tenantId) {
        return MarkingStructureCheck.of(
                configuration.value(MarkingConfigurationKeys.STRUCTURE_CHECK, scopeOf(tenantId)));
    }

    private static ResourceScope scopeOf(UUID tenantId) {
        return ResourceScope.tenant(tenantId);
    }

    private Set<UUID> markedVariantsOf(UUID tenantId, UUID brandId, List<LiveLine> lines) {
        if (lines.isEmpty()) {
            return Set.of();
        }
        return classification.markedNodes(
                tenantId, brandId, lines.stream().map(LiveLine::variantId).collect(Collectors.toSet()));
    }

    private List<LineMarkCount> markCountsFor(UUID tenantId, UUID brandId, UUID orderId) {
        List<LiveLine> lines = marks.liveLines(tenantId, orderId);
        Set<UUID> markedVariants = markedVariantsOf(tenantId, brandId, lines);
        Map<UUID, Integer> counts = marks.liveCounts(tenantId, orderId);
        List<LineMarkCount> result = new ArrayList<>();
        for (LiveLine line : lines) {
            int held = counts.getOrDefault(line.lineId(), 0);
            if (markedVariants.contains(line.variantId()) || held > 0) {
                result.add(new LineMarkCount(line.lineId(), line.units(), held));
            }
        }
        return result;
    }

    /** Where an order stands overall, after a capture. */
    private record Progress(int markedLineCount, int markedUnitCount, boolean complete) {}

    private Progress progressOf(UUID tenantId, UUID brandId, UUID orderId) {
        List<LineMarkCount> counts = markCountsFor(tenantId, brandId, orderId);
        int units = counts.stream().mapToInt(LineMarkCount::required).sum();
        return new Progress(
                counts.size(), units, !counts.isEmpty() && counts.stream().allMatch(LineMarkCount::complete));
    }

    private static int unitsOf(java.math.BigDecimal quantity) {
        return quantity.setScale(0, java.math.RoundingMode.CEILING).intValueExact();
    }

    private void recordCaptured(
            OrderRow order,
            LiveLine line,
            int sequence,
            MarkCaptureStage stage,
            CaptureCommand command,
            String gtin,
            boolean gtinChecked,
            Instant now) {
        // The change document names the line, the sequence, the stage, the entry method and the product
        // identifier. Never the code; never the customer. (Field names avoid ChangeDocuments' protected
        // terms, which match by substring: "gtin" contains "tin".)
        audit.record(AuditFact.of("ordering.order.line_mark_captured", AuditClass.BUSINESS)
                .by(ActorRef.user(command.actorId(), null))
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .because("MARK_CAPTURED")
                .usingCapability(Capability.ORDER_ADVANCE.code())
                .changed(ChangeDocuments.created(orderedMap(
                        "orderLineId",
                        line.lineId().toString(),
                        "sequence",
                        sequence,
                        "stage",
                        stage.name(),
                        "entryMethod",
                        command.entryMethod().name(),
                        "productCode",
                        gtin,
                        "productChecked",
                        gtinChecked)))
                .correlatedBy(order.orderId().toString())
                .occurredAt(now)
                .build());
    }

    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private static MarkRefusedException malformed(String message) {
        return new MarkRefusedException(Refusal.MARKING_CODE_MALFORMED, message, Map.of(), "none");
    }

    private static MarkRefusedException refusal(Refusal refusal, String message, MarkCaptureStage stage) {
        return new MarkRefusedException(refusal, message, Map.of(), stage.name());
    }

    private static MarkRefusedException alreadyCaptured(boolean onThisLine, MarkCaptureStage stage) {
        return new MarkRefusedException(
                Refusal.MARKING_CODE_ALREADY_CAPTURED,
                onThisLine
                        ? "This unit is already scanned on this line"
                        : "This unit is already scanned on another order",
                Map.of("onThisLine", onThisLine),
                stage.name());
    }

    // ---------------------------------------------------------------- exceptions

    /** Why a capture, a removal or a read was refused. Each name is a stable problem {@code reason}. */
    public enum Refusal {
        FEATURE_DISABLED,
        MARKING_CODE_MALFORMED,
        MARKING_CODE_WRONG_PRODUCT,
        MARKING_CODE_ALREADY_CAPTURED,
        MARKING_KEYED_ENTRY_REFUSED,
        LINE_NOT_MARKED,
        LINE_MARKS_COMPLETE,
        ORDER_NOT_MARKABLE,
        RATE_LIMITED
    }

    /**
     * A business refusal. Carries a reason and properties and never a code: its message is fixed text
     * written here, not text derived from what was scanned.
     */
    public static class MarkRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Refusal refusal;
        private final transient Map<String, Object> properties;
        private final String stage;

        public MarkRefusedException(Refusal refusal, String message, Map<String, Object> properties, String stage) {
            super(message);
            this.refusal = refusal;
            this.properties = Map.copyOf(properties);
            this.stage = stage;
        }

        public Refusal refusal() {
            return refusal;
        }

        public Map<String, Object> properties() {
            return properties;
        }

        String stageForMetrics() {
            return stage;
        }
    }

    /** No such order, line or scanned unit for this tenant and branch. */
    public static class MarkNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MarkNotFoundException(String message) {
            super(message);
        }
    }
}
