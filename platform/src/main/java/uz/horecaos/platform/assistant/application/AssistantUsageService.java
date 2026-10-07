package uz.horecaos.platform.assistant.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.assistant.api.AssistantModelPort;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcKnowledgeStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore.UsageRow;

/**
 * What the assistant has cost a tenant this month and what stands between it and
 * the ceiling (ADR 0069: "spend is capped and visible").
 *
 * <p>A read of the ledger and nothing else: the same rows the ceiling is decided
 * from, so the figure an operator reads is the figure that will stop the
 * assistant, not a second number that could disagree with it.
 */
@Service
public class AssistantUsageService {

    private final JdbcTurnStore turns;
    private final JdbcKnowledgeStore knowledge;
    private final AssistantSettings settings;
    private final ObjectProvider<AssistantModelPort> model;
    private final Clock clock;

    AssistantUsageService(
            JdbcTurnStore turns,
            JdbcKnowledgeStore knowledge,
            AssistantSettings settings,
            ObjectProvider<AssistantModelPort> model,
            Clock clock) {
        this.turns = turns;
        this.knowledge = knowledge;
        this.settings = settings;
        this.model = model;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report report(UUID tenantId) {
        Instant now = clock.instant();
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until =
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        UsageRow usage = turns.usage(tenantId, from, until);
        long ceilingCents = settings.monthlySpendCeilingUsdCents(tenantId);
        AssistantModelPort port = model.getIfAvailable();
        return new Report(
                month.toString(),
                LocalDate.ofInstant(from, ZoneOffset.UTC),
                usage.turns(),
                usage.answered(),
                usage.refused(),
                usage.escalated(),
                usage.declined(),
                usage.cached(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.costUsdMicros(),
                ceilingCents,
                usage.costUsdMicros() >= Math.multiplyExact(ceilingCents, 10_000L),
                settings.entitled(tenantId),
                port != null && port.configured(),
                knowledge.countPublished(tenantId));
    }

    /**
     * @param month the calendar month (UTC) the figures are for, e.g. {@code 2026-10}
     * @param costUsdMicros millionths of a US dollar; one cent is 10 000
     * @param ceilingUsdCents what the platform will pay for this tenant this month
     * @param ceilingReached whether the assistant is currently refusing for that reason
     * @param entitled whether the plan includes the assistant and the conversations it speaks in
     * @param providerConfigured whether the platform has a model provider to call at all
     */
    public record Report(
            String month,
            LocalDate monthStart,
            long turns,
            long answered,
            long refused,
            long escalated,
            long declined,
            long servedFromCache,
            long inputTokens,
            long outputTokens,
            long costUsdMicros,
            long ceilingUsdCents,
            boolean ceilingReached,
            boolean entitled,
            boolean providerConfigured,
            long publishedKnowledgeEntries) {}
}
