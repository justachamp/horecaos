package uz.qoida.platform.courier.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.courier.domain.CostBasis;
import uz.qoida.platform.courier.domain.CostPath;
import uz.qoida.platform.courier.domain.MatchStatus;
import uz.qoida.platform.courier.domain.PartnerChargeType;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceLineRow;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceRow;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Importing a partner's delivery invoice and reconciling it (ADR 0042).
 *
 * <p>Matching produces four outcomes and reports all four. {@code UNMATCHED_LINE}
 * — the partner billed for something Qoida has no shipment for — is the
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
        costs.insertInvoice(new InvoiceRow(invoiceId, command.tenantId(), command.providerCode(),
                command.providerInvoiceRef(), command.legalEntityId(), command.periodStart(),
                command.periodEnd(), command.totalMinor(), command.currency(), "IMPORTED",
                command.actor().subject()));

        for (ImportedLine line : command.lines()) {
            costs.insertInvoiceLine(new InvoiceLineRow(UUID.randomUUID(), command.tenantId(),
                    invoiceId, line.providerShipmentRef(), null, line.amountMinor(),
                    command.currency(), line.chargeType(), MatchStatus.PENDING, null, null));
        }

        audit.record(AuditFact.of("partner.invoice.imported", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.tenant(command.tenantId()))
                .target("partner_delivery_invoice", invoiceId)
                .because(command.reason())
                .changed(Map.of("providerCode", command.providerCode(),
                        "providerInvoiceRef", command.providerInvoiceRef(),
                        "totalMinor", command.totalMinor(),
                        "lineCount", command.lines().size()))
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
    public UUID recordPartnerCost(UUID tenantId, UUID shipmentId, String providerCode,
            long amountMinor, String currency, LocalDate businessDate, UUID legalEntityId,
            PartnerChargeType chargeType, String recordedBy) {

        UUID lineId = UUID.randomUUID();
        costs.insertLine(new CostLineRow(lineId, tenantId, shipmentId, legalEntityId, businessDate,
                CostPath.PARTNER, CostBasis.ACCRUED, amountMinor, currency,
                "partner_booking:" + chargeType.name(), null, null, providerCode,
                clock.instant(), null, recordedBy));
        return lineId;
    }

    /**
     * Runs matching over one imported invoice.
     *
     * @param shipmentsByProviderRef the caller's resolution from the partner's
     *                               own shipment reference to a Qoida shipment.
     *                               Passed in rather than looked up here, because
     *                               ADR 0014 owns the provider reference on the
     *                               assignment attempt and this module must not
     *                               grow a second copy of it
     */
    @Transactional
    public MatchReport match(UUID tenantId, UUID invoiceId,
            Map<String, UUID> shipmentsByProviderRef, ActorRef actor, String reason) {

        InvoiceRow invoice = costs.findInvoice(tenantId, invoiceId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such partner invoice: " + invoiceId));

        List<UUID> unmatched = new ArrayList<>();
        List<UUID> variances = new ArrayList<>();
        int matched = 0;

        for (InvoiceLineRow line : costs.linesOfInvoice(tenantId, invoiceId)) {
            UUID shipmentId = shipmentsByProviderRef.get(line.providerShipmentRef());
            if (shipmentId == null) {
                costs.matchLine(tenantId, line.id(), null, MatchStatus.UNMATCHED_LINE, null,
                        "NO_SHIPMENT_FOR_PROVIDER_REFERENCE");
                unmatched.add(line.id());
                // Deliberately no cost line. Recording a cost for a shipment
                // Qoida has no record of would put a phantom delivery into the
                // total that this status exists to keep out of it.
                continue;
            }

            Optional<Long> accrued = accruedPartnerAmount(tenantId, shipmentId);
            long variance = accrued.map(amount -> line.amountMinor() - amount).orElse(0L);
            MatchStatus status = accrued.isEmpty() || variance == 0
                    ? MatchStatus.MATCHED : MatchStatus.VARIANCE;

            costs.matchLine(tenantId, line.id(), shipmentId, status,
                    status == MatchStatus.VARIANCE ? variance : null,
                    status == MatchStatus.VARIANCE ? "AMOUNT_DIFFERS_FROM_BOOKING" : null);
            if (status == MatchStatus.VARIANCE) {
                variances.add(line.id());
            }
            matched++;

            // The partner half of the two cost paths, now at INVOICED. The
            // ACCRUED estimate recorded at booking stays where it is: a report at
            // ACCRUED must keep showing what was known that day.
            costs.insertLine(new CostLineRow(UUID.randomUUID(), tenantId, shipmentId,
                    invoice.legalEntityId(), invoice.periodEnd(), CostPath.PARTNER,
                    CostBasis.INVOICED, line.amountMinor(), invoice.currency(),
                    "partner_delivery_invoice_line", line.id(), null, invoice.providerCode(),
                    clock.instant(), null, actor.subject()));
        }

        costs.markInvoiceMatched(tenantId, invoiceId);

        audit.record(AuditFact.of("partner.invoice.matched", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("partner_delivery_invoice", invoiceId)
                .because(reason)
                .changed(Map.of("matchedLines", matched,
                        "varianceLines", variances.size(),
                        "unmatchedLines", unmatched.size()))
                .usingCapability("partner.invoice.manage")
                .correlatedBy("partner-invoice")
                .occurredAt(clock.instant())
                .build());

        return new MatchReport(matched, List.copyOf(variances), List.copyOf(unmatched));
    }

    /** What the booking said this partner delivery would cost, if anything did. */
    private Optional<Long> accruedPartnerAmount(UUID tenantId, UUID shipmentId) {
        return costs.linesOfShipment(tenantId, shipmentId).stream()
                .filter(line -> line.costPath() == CostPath.PARTNER)
                .filter(line -> line.costBasis() == CostBasis.ACCRUED)
                .map(CostLineRow::amountMinor)
                .reduce(Long::sum);
    }

    public record ImportInvoice(UUID tenantId, String providerCode, String providerInvoiceRef,
            UUID legalEntityId, LocalDate periodStart, LocalDate periodEnd, long totalMinor,
            String currency, List<ImportedLine> lines, ActorRef actor, String reason) { }

    public record ImportedLine(String providerShipmentRef, long amountMinor,
            PartnerChargeType chargeType) { }

    public record MatchReport(int matchedLines, List<UUID> varianceLineIds,
            List<UUID> unmatchedLineIds) { }
}
