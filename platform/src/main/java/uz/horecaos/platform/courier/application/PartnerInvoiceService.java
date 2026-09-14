package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
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
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CostPath;
import uz.horecaos.platform.courier.domain.MatchStatus;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceLineRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Importing a partner's delivery invoice and reconciling it (ADR 0042).
 *
 * <p>Matching produces four outcomes and reports all four. {@code UNMATCHED_LINE}
 * — the partner billed for something HorecaOS has no shipment for — is the
 * direction reconciliation reports usually omit, and the only one that can hide
 * a charge for a delivery that never happened. It is never netted into a total,
 * so an invoice with one phantom line does not quietly reconcile because a real
 * line somewhere else was cheaper than expected.
 *
 * <p>A variance blocks nothing and raises an operations task. Disputing a
 * partner invoice is a human activity between two companies, and the platform's
 * job is to record the evidence for it rather than to pretend to automate it.
 */
@Service
public class PartnerInvoiceService {

    private final JdbcDeliveryCostStore costs;
    private final AuditRecorder audit;
    private final Clock clock;

    public PartnerInvoiceService(JdbcDeliveryCostStore costs, AuditRecorder audit, Clock clock) {
        this.costs = costs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID importInvoice(ImportInvoice command) {
        UUID invoiceId = UUID.randomUUID();
        costs.insertInvoice(new InvoiceRow(
                invoiceId,
                command.tenantId(),
                command.providerCode(),
                command.providerInvoiceRef(),
                command.legalEntityId(),
                command.periodStart(),
                command.periodEnd(),
                command.totalMinor(),
                command.currency(),
                "IMPORTED",
                command.actor().subject()));

        for (ImportedLine line : command.lines()) {
            costs.insertInvoiceLine(new InvoiceLineRow(
                    UUID.randomUUID(),
                    command.tenantId(),
                    invoiceId,
                    line.providerShipmentRef(),
                    null,
                    line.amountMinor(),
                    command.currency(),
                    line.chargeType(),
                    MatchStatus.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null));
        }

        audit.record(AuditFact.of("partner.invoice.imported", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.tenant(command.tenantId()))
                .target("partner_delivery_invoice", invoiceId)
                .because(command.reason())
                .changed(Map.of(
                        "providerCode",
                        command.providerCode(),
                        "providerInvoiceRef",
                        command.providerInvoiceRef(),
                        "totalMinor",
                        command.totalMinor(),
                        "lineCount",
                        command.lines().size()))
                .usingCapability("partner.invoice.manage")
                .correlatedBy("partner-invoice")
                .occurredAt(clock.instant())
                .build());

        return invoiceId;
    }

    /**
     * Records what a partner booking is expected to cost, at {@code ACCRUED}.
     *
     * <p>Called by ADR 0014 sourcing when it books with a partner, and again
     * when the partner charges a cancellation. Both are real costs on the same
     * shipment, and a shipment cancelled with Noor at a fee and then delivered
     * in-house carries this line and an internal accrual — which is the case a
     * single cost column silently discards.
     */
    @Transactional
    public UUID recordPartnerCost(
            UUID tenantId,
            UUID shipmentId,
            String providerCode,
            long amountMinor,
            String currency,
            LocalDate businessDate,
            @Nullable UUID legalEntityId,
            PartnerChargeType chargeType,
            String recordedBy) {

        UUID lineId = UUID.randomUUID();
        costs.insertLine(new CostLineRow(
                lineId,
                tenantId,
                shipmentId,
                legalEntityId,
                businessDate,
                CostPath.PARTNER,
                CostBasis.ACCRUED,
                amountMinor,
                currency,
                "partner_booking:" + chargeType.name(),
                null,
                null,
                providerCode,
                clock.instant(),
                null,
                recordedBy));
        return lineId;
    }

    /**
     * Runs matching over one imported invoice.
     *
     * <p>Only lines still {@code PENDING} or {@code UNMATCHED_LINE} are
     * processed — a line already {@code MATCHED}, {@code VARIANCE} or
     * {@code UNBILLED} is left exactly as matching last found it. This is what
     * makes the operation safe to call a second time with a fuller map: it is
     * the resolution path for an {@code UNMATCHED_LINE} row an operator has
     * since found the correct shipment reference for, and reprocessing every
     * already-resolved line on each retry would insert a second {@code
     * delivery_cost_lines} row at {@code INVOICED} for the same shipment,
     * double-counting a cost that was already recognised.
     *
     * @param shipmentsByProviderRef the caller's resolution from the partner's
     *                               own shipment reference to a HorecaOS shipment.
     *                               Passed in rather than looked up here, because
     *                               ADR 0014 owns the provider reference on the
     *                               assignment attempt and this module must not
     *                               grow a second copy of it
     */
    @Transactional
    public MatchReport match(
            UUID tenantId, UUID invoiceId, Map<String, UUID> shipmentsByProviderRef, ActorRef actor, String reason) {

        InvoiceRow invoice = costs.findInvoice(tenantId, invoiceId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such partner invoice: " + invoiceId));

        List<UUID> unmatched = new ArrayList<>();
        List<UUID> variances = new ArrayList<>();
        int matched = 0;

        List<InvoiceLineRow> unresolved = costs.linesOfInvoice(tenantId, invoiceId).stream()
                .filter(line ->
                        line.matchStatus() == MatchStatus.PENDING || line.matchStatus() == MatchStatus.UNMATCHED_LINE)
                .toList();

        for (InvoiceLineRow line : unresolved) {
            UUID shipmentId = shipmentsByProviderRef.get(line.providerShipmentRef());
            if (shipmentId == null) {
                costs.matchLine(
                        tenantId,
                        line.id(),
                        null,
                        MatchStatus.UNMATCHED_LINE,
                        null,
                        "NO_SHIPMENT_FOR_PROVIDER_REFERENCE");
                unmatched.add(line.id());
                // Deliberately no cost line. Recording a cost for a shipment
                // HorecaOS has no record of would put a phantom delivery into the
                // total that this status exists to keep out of it.
                continue;
            }

            Optional<Long> accrued = accruedPartnerAmount(tenantId, shipmentId);
            long variance = accrued.map(amount -> line.amountMinor() - amount).orElse(0L);
            MatchStatus status = accrued.isEmpty() || variance == 0 ? MatchStatus.MATCHED : MatchStatus.VARIANCE;

            costs.matchLine(
                    tenantId,
                    line.id(),
                    shipmentId,
                    status,
                    status == MatchStatus.VARIANCE ? variance : null,
                    status == MatchStatus.VARIANCE ? "AMOUNT_DIFFERS_FROM_BOOKING" : null);
            if (status == MatchStatus.VARIANCE) {
                variances.add(line.id());
            }
            matched++;

            // The partner half of the two cost paths, now at INVOICED. The
            // ACCRUED estimate recorded at booking stays where it is: a report at
            // ACCRUED must keep showing what was known that day.
            costs.insertLine(new CostLineRow(
                    UUID.randomUUID(),
                    tenantId,
                    shipmentId,
                    invoice.legalEntityId(),
                    invoice.periodEnd(),
                    CostPath.PARTNER,
                    CostBasis.INVOICED,
                    line.amountMinor(),
                    invoice.currency(),
                    "partner_delivery_invoice_line",
                    line.id(),
                    null,
                    invoice.providerCode(),
                    clock.instant(),
                    null,
                    actor.subject()));
        }

        costs.markInvoiceMatched(tenantId, invoiceId);

        audit.record(AuditFact.of("partner.invoice.matched", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("partner_delivery_invoice", invoiceId)
                .because(reason)
                .changed(Map.of(
                        "matchedLines", matched, "varianceLines", variances.size(), "unmatchedLines", unmatched.size()))
                .usingCapability("partner.invoice.manage")
                .correlatedBy("partner-invoice")
                .occurredAt(clock.instant())
                .build());

        return new MatchReport(matched, List.copyOf(variances), List.copyOf(unmatched));
    }

    /**
     * Flags the whole invoice for pushback to the partner — the акт сверки
     * dispute path. Refused once the invoice is {@code PAID}: a paid invoice
     * is disputed by other means, not by reopening this workflow.
     */
    @Transactional
    public void disputeInvoice(UUID tenantId, UUID invoiceId, ActorRef actor, String reason) {
        InvoiceRow invoice = costs.findInvoice(tenantId, invoiceId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such partner invoice: " + invoiceId));
        if (!costs.markInvoiceDisputed(tenantId, invoiceId)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "This invoice is " + invoice.status() + " and cannot be disputed");
        }

        audit.record(AuditFact.of("partner.invoice.disputed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("partner_delivery_invoice", invoiceId)
                .because(reason)
                .changed(Map.of(
                        "providerCode", invoice.providerCode(), "providerInvoiceRef", invoice.providerInvoiceRef()))
                .usingCapability("partner.invoice.manage")
                .correlatedBy("partner-invoice")
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * An operator's disposition of one {@code VARIANCE} line: {@code accept}
     * pays the partner's charge as invoiced despite the mismatch from the
     * booked accrual; {@code false} disputes it instead. Neither changes {@code
     * match_status} — a variance is a fact about the money, and stays reported
     * as one — only {@code variance_resolution}, which the worklist reads to
     * stop treating the row as unresolved.
     */
    @Transactional
    public InvoiceLineRow resolveVariance(
            UUID tenantId, UUID invoiceId, UUID lineId, boolean accept, ActorRef actor, String reason) {

        List<InvoiceLineRow> lines = costs.linesOfInvoice(tenantId, invoiceId);
        InvoiceLineRow line = lines.stream()
                .filter(candidate -> candidate.id().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such invoice line: " + lineId));
        if (line.matchStatus() != MatchStatus.VARIANCE) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only a VARIANCE line can be accepted or disputed, this one is " + line.matchStatus());
        }

        String resolution = accept ? "ACCEPTED" : "DISPUTED";
        if (!costs.resolveVarianceLine(tenantId, lineId, resolution, actor.subject())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This line's match status changed under us");
        }

        audit.record(AuditFact.of("partner.invoice.variance.resolved", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("partner_delivery_invoice_line", lineId)
                .because(reason)
                .changed(Map.of("resolution", resolution, "varianceMinor", String.valueOf(line.varianceMinor())))
                .usingCapability("partner.invoice.manage")
                .correlatedBy("partner-invoice")
                .occurredAt(clock.instant())
                .build());

        return costs.linesOfInvoice(tenantId, invoiceId).stream()
                .filter(candidate -> candidate.id().equals(lineId))
                .findFirst()
                .orElseThrow();
    }

    /** What the booking said this partner delivery would cost, if anything did. */
    private Optional<Long> accruedPartnerAmount(UUID tenantId, UUID shipmentId) {
        return costs.linesOfShipment(tenantId, shipmentId).stream()
                .filter(line -> line.costPath() == CostPath.PARTNER)
                .filter(line -> line.costBasis() == CostBasis.ACCRUED)
                .map(CostLineRow::amountMinor)
                .reduce(Long::sum);
    }

    /**
     * A partner invoice as imported.
     *
     * @param legalEntityId null until ADR 0038's registry can resolve one
     */
    public record ImportInvoice(
            UUID tenantId,
            String providerCode,
            String providerInvoiceRef,
            @Nullable UUID legalEntityId,
            LocalDate periodStart,
            LocalDate periodEnd,
            long totalMinor,
            String currency,
            List<ImportedLine> lines,
            ActorRef actor,
            String reason) {}

    public record ImportedLine(String providerShipmentRef, long amountMinor, PartnerChargeType chargeType) {}

    public record MatchReport(int matchedLines, List<UUID> varianceLineIds, List<UUID> unmatchedLineIds) {}
}
