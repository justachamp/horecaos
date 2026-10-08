package uz.horecaos.platform.marketing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.ConsentService.Decision;
import uz.horecaos.platform.customers.application.ConsentService.Source;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.RecipientContactService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcAccrualRuleReferenceAdapter;
import uz.horecaos.platform.marketing.application.AudienceService;
import uz.horecaos.platform.marketing.application.CampaignCostEstimator;
import uz.horecaos.platform.marketing.application.CampaignSendService;
import uz.horecaos.platform.marketing.application.CampaignService;
import uz.horecaos.platform.marketing.application.ContactPolicyService;
import uz.horecaos.platform.marketing.application.CustomerMetricProjectionService;
import uz.horecaos.platform.marketing.application.MarketingEligibility;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.application.OfferService;
import uz.horecaos.platform.marketing.application.PresentedOfferService;
import uz.horecaos.platform.marketing.application.ScenarioEnrolmentService;
import uz.horecaos.platform.marketing.application.ScenarioResultsService;
import uz.horecaos.platform.marketing.application.ScenarioRunner;
import uz.horecaos.platform.marketing.application.ScenarioService;
import uz.horecaos.platform.marketing.application.ScenarioService.ScenarioDraft;
import uz.horecaos.platform.marketing.application.ScenarioService.StepDraft;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.EngagementPolicy.EngagementOverride;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcContactPolicyStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcPresentedOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.DecisionRow;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionReferenceAdapter;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * The ADR 0112 engine wired by hand against a real PostgreSQL, with a clock a test moves.
 *
 * <p>The stand-ins are the same two {@link MarketingCampaignTests} uses and for the same
 * reason: {@link FakeCampaignMessagePort} for the ADR 0020 boundary, and a recording
 * publisher for the events. Everything between them, the stores, the eligibility rules,
 * the contact policy, the runner and its transactions, is the real code, so a test that
 * asserts a row asserts what production would have written.
 *
 * <p>The clock is mutable because a scenario is a sequence of waits: the whole point of
 * what is asserted here is what happens when time passes, and a fixture whose clock never
 * moves would assert against an instant instead of a duration.
 */
final class ScenarioHarness {

    static final UUID TENANT = UUID.randomUUID();
    static final UUID OTHER_TENANT = UUID.randomUUID();
    static final UUID BRAND = UUID.randomUUID();
    static final UUID OTHER_BRAND = UUID.randomUUID();

    static final String PURPOSE = "MARKETING_PROMOTIONS";

    /** 14:00 in Tashkent: outside quiet hours. */
    static final Instant START = Instant.parse("2026-08-22T09:00:00Z");

    /** A clock whose instant a test sets. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration by) {
            now = now.plus(by);
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

    /** ADR 0030 resolution with values a test sets, whatever the scope. */
    static final class MutableConfiguration implements ConfigurationResolver {

        private final Map<String, Object> overrides = new HashMap<>();
        private ConfigurationResolver delegate = new FakeConfigurationResolver();

        void set(String code, Object value) {
            overrides.put(code, value);
            delegate = new FakeConfigurationResolver(overrides);
        }

        @Override
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            return delegate.resolve(key, scope);
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return delegate.explain(key, scope);
        }
    }

    /** What the order module would say about a guest's orders, set by a test. */
    static final class OrdersByGuest implements OrderDirectory {

        private final Map<UUID, List<RecentOrder>> byGuest = new HashMap<>();

        void placed(UUID accountId, Instant at) {
            byGuest.computeIfAbsent(accountId, key -> new ArrayList<>())
                    .add(new RecentOrder(
                            UUID.randomUUID(), "A-" + byGuest.size(), UUID.randomUUID(), "PLACED", "UZS", 50_000, at));
        }

        @Override
        public java.util.Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<RecentOrder> recentForCustomer(UUID tenantId, UUID brandId, UUID customerAccountId, int limit) {
            List<RecentOrder> orders = new ArrayList<>(byGuest.getOrDefault(customerAccountId, List.of()));
            orders.sort((left, right) -> right.placedAt().compareTo(left.placedAt()));
            return orders.stream().limit(limit).toList();
        }
    }

    final JdbcClient jdbc;
    final MutableClock clock = new MutableClock(START);
    final MutableConfiguration configuration = new MutableConfiguration();
    final OrdersByGuest orders = new OrdersByGuest();
    final List<Object> events = new CopyOnWriteArrayList<>();
    final FakeCampaignMessagePort port = new FakeCampaignMessagePort()
            .withBody("ru", "Скидка для вас, {{name}}!")
            .withBody("uz-Latn", "Sizga chegirma, {{name}}!")
            .withBody("en", "A discount for you, {{name}}!");

    final ObjectMapper objectMapper = JsonMapper.builder().build();
    final AuditRecorder audit;

    final JdbcAudienceStore audienceStore;
    final JdbcCampaignStore campaignStore;
    final JdbcEngagementStore engagementStore;
    final JdbcScenarioStore scenarioStore;
    final JdbcOfferStore offerStore;
    final JdbcContactPolicyStore contactPolicyStore;
    final JdbcPresentedOfferStore presentedStore;

    final CustomerProfileService profiles;
    final ConsentService consent;
    final CustomerMetricProjectionService projection;
    final MarketingEligibility eligibility;
    final FieldProtection protection;
    final AudienceService audiences;
    final CampaignService campaigns;
    final CampaignSendService sends;
    final MarketingSuppressionService suppressions;
    final ScenarioService scenarioService;
    final ScenarioEnrolmentService enrolment;
    final ScenarioRunner runner;
    final ScenarioResultsService results;
    final ContactPolicyService contactPolicy;
    final OfferService offerService;
    final PresentedOfferService presented;

    final ActorRef author = ActorRef.user(UUID.randomUUID().toString(), "Author");
    final ActorRef approver = ActorRef.user(UUID.randomUUID().toString(), "Approver");

    ScenarioHarness(TestDatabase.Handle db) {
        jdbc = JdbcClient.create(db.dataSource());
        truncate();

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                Clock.fixed(START, ZoneOffset.UTC));
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));
        audit = AuditTrail.recorder(jdbc);

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(customerStore, protection, objectMapper, clock, audit);
        consent = new ConsentService(customerStore, clock);
        RecipientContactService contacts = new RecipientContactService(customerStore, protection);

        audienceStore = new JdbcAudienceStore(jdbc, objectMapper);
        campaignStore = new JdbcCampaignStore(jdbc);
        engagementStore = new JdbcEngagementStore(jdbc);
        scenarioStore = new JdbcScenarioStore(jdbc);
        offerStore = new JdbcOfferStore(jdbc);
        contactPolicyStore = new JdbcContactPolicyStore(jdbc);
        presentedStore = new JdbcPresentedOfferStore(jdbc);
        JdbcCustomerMetricStore metricStore = new JdbcCustomerMetricStore(jdbc);

        eligibility = new MarketingEligibility(consent, contacts, engagementStore);
        CampaignCostEstimator estimator = new CampaignCostEstimator();
        ApplicationEventPublisher publisher = events::add;
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));

        projection = new CustomerMetricProjectionService(metricStore, clock);
        audiences = new AudienceService(audienceStore, metricStore, engagementStore, eligibility, audit, clock);
        scenarioService = new ScenarioService(
                campaignStore, scenarioStore, offerStore, engagementStore, audiences, port, audit, clock);
        enrolment = new ScenarioEnrolmentService(campaignStore, audienceStore, scenarioStore);
        campaigns = new CampaignService(
                campaignStore,
                engagementStore,
                audiences,
                estimator,
                port,
                audit,
                new AlwaysEntitledService(),
                scenarioService,
                clock);
        contactPolicy = new ContactPolicyService(contactPolicyStore, engagementStore, audit, clock);
        sends = new CampaignSendService(
                campaignStore,
                audienceStore,
                engagementStore,
                eligibility,
                contactPolicy,
                estimator,
                port,
                enrolment,
                clock,
                100);
        suppressions = new MarketingSuppressionService(engagementStore, audit, clock);
        presented = new PresentedOfferService(presentedStore, engagementStore, configuration, clock);
        offerService = new OfferService(
                offerStore,
                new JdbcPromotionReferenceAdapter(jdbc),
                new JdbcAccrualRuleReferenceAdapter(jdbc),
                audiences,
                port,
                audit,
                publisher,
                clock);
        runner = new ScenarioRunner(
                campaignStore,
                scenarioStore,
                offerStore,
                audienceStore,
                engagementStore,
                eligibility,
                contactPolicy,
                port,
                estimator,
                presented,
                orders,
                configuration,
                publisher,
                transactions,
                clock);
        results = new ScenarioResultsService(campaignStore, scenarioStore, clock);

        seedTenantAndBrand();
    }

    // ------------------------------------------------------------- fixtures

    /** A verified-phone guest with an SMS consent for {@link #PURPOSE}. */
    UUID reachableGuest(String phone) {
        UUID account = customer(phone, "ru", true);
        grantConsent(account, PURPOSE, "SMS");
        return account;
    }

    UUID customer(String phone, String locale, boolean verified) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', :locale, :now)
                """)
                .param("id", accountId)
                .param("tenantId", TENANT)
                .param("locale", locale)
                .param("now", OffsetDateTime.ofInstant(START.minusSeconds(172_800), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (id, tenant_id, brand_id, customer_account_id)
                VALUES (:id, :tenantId, :brandId, :accountId)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("accountId", accountId)
                .update();
        UUID contactId = profiles.addContactPoint(TENANT, accountId, ContactType.PHONE, phone, true);
        if (verified) {
            jdbc.sql("""
                    UPDATE customer.contact_points
                       SET verification_status = 'VERIFIED', verified_at = :now
                     WHERE id = :id
                    """)
                    .param("id", contactId)
                    .param("now", OffsetDateTime.ofInstant(START, ZoneOffset.UTC))
                    .update();
        }
        return accountId;
    }

    void grantConsent(UUID accountId, String purpose, String channel) {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                purpose,
                channel,
                Decision.GRANTED,
                "v1",
                Source.STOREFRONT,
                "storefront-checkbox",
                START.minusSeconds(86_400));
    }

    void withdrawConsent(UUID accountId, String purpose, String channel) {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                purpose,
                channel,
                Decision.WITHDRAWN,
                "v1",
                Source.STOREFRONT,
                "storefront-checkbox",
                clock.instant());
    }

    UUID everybody() {
        return audiences.define(
                TENANT,
                BRAND,
                "Everybody " + UUID.randomUUID(),
                null,
                List.of(AudiencePredicate.numeric(
                        PredicateType.ORDER_COUNT, PredicateOperator.AT_MOST, 1_000_000L, null)),
                UUID.fromString(author.subject()),
                "corr");
    }

    void priceSegmentsAt(long minorPerSegment) {
        engagementStore.saveOverride(
                TENANT,
                BRAND,
                new EngagementOverride(null, null, null, null, null, minorPerSegment, "UZS"),
                clock.instant());
    }

    /** An SMS step with no wait, no offer and no conditions. */
    static StepDraft smsStep(String templateKey, int waitSeconds) {
        return new StepDraft("SMS", null, templateKey, waitSeconds, null, null);
    }

    static StepDraft smsStep(
            String templateKey, int waitSeconds, @Nullable String continuation, @Nullable String stop) {
        return new StepDraft("SMS", null, templateKey, waitSeconds, continuation, stop);
    }

    UUID draftScenario(@Nullable Integer controlGroupPercent, StepDraft... steps) {
        return draftScenario(PURPOSE, controlGroupPercent, 500_000L, steps);
    }

    UUID draftScenario(
            String consentPurpose,
            @Nullable Integer controlGroupPercent,
            @Nullable Long ceilingMinor,
            StepDraft... steps) {
        return scenarioService.create(
                TENANT,
                BRAND,
                new ScenarioDraft(
                        "Win-back " + UUID.randomUUID(),
                        everybody(),
                        consentPurpose,
                        1_000,
                        ceilingMinor,
                        "UZS",
                        controlGroupPercent,
                        null,
                        List.of(steps)),
                UUID.fromString(author.subject()),
                author,
                "corr");
    }

    /** Estimated, submitted and approved by somebody who is not the author; not started. */
    UUID approved(UUID campaignId) {
        projection.backfill(TENANT, BRAND);
        campaigns.prepare(TENANT, campaignId, author, "corr");
        campaigns.submitForReview(TENANT, campaignId);
        boolean approved = campaigns.approve(
                TENANT,
                campaignId,
                UUID.fromString(approver.subject()),
                UUID.randomUUID(),
                approver,
                "Reviewed the steps and the reach",
                "corr");
        if (!approved) {
            throw new IllegalStateException("The fixture could not approve campaign " + campaignId);
        }
        return campaignId;
    }

    /** Approved and SENDING. */
    UUID launched(UUID campaignId) {
        approved(campaignId);
        if (!campaigns.start(TENANT, campaignId)) {
            throw new IllegalStateException("The fixture could not start campaign " + campaignId);
        }
        return campaignId;
    }

    /** A broadcast on the same channel, launched but not yet expanded: it is due for everybody in its snapshot. */
    UUID launchedBroadcast(String consentPurpose) {
        UUID campaign = campaigns.create(
                TENANT,
                BRAND,
                "Broadcast " + UUID.randomUUID(),
                MarketingChannel.SMS,
                consentPurpose,
                everybody(),
                "MARKETING_PROMOTION",
                100,
                500_000L,
                "UZS",
                null,
                null,
                null,
                UUID.fromString(author.subject()));
        return launched(campaign);
    }

    /** Takes every guest in the snapshot into the scenario: expands batches until none is left to enrol. */
    void enrolEverybody(UUID campaignId) {
        for (int pass = 0; pass < 20; pass++) {
            CampaignSendService.BatchOutcome outcome = sends.expandNextBatch(TENANT, campaignId);
            if (outcome.claimed() == 0) {
                return;
            }
        }
        throw new IllegalStateException("Enrolment did not finish");
    }

    /** Decides every guest who is due now, once. */
    int decide(UUID campaignId) {
        return runner.processDue(TENANT, campaignId);
    }

    List<DecisionRow> decisions(UUID campaignId) {
        return scenarioStore.decisions(TENANT, campaignId, null, 1_000);
    }

    List<DecisionRow> decisions(UUID campaignId, UUID accountId) {
        return scenarioStore.decisions(TENANT, campaignId, accountId, 1_000);
    }

    String outcomeOf(UUID campaignId, UUID accountId) {
        return jdbc.sql("""
                SELECT coalesce(outcome, '') FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("tenantId", TENANT)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .query(String.class)
                .single();
    }

    int currentStep(UUID campaignId, UUID accountId) {
        return jdbc.sql("""
                SELECT current_step_sequence FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("tenantId", TENANT)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
    }

    Instant waitUntil(UUID campaignId, UUID accountId) {
        return jdbc.sql("""
                SELECT wait_until FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("tenantId", TENANT)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /** A published offer pointing at a freshly seeded ACTIVE promotion, allowed on SMS and IN_APP. */
    UUID publishedOffer(String name) {
        UUID promotion = seedPromotion("ACTIVE");
        OfferService.OfferDraft draft = new OfferService.OfferDraft(
                name,
                promotion,
                null,
                START.minus(Duration.ofDays(1)),
                START.plus(Duration.ofDays(30)),
                null,
                List.of("SMS", "IN_APP"),
                "MARKETING_PROMOTION",
                null,
                null);
        var created = offerService.create(TENANT, BRAND, draft, author, UUID.fromString(author.subject()), "corr");
        return offerService
                .publish(
                        TENANT,
                        BRAND,
                        created.id(),
                        created.rowVersion(),
                        approver,
                        UUID.fromString(approver.subject()),
                        "corr")
                .id();
    }

    UUID seedPromotion(String status) {
        return seedPromotion(BRAND, status);
    }

    UUID seedPromotion(UUID brandId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.promotions (
                    id, tenant_id, brand_id, code, name, scope, status, stacking_group,
                    requires_coupon, currency, valid_from, validated_at, activated_at)
                VALUES (:id, :tenantId, :brandId, :code, 'Autumn 10', 'ORDER', :status, 'default',
                    false, 'UZS', :validFrom, :validFrom, :validFrom)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", "P" + id.toString().substring(0, 8))
                .param("status", status)
                .param("validFrom", START.minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    UUID seedAccrualRule(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO loyalty.accrual_rules (id, tenant_id, brand_id, scope_type,
                    rate_basis_points, max_accrual_minor, earn_delay_hours, lot_lifetime_days,
                    expiry_warning_days, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 'BRAND', 300, 30000, 24, 180, 14, :status, 1, :validFrom)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("status", status)
                .param("validFrom", START.minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    /** A scenario step that names the offer, on the given channel. */
    static StepDraft offerStep(String channel, UUID offerId, int waitSeconds) {
        return new StepDraft(channel, offerId, null, waitSeconds, null, null);
    }

    // ----------------------------------------------------------------- orders

    /**
     * A completed order that was promised at one moment and closed at another: the row a late-order
     * apology is about. A promise carries a basis and its preparation minutes, because the schema
     * refuses a promised time that cannot say how it was derived.
     */
    UUID completedOrder(UUID accountId, @Nullable Instant promisedAt, Instant closedAt, String status) {
        UUID orderId = order(accountId, closedAt.minus(Duration.ofHours(1)), status);
        jdbc.sql("""
                UPDATE ordering.orders
                   SET promised_at = :promisedAt,
                       promise_basis = CASE WHEN CAST(:promisedAt AS timestamptz) IS NULL THEN 'NOT_PROMISED' ELSE 'PLATFORM_DEFAULT' END,
                       promise_prep_minutes = CASE WHEN CAST(:promisedAt AS timestamptz) IS NULL THEN NULL ELSE 30 END,
                       closed_at = :closedAt
                 WHERE id = :id
                """)
                .param("promisedAt", promisedAt == null ? null : OffsetDateTime.ofInstant(promisedAt, ZoneOffset.UTC))
                .param("closedAt", OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC))
                .param("id", orderId)
                .update();
        return orderId;
    }

    /** An ADR 0013 remedy recorded against an order: a future discount, the one kind that needs no money. */
    void remedy(UUID orderId) {
        jdbc.sql("""
                INSERT INTO payments.order_remedies (id, tenant_id, brand_id, order_id, remedy_type, reason_code,
                    reason, currency, amount_minor, attested_money_minor, platform_settled_minor, settlement_basis,
                    recorded_by, recorded_at, idempotency_key)
                VALUES (:id, :tenantId, :brandId, :orderId, 'FUTURE_DISCOUNT', 'LATE_DELIVERY',
                    'Support recorded a discount for a late delivery', 'UZS', 0, 0, 0, 'NOT_MONEY',
                    'support-agent', now(), :key)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("orderId", orderId)
                .param("key", "remedy-" + orderId)
                .update();
    }

    String orderNumber(UUID orderId) {
        return jdbc.sql("SELECT public_order_number FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private @Nullable UUID locationId;
    private @Nullable UUID channelId;
    private @Nullable UUID publicationId;

    /**
     * An order of the pilot brand placed by a guest at a given moment: the fact a scenario's goal is
     * measured against. Inserted the way the order module's own tests insert one, through the quote,
     * cart and publication it references, because a row that skipped them would be one production
     * cannot produce.
     */
    UUID order(UUID accountId, Instant createdAt, String status) {
        seedOrderChainOnce();
        UUID location = Objects.requireNonNull(locationId);
        UUID channel = Objects.requireNonNull(channelId);
        UUID publication = Objects.requireNonNull(publicationId);
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        84000, 0, 84000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("publicationId", publication)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("channelId", channel)
                .param("customer", accountId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at, created_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :customer, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, :status,
                    'UZS', 84000, 0, 0, 84000, :quoteId, 'hash', :publicationId, :cartId,
                    :key, 1, :createdAt, :createdAt)
                """)
                .param("id", orderId)
                .param("number", "S-" + orderId.toString().substring(0, 8))
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("channelId", channel)
                .param("customer", accountId)
                .param("status", status)
                .param("quoteId", quoteId)
                .param("publicationId", publication)
                .param("cartId", cartId)
                .param("key", "idem-" + orderId)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        return orderId;
    }

    private void seedOrderChainOnce() {
        if (locationId != null) {
            return;
        }
        UUID location = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", location)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        UUID publication = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
        locationId = location;
        channelId = channel;
        publicationId = publication;
    }

    // -------------------------------------------------------------- plumbing

    private void seedTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'other', 'Legal', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'OTHER', 'other-brand', 'Other brand', 'ACTIVE')
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE marketing.scenario_step_decisions, marketing.scenario_participant_state, "
                        + "marketing.scenario_steps, marketing.presented_offers, marketing.offers, "
                        + "marketing.contact_policy_overrides, marketing.campaign_recipients, "
                        + "marketing.campaign_batches, marketing.campaigns, marketing.audience_snapshot_members, "
                        + "marketing.audience_snapshots, marketing.audience_predicates, marketing.audiences, "
                        + "marketing.marketing_sends, marketing.suppressions, marketing.metric_drift_observations, "
                        + "marketing.customer_metrics, marketing.engagement_policies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.promotions, loyalty.accrual_rules CASCADE")
                .update();
        jdbc.sql(
                        "TRUNCATE TABLE payments.order_remedies, ordering.orders, ordering.carts, pricing.quotes, catalog.publications, "
                                + "catalog.catalogs, tenant.sales_channels, tenant.locations CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
    }
}
