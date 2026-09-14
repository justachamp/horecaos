package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.marketing.application.AttributionLinkService;
import uz.horecaos.platform.marketing.application.CourierBroadcastService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore.AttributionLinkRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * T18, operations §6.4b (courier broadcasts) and §6.6a (attribution links) —
 * two shapes this wave built from nothing: neither table nor service existed
 * before V0307/V0309.
 *
 * <p>Against a real PostgreSQL, the same reason every other marketing suite
 * here gives: a target's own resolved recipient count and a token's own
 * uniqueness are properties of the schema, not properties {@code Mockito}
 * could stand in for.
 */
class CourierBroadcastAndAttributionLinkTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private CourierBroadcastService broadcasts;
    private AttributionLinkService links;
    private FakeCampaignMessagePort port;
    private RecordingAuditRecorder audit;

    private UUID courierType;
    private UUID groupId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for marketing tests");
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
        jdbc.sql("TRUNCATE TABLE marketing.courier_broadcasts, marketing.attribution_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.campaigns, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_groups CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();

        port = new FakeCampaignMessagePort();
        audit = new RecordingAuditRecorder();
        broadcasts = new CourierBroadcastService(new JdbcCourierBroadcastStore(jdbc), port, audit, CLOCK);
        links = new AttributionLinkService(
                new JdbcAttributionLinkStore(jdbc), new JdbcCampaignStore(jdbc), audit, CLOCK);
    }

    // ------------------------------------------------------------ courier broadcasts

    @Test
    @DisplayName("ALL_ACTIVE resolves to every ACTIVE courier of the tenant, and archived ones are excluded")
    void allActiveResolvesOnlyActiveCouriers() {
        insertCourier("K-001", "ACTIVE");
        insertCourier("K-002", "ACTIVE");
        insertCourier("K-003", "ARCHIVED");
        UUID broadcastId = broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", null, "Shift change at 18:00", AUTHOR);
        CourierBroadcastRow sent = broadcasts.send(TENANT, broadcastId, AUTHOR);

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.recipientCount()).isEqualTo(2);
        assertThat(sent.sentAt()).isNotNull();
    }

    @Test
    @DisplayName("a GROUP broadcast counts only that group's active members")
    void groupBroadcastCountsOnlyGroupMembers() {
        UUID inGroup1 = insertCourier("K-010", "ACTIVE");
        UUID inGroup2 = insertCourier("K-011", "ACTIVE");
        UUID notInGroup = insertCourier("K-012", "ACTIVE");
        addToGroup(inGroup1);
        addToGroup(inGroup2);

        UUID broadcastId = broadcasts.draft(TENANT, BRAND, "GROUP", groupId, "Route closure on Amir Temur", AUTHOR);
        CourierBroadcastRow sent = broadcasts.send(TENANT, broadcastId, AUTHOR);

        assertThat(sent.recipientCount())
                .as("K-012 is active but not in the targeted group, so it is not counted")
                .isEqualTo(2);
        assertThat(notInGroup).isNotNull();
    }

    @Test
    @DisplayName("a GROUP broadcast must name a targetGroupId, and ALL_ACTIVE must not")
    void targetKindShapeIsEnforced() {
        assertThatThrownBy(() -> broadcasts.draft(TENANT, BRAND, "GROUP", null, "x", AUTHOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", UUID.randomUUID(), "x", AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName(
            "send is refused, visibly, when SMS has no wired delivery path — the same isWired honesty campaigns get")
    void sendRefusesWhenSmsIsNotWired() {
        // FakeCampaignMessagePort answers isWired the same for every channel
        // name; unwire() flips that shared answer to false, standing in for
        // production's own SMS — notifications implements isWired for
        // TELEGRAM only, and SMS has no adapter at all (row 6.4a).
        UUID broadcastId = broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", null, "Weather closure", AUTHOR);
        port.unwire();

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);

        CourierBroadcastRow row = broadcasts.require(TENANT, broadcastId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.refusalReason()).contains("SMS");
    }

    @Test
    @DisplayName("a message over 480 characters is refused at draft time")
    void overlongMessageIsRefused() {
        String tooLong = "x".repeat(481);
        assertThatThrownBy(() -> broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", null, tooLong, AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("drafting and sending a broadcast each write an ADR 0027 audit fact in the same transaction")
    void draftAndSendAreAudited() {
        insertCourier("K-020", "ACTIVE");
        UUID broadcastId = broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", null, "Shift change", AUTHOR);
        broadcasts.send(TENANT, broadcastId, AUTHOR);

        assertThat(audit.facts)
                .as("a dispatcher's own courier SMS blast is an operator-initiated action "
                        + "ADR 0027 requires evidence for")
                .extracting(AuditFact::actionCode)
                .contains("MARKETING_COURIER_BROADCAST_DRAFTED", "MARKETING_COURIER_BROADCAST_SENT");
    }

    // ------------------------------------------------------------ attribution links

    @Test
    @DisplayName("minting a link produces a unique, URL-safe token and a listable row")
    void mintingProducesAUniqueToken() {
        UUID id = links.mint(
                TENANT,
                BRAND,
                "Summer campaign",
                "For the Instagram post",
                "WEB",
                "STOREFRONT_HOME",
                null,
                null,
                AUTHOR);

        AttributionLinkRow link = links.require(TENANT, id);
        assertThat(link.token()).matches("^[A-Za-z0-9]{10}$");
        assertThat(link.status()).isEqualTo("ACTIVE");
        assertThat(link.clickCount()).isZero();

        List<AttributionLinkRow> listed = links.list(TENANT, BRAND);
        assertThat(listed).extracting(AttributionLinkRow::id).containsExactly(id);
    }

    @Test
    @DisplayName("a CAMPAIGN destination must name a destinationId; STOREFRONT_HOME and INFLUENCER must not")
    void destinationShapeIsEnforced() {
        assertThatThrownBy(() -> links.mint(TENANT, BRAND, "x", null, "WEB", "CAMPAIGN", null, null, AUTHOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        links.mint(TENANT, BRAND, "x", null, "WEB", "STOREFRONT_HOME", UUID.randomUUID(), null, AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an unknown channel or destination type is refused")
    void unknownChannelIsRefused() {
        assertThatThrownBy(() -> links.mint(TENANT, BRAND, "x", null, "EMAIL", "STOREFRONT_HOME", null, null, AUTHOR))
                .as("EMAIL is not in ADR 0044's own closed list (WEB, TELEGRAM_BOT, TELEGRAM_MINI_APP, MOBILE_APP)")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a CAMPAIGN destination naming a sibling brand's campaign is refused, not minted")
    void aCrossBrandCampaignDestinationIsRefused() {
        UUID otherBrandCampaign = insertCampaign(OTHER_BRAND, "Other brand's campaign");

        assertThatThrownBy(
                        () -> links.mint(TENANT, BRAND, "x", null, "WEB", "CAMPAIGN", otherBrandCampaign, null, AUTHOR))
                .as("V0309's own FK only constrains tenant_id, not brand_id — the service is the only guard")
                .isInstanceOf(ApiException.class);
        assertThat(links.list(TENANT, BRAND))
                .as("nothing was minted for the refusal to leave behind")
                .isEmpty();
    }

    @Test
    @DisplayName("a CAMPAIGN destination naming this brand's own campaign is accepted")
    void aSameBrandCampaignDestinationIsAccepted() {
        UUID ownCampaign = insertCampaign(BRAND, "This brand's campaign");

        UUID id = links.mint(TENANT, BRAND, "x", null, "WEB", "CAMPAIGN", ownCampaign, null, AUTHOR);

        assertThat(links.require(TENANT, id).destinationId()).isEqualTo(ownCampaign);
    }

    @Test
    @DisplayName("a CAMPAIGN destination naming no real campaign at all is refused the same way")
    void aNonExistentCampaignDestinationIsRefused() {
        assertThatThrownBy(
                        () -> links.mint(TENANT, BRAND, "x", null, "WEB", "CAMPAIGN", UUID.randomUUID(), null, AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("minting and archiving a link each write an ADR 0027 audit fact in the same transaction")
    void mintAndArchiveAreAudited() {
        UUID id = links.mint(TENANT, BRAND, "x", null, "WEB", "STOREFRONT_HOME", null, null, AUTHOR);
        links.archive(TENANT, id, AUTHOR);

        assertThat(audit.facts)
                .as("a marketer minting or archiving a trackable link is an operator-initiated "
                        + "action ADR 0027 requires evidence for")
                .extracting(AuditFact::actionCode)
                .contains("MARKETING_LINK_MINTED", "MARKETING_LINK_ARCHIVED");
        AuditFact minted = audit.facts.stream()
                .filter(fact -> fact.actionCode().equals("MARKETING_LINK_MINTED"))
                .findFirst()
                .orElseThrow();
        assertThat(minted.targetId()).isEqualTo(id);
        assertThat(minted.scope().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("archiving a link stops it from listing as ACTIVE, and archiving it twice is refused")
    void archiveIsOnceOnly() {
        UUID id = links.mint(TENANT, BRAND, "Influencer link", null, "TELEGRAM_BOT", "INFLUENCER", null, null, AUTHOR);

        links.archive(TENANT, id, AUTHOR);
        assertThat(links.require(TENANT, id).status()).isEqualTo("ARCHIVED");

        assertThatThrownBy(() -> links.archive(TENANT, id, AUTHOR)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("recording a click increments the count and nothing else")
    void recordClickIncrementsTheCount() {
        UUID id = links.mint(TENANT, BRAND, "x", null, "WEB", "STOREFRONT_HOME", null, null, AUTHOR);

        links.recordClick(TENANT, id);
        links.recordClick(TENANT, id);

        assertThat(links.require(TENANT, id).clickCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------- fixtures

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'courier-broadcast-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'MAIN', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'SECOND', 'second', 'SECOND', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();

        courierType = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'BIKE', 'Bicycle', 'BICYCLE')
                """).param("id", courierType).param("tenantId", TENANT).update();

        groupId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_groups (id, tenant_id, code, display_name, status, version)
                VALUES (:id, :tenantId, 'NIGHT', 'Night shift', 'ACTIVE', 1)
                """).param("id", groupId).param("tenantId", TENANT).update();
    }

    private UUID insertCourier(String reference, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id, principal_subject,
                    display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'encrypted-placeholder', :status, 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("typeId", courierType)
                .param("subject", UUID.randomUUID().toString())
                .param("reference", reference)
                .param("status", status)
                .update();
        return id;
    }

    private void addToGroup(UUID courierId) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_group_members (tenant_id, group_id, courier_id, added_at, added_by)
                VALUES (:tenantId, :groupId, :courierId, now(), 'test-fixture')
                """)
                .param("tenantId", TENANT)
                .param("groupId", groupId)
                .param("courierId", courierId)
                .update();
    }

    /**
     * A minimal, real campaign row — just enough to satisfy
     * {@code marketing.campaigns}' NOT NULL columns and its audience FK —
     * for the CAMPAIGN-destination brand-ownership check under test.
     */
    private UUID insertCampaign(UUID brandId, String name) {
        UUID audienceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :tenantId, :brandId, :name, :createdBy)
                """)
                .param("id", audienceId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("name", name + " audience")
                .param("createdBy", AUTHOR)
                .update();

        UUID campaignId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.campaigns (id, tenant_id, brand_id, name, channel, consent_purpose,
                    audience_id, template_key, recipient_cap, created_by)
                VALUES (:id, :tenantId, :brandId, :name, 'SMS', 'MARKETING', :audienceId, 'tmpl', 100, :createdBy)
                """)
                .param("id", campaignId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("name", name)
                .param("audienceId", audienceId)
                .param("createdBy", AUTHOR)
                .update();
        return campaignId;
    }

    /** Captures every fact recorded, so a test can assert on what ADR 0027 requires. */
    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
