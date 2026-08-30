package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Writing to the one append-only ledger per courier (ADR 0042).
 *
 * <p>Two invariants live here and nowhere else.
 *
 * <p>The settlement period is stamped when the entry is written, never derived
 * by a date query at close time. An entry written a second after a period closed
 * would otherwise be pulled into it by a date range and change a figure somebody
 * has already been paid against.
 *
 * <p>Everything arriving after close lands in the next open period as a
 * {@code PRIOR_PERIOD_ADJUSTMENT} that keeps its original occurrence instant and
 * names the entry it corrects. Closed periods are never reopened. Accountants
 * understand this and couriers find it confusing on a statement, which is a
 * stated cost rather than an oversight.
 */
@Service
public class CourierLedgerService {

    private final JdbcCourierLedgerStore ledger;
    private final JdbcCourierStore couriers;
    private final CourierPolicyResolver policies;
    private final LegalEntityResolver legalEntities;
    private final Clock clock;

    public CourierLedgerService(JdbcCourierLedgerStore ledger, JdbcCourierStore couriers,
            CourierPolicyResolver policies, LegalEntityResolver legalEntities, Clock clock) {
        this.ledger = ledger;
        this.couriers = couriers;
        this.policies = policies;
        this.legalEntities = legalEntities;
        this.clock = clock;
    }

    /**
     * The courier's open period, opened if there is none.
     *
     * <p>A courier who worked once and never again keeps an open period holding
     * one entry, which is correct: the tenant owes it, and a period nobody
     * opened would leave the entry with nowhere to be stamped.
     */
    @Transactional
    public PeriodRow currentPeriod(UUID tenantId, UUID courierId, String currency,
            LocalDate businessDate) {

        Optional<PeriodRow> open = ledger.findOpenPeriod(tenantId, courierId);
        if (open.isPresent()) {
            return open.get();
        }

        EngagementRow engagement = couriers.findLiveEngagement(tenantId, courierId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                        "This courier has no live engagement, so nothing can be owed against one"));

        CourierCompensationPolicy policy = policies.resolve(ResourceScope.tenant(tenantId));
        UUID periodId = UUID.randomUUID();

        // Periods never overlap. A prior-period adjustment keeps the original
        // occurrence instant, so the business date that triggers this can be
        // inside a period that has already closed; the new one starts the day
        // after the last one ended.
        LocalDate previousEnd = ledger.latestPeriodEnd(tenantId, courierId).orElse(null);
        LocalDate start = previousEnd != null && !businessDate.isAfter(previousEnd)
                ? previousEnd.plusDays(1)
                : businessDate;
        LocalDate end = start.plusDays(policy.settlementPeriodDays() - 1L);

        ledger.insertPeriod(new PeriodRow(periodId, tenantId, courierId, engagement.id(),
                start, end, uz.horecaos.platform.courier.domain.SettlementPeriodStatus.OPEN, currency,
                0, 0, 0, 0, 0, 0, 0, 0, 0, false, null, null, null, null, 1));

        return ledger.findPeriod(tenantId, periodId).orElseThrow();
    }

    /**
     * Appends an entry into the courier's open period.
     *
     * @return the entry, whether this call wrote it or a previous call with the
     *         same idempotency key did. A caller retrying must not be able to
     *         tell the difference, and must not pay twice to find out
     */
    @Transactional
    public LedgerEntryRow append(NewEntry entry) {
        if (!entry.entryType().accepts(entry.amountMinor())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s cannot carry %d: the sign is fixed per entry type"
                            .formatted(entry.entryType(), entry.amountMinor()));
        }
        if (entry.entryType() == LedgerEntryType.PENALTY
                && entry.origin() == AdjustmentOrigin.MANUAL
                && entry.approvalRequestId() == null) {
            // Also a database constraint. Refused here as well so the caller
            // gets ADR 0031's vocabulary rather than a constraint violation.
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "A manual penalty requires ADR 0027 approval before it is written");
        }

        LocalDate businessDate = LocalDate.ofInstant(entry.occurredAt(), ZoneOffset.UTC);
        PeriodRow period = currentPeriod(entry.tenantId(), entry.courierId(), entry.currency(),
                businessDate);

        UUID legalEntityId = entry.locationId() == null ? null
                : legalEntities.resolve(entry.tenantId(), entry.locationId(), businessDate)
                        .orElse(null);

        UUID id = UUID.randomUUID();
        boolean written = ledger.append(new LedgerEntryRow(id, entry.tenantId(), entry.courierId(),
                period.id(), legalEntityId, entry.entryType(), entry.amountMinor(),
                entry.currency(), entry.sourceType(), entry.sourceId(), entry.origin(),
                entry.reasonCode(), entry.occurredAt(), clock.instant(), entry.idempotencyKey(),
                entry.approvalRequestId(), entry.adjustsEntryId(), entry.createdBy()));

        if (written) {
            return ledger.findEntry(entry.tenantId(), id).orElseThrow();
        }
        return ledger.entriesOf(entry.tenantId(), period.id()).stream()
                .filter(existing -> existing.idempotencyKey().equals(entry.idempotencyKey()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "This idempotency key already wrote an entry into another period"));
    }

    /**
     * Corrects an entry that landed in a period which has since closed.
     *
     * <p>The adjustment keeps the original {@code occurred_at}, so a report over
     * business dates still attributes the work to the day it happened while the
     * money moves in the period that can still accept it.
     */
    @Transactional
    public LedgerEntryRow appendPriorPeriodAdjustment(UUID tenantId, UUID originalEntryId,
            long amountMinor, String reasonCode, String createdBy, String idempotencyKey) {

        LedgerEntryRow original = ledger.findEntry(tenantId, originalEntryId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such ledger entry: " + originalEntryId));

        return append(new NewEntry(tenantId, original.courierId(), null,
                LedgerEntryType.PRIOR_PERIOD_ADJUSTMENT, amountMinor, original.currency(),
                "courier_ledger_entry", originalEntryId, AdjustmentOrigin.SYSTEM, reasonCode,
                original.occurredAt(), idempotencyKey, null, originalEntryId, createdBy));
    }

    public List<LedgerEntryRow> entriesOfPeriod(UUID tenantId, UUID periodId) {
        return ledger.entriesOf(tenantId, periodId);
    }

    public long balance(UUID tenantId, UUID courierId) {
        return ledger.balanceMinor(tenantId, courierId);
    }

    /**
     * @param locationId  used to resolve the ADR 0038 legal entity; null where the
     *                    entry belongs to no branch, such as a payout
     * @param occurredAt  when the fact happened, which a prior-period adjustment
     *                    keeps from the entry it corrects
     */
    public record NewEntry(UUID tenantId, UUID courierId, UUID locationId,
            LedgerEntryType entryType, long amountMinor, String currency, String sourceType,
            UUID sourceId, AdjustmentOrigin origin, String reasonCode, Instant occurredAt,
            String idempotencyKey, UUID approvalRequestId, UUID adjustsEntryId, String createdBy) { }
}
