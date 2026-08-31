package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.marketing.application.AudienceService;
import uz.horecaos.platform.marketing.application.CampaignCostEstimator;
import uz.horecaos.platform.marketing.application.CampaignSendService;
import uz.horecaos.platform.marketing.application.CampaignService;
import uz.horecaos.platform.marketing.application.CustomerMetricProjectionService;
import uz.horecaos.platform.marketing.application.MarketingEligibility;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy.EngagementOverride;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.MetricDefinitions;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;
import uz.horecaos.platform.marketing.domain.SuppressionReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The ADR 0044 slice against a real PostgreSQL.
 *
 * <p>What is asserted here is the set of properties the ADR says a tenant has to
 * be able to rely on: that consent is read from ADR 0015 and never re-decided,
 * that a refusal is recorded rather than dropped, that suppression outranks a
 * positive consent decision, that the unsubscribe arriving after approval wins,
 * that the cost ceiling cannot be exceeded, that a replayed batch produces no
 * second message, and that an erasure removes a person without moving a finance
 * number.
 *
 * <p>The one stand-in is {@link FakeCampaignMessagePort}. That port is the
 * boundary between this module and ADR 0020, and building a genuine notification
 * with a template version, an endpoint, and a provider binding here would drag in
 * four modules to assert nothing about marketing.
 */
class MarketingCampaignTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String PURPOSE = "MARKETING_PROMOTIONS";

    /** 14:00 in Tashkent: outside quiet hours, so nothing is deferred by accident. */
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    /** 22:30 in Tashkent: inside the closed window. */
    private static final Instant LATE_EVENING = Instant.parse("2026-08-22T17:30:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private FieldProtection protection;

    private JdbcAudienceStore audienceStore;
    private JdbcCampaignStore campaignStore;
    private JdbcEngagementStore engagementStore;
    private JdbcCustomerMetricStore metricStore;

    private CustomerProfileService profiles;
    private ConsentService consent;
    private RecordingAuditRecorder audit;

    /**
     * Null between {@link #truncate} clearing it and {@link #wire} rebuilding it,
     * so a fresh fixture starts empty for the next test. Every test-body read goes
     * through {@link #port()}, which asserts it has been wired by then.
     */
    private @Nullable FakeCampaignMessagePort port;

    private AudienceService audiences;
    private CampaignService campaigns;
    private CampaignSendService sends;
    private CustomerMetricProjectionService projection;
    private MarketingSuppressionService suppressions;

    private final ActorRef author = ActorRef.user(UUID.randomUUID().toString(), "Author");
    private final ActorRef approver = ActorRef.user(UUID.randomUUID().toString(), "Approver");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for marketing campaign tests");
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

    /**
     * Builds the module at one moment in time.
     *
     * <p>A method rather than fixed setup because quiet hours are the one behaviour
     * that can only be observed by moving the clock, and rebuilding the services is
     * cheaper and clearer than making every one of them take a mutable clock.
     */
    private void wire(Instant moment) {
        Clock clock = Clock.fixed(moment, ZoneOffset.UTC);

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(customerStore, protection, objectMapper, clock);
        consent = new ConsentService(customerStore, clock);
        RecipientContactService contacts = new RecipientContactService(customerStore, protection);

        audienceStore = new JdbcAudienceStore(jdbc, objectMapper);
        campaignStore = new JdbcCampaignStore(jdbc);
        engagementStore = new JdbcEngagementStore(jdbc);
        metricStore = new JdbcCustomerMetricStore(jdbc);

        audit = new RecordingAuditRecorder();
        if (port == null) {
            port = new FakeCampaignMessagePort()
                    .withBody("ru", "Скидка для вас, {{name}}!")
                    .withBody("uz-Latn", "Sizga chegirma, {{name}}!")
                    .withBody("en", "A discount for you, {{name}}!");
        }

        MarketingEligibility eligibility = new MarketingEligibility(consent, contacts, engagementStore);
        CampaignCostEstimator estimator = new CampaignCostEstimator();

        projection = new CustomerMetricProjectionService(metricStore, clock);
        audiences = new AudienceService(audienceStore, metricStore, engagementStore, eligibility, audit, clock);
        campaigns = new CampaignService(campaignStore, engagementStore, audiences, estimator, port, audit, clock);
        sends = new CampaignSendService(
                campaignStore, audienceStore, engagementStore, eligibility, estimator, port, clock, 100);
        suppressions = new MarketingSuppressionService(engagementStore, audit, clock);
    }

    /** The wired fake, which every {@code @BeforeEach} guarantees is set before a test body runs. */
    private FakeCampaignMessagePort port() {
        return Objects.requireNonNull(port, "wire() must run before a test reads the port");
    }

    // ------------------------------------------------------------- the model

    @Test
    @DisplayName("consent withdrawn between approval and send refuses that recipient, with a reason")
    void theUnsubscribeThatArrivesAfterApprovalWins() {
        UUID account = customer("+998901111111", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        UUID campaign = readyCampaign(1_000_000L);
        assertThat(snapshotMembers(campaign)).isEqualTo(1);

        // The decision changes after the approver signed. ADR 0015 is append-only,
        // so this is a new decision rather than an edit of the old one.
        consent.record(TENANT, account, BRAND, PURPOSE, "SMS", Decision.WITHDRAWN, "v1", Source.STOREFRONT, null, NOW);

        sends.expandNextBatch(TENANT, campaign);

        var recipients = campaignStore.recipients(TENANT, campaign, 10);
        assertThat(recipients).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("REFUSED");
            assertThat(row.refusalReason()).isEqualTo("CONSENT_WITHHELD");
            assertThat(row.notificationId()).isNull();
        });
        // Recorded, not dropped. The row is the answer to "why did this customer
        // not get it", which is the question a tenant actually asks.
        assertThat(port().sent()).isEmpty();
    }

    @Test
    @DisplayName("a suppression outranks a positive consent decision")
    void suppressionBeatsConsent() {
        UUID account = customer("+998902222222", "ru", true);
        grantConsent(account);
        suppressions.suppress(
                TENANT,
                BRAND,
                account,
                MarketingChannel.SMS,
                SuppressionReason.HARD_BOUNCE,
                MarketingSuppressionService.ACTOR_PROVIDER,
                null,
                ActorRef.service("sms-gateway"),
                "The operator reported an invalid number",
                "corr");
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        assertThat(snapshot.candidateCount()).isEqualTo(1);
        assertThat(snapshot.memberCount()).isZero();
        assertThat(exclusionReason(snapshot.snapshotId(), account)).isEqualTo("SUPPRESSED");
    }

    @Test
    @DisplayName("absence of a consent decision is not consent")
    void aMigratedCustomerWithNoDecisionIsExcluded() {
        UUID account = customer("+998903333333", "ru", true);
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        // The migrated base carries no marketing consent, because no legacy table
        // records one. The first post-cutover campaign reaching a small fraction of
        // the customers a merchant believes they have is the correct outcome.
        assertThat(snapshot.memberCount()).isZero();
        assertThat(exclusionReason(snapshot.snapshotId(), account)).isEqualTo("CONSENT_WITHHELD");
    }

    @Test
    @DisplayName("an unverified endpoint is a reason, not a silent gap")
    void anUnreachableCustomerIsRecorded() {
        UUID account = customer("+998904444444", "ru", false);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        assertThat(exclusionReason(snapshot.snapshotId(), account)).isEqualTo("NO_VERIFIED_ENDPOINT");
    }

    // ------------------------------------------------------- cost and replay

    @Test
    @DisplayName("the cost ceiling halts the send rather than trimming it to fit")
    void theCeilingHolds() {
        for (int index = 0; index < 5; index++) {
            UUID account = customer("+99890555555" + index, "ru", true);
            grantConsent(account);
        }
        projection.backfill(TENANT, BRAND);
        priceSegmentsAt(1_000L);

        // One som short of a single recipient's worst case, so the very first batch
        // cannot be reserved.
        UUID campaign = readyCampaign(1L);
        var outcome = sends.expandNextBatch(TENANT, campaign);

        assertThat(outcome.haltedAtCeiling()).isTrue();
        assertThat(campaignStore.find(TENANT, campaign)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(CampaignStatus.HALTED_BUDGET);
            assertThat(row.reservedCostMinor()).isLessThanOrEqualTo(row.costCeilingMinor());
        });
        assertThat(port().sent()).isEmpty();
    }

    @Test
    @DisplayName("a replayed batch produces no second message")
    void expansionIsIdempotent() {
        UUID account = customer("+998906666666", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        priceSegmentsAt(100L);

        UUID campaign = readyCampaign(10_000_000L);

        var first = sends.expandNextBatch(TENANT, campaign);
        assertThat(first.queued()).isEqualTo(1);

        // The same worker, or another, replaying the same sequence. The batch row's
        // primary key refuses the claim and nothing else happens.
        var replay = campaignStore.claimBatch(
                TENANT,
                campaign,
                campaignStore.find(TENANT, campaign).orElseThrow().snapshotId(),
                0,
                1,
                100L,
                NOW);
        assertThat(replay).isEqualTo(JdbcCampaignStore.BatchClaim.ALREADY_CLAIMED);

        assertThat(port().distinctMessages()).isEqualTo(1);
        assertThat(campaignStore.recipientCount(TENANT, campaign)).isEqualTo(1);
    }

    // ----------------------------------------------------------- quiet hours

    @Test
    @DisplayName("a message eligible inside quiet hours is deferred, not dropped")
    void quietHoursDefer() {
        UUID account = customer("+998907777777", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        priceSegmentsAt(100L);

        UUID campaign = readyCampaign(10_000_000L);

        // Move to 22:30 Tashkent and expand. The message is scheduled rather than
        // abandoned, and the recipient row says so.
        wire(LATE_EVENING);
        var outcome = sends.expandNextBatch(TENANT, campaign);

        assertThat(outcome.deferred()).isTrue();
        assertThat(campaignStore.recipients(TENANT, campaign, 10))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("DEFERRED");
                    assertThat(row.notificationId()).isNotNull();
                    assertThat(row.deferredUntil()).isEqualTo(Instant.parse("2026-08-23T05:00:00Z"));
                });
    }

    // --------------------------------------------------------- frequency cap

    @Test
    @DisplayName("the cap counts one SMS, one push, and one Telegram message as three")
    void theCapCountsAcrossChannels() {
        UUID account = customer("+998908888888", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        engagementStore.recordSend(
                TENANT, BRAND, account, "SMS", "CAMPAIGN", UUID.randomUUID(), null, NOW.minusSeconds(3600));
        engagementStore.recordSend(
                TENANT, BRAND, account, "PUSH", "TRIGGER", UUID.randomUUID(), null, NOW.minusSeconds(7200));
        engagementStore.recordSend(
                TENANT, BRAND, account, "MESSAGING_APP", "CAMPAIGN", UUID.randomUUID(), null, NOW.minusSeconds(10800));

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        // Three against a cap of three. The customer experiences one brand rather
        // than three transports, and enabling another channel substitutes for reach
        // rather than adding to it.
        assertThat(exclusionReason(snapshot.snapshotId(), account)).isEqualTo("FREQUENCY_CAP_REACHED");
    }

    // ---------------------------------------------------------- the boundary

    @Test
    @DisplayName("a tenant override may tighten the cap and is refused when it loosens")
    void overridesTightenOnly() {
        engagementStore.saveOverride(TENANT, BRAND, new EngagementOverride(null, null, null, 1, 4, 500L, "UZS"), NOW);

        var policy = engagementStore.resolvePolicy(TENANT, BRAND);
        assertThat(policy.messagesPer7Days()).isEqualTo(1);
        assertThat(policy.smsPricePerSegmentMinor()).isEqualTo(500L);

        // The database refuses the loosening even if a caller bypasses the service,
        // because the limit protects a sender identity shared across tenants.
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE marketing.engagement_policies SET marketing_messages_per_7d = 20
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                        .param("tenantId", TENANT)
                        .param("brandId", BRAND)
                        .update())
                .hasMessageContaining("ck_engagement_cap_tighten_only");
    }

    @Test
    @DisplayName("another tenant cannot read this tenant's audiences, snapshots, or recipients")
    void crossTenantReadsFail() {
        UUID account = customer("+998909999999", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        assertThat(audienceStore.findAudience(OTHER_TENANT, audience)).isEmpty();
        assertThat(audienceStore.findSnapshot(OTHER_TENANT, snapshot.snapshotId()))
                .isEmpty();
        assertThat(audienceStore.includedMembersAfter(OTHER_TENANT, snapshot.snapshotId(), null, 100))
                .isEmpty();
    }

    // ------------------------------------------------- retention and erasure

    @Test
    @DisplayName("snapshot membership is purged while the header and its counts survive")
    void retentionKeepsTheEvidenceAndDropsTheList() {
        UUID account = customer("+998900000001", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var snapshot = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        audienceStore.purgeMembers(TENANT, snapshot.snapshotId(), NOW);

        assertThat(audienceStore.includedMembersAfter(TENANT, snapshot.snapshotId(), null, 100))
                .isEmpty();
        // Somebody will eventually want the list, and the answer will be that it
        // was deliberately not kept. The counts and the predicate version stay.
        assertThat(audienceStore.findSnapshot(TENANT, snapshot.snapshotId())).hasValueSatisfying(header -> {
            assertThat(header.memberCount()).isEqualTo(1);
            assertThat(header.candidateCount()).isEqualTo(1);
            assertThat(header.membersPurgedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("an erasure removes the person and leaves the campaign's numbers alone")
    void erasureDoesNotRewriteFinance() {
        UUID account = customer("+998900000002", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        priceSegmentsAt(100L);

        UUID campaign = readyCampaign(10_000_000L);
        sends.expandNextBatch(TENANT, campaign);

        long spentBefore = campaignStore.find(TENANT, campaign).orElseThrow().spentCostMinor();
        int recipientsBefore = campaignStore.recipientCount(TENANT, campaign);

        audienceStore.eraseMembership(TENANT, account);
        projection.erase(TENANT, account);

        assertThat(metricStore.find(TENANT, BRAND, account)).isEmpty();
        // An aggregate that no longer identifies anyone is not erased. Reversing a
        // finance number to honour a privacy request is a different kind of wrong.
        assertThat(campaignStore.find(TENANT, campaign).orElseThrow().spentCostMinor())
                .isEqualTo(spentBefore);
        assertThat(campaignStore.recipientCount(TENANT, campaign)).isEqualTo(recipientsBefore);
    }

    @Test
    @DisplayName("an erasure clears the customer from every snapshot and nobody else from any")
    void erasureReachesEverySnapshotWithoutTouchingAnotherCustomer() {
        UUID erased = customer("+998900000004", "ru", true);
        UUID kept = customer("+998900000005", "ru", true);
        grantConsent(erased);
        grantConsent(kept);
        projection.backfill(TENANT, BRAND);

        UUID audience = everybodyRegistered();
        var first = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");
        var second = audiences.buildSnapshot(TENANT, BRAND, audience, MarketingChannel.SMS, PURPOSE, author, "corr");

        // Two snapshots and two customers is the fixture that separates the delete
        // that scans the member table from the one driven through the snapshot
        // header: a join that fanned out would delete twice, and a join that lost
        // rows would leave the second snapshot's membership behind.
        assertThat(memberRows(erased)).isEqualTo(2);

        assertThat(audienceStore.eraseMembership(TENANT, erased)).isEqualTo(2);

        assertThat(memberRows(erased)).isZero();
        assertThat(memberRows(kept))
                .as("the erasure names one person, and the snapshots are shared")
                .isEqualTo(2);
        assertThat(audienceStore.findSnapshot(TENANT, first.snapshotId())).isPresent();
        assertThat(audienceStore.findSnapshot(TENANT, second.snapshotId())).isPresent();
    }

    @Test
    @DisplayName("one sweep statement writes what observe-then-recompute wrote")
    void theSingleStatementSweepAgreesWithTheTwoItReplaces() {
        UUID account = customer("+998900000006", "ru", true);
        projection.backfill(TENANT, BRAND);

        corruptProjection(account);
        int driftedInTwoStatements = metricStore.observeDrift(TENANT, BRAND, NOW, MetricDefinitions.CURRENT_VERSION);
        int rowsInTwoStatements = metricStore.recompute(TENANT, BRAND, NOW, MetricDefinitions.CURRENT_VERSION);
        List<String> driftFromTwoStatements = driftSignature();
        var rowFromTwoStatements = metricStore.find(TENANT, BRAND, account).orElseThrow();

        jdbc.sql("TRUNCATE TABLE marketing.metric_drift_observations").update();
        corruptProjection(account);
        var counts = metricStore.sweep(TENANT, BRAND, NOW, MetricDefinitions.CURRENT_VERSION);

        // The order stops mattering: PostgreSQL gives both halves of a
        // data-modifying WITH the same snapshot, so the drift half compares against
        // the projection as it stood before the rebuild whichever half runs first.
        assertThat(counts.driftObservations()).isEqualTo(driftedInTwoStatements);
        assertThat(counts.rowsRecomputed()).isEqualTo(rowsInTwoStatements);
        assertThat(driftSignature()).isEqualTo(driftFromTwoStatements);
        assertThat(metricStore.find(TENANT, BRAND, account)).hasValue(rowFromTwoStatements);
    }

    @Test
    @DisplayName("the sweep reports projection drift rather than rewriting it")
    void driftIsReported() {
        UUID account = customer("+998900000003", "ru", true);
        projection.backfill(TENANT, BRAND);

        // Corrupt the projection the way a bad incremental fold would.
        jdbc.sql("""
                UPDATE marketing.customer_metrics
                   SET order_count = 7, completed_order_count = 7, net_spend_minor = 99
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """).param("tenantId", TENANT).param("accountId", account).update();

        var result = projection.sweep(TENANT, BRAND);

        assertThat(result.driftObservations()).isGreaterThanOrEqualTo(2);
        assertThat(projection.drift(TENANT, BRAND))
                .extracting(JdbcCustomerMetricStore.DriftRow::metricName)
                .contains("completed_order_count", "net_spend_minor");
        // The row is a bug report about the projection. The recompute that follows
        // it fixes the number; the observation is what stops the fix from hiding
        // the fault.
        assertThat(metricStore.find(TENANT, BRAND, account))
                .hasValueSatisfying(row -> assertThat(row.completedOrderCount()).isZero());
    }

    @Test
    @DisplayName("a campaign refuses to expand when no delivery path is wired")
    void anUnwiredPortIsRefusedUpFront() {
        UUID account = customer("+998900000004", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);

        UUID campaign = readyCampaign(10_000_000L);
        port().unwire();

        // Discovered before anything is claimed. A campaign that expands forty
        // thousand recipients against an unwired path has spent an approval and
        // produced nothing.
        assertThatThrownBy(() -> sends.expandNextBatch(TENANT, campaign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery path");
    }

    @Test
    @DisplayName("the approver may not be the author")
    void fourEyesHolds() {
        UUID account = customer("+998900000005", "ru", true);
        grantConsent(account);
        projection.backfill(TENANT, BRAND);
        priceSegmentsAt(100L);

        UUID campaign = draftCampaign(10_000_000L);
        campaigns.prepare(TENANT, campaign, author, "corr");
        campaigns.submitForReview(TENANT, campaign);

        boolean selfApproved = campaigns.approve(
                TENANT,
                campaign,
                UUID.fromString(author.subject()),
                UUID.randomUUID(),
                author,
                "Approving my own work",
                "corr");

        assertThat(selfApproved).isFalse();
        assertThat(campaignStore.find(TENANT, campaign).orElseThrow().status()).isEqualTo(CampaignStatus.IN_REVIEW);

        assertThat(campaigns.approve(
                        TENANT,
                        campaign,
                        UUID.fromString(approver.subject()),
                        UUID.randomUUID(),
                        approver,
                        "Reviewed the copy and the reach",
                        "corr"))
                .isTrue();
    }

    // ------------------------------------------------------------- fixtures

    private UUID readyCampaign(long ceilingMinor) {
        UUID campaign = draftCampaign(ceilingMinor);
        campaigns.prepare(TENANT, campaign, author, "corr");
        campaigns.submitForReview(TENANT, campaign);
        campaigns.approve(
                TENANT,
                campaign,
                UUID.fromString(approver.subject()),
                UUID.randomUUID(),
                approver,
                "Reviewed the copy and the reach",
                "corr");
        campaigns.start(TENANT, campaign);
        return campaign;
    }

    private UUID draftCampaign(long ceilingMinor) {
        UUID audience = everybodyRegistered();
        return campaigns.create(
                TENANT,
                BRAND,
                "Autumn promotion " + UUID.randomUUID(),
                MarketingChannel.SMS,
                PURPOSE,
                audience,
                "MARKETING_PROMOTION",
                100,
                ceilingMinor,
                "UZS",
                null,
                null,
                UUID.fromString(author.subject()));
    }

    /**
     * Everybody with a brand profile, expressed as a predicate rather than as an
     * empty definition.
     *
     * <p>{@code ORDER_COUNT AT_MOST} a large number is a real segment — the whole
     * base — and it has to be asked for. An audience with no predicates is refused
     * precisely so nobody gets it by leaving a form empty.
     */
    private UUID everybodyRegistered() {
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

    private int snapshotMembers(UUID campaignId) {
        UUID snapshotId = campaignStore.find(TENANT, campaignId).orElseThrow().snapshotId();
        return audienceStore.findSnapshot(TENANT, snapshotId).orElseThrow().memberCount();
    }

    private String exclusionReason(UUID snapshotId, UUID accountId) {
        return jdbc.sql("""
                SELECT exclusion_reason FROM marketing.audience_snapshot_members
                 WHERE snapshot_id = :snapshotId AND customer_account_id = :accountId
                """)
                .param("snapshotId", snapshotId)
                .param("accountId", accountId)
                .query(String.class)
                .single();
    }

    private int memberRows(UUID accountId) {
        return jdbc.sql("""
                SELECT count(*) FROM marketing.audience_snapshot_members
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """)
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
    }

    /** Corrupts the projection the way a bad incremental fold would. */
    private void corruptProjection(UUID accountId) {
        jdbc.sql("""
                UPDATE marketing.customer_metrics
                   SET order_count = 7, completed_order_count = 7, net_spend_minor = 99
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """).param("tenantId", TENANT).param("accountId", accountId).update();
    }

    /** The drift rows as values, so two runs can be compared without their ids. */
    private List<String> driftSignature() {
        return projection.drift(TENANT, BRAND).stream()
                .map(row -> "%s=%s/%s".formatted(row.metricName(), row.projectedValue(), row.recomputedValue()))
                .sorted()
                .toList();
    }

    private void priceSegmentsAt(long minorPerSegment) {
        engagementStore.saveOverride(
                TENANT, BRAND, new EngagementOverride(null, null, null, null, null, minorPerSegment, "UZS"), NOW);
    }

    private void grantConsent(UUID accountId) {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                PURPOSE,
                "SMS",
                Decision.GRANTED,
                "v1",
                Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private UUID customer(String phone, String locale, boolean verified) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale,
                    created_at)
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
            jdbc.sql("""
                    UPDATE customer.contact_points
                       SET verification_status = 'VERIFIED', verified_at = :now
                     WHERE id = :id
                    """)
                    .param("id", contactId)
                    .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                    .update();
        }
        return accountId;
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
        jdbc.sql("TRUNCATE TABLE marketing.campaign_recipients, marketing.campaign_batches, "
                        + "marketing.campaigns, marketing.audience_snapshot_members, "
                        + "marketing.audience_snapshots, marketing.audience_predicates, "
                        + "marketing.audiences, marketing.marketing_sends, marketing.suppressions, "
                        + "marketing.metric_drift_observations, marketing.customer_metrics, "
                        + "marketing.engagement_policies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        port = null;
    }

    /**
     * A no-op stand-in for the ADR 0027 audit trail.
     *
     * <p>These tests assert on database state and API/domain behaviour rather than
     * on what was written to the audit trail, so nothing here needs to be kept.
     */
    private static final class RecordingAuditRecorder implements AuditRecorder {

        @Override
        public void record(AuditFact fact) {
            // Not inspected by these tests.
        }
    }
}
