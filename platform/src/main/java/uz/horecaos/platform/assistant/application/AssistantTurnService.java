package uz.horecaos.platform.assistant.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.assistant.api.AssistantModelPort;
import uz.horecaos.platform.assistant.api.AssistantModelRequest;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.AssistantModelUnavailableException;
import uz.horecaos.platform.assistant.api.ModelTurn;
import uz.horecaos.platform.assistant.api.PiiEgressGuard;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.assistant.api.TokenUsage;
import uz.horecaos.platform.assistant.application.TurnRecorder.Evidence;
import uz.horecaos.platform.assistant.domain.AssistantPosture;
import uz.horecaos.platform.assistant.domain.CustomerWording;
import uz.horecaos.platform.assistant.domain.EscalationTopic;
import uz.horecaos.platform.assistant.domain.GroundingVerdict;
import uz.horecaos.platform.assistant.domain.GroundingVerifier;
import uz.horecaos.platform.assistant.domain.QuestionClassification;
import uz.horecaos.platform.assistant.domain.QuestionClassifier;
import uz.horecaos.platform.assistant.domain.RefusalReason;
import uz.horecaos.platform.assistant.domain.ReplyLocale;
import uz.horecaos.platform.assistant.domain.RetrievalKind;
import uz.horecaos.platform.assistant.domain.TurnOutcome;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore.TurnRecord;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.voice.api.OperatorPresenceQueryPort;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The assistant, taking one turn in a conversation (ADR 0069).
 *
 * <p>The order of the checks below is the design. Everything that can be decided
 * without spending money is decided first, so the expensive step -- a call to a
 * processor the tenant did not choose -- is the last thing that happens, behind a
 * rate limit, a person-only topic filter, a per-conversation turn cap, the
 * tenant's spend ceiling, the plan's allowance, and a retrieval that found
 * something to ground an answer in:
 *
 * <ol>
 *   <li><strong>Gate.</strong> Entitled, switched on, and a provider configured;
 *       otherwise the assistant is not here and the conversation behaves as it did
 *       before it existed.
 *   <li><strong>Rate limit</strong> (ADR 0033), per conversation and per tenant,
 *       failing closed: inference costs money, so a limiter that cannot answer
 *       does not let the question through. A limited turn sends nothing.
 *   <li><strong>Escalation.</strong> A complaint, a refund or a request for a person
 *       is a person's -- handed over before anything is retrieved or asked.
 *   <li><strong>Turn cap and spend ceiling.</strong> Reached means refused with a
 *       stable reason, handed to a person, not silence.
 *   <li><strong>Retrieval.</strong> Nothing retrieved is a refusal. The assistant
 *       never asks a model a question it has no facts for.
 *   <li><strong>The model, then the proof.</strong> The reply is checked against
 *       the facts by {@link GroundingVerifier}; one that fails is discarded and the
 *       customer is handed to a person, never sent a fluent guess.
 * </ol>
 *
 * <p>Every outcome is a row in the ledger ({@code assistant.turns}); a turn that
 * stated something binding, or that handed over, also leaves an ADR 0027 fact.
 * Nothing in a log, a metric or an exception message contains anything a customer
 * wrote: this class logs identifiers and codes only.
 */
@Service
public class AssistantTurnService implements ConversationParticipant {

    private static final Logger log = LoggerFactory.getLogger(AssistantTurnService.class);

    private static final String CUSTOMER_PSEUDONYM_DOMAIN = "assistant.customer";

    /** Characters of any one earlier or current message the model is shown. */
    static final int MAX_TURN_CHARACTERS = 600;

    /** How many earlier messages the model is shown. */
    static final int MAX_HISTORY_TURNS = 6;

    static final int MAX_REPLY_CHARACTERS = 700;

    private static final RateLimiter.Policy PER_CONVERSATION = RateLimiter.Policy.strictPerMinute(6);
    private static final RateLimiter.Policy PER_TENANT = RateLimiter.Policy.strictPerMinute(120);

    private final ObjectProvider<AssistantModelPort> modelProvider;
    private final AssistantSettings settings;
    private final RetrievalService retrieval;
    private final TurnRecorder recorder;
    private final JdbcTurnStore turns;
    private final ProviderPricing pricing;
    private final AssistantMetrics metrics;
    private final RateLimiter rateLimiter;
    private final CacheManager caches;
    private final EntitlementService entitlements;
    private final UsageMeter usage;
    private final FieldProtection protection;
    private final RecipientContactDirectory contacts;
    private final BrandLocaleLookup brandLocales;
    private final BranchDirectory branches;
    private final OperatorPresenceQueryPort presence;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    AssistantTurnService(
            ObjectProvider<AssistantModelPort> modelProvider,
            AssistantSettings settings,
            RetrievalService retrieval,
            TurnRecorder recorder,
            JdbcTurnStore turns,
            ProviderPricing pricing,
            AssistantMetrics metrics,
            RateLimiter rateLimiter,
            CacheManager caches,
            EntitlementService entitlements,
            UsageMeter usage,
            FieldProtection protection,
            RecipientContactDirectory contacts,
            BrandLocaleLookup brandLocales,
            BranchDirectory branches,
            OperatorPresenceQueryPort presence,
            Clock clock) {
        this.modelProvider = modelProvider;
        this.settings = settings;
        this.retrieval = retrieval;
        this.recorder = recorder;
        this.turns = turns;
        this.pricing = pricing;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.caches = caches;
        this.entitlements = entitlements;
        this.usage = usage;
        this.protection = protection;
        this.contacts = contacts;
        this.brandLocales = brandLocales;
        this.branches = branches;
        this.presence = presence;
        this.clock = clock;
    }

    // -------------------------------------------------------------------- gate

    @Override
    public boolean willingToParticipate(UUID tenantId, UUID brandId) {
        AssistantModelPort model = modelProvider.getIfAvailable();
        return model != null
                && model.configured()
                && settings.entitled(tenantId)
                && settings.switchedOn(tenantId, brandId);
    }

    // -------------------------------------------------------------------- turn

    @Override
    public Outcome offer(Turn offered) {
        ConversationChannelRef channel = offered.channel();
        UUID tenantId = channel.tenantId();
        UUID brandId = channel.brandId();
        AssistantModelPort model = modelProvider.getIfAvailable();
        if (model == null || !willingToParticipate(tenantId, brandId)) {
            return new NotParticipating();
        }

        Instant now = clock.instant();
        UUID turnId = Ids.newId();
        TurnContext context = contextOf(offered, turnId, now);
        // Classified on the redacted text: a phone number, an email or a street the customer
        // typed beside the question is not a dish and must not be searched for as one.
        QuestionClassification classification = offered.sharedLocation() != null
                ? locationShare()
                : QuestionClassifier.classify(PiiEgressGuard.redact(offered.customerText()));

        // 2. Rate limit. A limited turn is dropped and nothing is sent: a customer
        // flooding the bot is not owed a conversation per message.
        if (!allowed(context)) {
            record(
                    context,
                    classification,
                    TurnOutcome.DECLINED,
                    RefusalReason.RATE_LIMITED,
                    null,
                    TokenUsage.NONE,
                    0,
                    0,
                    false,
                    RetrievedFacts.EMPTY,
                    Set.of(),
                    Evidence.NONE);
            return new NotParticipating();
        }

        // 3. Topics only a person handles: no retrieval, no model, no spend.
        if (classification.escalation() != null) {
            return handOff(context, classification, TurnOutcome.ESCALATED, null, classification.escalation(), model, 0);
        }

        // 4. Caps.
        long cap = settings.conversationTurnCap(tenantId, brandId);
        if (turns.turnsInConversationSince(tenantId, context.conversationId(), now.minus(Duration.ofHours(24)))
                >= cap) {
            return handOff(context, classification, TurnOutcome.REFUSED, RefusalReason.TURN_CAP, null, model, 0);
        }
        if (spendCeilingReached(tenantId, now)) {
            return handOff(context, classification, TurnOutcome.REFUSED, RefusalReason.SPEND_CEILING, null, model, 0);
        }

        // 5. Retrieval. Nothing retrieved is a refusal, before any model is asked.
        RetrievedFacts retrieved = retrieval.retrieve(
                tenantId,
                brandId,
                channel.customerAccountId(),
                classification,
                PiiEgressGuard.redact(offered.customerText()),
                context.locale(),
                context.localeOrder(),
                settings.priceChannelCode(tenantId, brandId),
                offered.sharedLocation() == null
                        ? null
                        : new GeoPoint(
                                offered.sharedLocation().latitude(),
                                offered.sharedLocation().longitude()));
        if (retrieved.isEmpty()) {
            return handOff(
                    context,
                    classification,
                    TurnOutcome.REFUSED,
                    RefusalReason.NO_GROUNDING,
                    null,
                    model,
                    0,
                    retrieved);
        }

        // 6. The plan's allowance. Under the pilot's meter-only mode this never refuses.
        LimitCheck allowance = entitlements.check(tenantId, EntitlementKeys.ASSISTANT_TURNS_MONTHLY_INCLUDED, 1);
        if (!allowance.allowed()) {
            return handOff(
                    context,
                    classification,
                    TurnOutcome.REFUSED,
                    RefusalReason.ENTITLEMENT_LIMIT,
                    null,
                    model,
                    0,
                    retrieved);
        }

        // 7. The model, or the cache of a question already answered from these exact facts.
        AssistantModelRequest request = requestOf(offered, context, retrieved);
        String cacheKey = cacheKey(context, classification, retrieved);
        boolean cacheable = !retrieved.customerSpecific() && request.turns().size() == 1;
        AssistantModelResponse response = cacheable ? cached(cacheKey) : null;
        boolean fromCache = response != null;
        TokenUsage tokens = TokenUsage.NONE;
        long latency = 0;
        if (response == null) {
            long started = System.nanoTime();
            try {
                response = model.answer(request);
            } catch (AssistantModelUnavailableException unavailable) {
                metrics.modelFailure(unavailable.code(), unavailable.retryable());
                return handOff(
                        context,
                        classification,
                        TurnOutcome.REFUSED,
                        RefusalReason.PROVIDER_UNAVAILABLE,
                        null,
                        model,
                        0,
                        retrieved);
            }
            latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            tokens = response.usage();
            meter(tenantId, turnId, now);
        }
        long cost = pricing.costUsdMicros(tokens);
        if (!fromCache) {
            metrics.modelCall(Duration.ofMillis(latency), tokens, cost);
        }

        // 8. The proof. A reply that does not survive it is discarded, and still paid for.
        GroundingVerdict verdict = GroundingVerifier.verify(response, retrieved.facts(), MAX_REPLY_CHARACTERS);
        if (!verdict.grounded()) {
            RefusalReason reason = verdict.reason();
            return handOff(
                    context,
                    classification,
                    TurnOutcome.REFUSED,
                    reason,
                    null,
                    model,
                    cost,
                    retrieved,
                    tokens,
                    latency);
        }
        if (cacheable && !fromCache) {
            cache().put(cacheKey, response);
        }

        // 9. Send. The disclosure rides on the first answer of a conversation.
        String reply = response.reply().strip();
        if (!turns.hasAnsweredIn(tenantId, context.conversationId())) {
            reply = CustomerWording.disclosure(context.locale()) + "\n\n" + reply;
        }
        Evidence evidence =
                retrieved.bindsThePlatform(verdict.citedFactIds()) ? Evidence.BINDING_ANSWER : Evidence.NONE;
        record(
                context,
                classification,
                TurnOutcome.ANSWERED,
                null,
                model,
                tokens,
                cost,
                latency,
                fromCache,
                retrieved,
                verdict.citedFactIds(),
                evidence);
        return new Replied(reply, turnId);
    }

    // ----------------------------------------------------------------- handoff

    private Outcome handOff(
            TurnContext context,
            QuestionClassification classification,
            TurnOutcome outcome,
            @Nullable RefusalReason reason,
            @Nullable EscalationTopic topic,
            AssistantModelPort model,
            long cost) {
        return handOff(context, classification, outcome, reason, topic, model, cost, RetrievedFacts.EMPTY);
    }

    private Outcome handOff(
            TurnContext context,
            QuestionClassification classification,
            TurnOutcome outcome,
            @Nullable RefusalReason reason,
            @Nullable EscalationTopic topic,
            AssistantModelPort model,
            long cost,
            RetrievedFacts retrieved) {
        return handOff(context, classification, outcome, reason, topic, model, cost, retrieved, TokenUsage.NONE, 0);
    }

    private Outcome handOff(
            TurnContext context,
            QuestionClassification classification,
            TurnOutcome outcome,
            @Nullable RefusalReason reason,
            @Nullable EscalationTopic topic,
            AssistantModelPort model,
            long cost,
            RetrievedFacts retrieved,
            TokenUsage tokens,
            long latency) {
        boolean someoneOnline = someoneOnline(context.tenantId(), context.brandId());
        String text = CustomerWording.handoff(context.locale(), topic, reason, someoneOnline);
        record(
                context,
                classification,
                outcome,
                reason,
                model,
                tokens,
                cost,
                latency,
                false,
                retrieved,
                Set.of(),
                Evidence.HANDOFF);
        return new HandedOff(text, context.turnId());
    }

    /**
     * Whether anyone is actually there to receive the handoff (ADR 0064's operator
     * presence), at any of the brand's branches. The assistant tells the customer the
     * truth about it: it never promises "shortly" to an empty room.
     */
    private boolean someoneOnline(UUID tenantId, UUID brandId) {
        try {
            for (BranchDirectory.Branch branch : branches.activeBranches(tenantId, brandId)) {
                if (!presence.online(tenantId, branch.locationId()).isEmpty()) {
                    return true;
                }
            }
        } catch (RuntimeException failure) {
            // Not knowing is not "online": the honest default is the cautious wording.
            log.warn(
                    "Operator presence could not be read: {}",
                    failure.getClass().getSimpleName());
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private TurnContext contextOf(Turn offered, UUID turnId, Instant now) {
        ConversationChannelRef channel = offered.channel();
        UUID tenantId = channel.tenantId();
        UUID brandId = channel.brandId();
        String fallback = fallbackLocale(tenantId, brandId, channel.customerAccountId());
        String locale = offered.sharedLocation() != null
                ? lastCustomerLocale(offered, fallback)
                : ReplyLocale.detect(offered.customerText(), fallback);
        List<String> order = new ArrayList<>();
        order.add(locale);
        for (String supported : ReplyLocale.SUPPORTED) {
            if (!order.contains(supported)) {
                order.add(supported);
            }
        }
        String pseudonym = channel.customerAccountId() == null
                ? null
                : protection.lookupHash(
                        tenantId,
                        CUSTOMER_PSEUDONYM_DOMAIN,
                        channel.customerAccountId().toString());
        return new TurnContext(
                turnId, tenantId, brandId, offered.conversationId(), locale, List.copyOf(order), pseudonym, now);
    }

    private String fallbackLocale(UUID tenantId, UUID brandId, @Nullable UUID customerAccountId) {
        String preferred = customerAccountId == null
                ? null
                : contacts.preferredLocale(tenantId, customerAccountId).orElse(null);
        String brandDefault = brandLocales.brandDefaultLocale(tenantId, brandId).orElse(null);
        String fromBrand = ReplyLocale.fromPreference(brandDefault, "ru");
        return ReplyLocale.fromPreference(preferred, fromBrand);
    }

    private static String lastCustomerLocale(Turn offered, String fallback) {
        for (int i = offered.history().size() - 1; i >= 0; i--) {
            HistoryEntry entry = offered.history().get(i);
            if (entry.author() == Author.CUSTOMER) {
                return ReplyLocale.detect(entry.text(), fallback);
            }
        }
        return fallback;
    }

    private static QuestionClassification locationShare() {
        return new QuestionClassification(Set.of(RetrievalKind.COVERAGE), null, List.of(), "");
    }

    private boolean allowed(TurnContext context) {
        String tenant = context.tenantId().toString();
        return rateLimiter
                        .check(
                                new RateLimiter.Key(
                                        "assistant.turn",
                                        tenant,
                                        context.conversationId().toString()),
                                PER_CONVERSATION)
                        .allowed()
                && rateLimiter
                        .check(new RateLimiter.Key("assistant.turn.tenant", tenant, "all"), PER_TENANT)
                        .allowed();
    }

    private boolean spendCeilingReached(UUID tenantId, Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        Instant from =
                utc.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = utc.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long ceilingMicros = Math.multiplyExact(settings.monthlySpendCeilingUsdCents(tenantId), 10_000L);
        return turns.spendMicros(tenantId, from, until) >= ceilingMicros;
    }

    private AssistantModelRequest requestOf(Turn offered, TurnContext context, RetrievedFacts retrieved) {
        List<ModelTurn> conversation = new ArrayList<>();
        List<HistoryEntry> history = offered.history();
        List<HistoryEntry> recent = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;
        for (HistoryEntry entry : recent) {
            // Staff replies and the flow's own messages never reach the model: a
            // flow message can echo a field the customer typed into it, and once a
            // person has written, the assistant is no longer in the conversation.
            if (entry.author() == Author.CUSTOMER) {
                conversation.add(new ModelTurn(ModelTurn.Role.CUSTOMER, sanitised(entry.text())));
            } else if (entry.author() == Author.ASSISTANT) {
                conversation.add(new ModelTurn(ModelTurn.Role.ASSISTANT, sanitised(entry.text())));
            }
        }
        String asked = offered.sharedLocation() != null ? "I shared my location." : offered.customerText();
        conversation.add(new ModelTurn(ModelTurn.Role.CUSTOMER, sanitised(asked)));
        return new AssistantModelRequest(
                AssistantPosture.forLocale(context.locale()),
                context.locale(),
                retrieved.facts(),
                conversation,
                context.pseudonym(),
                MAX_REPLY_CHARACTERS);
    }

    /** Redacts every personal shape and bounds the length: the one door text leaves through. */
    static String sanitised(String text) {
        String redacted = PiiEgressGuard.redact(text.strip());
        return redacted.length() > MAX_TURN_CHARACTERS ? redacted.substring(0, MAX_TURN_CHARACTERS) : redacted;
    }

    private void meter(UUID tenantId, UUID turnId, Instant now) {
        try {
            usage.record(UsageMovement.of(
                    tenantId,
                    EntitlementKeys.ASSISTANT_TURNS_MONTHLY_INCLUDED,
                    1,
                    "assistant.AssistantTurn",
                    turnId.toString(),
                    now));
        } catch (RuntimeException failure) {
            // Metering must never cost a customer their answer.
            log.warn(
                    "Assistant usage could not be metered for turn {}: {}",
                    turnId,
                    failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------- cache

    private Cache cache() {
        Cache cache = caches.getCache(CacheRegistry.ASSISTANT_GROUNDED_REPLIES.cacheName());
        if (cache == null) {
            throw new IllegalStateException("The assistant's reply cache is registered but was not built");
        }
        return cache;
    }

    private @Nullable AssistantModelResponse cached(String key) {
        Cache.ValueWrapper wrapper = cache().get(key);
        return wrapper == null ? null : (AssistantModelResponse) wrapper.get();
    }

    /**
     * Tenant, brand, language, and a digest of the question's skeleton and of every
     * retrieved fact: a price that changed, a dish that sold out and a knowledge entry
     * republished are each a different key, so a cached reply is by construction one
     * composed from exactly the facts a fresh retrieval just returned. The cache is an
     * accelerator and never a decision (ADR 0033); a miss asks the model again.
     */
    static String cacheKey(TurnContext context, QuestionClassification classification, RetrievedFacts retrieved) {
        StringBuilder facts = new StringBuilder();
        for (RetrievedFact fact : retrieved.facts()) {
            facts.append(fact.id()).append('|').append(fact.kind());
            new TreeMap<>(fact.attributes())
                    .forEach((name, value) ->
                            facts.append('|').append(name).append('=').append(value));
            facts.append('\n');
        }
        return context.tenantId() + ":" + context.brandId() + ":" + context.locale() + ":"
                + digest(classification.skeleton() + "\n" + facts);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    // ------------------------------------------------------------------ ledger

    private void record(
            TurnContext context,
            QuestionClassification classification,
            TurnOutcome outcome,
            @Nullable RefusalReason reason,
            @Nullable AssistantModelPort model,
            TokenUsage tokens,
            long cost,
            long latency,
            boolean fromCache,
            RetrievedFacts retrieved,
            Set<String> citedFactIds,
            Evidence evidence) {
        AssistantModelPort.ProviderDescriptor descriptor = model == null ? null : model.descriptor();
        List<String> kinds =
                classification.kinds().stream().map(Enum::name).sorted().toList();
        TurnRecord row = new TurnRecord(
                context.turnId(),
                context.tenantId(),
                context.brandId(),
                context.conversationId(),
                context.now(),
                context.locale(),
                String.join(",", kinds),
                outcome.name(),
                reason == null ? null : reason.name(),
                descriptor == null ? null : descriptor.providerType(),
                descriptor == null ? null : descriptor.modelId(),
                tokens.inputTokens(),
                tokens.outputTokens(),
                cost,
                (int) Math.min(latency, Integer.MAX_VALUE),
                fromCache,
                retrieved.provenance(),
                new ArrayList<>(new LinkedHashSet<>(citedFactIds)),
                retrieved.knowledgeVersions(),
                context.pseudonym());
        recorder.record(row, evidence, retrieved.provenance());
        metrics.turn(outcome, reason, fromCache);
    }

    /** What one turn knows about itself before it starts. */
    record TurnContext(
            UUID turnId,
            UUID tenantId,
            UUID brandId,
            UUID conversationId,
            String locale,
            List<String> localeOrder,
            @Nullable String pseudonym,
            Instant now) {}
}
