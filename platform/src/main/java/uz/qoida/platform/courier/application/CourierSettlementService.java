package uz.qoida.platform.courier.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.ApprovalOutcome;
import uz.qoida.platform.audit.api.ApprovalAction;
import uz.qoida.platform.audit.api.ApprovalParameters;
import uz.qoida.platform.audit.api.ApprovalRequestCommand;
import uz.qoida.platform.audit.api.ApprovalService;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.courier.domain.CostBasis;
import uz.qoida.platform.courier.domain.CostPath;
import uz.qoida.platform.courier.domain.LedgerEntryType;
import uz.qoida.platform.courier.domain.PayoutMethod;
import uz.qoida.platform.courier.domain.SettlementPeriodStatus;
import uz.qoida.platform.courier.domain.StatementVocabulary;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.EarningRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodTotals;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Closing a settlement period, producing its statement, and authorising the
 * payout (ADR 0042).
 *
 * <p>The statement carries gross only. There is no withholding line and no
 * net-of-tax line, because couriers here are registered self-employed persons
 * who invoice the tenant; the figure they invoice for is line four, and what the
 * tenant transfers is that figure plus adjustments less the cash the courier is
 * still holding. Cash is presented as its own block for the same reason: merging
 * it into earnings is how a courier concludes he was paid less than he earned.
 *
 * <p>Every figure on the statement is the sum of immutable ledger lines, and the
 * document is hashed and stored whole. Nothing recomputes it afterwards. Two
 * screens computing the same «К оплате» independently is precisely how they come
 * to differ, and the difference always surfaces as an argument on payday.
 */
@Service
public class CourierSettlementService {

    private final JdbcCourierLedgerStore ledger;
    private final JdbcCourierStore couriers;
    private final JdbcDeliveryCostStore costLines;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CourierSettlementService(JdbcCourierLedgerStore ledger, JdbcCourierStore couriers,
            JdbcDeliveryCostStore costLines, ApprovalService approvals, AuditRecorder audit,
            ObjectMapper objectMapper, Clock clock) {
        this.ledger = ledger;
        this.couriers = couriers;
        this.costLines = costLines;
        this.approvals = approvals;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Closes the period, writes its statement, and marks its internal cost lines
     * {@code SETTLED}.
     *
     * <p>Once closed the period never reopens. Anything arriving afterwards is a
     * prior-period adjustment in the next one, which is why this is safe to do
     * on a schedule rather than only when somebody is sure nothing is in flight.
     */
    @Transactional
    public Statement close(UUID tenantId, UUID periodId, ActorRef actor, String reason) {
        PeriodRow period = period(tenantId, periodId);
        if (period.status() != SettlementPeriodStatus.OPEN) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "This period is %s; a closed period is never reopened, because reopening "
                            .formatted(period.status())
                            + "changes a figure somebody has already been paid against");
        }

        List<LedgerEntryRow> entries = ledger.entriesOf(tenantId, periodId);
        List<EarningRow> earnings = ledger.earningsOf(tenantId, periodId);
        PeriodTotals totals = ledger.computeTotals(tenantId, periodId);
        EngagementRow engagement = couriers.findEngagement(tenantId, period.engagementId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such engagement: " + period.engagementId()));

        List<UUID> afterLapse = entriesAfterLapse(entries, engagement,
                couriers.firstLapseNoticeAt(tenantId, engagement.id()).orElse(null));
        boolean complianceFlag = !afterLapse.isEmpty();

        Map<String, Object> document = buildDocument(period, engagement, entries, earnings, totals,
                afterLapse);
        StatementVocabulary.assertCarriesNoTaxLanguage(document);
        String json = objectMapper.writeValueAsString(document);
        String hash = sha256(json);

        boolean closed = ledger.closePeriod(tenantId, periodId, period.version(), totals,
                complianceFlag, hash, actor.subject(), clock.instant());
        if (!closed) {
            throw ApiException.staleVersion(period.version(), period.version() + 1L);
        }
        ledger.insertStatement(UUID.randomUUID(), tenantId, periodId, hash, json, actor.subject());

        // The internal path moves to SETTLED at close: that is exactly what the
        // basis means on this side, and it is why INVOICED is not a valid
        // internal basis at all.
        for (EarningRow earning : earnings) {
            costLines.insertLine(new CostLineRow(UUID.randomUUID(), tenantId, earning.shipmentId(),
                    earning.legalEntityId(), earning.businessDate(), CostPath.INTERNAL,
                    CostBasis.SETTLED, earning.totalMinor(), earning.currency(),
                    "courier_settlement_period", periodId, earning.courierId(), null,
                    clock.instant(), null, "courier-settlement-service"));
        }

        audit.record(AuditFact.of("courier.settlement.closed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_settlement_period", periodId)
                .because(reason)
                .changed(Map.of("amountPayableMinor", totals.amountPayableMinor(),
                        "grossEarningsMinor", totals.grossEarningsMinor(),
                        "cashHeldMinor", totals.cashHeldMinor(),
                        "complianceFlag", complianceFlag,
                        "statementHash", hash))
                .evidence(hash)
                .usingCapability("courier.settlement.close")
                .correlatedBy("courier-settlement")
                .occurredAt(clock.instant())
                .build());

        return new Statement(periodId, hash, document, totals, complianceFlag);
    }

    /**
     * Authorises the payout for a closed period.
     *
     * <p>A period carrying the compliance flag needs ADR 0027 four-eyes approval
     * first. Note what that does and does not mean: the courier is paid either
     * way, because the work was done and the money is owed. What the flag buys is
     * that an accountant sees the exposure before the transfer rather than after.
     */
    @Transactional
    public PayoutOutcome authorisePayout(UUID tenantId, UUID periodId, PayoutMethod method,
            ActorRef actor, String reason) {

        PeriodRow period = period(tenantId, periodId);
        if (period.status() != SettlementPeriodStatus.CLOSED) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "A payout is authorised against a CLOSED period; this one is " + period.status());
        }
        if (period.amountPayableMinor() <= 0) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "Nothing is payable for this period; the courier's position is %d"
                            .formatted(period.amountPayableMinor()));
        }

        UUID approvalRequestId = null;
        if (period.complianceFlag()) {
            ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                    ApprovalAction.COURIER_PAYOUT_AUTHORISE.code(),
                    payoutApprovalHash(tenantId, period, method),
                    ResourceScope.tenant(tenantId),
                    actor,
                    reason,
                    ApprovalRequestCommand.DEFAULT_VALIDITY));

            switch (outcome) {
                case ApprovalOutcome.Approved approved -> {
                    // One signature authorises one payout, and — since
                    // payoutApprovalHash covers the method — one payout on the
                    // rail the checker saw.
                    approved.grant().consume();
                    approvalRequestId = approved.requestId();
                }
                case ApprovalOutcome.Pending pending -> {
                    return new PayoutOutcome(null, pending.requestId(), false);
                }
                case ApprovalOutcome.Declined declined -> throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The payout was declined: " + declined.reason());
                case ApprovalOutcome.NotRequired ignored -> throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "This period's work fell after a registration lapse, so its payout "
                                + "requires four-eyes approval and no approval policy provided one");
            }
        }

        UUID payoutId = UUID.randomUUID();
        ledger.insertPayout(payoutId, tenantId, period.courierId(), periodId,
                period.amountPayableMinor(), period.currency(), method, actor.subject(),
                approvalRequestId);

        // The ledger entry that takes the money off the courier's balance. Qoida
        // records the payout; somebody else moves the money, and that seam is
        // deliberate — worker payment is a different rail from ADR 0013's
        // customer-payment rail and building it now would be building it blind.
        ledger.append(new JdbcCourierLedgerStore.LedgerEntryRow(UUID.randomUUID(), tenantId,
                period.courierId(), periodId, null, LedgerEntryType.PAYOUT,
                -period.amountPayableMinor(), period.currency(), "courier_payout", payoutId,
                uz.qoida.platform.courier.domain.AdjustmentOrigin.SYSTEM, null, clock.instant(),
                clock.instant(), "payout:" + payoutId, approvalRequestId, null, actor.subject()));

        ledger.markSettled(tenantId, periodId, clock.instant());

        audit.record(AuditFact.of("courier.payout.authorised", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_payout", payoutId)
                .because(reason)
                .changed(Map.of("amountMinor", period.amountPayableMinor(),
                        "method", method.name(),
                        "complianceFlag", period.complianceFlag()))
                .underApproval(approvalRequestId)
                .usingCapability("courier.payout.authorise")
                .correlatedBy("courier-settlement")
                .occurredAt(clock.instant())
                .build());

        return new PayoutOutcome(payoutId, approvalRequestId, true);
    }

    /**
     * The statement as stored. Read back rather than recomputed, which is the
     * whole point of storing it.
     */
    public Map<String, Object> statementOf(UUID tenantId, UUID periodId) {
        return ledger.findStatement(tenantId, periodId)
                .map(row -> objectMapper.readValue(row.document(),
                        new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "This period has no statement; it has not been closed"));
    }

    // ------------------------------------------------------------- the document

    private Map<String, Object> buildDocument(PeriodRow period, EngagementRow engagement,
            List<LedgerEntryRow> entries, List<EarningRow> earnings, PeriodTotals totals,
            List<UUID> afterLapse) {

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("statementVersion", 1);
        document.put("tenantId", period.tenantId().toString());
        document.put("courierId", period.courierId().toString());
        document.put("engagementId", period.engagementId().toString());
        document.put("engagementType", "SELF_EMPLOYED");
        document.put("periodId", period.id().toString());
        document.put("periodStart", period.periodStart().toString());
        document.put("periodEnd", period.periodEnd().toString());
        document.put("currency", period.currency());

        // 2. Gross earnings by component, each naming the rate card version it
        // was computed under.
        Map<String, Long> byComponent = new LinkedHashMap<>();
        byComponent.put("perOrder", earnings.stream().mapToLong(EarningRow::perOrderMinor).sum());
        byComponent.put("perKilometre", earnings.stream().mapToLong(EarningRow::perKmMinor).sum());
        byComponent.put("perShiftFixed", entries.stream()
                .filter(entry -> entry.entryType() == LedgerEntryType.SHIFT_EARNING)
                .mapToLong(LedgerEntryRow::amountMinor).sum());
        byComponent.put("minimumTopUp",
                earnings.stream().mapToLong(EarningRow::minimumTopUpMinor).sum());
        document.put("grossEarningsByComponent", byComponent);
        document.put("rateCardVersions", earnings.stream()
                .map(earning -> earning.rateCardId() + ":" + earning.rateCardVersion())
                .distinct().sorted().toList());

        // 3. Adjustments, each naming its origin.
        document.put("adjustments", entries.stream()
                .filter(entry -> entry.entryType().isAdjustment())
                .map(entry -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("entryId", entry.id().toString());
                    line.put("entryType", entry.entryType().name());
                    line.put("amountMinor", entry.amountMinor());
                    line.put("origin", entry.origin().name());
                    line.put("reasonCode", entry.reasonCode());
                    line.put("approvalRequestId", String.valueOf(entry.approvalRequestId()));
                    line.put("recordedBy", entry.createdBy());
                    return line;
                })
                .toList());

        // 4. The figure the courier invoices for.
        document.put("grossTotalMinor", totals.grossEarningsMinor());

        // 5. Cash, in its own block.
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("collectedMinor", -entries.stream()
                .filter(entry -> entry.entryType() == LedgerEntryType.CASH_COLLECTED)
                .mapToLong(LedgerEntryRow::amountMinor).sum());
        cash.put("handedOverMinor", entries.stream()
                .filter(entry -> entry.entryType() == LedgerEntryType.CASH_HANDED_OVER)
                .mapToLong(LedgerEntryRow::amountMinor).sum());
        cash.put("varianceMinor", entries.stream()
                .filter(entry -> entry.entryType() == LedgerEntryType.CASH_VARIANCE)
                .mapToLong(LedgerEntryRow::amountMinor).sum());
        cash.put("closingPositionMinor", totals.cashHeldMinor());
        document.put("cashReconciliation", cash);

        // 6. What the tenant transfers, labelled in those words.
        document.put("amountToTransferMinor", totals.amountPayableMinor());
        document.put("declaration",
                "This is a gross figure. No tax has been deducted from it and none will be. "
                        + "The courier is a registered self-employed person and accounts for "
                        + "their own obligations.");

        // 7. The counts a courier actually disputes.
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deliveredOrders", totals.deliveredCount());
        counts.put("onTimeOrders", totals.onTimeCount());
        counts.put("distanceMeters", totals.distanceMeters());
        counts.put("paidSeconds", totals.paidSeconds());
        counts.put("shiftsClosed", totals.shiftCount());
        document.put("basisCounts", counts);

        // 8. One row per assignment.
        document.put("lines", earnings.stream().map(earning -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("shipmentId", earning.shipmentId().toString());
            line.put("deliveredAt", earning.deliveredAt().toString());
            line.put("distanceMeters", earning.distanceMeters());
            line.put("distanceSource", earning.distanceSource().name());
            line.put("rateCardVersion", earning.rateCardVersion());
            line.put("onTimeOutcome", earning.onTimeOutcome().name());
            line.put("amountMinor", earning.totalMinor());
            return line;
        }).toList());

        // 9. Per-entity subtotals, sorted so the document hashes reproducibly.
        Map<String, Long> byEntity = new TreeMap<>();
        for (LedgerEntryRow entry : entries) {
            if (!entry.entryType().isGrossEarning()) {
                continue;
            }
            byEntity.merge(String.valueOf(entry.legalEntityId()), entry.amountMinor(), Long::sum);
        }
        document.put("legalEntitySubtotals", byEntity);

        // 10. The compliance flag and the lines it applies to.
        Map<String, Object> compliance = new LinkedHashMap<>();
        compliance.put("flag", !afterLapse.isEmpty());
        compliance.put("registrationValidUntil",
                String.valueOf(engagement.registrationValidUntil()));
        compliance.put("affectedEntryIds", afterLapse.stream().map(UUID::toString).sorted().toList());
        document.put("compliance", compliance);

        return document;
    }

    /**
     * Entries whose work happened after the registration ran out.
     *
     * <p>They stay on the statement and stay payable. The flag exists so nobody
     * discovers them from an inspector.
     */
    private static List<UUID> entriesAfterLapse(List<LedgerEntryRow> entries,
            EngagementRow engagement, java.time.Instant lapsedAt) {

        LocalDate dueOn = engagement.reverificationDueOn();
        java.time.Instant reverifiedAt = engagement.registrationVerifiedAt();
        List<UUID> affected = new ArrayList<>();

        for (LedgerEntryRow entry : entries) {
            if (!entry.entryType().isGrossEarning()) {
                continue;
            }
            // A verification that predates the lapse cleared nothing: only one
            // performed after it reopens the window. Reading the current
            // verification instant without that check would treat the original
            // onboarding attestation as if it had cured a later lapse.
            java.time.Instant curedAt = reverifiedAt != null && lapsedAt != null
                    && reverifiedAt.isAfter(lapsedAt) ? reverifiedAt : null;

            boolean insideRecordedLapse = lapsedAt != null
                    && !entry.occurredAt().isBefore(lapsedAt)
                    && (curedAt == null || entry.occurredAt().isBefore(curedAt));

            // The fallback covers the window between a registration running out
            // and the sweeper noticing. Without it, closing a period during that
            // gap would produce an unflagged statement for work that was already
            // uncovered.
            boolean pastDueAndNeverSwept = lapsedAt == null && dueOn != null
                    && LocalDate.ofInstant(entry.occurredAt(), ZoneOffset.UTC).isAfter(dueOn);

            if (insideRecordedLapse || pastDueAndNeverSwept) {
                affected.add(entry.id());
            }
        }
        return List.copyOf(affected);
    }

    private PeriodRow period(UUID tenantId, UUID periodId) {
        return ledger.findPeriod(tenantId, periodId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such settlement period: " + periodId));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }

    /**
     * What a signature on a payout actually covers.
     *
     * <p><strong>It was {@code sha256(periodId + ":" + amountPayableMinor)}, and
     * the method escaped.</strong> {@link PayoutMethod} is the rail the money
     * leaves on — {@code CASH_AT_BRANCH}, {@code BANK_TRANSFER},
     * {@code CARD_TRANSFER} — it is written onto the payout row, and it is the
     * whole of what somebody downstream does next. An accountant asked to look at
     * a flagged period's exposure before a bank transfer signed for exactly that;
     * under the same signature the maker could authorise the identical sum as cash
     * handed over at a branch, which has a different control environment, a
     * different paper trail and a different person holding the money. Two other
     * things escaped with it: the tenant, so the hash alone did not name whose
     * payout it was — {@code findRequest} constrains on the tenant, but a hash
     * that omits it is one column away from a cross-tenant match the day something
     * else keys on it — and the currency and courier the payout is recorded
     * against.
     *
     * <p>Built from a record so the drift guard applies: add a field to
     * {@link PayoutParameters} and it is in the hash without anybody editing this
     * method. Nothing is excluded, and {@code excluding()} says so out loud.
     *
     * <p>The statement hash is a different instrument entirely and stays as it is:
     * {@link #sha256} over the serialised document is evidence of what was
     * published, not a binding on what may be executed.
     */
    static String payoutApprovalHash(UUID tenantId, PeriodRow period, PayoutMethod method) {
        return ApprovalParameters.of(new PayoutParameters(
                        tenantId, period.id(), period.courierId(), period.amountPayableMinor(),
                        period.currency(), method))
                .excluding()
                .hash();
    }

    /** The whole of what a payout approval is bound to. */
    private record PayoutParameters(UUID tenantId, UUID periodId, UUID courierId,
            long amountPayableMinor, String currency, PayoutMethod method) { }

    public record Statement(UUID periodId, String statementHash, Map<String, Object> document,
            PeriodTotals totals, boolean complianceFlag) { }

    /** @param authorised false when the payout is waiting on a second pair of eyes */
    public record PayoutOutcome(UUID payoutId, UUID approvalRequestId, boolean authorised) { }
}
