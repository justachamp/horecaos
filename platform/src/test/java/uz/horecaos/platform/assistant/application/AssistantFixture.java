package uz.horecaos.platform.assistant.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.assistant.api.AssistantModelPort;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcKnowledgeStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.MenuSearchPort;
import uz.horecaos.platform.commercial.api.Boundary;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.EntitlementSource;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.commercial.api.UsagePeriod;
import uz.horecaos.platform.conversations.api.ChannelKind;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.support.FakeAssistantModel;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.voice.api.OperatorPresenceQueryPort;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * The assistant wired the way production wires it, over a real PostgreSQL, with
 * every other module's {@code api} port replaced by a scripted stand-in and the
 * model provider replaced by {@link FakeAssistantModel}.
 *
 * <p>What is real is what the assistant owns: its ledger, its knowledge store, its
 * audit facts, its rate limiter, its cache and its checks. What is scripted is
 * what belongs to other modules -- the menu, the branches, the order -- because
 * those have their own suites, and a test that re-proved them here would pass or
 * fail for reasons that are not the assistant's.
 */
final class AssistantFixture {

    static final String CHANNEL_CODE = "STOREFRONT";

    final UUID tenantId = UUID.randomUUID();
    final UUID brandId = UUID.randomUUID();
    final UUID chilonzor = UUID.randomUUID();
    final UUID yunusabad = UUID.randomUUID();

    final JdbcClient jdbc;
    final Clock clock;
    final MutableClock mutableClock;
    final FakeAssistantModel model = new FakeAssistantModel();
    final ScriptedMenu menu = new ScriptedMenu();
    final ScriptedBranches branches = new ScriptedBranches();
    final ScriptedOrders orders = new ScriptedOrders();
    final ScriptedCoverage coverage = new ScriptedCoverage();
    final Presence presence = new Presence();
    final RecordingUsage usage = new RecordingUsage();
    final Entitlements entitlements = new Entitlements();
    final Map<String, Object> configuration = new HashMap<>();
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    final JdbcTurnStore turns;
    final JdbcKnowledgeStore knowledgeStore;
    final KnowledgeService knowledge;
    final AuditRecorder audit;
    final AssistantTurnService service;
    final CacheManager caches;
    final FieldProtection protection = TestProtection.envelope();

    private DefaultListableBeanFactory providerBeans = new DefaultListableBeanFactory();

    /** The menu the service reads: the scripted one, unless a test swaps in the real storefront menu. */
    MenuSearchPort menuPort = menu;

    AssistantFixture(JdbcClient jdbc, Instant start) {
        this.jdbc = jdbc;
        this.mutableClock = new MutableClock(start);
        this.clock = mutableClock;
        JsonMapper mapper = JsonMapper.builder().build();
        this.turns = new JdbcTurnStore(jdbc, mapper);
        this.knowledgeStore = new JdbcKnowledgeStore(jdbc);
        this.audit = new JdbcAuditRecorder(jdbc, mapper);
        this.knowledge = new KnowledgeService(knowledgeStore, audit, clock);

        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.setCacheNames(List.of(CacheRegistry.ASSISTANT_GROUNDED_REPLIES.cacheName()));
        this.caches = manager;

        configuration.put("assistant.enabled", true);
        entitlements.assistant = true;
        entitlements.conversations = true;

        branches.add(branch(chilonzor, "Chilonzor", "Bunyodkor ko'chasi 12", "Chilonzor", "Near the metro"));
        branches.add(branch(yunusabad, "Yunusabad", "Amir Temur ko'chasi 40", "Yunusabad", null));

        this.service = rebuild();
    }

    /** Re-reads {@link #configuration}, {@link #entitlements} and the provider wiring into a fresh service. */
    AssistantTurnService rebuild() {
        providerBeans = new DefaultListableBeanFactory();
        providerBeans.registerSingleton("model", model);
        return buildService(providerBeans.getBeanProvider(AssistantModelPort.class));
    }

    AssistantTurnService withoutProvider() {
        return buildService(new DefaultListableBeanFactory().getBeanProvider(AssistantModelPort.class));
    }

    private AssistantTurnService buildService(ObjectProvider<AssistantModelPort> provider) {
        AssistantSettings settings = new AssistantSettings(new FakeConfigurationResolver(configuration), entitlements);
        RetrievalService retrieval = new RetrievalService(
                branches, menuPort, new ChannelLookup(), new OpenWhenAsked(), coverage, orders, knowledge, clock);
        return new AssistantTurnService(
                provider,
                settings,
                retrieval,
                new TurnRecorder(turns, audit),
                turns,
                new ProviderPricing(2, 10),
                new AssistantMetrics(meters),
                new InProcessRateLimiter(clock),
                caches,
                entitlements,
                usage,
                protection,
                new Contacts(),
                BrandLocaleLookup.platformFallback(),
                branches,
                presence,
                clock);
    }

    // ------------------------------------------------------------------ turns

    ConversationParticipant.Turn ask(UUID conversationId, String text) {
        return ask(conversationId, text, null, List.of());
    }

    ConversationParticipant.Turn ask(UUID conversationId, String text, @Nullable UUID account) {
        return ask(conversationId, text, account, List.of());
    }

    ConversationParticipant.Turn ask(
            UUID conversationId,
            String text,
            @Nullable UUID account,
            List<ConversationParticipant.HistoryEntry> history) {
        return new ConversationParticipant.Turn(
                new ConversationChannelRef(tenantId, brandId, UUID.randomUUID(), ChannelKind.TELEGRAM, 4242L, account),
                conversationId,
                text,
                history,
                null);
    }

    ConversationParticipant.Turn shareLocation(UUID conversationId, @Nullable UUID account) {
        return new ConversationParticipant.Turn(
                new ConversationChannelRef(tenantId, brandId, UUID.randomUUID(), ChannelKind.TELEGRAM, 4242L, account),
                conversationId,
                "[location shared]",
                List.of(),
                new ConversationParticipant.SharedLocation(41.2995, 69.2401));
    }

    // ---------------------------------------------------------------- database

    void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "assistant-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "brand-" + brandId)
                .update();
        for (UUID location : List.of(chilonzor, yunusabad)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("code", "L" + location.toString().substring(0, 8).toUpperCase())
                    .param("slug", "loc-" + location.toString().substring(0, 12))
                    .update();
        }
    }

    /** A cost already on the ledger, as if earlier turns had spent it. */
    void spend(long usdMicros, Instant at) {
        turns.insert(new JdbcTurnStore.TurnRecord(
                UUID.randomUUID(),
                tenantId,
                brandId,
                UUID.randomUUID(),
                at,
                "en",
                "KNOWLEDGE",
                "ANSWERED",
                null,
                "FAKE",
                "fake-model-1",
                0,
                0,
                usdMicros,
                0,
                false,
                List.of(),
                List.of(),
                List.of(),
                null));
    }

    List<Map<String, Object>> ledger() {
        return jdbc.sql("""
                SELECT id, outcome, refusal_reason, question_kinds, cost_usd_micros, input_tokens, output_tokens,
                       served_from_cache, facts::text AS facts, cited_fact_ids::text AS cited,
                       knowledge_versions::text AS knowledge, customer_pseudonym, locale, model_id
                  FROM assistant.turns
                 WHERE tenant_id = :tenantId
                 ORDER BY occurred_at, id
                """).param("tenantId", tenantId).query().listOfRows();
    }

    // ------------------------------------------------------------------ stubs

    static BranchDirectory.Branch branch(
            UUID id, String name, String address, String district, @Nullable String landmark) {
        return new BranchDirectory.Branch(
                id,
                name,
                address,
                district,
                "Tashkent",
                landmark,
                "+998 71 200 00 00",
                new GeoPoint(41.31, 69.24),
                "Asia/Tashkent",
                Map.of(
                        FulfillmentMode.PICKUP,
                        List.of(
                                new BranchDirectory.WeeklyWindow(1, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                                new BranchDirectory.WeeklyWindow(2, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                                new BranchDirectory.WeeklyWindow(3, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                                new BranchDirectory.WeeklyWindow(4, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                                new BranchDirectory.WeeklyWindow(5, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                                new BranchDirectory.WeeklyWindow(6, LocalTime.of(10, 0), LocalTime.of(23, 30)),
                                new BranchDirectory.WeeklyWindow(7, LocalTime.of(10, 0), LocalTime.of(23, 30)))));
    }

    static MenuSearchPort.Dish dish(String name, long amountMinor, UUID variantId) {
        return dish(UUID.randomUUID(), name, amountMinor, variantId);
    }

    /** The same dish (one product id) as another branch sells, at whatever price this branch charges. */
    static MenuSearchPort.Dish dish(UUID productId, String name, long amountMinor, UUID variantId) {
        return new MenuSearchPort.Dish(
                productId,
                name,
                null,
                "ru",
                List.of(new MenuSearchPort.Form(variantId, null, true, true, true, amountMinor, null, null)));
    }

    /** A menu whose answers a test scripts per location. */
    static final class ScriptedMenu implements MenuSearchPort {
        final Map<UUID, List<Dish>> dishesByLocation = new HashMap<>();
        String currency = "UZS";
        final AtomicInteger searches = new AtomicInteger();
        final List<List<String>> termsSearched = new CopyOnWriteArrayList<>();

        void offer(UUID location, Dish... dishes) {
            dishesByLocation.put(location, List.of(dishes));
        }

        @Override
        public MenuSearchResult search(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                String channelCode,
                List<String> locales,
                List<String> terms,
                int limit) {
            searches.incrementAndGet();
            termsSearched.add(terms);
            List<Dish> all = dishesByLocation.getOrDefault(locationId, List.of());
            List<Dish> hits = all.stream()
                    .filter(dish -> uz.horecaos.platform.configuration.SearchText.containsAll(terms, dish.name()))
                    .toList();
            return new MenuSearchResult(true, currency, hits);
        }
    }

    static final class ScriptedBranches implements BranchDirectory {
        private final List<Branch> branches = new ArrayList<>();

        void add(Branch branch) {
            branches.add(branch);
        }

        void only(Branch... only) {
            branches.clear();
            branches.addAll(List.of(only));
        }

        @Override
        public List<Branch> activeBranches(UUID tenantId, UUID brandId) {
            return List.copyOf(branches);
        }
    }

    static final class ScriptedOrders implements CustomerBotOrderingPort {
        final List<UUID> askedFor = new CopyOnWriteArrayList<>();

        @Nullable
        OrderCard card;

        @Override
        public Optional<OrderCard> latestOrder(UUID tenantId, UUID brandId, UUID customerAccountId) {
            askedFor.add(customerAccountId);
            return Optional.ofNullable(card);
        }

        @Override
        public Repeat repeat(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId) {
            throw new AssertionError("the assistant must never build a cart");
        }

        @Override
        public Optional<CartCard> currentCart(UUID tenantId, UUID brandId, UUID customerAccountId) {
            throw new AssertionError("the assistant must never read a cart");
        }

        @Override
        public Checkout checkoutForCash(
                UUID tenantId, UUID brandId, UUID customerAccountId, UUID cartId, String idempotencyKey) {
            throw new AssertionError("the assistant must never complete a checkout");
        }
    }

    static final class ScriptedCoverage implements BranchResolutionPort {
        List<DeliveryBranchMatch> matches = List.of();
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public List<DeliveryBranchMatch> deliveryCandidates(UUID tenantId, UUID brandId, GeoPoint point, Instant at) {
            asked.incrementAndGet();
            return matches;
        }

        @Override
        public List<PickupBranchCandidate> pickupCandidates(UUID tenantId, UUID brandId) {
            return List.of();
        }
    }

    final class ChannelLookup implements SalesChannelLookup {
        @Override
        public Optional<SalesChannel> byId(UUID tenantId, UUID channelId) {
            return Optional.empty();
        }

        @Override
        public Optional<SalesChannel> byCode(UUID tenantId, String code) {
            return Optional.of(new SalesChannel(
                    UUID.randomUUID(),
                    tenantId,
                    code,
                    SalesChannelSystemType.WEB,
                    code,
                    SalesChannel.Status.ACTIVE,
                    null,
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    0));
        }

        @Override
        public Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId) {
            return Set.of();
        }
    }

    final class OpenWhenAsked implements ServiceabilityResolver {
        boolean open = true;

        @Override
        public Serviceability resolve(
                UUID tenantId, UUID brandId, UUID locationId, UUID channelId, FulfillmentMode mode, Instant at) {
            return open
                    ? Serviceability.available(false, 20)
                    : Serviceability.refused(
                            ServiceabilityReason.OUTSIDE_SERVICE_HOURS, at.plus(Duration.ofHours(10)), false);
        }
    }

    static final class Presence implements OperatorPresenceQueryPort {
        volatile boolean someoneOnline;

        @Override
        public List<OnlineOperator> online(UUID tenantId, UUID locationId) {
            return someoneOnline ? List.of(new OnlineOperator("operator-1", "ONLINE")) : List.of();
        }
    }

    static final class Contacts implements RecipientContactDirectory {
        @Override
        public Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method) {
            return Optional.empty();
        }

        @Override
        public Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
            return Optional.empty();
        }
    }

    static final class RecordingUsage implements UsageMeter {
        final List<UsageMovement> recorded = new CopyOnWriteArrayList<>();

        @Override
        public boolean record(UsageMovement movement) {
            recorded.add(movement);
            return true;
        }
    }

    /** Feature switches, and a plan allowance that can be exhausted. */
    static final class Entitlements implements EntitlementService {
        volatile boolean assistant;
        volatile boolean conversations;
        volatile boolean allowanceExhausted;

        @Override
        public EntitlementSnapshot snapshot(UUID tenantId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
            EntitlementValue value = new EntitlementValue(
                    key,
                    allowanceExhausted ? 100L : null,
                    null,
                    EnforcementMode.HARD,
                    EnforcementMode.HARD,
                    ResetPeriod.BILLING_PERIOD,
                    null,
                    null,
                    null,
                    EntitlementSource.PLAN_VERSION);
            Instant start = Instant.parse("2026-10-01T00:00:00Z");
            return new LimitCheck(
                    key.code(),
                    tenantId,
                    allowanceExhausted ? 100L : null,
                    allowanceExhausted ? 100 : 0,
                    requested,
                    new UsagePeriod("2026-10", start, start.plus(Duration.ofDays(31))),
                    value,
                    allowanceExhausted ? Boundary.REFUSED : Boundary.UNLIMITED,
                    allowanceExhausted ? Boundary.REFUSED : Boundary.UNLIMITED,
                    allowanceExhausted ? requested : 0);
        }

        @Override
        public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
            return check(tenantId, key, requested);
        }

        @Override
        public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
            if (key == EntitlementKeys.ASSISTANT_ANSWERING_ENABLED) {
                return assistant;
            }
            if (key == EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED) {
                return conversations;
            }
            return true;
        }

        @Override
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @SuppressWarnings("unused")
    private static BigDecimal unused() {
        return BigDecimal.ZERO;
    }
}
