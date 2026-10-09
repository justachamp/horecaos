package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.domain.ContactPeriod;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcContactPolicyStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcContactPolicyStore.OverrideRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The contact policy as an explicit decision with a written reason (ADR 0112).
 *
 * <p>ADR 0044 gave every brand quiet hours and a frequency cap and made a tenant's
 * override of them tighten-only. This class keeps both halves and adds the one thing
 * that was missing: a tenant may be stricter for one channel, one campaign purpose and
 * one period, and every time the policy says no it says why, in a sentence a marketer
 * can read, so the answer to "why did this guest not get step 2" is a row and not a
 * guess.
 *
 * <p><strong>Tighten-only is written four times and agrees with itself.</strong> Here, in
 * {@link EngagementPolicy}'s own rule it reuses, in the table's CHECK constraints, and in
 * the tests that try to loosen. The numbers protect a sending reputation shared across
 * tenants, so "it is their customer relationship" is not an argument for letting one
 * tenant spend it.
 *
 * <p>Quiet hours do not refuse: a message that becomes due inside the closed window is
 * held to the next open boundary and sent then, exactly as a broadcast's is, so the
 * decision is "allowed, at 10:00" and not "blocked". A cap does refuse, and the refusal is
 * a deferral to the next slot in which the cap would admit one more.
 */
@Service
public class ContactPolicyService {

    private final JdbcContactPolicyStore overrides;
    private final JdbcEngagementStore engagement;
    private final AuditRecorder audit;
    private final Clock clock;

    public ContactPolicyService(
            JdbcContactPolicyStore overrides, JdbcEngagementStore engagement, AuditRecorder audit, Clock clock) {
        this.overrides = overrides;
        this.engagement = engagement;
        this.audit = audit;
        this.clock = clock;
    }

    /** What a tenant asks to change. A null field leaves nothing to say about it. */
    public record OverrideRequest(
            String channel,
            String campaignPurpose,
            String period,
            @Nullable Integer capCount,
            @Nullable LocalTime quietHoursStart,
            @Nullable LocalTime quietHoursEnd,
            String statedReason) {}

    /**
     * Sets or replaces the override at one (channel, purpose, period).
     *
     * @param expectedVersion null to create, the version the caller read to replace
     */
    @Transactional
    public OverrideRow set(
            UUID tenantId,
            UUID brandId,
            OverrideRequest request,
            @Nullable Integer expectedVersion,
            ActorRef actor,
            UUID actorId,
            String correlationId) {
        MarketingChannel channel = channel(request.channel());
        ContactPeriod period = period(request.period());
        validateTightenOnly(period, request);

        Instant now = clock.instant();
        OverrideRow before = overrides
                .find(tenantId, brandId, channel.name(), request.campaignPurpose(), period.name())
                .orElse(null);
        if (before == null && expectedVersion != null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "There is no such override to replace");
        }
        if (before != null && expectedVersion == null) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An override already exists for this channel, purpose and period; replace it with its version");
        }

        OverrideRow row = new OverrideRow(
                tenantId,
                brandId,
                channel.name(),
                request.campaignPurpose(),
                period.name(),
                request.capCount(),
                request.quietHoursStart(),
                request.quietHoursEnd(),
                request.statedReason(),
                actorId,
                0,
                now);
        int version = overrides
                .upsert(row, expectedVersion, now)
                .orElseThrow(() -> ApiException.staleVersion(
                        expectedVersion == null ? 0 : expectedVersion, before == null ? 0 : before.version()));

        OverrideRow after = overrides
                .find(tenantId, brandId, channel.name(), request.campaignPurpose(), period.name())
                .orElseThrow();
        Map<String, Object> change = ChangeDocuments.created(snapshot(after));
        if (before != null) {
            change = ChangeDocuments.diff(snapshot(before), snapshot(after));
        }
        audit.record(AuditFact.of("MARKETING_CONTACT_POLICY_SET", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingContactPolicyOverride", overrideId(after))
                .targetVersion((long) version)
                .because(request.statedReason())
                .changed(change)
                .usingCapability("marketing.contact_policy.manage")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return after;
    }

    @Transactional
    public void remove(
            UUID tenantId,
            UUID brandId,
            String channel,
            String campaignPurpose,
            String period,
            ActorRef actor,
            String reason,
            String correlationId) {
        OverrideRow before = overrides
                .find(
                        tenantId,
                        brandId,
                        channel(channel).name(),
                        campaignPurpose,
                        period(period).name())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "There is no such override"));
        overrides.delete(tenantId, brandId, before.channel(), before.campaignPurpose(), before.periodKind());
        audit.record(AuditFact.of("MARKETING_CONTACT_POLICY_REMOVED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingContactPolicyOverride", overrideId(before))
                .because(reason)
                .changed(ChangeDocuments.diff(snapshot(before), Map.of("removed", true)))
                .usingCapability("marketing.contact_policy.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional(readOnly = true)
    public List<OverrideRow> list(UUID tenantId, UUID brandId) {
        return overrides.listByBrand(tenantId, brandId);
    }

    /** What a decision is asked about. */
    public record ContactRequest(
            UUID tenantId, UUID brandId, UUID customerAccountId, MarketingChannel channel, String campaignPurpose) {}

    /**
     * The answer, and always a reason when it is no.
     *
     * @param allowed whether a message may be sent
     * @param deliverAt when it should leave: now, or the next open boundary when the moment
     *                  falls inside quiet hours. Meaningful only when allowed
     * @param reason a {@link RefusalReason} when not allowed
     * @param reasonText a sentence naming the rule, the numbers and the period; free of any
     *                   contact value, so it can sit on a decision row and in a report
     * @param deferUntil when to ask again, so a refusal is a deferral and never a drop
     */
    public record ContactDecision(
            boolean allowed,
            @Nullable Instant deliverAt,
            @Nullable RefusalReason reason,
            @Nullable String reasonText,
            @Nullable Instant deferUntil) {

        public static ContactDecision allowAt(Instant deliverAt, @Nullable String note) {
            return new ContactDecision(true, deliverAt, null, note, null);
        }

        public static ContactDecision refuse(RefusalReason reason, String text, Instant deferUntil) {
            return new ContactDecision(false, null, reason, text, deferUntil);
        }
    }

    /**
     * Whether this guest may be messaged on this channel for this purpose now, under the
     * brand's policy and every override that bears on the send.
     *
     * <p>Does not re-ask consent, suppression or the platform cap: {@code
     * MarketingEligibility} owns those and the caller asks it first. This is the part that
     * is the tenant's own: their quiet hours and their caps.
     */
    @Transactional(readOnly = true)
    public ContactDecision decide(ContactRequest request, Instant now) {
        EngagementPolicy base = engagement.resolvePolicy(request.tenantId(), request.brandId());
        List<OverrideRow> applicable = overrides.applicable(
                request.tenantId(), request.brandId(), request.channel().name(), request.campaignPurpose());

        for (OverrideRow override : applicable) {
            if (override.capCount() == null) {
                continue;
            }
            ContactPeriod period = ContactPeriod.valueOf(override.periodKind());
            Instant since = windowStart(period, now, base);
            int sent = engagement.sendsWithinOnChannel(
                    request.tenantId(),
                    request.brandId(),
                    request.customerAccountId(),
                    request.channel().name(),
                    since);
            if (sent >= override.capCount()) {
                return ContactDecision.refuse(
                        RefusalReason.FREQUENCY_CAP_REACHED,
                        ("Contact policy for %s messages about %s: %d already sent in the %s window, and this "
                                        + "brand allows %d")
                                .formatted(
                                        request.channel(),
                                        request.campaignPurpose(),
                                        sent,
                                        period.name(),
                                        override.capCount()),
                        nextSlot(period, now, base));
            }
        }

        List<EngagementPolicy> windows = quietWindows(base, applicable);
        EngagementPolicy holding = holdingWindow(windows, now);
        if (holding != null) {
            Instant open = openAt(windows, now);
            return ContactDecision.allowAt(
                    open,
                    "Held to %s: quiet hours %s to %s (%s) apply to this channel"
                            .formatted(open, holding.quietHoursStart(), holding.quietHoursEnd(), holding.timezone()));
        }
        return ContactDecision.allowAt(now, null);
    }

    /**
     * Every closed window that bears on a send: the brand's own first, then one for each
     * override that states quiet hours.
     *
     * <p><strong>The allowed time is the intersection of the windows' allowed times.</strong>
     * A send may leave only when no window holds it, so an override can add closed time and
     * can never remove any. The previous reading folded the overrides into one window by
     * taking the earliest start and the latest end, which is the union of two windows only
     * while both wrap midnight: an override of 05:00 to 11:00 satisfies "starts no later than
     * 21:00 and ends no earlier than 10:00" and does not wrap, and folding it in left the
     * window 05:00 to 11:00 and opened the whole evening. Asking each window on its own
     * has no such case, whatever a row says.
     */
    static List<EngagementPolicy> quietWindows(EngagementPolicy base, List<OverrideRow> applicable) {
        List<EngagementPolicy> windows = new ArrayList<>();
        windows.add(base);
        for (OverrideRow override : applicable) {
            if (override.quietHoursStart() == null || override.quietHoursEnd() == null) {
                continue;
            }
            windows.add(new EngagementPolicy(
                    override.quietHoursStart(),
                    override.quietHoursEnd(),
                    base.timezone(),
                    base.messagesPer7Days(),
                    base.messagesPer30Days(),
                    base.smsPricePerSegmentMinor(),
                    base.currency()));
        }
        return windows;
    }

    /** The window that holds {@code moment} for longest, or null when none does. */
    static @Nullable EngagementPolicy holdingWindow(List<EngagementPolicy> windows, Instant moment) {
        EngagementPolicy holding = null;
        for (EngagementPolicy window : windows) {
            if (window.isQuiet(moment)
                    && (holding == null || window.nextOpenBoundary(moment).isAfter(holding.nextOpenBoundary(moment)))) {
                holding = window;
            }
        }
        return holding;
    }

    /**
     * The first instant at or after {@code moment} at which every window is open.
     *
     * <p>Leaving one window can land inside another, so the answer is walked forward until
     * a moment is open to all. Each step passes the end of at least one window, so a handful
     * of steps is the most that two or three windows can need; the bound is only there so a
     * window that is never open (a start equal to its end reads as always quiet) cannot spin.
     */
    static Instant openAt(List<EngagementPolicy> windows, Instant moment) {
        Instant at = moment;
        for (int step = 0; step < windows.size() * 2 + 2; step++) {
            Instant latest = at;
            boolean held = false;
            for (EngagementPolicy window : windows) {
                if (window.isQuiet(at)) {
                    held = true;
                    Instant open = window.nextOpenBoundary(at);
                    if (open.isAfter(latest)) {
                        latest = open;
                    }
                }
            }
            if (!held) {
                return at;
            }
            at = latest;
        }
        return at;
    }

    /** Where a period's count begins: a calendar day or week in the brand's zone, or a rolling window. */
    static Instant windowStart(ContactPeriod period, Instant now, EngagementPolicy policy) {
        ZonedDateTime local = now.atZone(policy.timezone());
        return switch (period) {
            case DAILY -> local.truncatedTo(ChronoUnit.DAYS).toInstant();
            case WEEKLY ->
                local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .truncatedTo(ChronoUnit.DAYS)
                        .toInstant();
            case ROLLING_7D -> now.minus(Duration.ofDays(7));
            case ROLLING_30D -> now.minus(Duration.ofDays(30));
        };
    }

    /**
     * When a refused step should be asked about again. A calendar period names its own next
     * start; a rolling one has no single answer short of reading the ledger, so it is asked
     * again in a day, which is as often as a cap per day could change.
     */
    static Instant nextSlot(ContactPeriod period, Instant now, EngagementPolicy policy) {
        ZonedDateTime local = now.atZone(policy.timezone());
        return switch (period) {
            case DAILY -> local.truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant();
            case WEEKLY ->
                local.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                        .truncatedTo(ChronoUnit.DAYS)
                        .toInstant();
            case ROLLING_7D, ROLLING_30D -> now.plus(Duration.ofDays(1));
        };
    }

    // ------------------------------------------------------------ validation

    private static void validateTightenOnly(ContactPeriod period, OverrideRequest request) {
        if (request.statedReason() == null || request.statedReason().isBlank()) {
            throw invalid("An override carries the reason it was set: a rule that silences a campaign is "
                    + "a decision somebody should be able to attribute");
        }
        if (request.capCount() == null && request.quietHoursStart() == null && request.quietHoursEnd() == null) {
            throw invalid("An override says something: a cap, a quiet window, or both");
        }
        if ((request.quietHoursStart() == null) != (request.quietHoursEnd() == null)) {
            throw invalid("A quiet window has both a start and an end");
        }
        if (request.capCount() != null) {
            if (request.capCount() < 0) {
                throw invalid("A cap cannot be negative");
            }
            if (request.capCount() > period.platformCeiling()) {
                throw invalid(("The %s cap may be tightened and never loosened: %d exceeds the platform's %d")
                        .formatted(period, request.capCount(), period.platformCeiling()));
            }
        }
        if (request.quietHoursStart() != null) {
            if (request.quietHoursStart().isAfter(EngagementPolicy.DEFAULT_QUIET_START)) {
                throw invalid(("Quiet hours may be tightened and never loosened: a start of %s is later than %s")
                        .formatted(request.quietHoursStart(), EngagementPolicy.DEFAULT_QUIET_START));
            }
            if (request.quietHoursEnd().isBefore(EngagementPolicy.DEFAULT_QUIET_END)) {
                throw invalid(("Quiet hours may be tightened and never loosened: an end of %s is earlier than %s")
                        .formatted(request.quietHoursEnd(), EngagementPolicy.DEFAULT_QUIET_END));
            }
            // The closed window is the evening and the morning, so it wraps midnight: it starts
            // after it ends. A start before its end is a window inside one day, which can
            // satisfy both bounds above (05:00 to 11:00) and still leave the evening open.
            if (!request.quietHoursStart().isAfter(request.quietHoursEnd())) {
                throw invalid(("Quiet hours may be tightened and never loosened: %s to %s does not wrap midnight, "
                                + "so it would leave open hours the platform keeps closed")
                        .formatted(request.quietHoursStart(), request.quietHoursEnd()));
            }
        }
    }

    private static MarketingChannel channel(String value) {
        try {
            return MarketingChannel.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid(value + " is not a channel a contact policy can govern");
        }
    }

    private static ContactPeriod period(String value) {
        try {
            return ContactPeriod.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid(value + " is not a contact-policy period");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    /** A stable id for the audit target: the override has no id of its own, its key is its identity. */
    private static UUID overrideId(OverrideRow row) {
        return UUID.nameUUIDFromBytes((row.tenantId() + "|" + row.brandId() + "|" + row.channel() + "|"
                        + row.campaignPurpose() + "|" + row.periodKind())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Object> snapshot(OverrideRow row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("channel", row.channel());
        state.put("campaignPurpose", row.campaignPurpose());
        state.put("period", row.periodKind());
        state.put("capCount", row.capCount() == null ? "" : row.capCount().toString());
        state.put(
                "quietHoursStart",
                row.quietHoursStart() == null ? "" : row.quietHoursStart().toString());
        state.put(
                "quietHoursEnd",
                row.quietHoursEnd() == null ? "" : row.quietHoursEnd().toString());
        return state;
    }
}
