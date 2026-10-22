package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * «Who would this rule have hit last week, and for how much» (couriers.md §11, ADR 0108, ADR 0178).
 *
 * <p>Replays {@link AdjustmentRuleEvaluator}'s own arithmetic -- the same {@link
 * AdjustmentRuleEvaluator#shiftValue} and {@link AdjustmentRuleEvaluator#periodValue} the evaluator posts from,
 * not a copy -- over shifts that closed and settlement periods that ended in the last few days, and writes
 * nothing: no ledger entry, no approval, no audit fact about a courier, no state. A penalty rule shipped without
 * this preview is a labour dispute waiting for a payday.
 *
 * <p>What it cannot tell you is stated rather than implied. A {@code GEO_UNVERIFIED_RATE} rule at a location
 * that never asked for the position check hits everybody, because nothing was verified, and the simulator shows
 * exactly that. A period that is still open is replayed as it stands today and flagged, because it may yet
 * change. Settlement periods carry no location, so a location filter narrows shifts only.
 */
@Service
public class CourierRuleSimulator {

    /** The lookback the console offers, in days. */
    public static final int MAX_LOOKBACK_DAYS = 31;

    /** Rows kept; more than this and the answer says it was cut. */
    static final int MAX_HITS = 500;

    private static final int MAX_WINDOWS = 5_000;

    private static final Set<String> COMPARATORS = Set.of("GTE", "LTE");
    private static final Set<String> WINDOWS = Set.of("SHIFT", "SETTLEMENT_PERIOD");

    private final JdbcCourierLedgerStore ledger;
    private final JdbcCourierShiftStore shifts;
    private final JdbcCourierStore couriers;
    private final Clock clock;

    public CourierRuleSimulator(
            JdbcCourierLedgerStore ledger, JdbcCourierShiftStore shifts, JdbcCourierStore couriers, Clock clock) {
        this.ledger = ledger;
        this.shifts = shifts;
        this.couriers = couriers;
        this.clock = clock;
    }

    /** A rule as it would be authored: the same six fields the registry stores. */
    public record Candidate(
            String outcomeBasis, String comparator, long threshold, String window, long amountMinor, String currency) {}

    /**
     * @throws ApiException {@code VALIDATION_FAILED} for a comparator, window or lookback this does not know, or a
     *     {@code CASH_VARIANCE} rule on a shift window (it is read at period close only); {@code UNPROCESSABLE_STATE}
     *     for a basis nothing reads
     */
    @Transactional(readOnly = true)
    public Simulation simulate(UUID tenantId, Candidate candidate, int lookbackDays, @Nullable UUID locationId) {
        validate(candidate, lookbackDays);
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(lookbackDays));
        List<RawHit> raw = new ArrayList<>();
        int replayed;
        boolean cut = false;

        if ("SHIFT".equals(candidate.window())) {
            List<ShiftRow> closed = shifts.closedSince(tenantId, since, locationId, MAX_WINDOWS);
            replayed = closed.size();
            for (ShiftRow shift : closed) {
                Optional<Long> value = AdjustmentRuleEvaluator.shiftValue(
                        candidate.outcomeBasis(), ledger.shiftDeliveryMetrics(tenantId, shift.id()));
                if (value.isPresent()
                        && AdjustmentRuleEvaluator.holds(candidate.comparator(), candidate.threshold(), value.get())) {
                    raw.add(new RawHit(shift.courierId(), shift.id(), shift.closedAt(), value.get(), "CLOSED", false));
                }
            }
        } else {
            LocalDate sinceDate = LocalDate.ofInstant(since, ZoneOffset.UTC);
            List<PeriodRow> periods = ledger.periodsEndingSince(tenantId, sinceDate, MAX_WINDOWS);
            replayed = periods.size();
            for (PeriodRow period : periods) {
                long cashVariance = ledger.entriesOf(tenantId, period.id()).stream()
                        .filter(entry -> entry.entryType() == LedgerEntryType.CASH_VARIANCE)
                        .count();
                Optional<Long> value = AdjustmentRuleEvaluator.periodValue(
                        candidate.outcomeBasis(), ledger.earningsOf(tenantId, period.id()), cashVariance);
                if (value.isPresent()
                        && AdjustmentRuleEvaluator.holds(candidate.comparator(), candidate.threshold(), value.get())) {
                    raw.add(new RawHit(
                            period.courierId(),
                            period.id(),
                            period.periodEnd().atStartOfDay().toInstant(ZoneOffset.UTC),
                            value.get(),
                            period.status().name(),
                            "OPEN".equals(period.status().name())));
                }
            }
        }
        if (raw.size() > MAX_HITS) {
            raw = new ArrayList<>(raw.subList(0, MAX_HITS));
            cut = true;
        }

        Map<UUID, String> references = couriers.displayReferencesOf(
                tenantId, raw.stream().map(RawHit::courierId).collect(Collectors.toSet()));
        List<Hit> hits = raw.stream()
                .map(hit -> new Hit(
                        hit.courierId(),
                        references.get(hit.courierId()),
                        hit.windowId(),
                        hit.windowEnd(),
                        hit.value(),
                        candidate.amountMinor(),
                        candidate.currency(),
                        hit.windowStatus(),
                        hit.stillOpen()))
                .toList();
        long total = hits.stream().mapToLong(Hit::amountMinor).sum();
        long distinct = hits.stream().map(Hit::courierId).distinct().count();
        return new Simulation(
                lookbackDays,
                candidate.window(),
                replayed,
                (int) distinct,
                total,
                candidate.currency(),
                cut,
                hits.stream().anyMatch(Hit::windowStillOpen),
                hits);
    }

    private static void validate(Candidate candidate, int lookbackDays) {
        if (!AdjustmentRuleEvaluator.hasReader(candidate.outcomeBasis())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "%s has no reader, so a rule on it would never fire and there is nothing to replay"
                            .formatted(candidate.outcomeBasis()),
                    Map.of("reason", "BASIS_HAS_NO_EVALUATOR_READER", "outcomeBasis", candidate.outcomeBasis()));
        }
        if (!COMPARATORS.contains(candidate.comparator())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The comparator is GTE or LTE");
        }
        if (!WINDOWS.contains(candidate.window())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The window is SHIFT or SETTLEMENT_PERIOD");
        }
        if ("SHIFT".equals(candidate.window()) && "CASH_VARIANCE".equals(candidate.outcomeBasis())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "CASH_VARIANCE is read at settlement period close only",
                    Map.of("field", "window"));
        }
        if (lookbackDays < 1 || lookbackDays > MAX_LOOKBACK_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Look back between 1 and %d days".formatted(MAX_LOOKBACK_DAYS),
                    Map.of("field", "lookbackDays"));
        }
    }

    private record RawHit(
            UUID courierId,
            UUID windowId,
            @Nullable Instant windowEnd,
            long value,
            String windowStatus,
            boolean stillOpen) {}

    /**
     * One window the rule would have fired on.
     *
     * @param courierReference the courier's display reference, never a name (ADR 0029)
     * @param value            what the basis read: a count, or basis points for a rate
     * @param amountMinor      what the rule would have posted, negative for a penalty
     * @param windowStillOpen  an unsettled period replayed as it stands today, which may yet change
     */
    public record Hit(
            UUID courierId,
            @Nullable String courierReference,
            UUID windowId,
            @Nullable Instant windowEnd,
            long value,
            long amountMinor,
            String currency,
            String windowStatus,
            boolean windowStillOpen) {}

    /**
     * @param windowsReplayed how many closed shifts or settlement periods the rule was held against, so «nobody»
     *     can be told apart from «nothing was there to look at»
     * @param truncated       more windows hit than are listed
     */
    public record Simulation(
            int lookbackDays,
            String window,
            int windowsReplayed,
            int couriersHit,
            long totalAmountMinor,
            String currency,
            boolean truncated,
            boolean includesOpenPeriods,
            List<Hit> hits) {}
}
