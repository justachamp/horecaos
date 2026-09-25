package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.ConsentService.Decision;
import uz.horecaos.platform.customers.application.ConsentService.Source;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.RecipientContactService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.marketing.application.AutomationFiringService;
import uz.horecaos.platform.marketing.application.AutomationRuleService;
import uz.horecaos.platform.marketing.application.AutomationSweepService;
import uz.horecaos.platform.marketing.application.CustomerMetricProjectionService;
import uz.horecaos.platform.marketing.application.MarketingEligibility;
import uz.horecaos.platform.marketing.domain.AutomationTriggerType;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore.AutomationRunRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Gap-map row 6.5 against a real PostgreSQL: BIRTHDAY, INACTIVITY, and
 * CART_ABANDONMENT (ADR 0044 Triggers).
 *
 * <p>{@link AbandonedCartDirectory} and {@link OrderDirectory} are stood in by
 * hand — the same reasoning {@link FakeCampaignMessagePort}'s own doc gives:
 * these tests are about who is guarded, who is refused, and who is cancelled,
 * and driving a real cart through checkout here would drag in the ordering
 * module to assert nothing about marketing.
 */
class AutomationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String PURPOSE = "MARKETING_PROMOTIONS";
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    /** 14:00 in Tashkent: outside quiet hours, so nothing is deferred by accident. */
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private FieldProtection protection;

    private JdbcAudienceStore audienceStore;
    private JdbcEngagementStore engagementStore;
    private JdbcCustomerMetricStore metricStore;
    private JdbcAutomationRuleStore ruleStore;
    private JdbcAutomationRunStore runStore;

    private CustomerProfileService profiles;
    private ConsentService consent;
    private RecordingAuditRecorder audit;

    private FakeCampaignMessagePort port;
    private FakeAbandonedCartDirectory carts;
    private FakeOrderDirectory orders;

    private CustomerMetricProjectionService projection;
    private AutomationRuleService rules;
    private AutomationFiringService firing;
    private AutomationSweepService sweeps;

    private final ActorRef author = ActorRef.user(UUID.randomUUID().toString(), "Author");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for automation tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        objectMapper = JsonMapper.builder().build();
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                Clock.fixed(NOW, ZoneOffset.UTC));
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        wire(NOW);
        seedTenantAndBrand();
    }

    private void wire(Instant moment) {
        Clock clock = Clock.fixed(moment, ZoneOffset.UTC);
        audit = new RecordingAuditRecorder();

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(customerStore, protection, objectMapper, clock, audit);
        consent = new ConsentService(customerStore, clock);
        RecipientContactService contacts = new RecipientContactService(customerStore, protection);

        audienceStore = new JdbcAudienceStore(jdbc, objectMapper);
        engagementStore = new JdbcEngagementStore(jdbc);
        metricStore = new JdbcCustomerMetricStore(jdbc);
        ruleStore = new JdbcAutomationRuleStore(jdbc, objectMapper);
        runStore = new JdbcAutomationRunStore(jdbc);

        port = new FakeCampaignMessagePort()
                .withBody("ru", "Для вас, {{name}}!")
                .withBody("uz-Latn", "Siz uchun, {{name}}!")
                .withBody("en", "For you, {{name}}!");
        carts = new FakeAbandonedCartDirectory();
        orders = new FakeOrderDirectory();

        MarketingEligibility eligibility = new MarketingEligibility(consent, contacts, engagementStore);
        projection = new CustomerMetricProjectionService(metricStore, clock);
        rules = new AutomationRuleService(ruleStore, objectMapper, port, audit, clock);
        firing = new AutomationFiringService(runStore, audienceStore, engagementStore, eligibility, port, audit, clock);
        sweeps = new AutomationSweepService(ruleStore, metricStore, engagementStore, carts, orders, firing, clock);
    }

    // --------------------------------------------------------------- BIRTHDAY

    @Test
    @DisplayName("a BIRTHDAY rule fires once, then the same calendar year guards it")
    void birthdayFiresOnceThenGuardsTheYear() {
        UUID account = customer("+998901111111", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        setBirthMonthDay(account, MONTH_DAY.format(NOW.atZone(TASHKENT)));

        UUID ruleId = createAndActivate(AutomationTriggerType.BIRTHDAY, Map.of("birthdayWindowDays", 0), 365);

        assertThat(sweeps.sweepBirthday()).isEqualTo(1);
        assertThat(port.sent()).hasSize(1);
        assertThat(runStatuses(ruleId)).containsExactly("FIRED");

        // A second pass, the same day: the calendar-year guard key already has a
        // row, so no second message goes out.
        sweeps.sweepBirthday();
        assertThat(port.sent()).hasSize(1);
        assertThat(runStatuses(ruleId)).containsExactly("FIRED");
    }

    @Test
    @DisplayName("a customer outside the birthday window is not a candidate")
    void birthdayOutsideTheWindowIsNotFired() {
        UUID account = customer("+998901111112", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        setBirthMonthDay(account, MONTH_DAY.format(NOW.atZone(TASHKENT).plusDays(10)));

        createAndActivate(AutomationTriggerType.BIRTHDAY, Map.of("birthdayWindowDays", 0), 365);

        assertThat(sweeps.sweepBirthday()).isZero();
        assertThat(port.sent()).isEmpty();
    }

    // ------------------------------------------------------------ INACTIVITY

    @Test
    @DisplayName("an INACTIVITY rule fires once, then its cooldown bucket guards it")
    void inactivityFiresOnceThenGuardsTheCooldown() {
        UUID account = customer("+998902222221", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        setDaysSinceLastOrder(account, 95);

        UUID ruleId = createAndActivate(AutomationTriggerType.INACTIVITY, Map.of("inactivityDays", 90), 90);

        assertThat(sweeps.sweepInactivity()).isEqualTo(1);
        assertThat(port.sent()).hasSize(1);
        assertThat(runStatuses(ruleId)).containsExactly("FIRED");

        sweeps.sweepInactivity();
        assertThat(port.sent())
                .as("the same cooldown bucket must not fire a second message")
                .hasSize(1);
    }

    @Test
    @DisplayName("a customer who never ordered is excluded from an INACTIVITY sweep")
    void neverOrderedIsExcludedFromInactivity() {
        UUID account = customer("+998902222222", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        // days_since_last_order stays null: no last order to have gone quiet since.

        createAndActivate(AutomationTriggerType.INACTIVITY, Map.of("inactivityDays", 90), 90);

        assertThat(sweeps.sweepInactivity()).isZero();
        assertThat(port.sent()).isEmpty();
    }

    // -------------------------------------------------------- CART_ABANDONMENT

    @Test
    @DisplayName("an abandoned cart fires once, then the same cart's guard key blocks a second sweep")
    void cartAbandonmentFiresOnceThenGuardsTheCart() {
        UUID account = customer("+998903333331", "ru", true);
        grantConsent(account);
        UUID cartId = UUID.randomUUID();
        carts.abandoned(TENANT, BRAND, cartId, account, NOW.minusSeconds(3 * 3600));
        orders.noRecentOrderFor(account);

        UUID ruleId = createAndActivate(AutomationTriggerType.CART_ABANDONMENT, Map.of("abandonmentDelayHours", 2), 30);

        assertThat(sweeps.sweepCartAbandonment()).isEqualTo(1);
        assertThat(port.sent()).hasSize(1);
        assertThat(runStatuses(ruleId)).containsExactly("FIRED");

        sweeps.sweepCartAbandonment();
        assertThat(port.sent())
                .as("the same cart's guard key must not fire a second message")
                .hasSize(1);
    }

    @Test
    @DisplayName("ADR 0044: a cart is cancelled, not sent to, when the customer orders before the send")
    void cartAbandonmentCancelsWhenTheCustomerConvertsFirst() {
        UUID account = customer("+998903333332", "ru", true);
        grantConsent(account);
        Instant abandonedAt = NOW.minusSeconds(3 * 3600);
        UUID cartId = UUID.randomUUID();
        carts.abandoned(TENANT, BRAND, cartId, account, abandonedAt);
        // The customer placed an order after the cart went quiet.
        orders.recentOrderFor(account, abandonedAt.plusSeconds(1800));

        UUID ruleId = createAndActivate(AutomationTriggerType.CART_ABANDONMENT, Map.of("abandonmentDelayHours", 2), 30);

        assertThat(sweeps.sweepCartAbandonment()).isEqualTo(1);
        assertThat(port.sent())
                .as("a customer who already converted must not receive a recovery message")
                .isEmpty();
        assertThat(runStatuses(ruleId)).containsExactly("CANCELLED");
    }

    @Test
    @DisplayName("ADR 0044: a conversion landing between the guard claim and the send still cancels it (TOCTOU)")
    void cartAbandonmentCancelsWhenTheCustomerConvertsDuringTheFiringWindow() {
        UUID account = customer("+998903333334", "ru", true);
        grantConsent(account);
        Instant abandonedAt = NOW.minusSeconds(3 * 3600);
        UUID cartId = UUID.randomUUID();
        carts.abandoned(TENANT, BRAND, cartId, account, abandonedAt);
        orders.noRecentOrderFor(account);

        // Rewire the run store so that the instant the guard key is claimed — the
        // same instant AutomationFiringService#attemptFire commits to firing —
        // the customer's order "lands", the way a concurrent checkout commit
        // would. AutomationSweepService#convertedSince reads OrderDirectory
        // before attemptFire is even called, so any implementation that trusts
        // that earlier read instead of re-checking after the claim will still
        // see "no order" and send anyway.
        runStore = new ClaimTriggeredAutomationRunStore(
                jdbc, () -> orders.recentOrderFor(account, abandonedAt.plusSeconds(1800)));
        firing = new AutomationFiringService(
                runStore,
                audienceStore,
                engagementStore,
                new MarketingEligibility(
                        consent, new RecipientContactService(new JdbcCustomerStore(jdbc), protection), engagementStore),
                port,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        sweeps = new AutomationSweepService(
                ruleStore, metricStore, engagementStore, carts, orders, firing, Clock.fixed(NOW, ZoneOffset.UTC));

        UUID ruleId = createAndActivate(AutomationTriggerType.CART_ABANDONMENT, Map.of("abandonmentDelayHours", 2), 30);

        assertThat(sweeps.sweepCartAbandonment()).isEqualTo(1);
        assertThat(port.sent())
                .as("a customer who converted after the guard was claimed but before the send went out "
                        + "must not receive a recovery message")
                .isEmpty();
        assertThat(runStatuses(ruleId)).containsExactly("CANCELLED");
    }

    @Test
    @DisplayName("an order placed before the cart was abandoned does not cancel a later abandonment")
    void anEarlierOrderDoesNotCancelALaterAbandonment() {
        UUID account = customer("+998903333333", "ru", true);
        grantConsent(account);
        Instant abandonedAt = NOW.minusSeconds(3 * 3600);
        UUID cartId = UUID.randomUUID();
        carts.abandoned(TENANT, BRAND, cartId, account, abandonedAt);
        // An order placed well before this cart was even started must not read as
        // "converted first".
        orders.recentOrderFor(account, abandonedAt.minusSeconds(86_400));

        createAndActivate(AutomationTriggerType.CART_ABANDONMENT, Map.of("abandonmentDelayHours", 2), 30);

        assertThat(sweeps.sweepCartAbandonment()).isEqualTo(1);
        assertThat(port.sent()).hasSize(1);
    }

    // ---------------------------------------------------- channel wiring & refusal

    @Test
    @DisplayName("arming a rule for an unwired channel is refused visibly, at the moment an operator arms it")
    void armingAnUnwiredChannelIsRefused() {
        port.unwire();
        UUID account = customer("+998904444441", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        setBirthMonthDay(account, MONTH_DAY.format(NOW.atZone(TASHKENT)));

        UUID ruleId = rules.create(
                TENANT,
                BRAND,
                "Birthday " + UUID.randomUUID(),
                AutomationTriggerType.BIRTHDAY,
                MarketingChannel.MESSAGING_APP,
                PURPOSE,
                "automation_birthday",
                Map.of("birthdayWindowDays", 0),
                365,
                actorId());
        AutomationRuleRow rule = rules.require(TENANT, BRAND, ruleId);

        assertThatThrownBy(() -> rules.activate(TENANT, BRAND, ruleId, rule.version(), author, "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("MESSAGING_APP");
    }

    @Test
    @DisplayName("a channel that stops being wired after arming is refused per firing, visibly, not silently")
    void aChannelUnwiredAfterArmingIsRefusedPerFiring() {
        UUID account = customer("+998904444442", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        setBirthMonthDay(account, MONTH_DAY.format(NOW.atZone(TASHKENT)));

        UUID ruleId = createAndActivate(AutomationTriggerType.BIRTHDAY, Map.of("birthdayWindowDays", 0), 365);

        // The deployment loses its delivery path after the rule was already armed —
        // CampaignService#start's own defense-in-depth reasoning, restated here.
        port.unwire();

        assertThat(sweeps.sweepBirthday()).isEqualTo(1);
        assertThat(port.sent()).isEmpty();
        var runs = runStore.recentByRule(TENANT, ruleId, 10);
        assertThat(runs).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("REFUSED");
            assertThat(row.refusalReason()).isEqualTo("CHANNEL_NOT_WIRED");
        });
    }

    // ------------------------------------------------------------ tenant isolation

    @Test
    @DisplayName("tenant isolation: a rule from one tenant is invisible to another")
    void aRuleIsInvisibleToAnotherTenant() {
        UUID ruleId = rules.create(
                TENANT,
                BRAND,
                "Birthday " + UUID.randomUUID(),
                AutomationTriggerType.BIRTHDAY,
                MarketingChannel.MESSAGING_APP,
                PURPOSE,
                "automation_birthday",
                Map.of("birthdayWindowDays", 0),
                365,
                actorId());

        assertThatThrownBy(() -> rules.require(UUID.randomUUID(), BRAND, ruleId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tenant isolation: a birthday sweep never crosses into another tenant's customers")
    void birthdaySweepDoesNotCrossTenants() {
        UUID account = customer("+998905555551", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        String today = MONTH_DAY.format(NOW.atZone(TASHKENT));
        setBirthMonthDay(account, today);

        // A tenant id that owns no rows at all: the projection query is scoped by
        // tenant_id in the WHERE clause, not by matching the account it happens to
        // find.
        assertThat(metricStore.birthdaysWithin(
                        UUID.randomUUID(), BRAND, 0, NOW.atZone(TASHKENT).toLocalDate()))
                .isEmpty();
        assertThat(metricStore.birthdaysWithin(
                        TENANT, BRAND, 0, NOW.atZone(TASHKENT).toLocalDate()))
                .containsExactly(account);
    }

    // --------------------------------------------------------------- authoring

    @Test
    @DisplayName("q-rule-list's whole-set reorder rewrites every named rule's priority")
    void reorderRewritesPriority() {
        UUID first = plainRule("A");
        UUID second = plainRule("B");
        UUID third = plainRule("C");

        rules.reorder(TENANT, BRAND, List.of(third, first, second));

        var byId = rules.list(TENANT, BRAND).stream()
                .collect(java.util.stream.Collectors.toMap(AutomationRuleRow::id, AutomationRuleRow::priority));
        assertThat(byId.get(third)).isZero();
        assertThat(byId.get(first)).isEqualTo(1);
        assertThat(byId.get(second)).isEqualTo(2);
    }

    @Test
    @DisplayName("a rule cannot be edited while active — it is deactivated first")
    void anActiveRuleRefusesEditing() {
        UUID ruleId = createAndActivate(AutomationTriggerType.INACTIVITY, Map.of("inactivityDays", 90), 90);
        AutomationRuleRow rule = rules.require(TENANT, BRAND, ruleId);

        assertThatThrownBy(() -> rules.update(
                        TENANT,
                        BRAND,
                        ruleId,
                        rule.version(),
                        "Renamed",
                        MarketingChannel.MESSAGING_APP,
                        PURPOSE,
                        "automation_inactivity",
                        Map.of("inactivityDays", 90),
                        90))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------- helpers

    private UUID createAndActivate(AutomationTriggerType type, Map<String, Integer> config, int cooldownDays) {
        UUID id = rules.create(
                TENANT,
                BRAND,
                type + " " + UUID.randomUUID(),
                type,
                MarketingChannel.MESSAGING_APP,
                PURPOSE,
                "automation_" + type.name().toLowerCase(java.util.Locale.ROOT),
                config,
                cooldownDays,
                actorId());
        AutomationRuleRow row = rules.require(TENANT, BRAND, id);
        boolean activated = rules.activate(TENANT, BRAND, id, row.version(), author, "corr");
        assertThat(activated).as("activation must succeed for a wired channel").isTrue();
        return id;
    }

    private UUID plainRule(String suffix) {
        return rules.create(
                TENANT,
                BRAND,
                "Rule " + suffix,
                AutomationTriggerType.INACTIVITY,
                MarketingChannel.MESSAGING_APP,
                PURPOSE,
                "automation_inactivity",
                Map.of("inactivityDays", 90),
                90,
                actorId());
    }

    private List<String> runStatuses(UUID ruleId) {
        return runStore.recentByRule(TENANT, ruleId, 50).stream()
                .map(AutomationRunRow::status)
                .toList();
    }

    private void setBirthMonthDay(UUID accountId, String monthDay) {
        jdbc.sql("UPDATE marketing.customer_metrics SET birth_month_day = :monthDay "
                        + "WHERE tenant_id = :tenantId AND customer_account_id = :accountId")
                .param("monthDay", monthDay)
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .update();
    }

    /** {@code ck_customer_metrics_recency} ties the two together: null exactly together. */
    private void setDaysSinceLastOrder(UUID accountId, int days) {
        jdbc.sql("UPDATE marketing.customer_metrics SET days_since_last_order = :days, last_order_at = :lastOrderAt, "
                        + "first_order_at = COALESCE(first_order_at, :lastOrderAt), order_count = GREATEST(order_count, 1), "
                        + "completed_order_count = GREATEST(completed_order_count, 1) "
                        + "WHERE tenant_id = :tenantId AND customer_account_id = :accountId")
                .param("days", days)
                .param("lastOrderAt", OffsetDateTime.ofInstant(NOW.minusSeconds(days * 86_400L), ZoneOffset.UTC))
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .update();
    }

    private void grantConsent(UUID accountId) {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                PURPOSE,
                "TELEGRAM",
                Decision.GRANTED,
                "v1",
                Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private UUID customer(String phone, String locale, boolean verified) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', :locale, :now)
                """)
                .param("id", accountId)
                .param("tenantId", TENANT)
                .param("locale", locale)
                .param("now", OffsetDateTime.ofInstant(NOW.minusSeconds(172_800), ZoneOffset.UTC))
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
            jdbc.sql("UPDATE customer.contact_points SET verification_status = 'VERIFIED', verified_at = :now "
                            + "WHERE id = :id")
                    .param("id", contactId)
                    .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                    .update();
        }
        return accountId;
    }

    private UUID actorId() {
        return UUID.fromString(author.subject());
    }

    private void seedTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE marketing.automation_runs, marketing.automation_rules, "
                        + "marketing.marketing_sends, marketing.suppressions, "
                        + "marketing.metric_drift_observations, marketing.customer_metrics, "
                        + "marketing.engagement_policies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {
        @Override
        public void record(AuditFact fact) {
            // Not inspected by these tests.
        }
    }

    /** A stand-in for {@link AbandonedCartDirectory} — see this class's own doc. */
    private static final class FakeAbandonedCartDirectory implements AbandonedCartDirectory {
        private final List<AbandonedCart> carts = new ArrayList<>();

        void abandoned(UUID tenantId, UUID brandId, UUID cartId, UUID customerAccountId, Instant abandonedAt) {
            carts.add(new AbandonedCart(tenantId, brandId, cartId, customerAccountId, abandonedAt));
        }

        @Override
        public List<AbandonedCart> abandonedSince(Instant olderThan, int limit) {
            return carts.stream()
                    .filter(cart -> !cart.abandonedAt().isAfter(olderThan))
                    .toList();
        }
    }

    /**
     * A real {@link JdbcAutomationRunStore} whose {@link #claim} fires a
     * callback the instant it successfully reserves the guard key — modelling
     * a concurrent transaction (a checkout) committing at exactly that moment,
     * the narrowest possible window a correct re-check has to close.
     */
    private static final class ClaimTriggeredAutomationRunStore extends JdbcAutomationRunStore {
        private final Runnable onClaimed;

        ClaimTriggeredAutomationRunStore(JdbcClient jdbc, Runnable onClaimed) {
            super(jdbc);
            this.onClaimed = onClaimed;
        }

        @Override
        public boolean claim(
                UUID id,
                UUID tenantId,
                UUID brandId,
                UUID automationRuleId,
                UUID customerAccountId,
                String triggerType,
                String guardKey,
                @Nullable UUID subjectId,
                Instant now) {
            boolean claimed = super.claim(
                    id, tenantId, brandId, automationRuleId, customerAccountId, triggerType, guardKey, subjectId, now);
            if (claimed) {
                onClaimed.run();
            }
            return claimed;
        }
    }

    /** A stand-in for {@link OrderDirectory} — only {@link #recentForCustomer} is exercised. */
    private static final class FakeOrderDirectory implements OrderDirectory {
        private final Map<UUID, RecentOrder> recentByAccount = new java.util.HashMap<>();

        void recentOrderFor(UUID accountId, Instant placedAt) {
            recentByAccount.put(
                    accountId,
                    new RecentOrder(
                            UUID.randomUUID(), "0001-001", UUID.randomUUID(), "COMPLETED", "UZS", 10_000L, placedAt));
        }

        void noRecentOrderFor(UUID accountId) {
            recentByAccount.remove(accountId);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }

        @Override
        public List<RecentOrder> recentForCustomer(UUID tenantId, UUID brandId, UUID customerAccountId, int limit) {
            RecentOrder order = recentByAccount.get(customerAccountId);
            return order == null ? List.of() : List.of(order);
        }
    }
}
