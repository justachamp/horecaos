package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsagePeriod;
import uz.horecaos.platform.commercial.domain.EntitlementOverride;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.PlanVersion;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.domain.TenantModule;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Closing a tenant's month with a statement (ADR 0088).
 *
 * <p>A statement is computed from the terms and the ledger alone: the plan
 * version in force, the modules live during the month, and the metered usage
 * beyond what the plan includes. Nothing is prorated, as nothing in ADR 0021
 * is. A draft is computed every time it is asked for; issuing freezes it with
 * a number, and a mistake is corrected by voiding and issuing again.
 *
 * <p>It records what is owed, before tax. How it is paid, and the tax on it,
 * stay outside this module.
 */
@Service
public class StatementService {

    private final JdbcSubscriptionStore subscriptions;
    private final JdbcPlanStore plans;
    private final JdbcModuleStore modules;
    private final JdbcStatementStore statements;
    private final JdbcUsageStore usage;
    private final AuditRecorder audit;
    private final Clock clock;

    public StatementService(
            JdbcSubscriptionStore subscriptions,
            JdbcPlanStore plans,
            JdbcModuleStore modules,
            JdbcStatementStore statements,
            JdbcUsageStore usage,
            AuditRecorder audit,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.modules = modules;
        this.statements = statements;
        this.usage = usage;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Statement> list(UUID tenantId) {
        return statements.list(tenantId);
    }

    public Statement find(UUID tenantId, UUID statementId) {
        return statements
                .find(tenantId, statementId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such statement"));
    }

    /**
     * What the month would be billed if it were issued now.
     *
     * <p>Allowed for the month in progress, so an account manager can see where
     * it is heading; only a month that has ended can be issued.
     */
    @Transactional(readOnly = true)
    public Statement draft(UUID tenantId, String periodKey) {
        YearMonth month = parse(periodKey);
        ZoneId zone = subscriptions.timezone(tenantId);
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        if (!start.isBefore(clock.instant())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Month %s has not started".formatted(periodKey));
        }

        List<StatementLine> lines = new ArrayList<>();
        String currency = null;
        UUID subscriptionId = null;

        Optional<Subscription> inForce = subscriptions.history(tenantId).stream()
                .filter(subscription -> subscription.status() != SubscriptionStatus.DRAFT)
                .filter(subscription -> overlaps(subscription, start, end))
                .max(Comparator.comparing(Subscription::startAt));

        if (inForce.isPresent()) {
            Subscription subscription = inForce.get();
            subscriptionId = subscription.id();
            PlanVersion version = plans.findVersion(subscription.planVersionId())
                    .orElseThrow(() -> new IllegalStateException("A subscription names a missing plan version"));
            currency = version.currency();
            String reference = version.planCode() + "@v" + version.versionNumber();

            if (chargedIn(version, subscription, month, zone)) {
                Instant trialEnd = subscription.trialEndAt();
                boolean wholeMonthInTrial = trialEnd != null && !trialEnd.isBefore(end);
                lines.add(StatementLine.of(
                        lines.size() + 1,
                        StatementLine.PLAN,
                        reference,
                        version.planCode() + " v" + version.versionNumber() + ", " + version.billingPeriod()
                                + (wholeMonthInTrial ? ", trial" : ""),
                        wholeMonthInTrial ? 0 : 1,
                        version.priceMinor()));
            }
            addOverage(lines, tenantId, version, periodKey, start, end);
        }

        for (TenantModule held : modules.overlapping(tenantId, start, end)) {
            SellableModule module = modules.find(held.moduleId())
                    .orElseThrow(() -> new IllegalStateException("A tenant module names a missing module"));
            Long quantity = quantityOf(module, held, tenantId, start, end);
            if (quantity == null) {
                continue;
            }
            if (currency == null) {
                currency = module.currency();
            } else if (!currency.equals(module.currency())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Module %s is priced in %s and the plan in %s; one statement has one currency"
                                .formatted(module.code(), module.currency(), currency),
                        Map.of("moduleCode", module.code()));
            }
            lines.add(StatementLine.of(
                    lines.size() + 1,
                    StatementLine.MODULE,
                    module.code(),
                    module.name() + ", " + module.billingUnit().name(),
                    quantity,
                    module.unitPriceMinor()));
        }

        long total = 0;
        for (StatementLine line : lines) {
            total = Math.addExact(total, line.amountMinor());
        }
        return new Statement(
                null,
                tenantId,
                null,
                periodKey,
                start,
                end,
                currency,
                total,
                subscriptionId,
                Statement.DRAFT,
                null,
                null,
                null,
                null,
                null,
                null,
                lines);
    }

    @Transactional
    public IssuedRef issue(UUID tenantId, String periodKey, ActorRef actor, String reason, String correlationId) {
        Statement draft = draft(tenantId, periodKey);
        Instant now = clock.instant();
        if (draft.periodEnd().isAfter(now)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Month %s has not ended; it is issued once it has".formatted(periodKey));
        }
        String currency = draft.currency();
        if (draft.lines().isEmpty() || currency == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Nothing is billable for %s".formatted(periodKey));
        }

        UUID id = Ids.newId();
        String number;
        try {
            number = statements.insertIssued(id, draft, currency, subject(actor), reason, now);
        } catch (DuplicateKeyException already) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "%s already has a statement; void it before issuing another".formatted(periodKey),
                    Map.of("periodKey", periodKey));
        }

        Map<String, Object> change = new HashMap<>();
        change.put("number", number);
        change.put("periodKey", periodKey);
        change.put("currency", currency);
        change.put("totalMinor", draft.totalMinor());
        change.put("lineCount", draft.lines().size());
        audit.record(AuditFact.of("commercial.statement.issued", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.statement", id)
                .because(reason)
                .changed(change)
                .usingCapability(Capability.COMMERCIAL_STATEMENT_ISSUE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new IssuedRef(id, number);
    }

    @Transactional
    public void voidStatement(UUID tenantId, UUID statementId, ActorRef actor, String reason, String correlationId) {
        Statement statement = find(tenantId, statementId);
        Instant now = clock.instant();
        if (!statements.voidStatement(tenantId, statementId, subject(actor), reason, now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Statement %s is already void".formatted(statement.number()));
        }
        audit.record(AuditFact.of("commercial.statement.voided", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.statement", statementId)
                .because(reason)
                .changed(Map.of("number", String.valueOf(statement.number()), "periodKey", statement.periodKey()))
                .usingCapability(Capability.COMMERCIAL_STATEMENT_ISSUE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /** The id and number an issued statement was filed under. */
    public record IssuedRef(UUID statementId, String number) {}

    // -------------------------------------------------------------- lines

    /**
     * Usage beyond what the plan includes, for every key the plan prices overage on.
     *
     * <p>The allowance is the plan's, or an override's where one was live when
     * the month closed; a monthly key is read for the month, a billing-period
     * key for every period that started inside it.
     */
    private void addOverage(
            List<StatementLine> lines,
            UUID tenantId,
            PlanVersion version,
            String monthKey,
            Instant start,
            Instant end) {
        Map<String, EntitlementOverride> overrides = subscriptions.overrides(tenantId);
        Instant lastMoment = end.minusMillis(1);
        List<PlanEntitlement> priced = plans.entitlementsOf(version.id()).values().stream()
                .filter(line -> line.overageUnitPriceMinor() != null && line.integerValue() != null)
                .filter(line ->
                        line.resetPeriod() == ResetPeriod.MONTHLY || line.resetPeriod() == ResetPeriod.BILLING_PERIOD)
                .sorted(Comparator.comparing(PlanEntitlement::entitlementKey))
                .toList();

        for (PlanEntitlement line : priced) {
            String key = line.entitlementKey();
            if (EntitlementKeys.find(key).isEmpty()) {
                continue;
            }
            long included = included(line, overrides.get(key), lastMoment);
            long price = java.util.Objects.requireNonNull(line.overageUnitPriceMinor());
            List<String> periods = line.resetPeriod() == ResetPeriod.MONTHLY
                    ? List.of(monthKey)
                    : statements.periodKeysWithin(tenantId, key, monthKey);
            for (String periodKey : periods) {
                long consumed = usage.recompute(tenantId, key, new UsagePeriod(periodKey, start, end))
                        .consumed();
                long over = consumed - included;
                if (over > 0) {
                    lines.add(StatementLine.of(
                            lines.size() + 1,
                            StatementLine.OVERAGE,
                            key,
                            "%s %s: %d used, %d included".formatted(key, periodKey, consumed, included),
                            over,
                            price));
                }
            }
        }
    }

    private static long included(PlanEntitlement line, @Nullable EntitlementOverride override, Instant at) {
        if (override != null && override.isLiveAt(at) && override.integerValue() != null) {
            return override.integerValue();
        }
        return java.util.Objects.requireNonNull(line.integerValue());
    }

    /**
     * How many of a module's unit the month bills, or null when it bills none.
     *
     * <p>Brands and branches are read from the usage ledger as they stood when
     * the month closed, so issuing the same month twice bills the same number.
     */
    private @Nullable Long quantityOf(
            SellableModule module, TenantModule held, UUID tenantId, Instant start, Instant end) {
        return switch (module.billingUnit()) {
            case PER_TENANT -> 1L;
            case PER_BRAND -> statements.standingCountAt(tenantId, EntitlementKeys.BRANDS_MAX_COUNT.code(), end);
            case PER_LOCATION -> statements.standingCountAt(tenantId, EntitlementKeys.LOCATIONS_MAX_COUNT.code(), end);
            case PER_UNIT -> held.quantity() == null ? 0L : held.quantity().longValue();
            case ONE_OFF ->
                !held.startedAt().isBefore(start) && held.startedAt().isBefore(end) ? 1L : null;
        };
    }

    /**
     * Whether the plan's price falls in this month.
     *
     * <p>Monthly plans every month; quarterly and yearly ones in the months a
     * billing period starts, counted from the subscription's start.
     */
    static boolean chargedIn(PlanVersion version, Subscription subscription, YearMonth month, ZoneId zone) {
        int months = version.billingMonths();
        if (months == 0) {
            return false;
        }
        YearMonth first = YearMonth.from(subscription.startAt().atZone(zone));
        long elapsed = first.until(month, ChronoUnit.MONTHS);
        return elapsed >= 0 && elapsed % months == 0;
    }

    private static boolean overlaps(Subscription subscription, Instant start, Instant end) {
        Instant ended = subscription.endedAt();
        return subscription.startAt().isBefore(end) && (ended == null || ended.isAfter(start));
    }

    private static YearMonth parse(String periodKey) {
        try {
            if (periodKey.length() != 7) {
                throw new DateTimeParseException("not yyyy-MM", periodKey, 0);
            }
            return YearMonth.parse(periodKey);
        } catch (DateTimeParseException invalid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A statement covers a month, written yyyy-MM",
                    Map.of("periodKey", periodKey));
        }
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }
}
