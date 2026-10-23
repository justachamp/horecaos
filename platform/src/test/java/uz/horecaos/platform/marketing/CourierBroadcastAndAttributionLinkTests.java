package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
import uz.horecaos.platform.marketing.api.CampaignMessagePort.DeliveryEvidence;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.application.AttributionLinkService;
import uz.horecaos.platform.marketing.application.CourierBroadcastLedger;
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
    private FakeCourierContacts contacts;
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
        jdbc.sql(
                        "TRUNCATE TABLE marketing.courier_broadcast_recipients, marketing.courier_broadcasts, marketing.attribution_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.campaigns, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_groups CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();

        port = new FakeCampaignMessagePort();
        audit = new RecordingAuditRecorder();
        contacts = new FakeCourierContacts();
        port.withCourierTemplate("COURIER_SHIFT_CHANGE", List.of("note", "time"), false);
        JdbcCourierBroadcastStore store = new JdbcCourierBroadcastStore(jdbc);
        broadcasts = new CourierBroadcastService(
                store,
                port,
                contacts,
                new CourierBroadcastLedger(store, port, audit, CLOCK),
                audit,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                CLOCK);
        links = new AttributionLinkService(
                new JdbcAttributionLinkStore(jdbc), new JdbcCampaignStore(jdbc), audit, CLOCK);
    }

    // ------------------------------------------------------------ courier broadcasts

    private UUID draftToAll() {
        return broadcasts.draft(
                TENANT,
                BRAND,
                "ALL_ACTIVE",
                null,
                "COURIER_SHIFT_CHANGE",
                Map.of("note", "Shift change", "time", "18:00"),
                AUTHOR);
    }

    @Test
    @DisplayName("ALL_ACTIVE addresses every ACTIVE courier of the tenant, one message each, archived ones excluded")
    void allActiveAddressesOnlyActiveCouriers() {
        UUID first = insertCourier("K-001", "ACTIVE");
        UUID second = insertCourier("K-002", "ACTIVE");
        insertCourier("K-003", "ARCHIVED");

        CourierBroadcastRow sent = broadcasts.send(TENANT, draftToAll(), AUTHOR);

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.recipientCount()).isEqualTo(2);
        assertThat(sent.sentAt()).isNotNull();
        assertThat(port.courierSent())
                .extracting(message -> message.courierId())
                .containsExactlyInAnyOrder(first, second);
        assertThat(port.courierSent()).allSatisfy(message -> {
            assertThat(message.templateKey()).isEqualTo("COURIER_SHIFT_CHANGE");
            assertThat(message.variables())
                    .containsEntry("note", "Shift change")
                    .containsEntry("time", "18:00");
            assertThat(message.idempotencyKey())
                    .isEqualTo("courier-broadcast:" + sent.id() + ":" + message.courierId());
        });
        assertThat(broadcasts.results(TENANT, sent.id()))
                .extracting(CourierBroadcastService.CourierResult::courierReference)
                .containsExactly("K-001", "K-002");
    }

    @Test
    @DisplayName("a GROUP broadcast addresses only that group's active members")
    void groupBroadcastAddressesOnlyGroupMembers() {
        UUID inGroup1 = insertCourier("K-010", "ACTIVE");
        UUID inGroup2 = insertCourier("K-011", "ACTIVE");
        UUID notInGroup = insertCourier("K-012", "ACTIVE");
        addToGroup(inGroup1);
        addToGroup(inGroup2);

        UUID broadcastId = broadcasts.draft(
                TENANT,
                BRAND,
                "GROUP",
                groupId,
                "COURIER_SHIFT_CHANGE",
                Map.of("note", "Route closure", "time", "now"),
                AUTHOR);
        CourierBroadcastRow sent = broadcasts.send(TENANT, broadcastId, AUTHOR);

        assertThat(sent.recipientCount())
                .as("K-012 is active but not in the targeted group, so it is not addressed")
                .isEqualTo(2);
        assertThat(port.courierSent())
                .extracting(message -> message.courierId())
                .doesNotContain(notInGroup);
    }

    @Test
    @DisplayName("a courier with no number is in the ledger as NO_CONTACT and is never messaged")
    void aCourierWithNoNumberIsLedgeredNotMessaged() {
        UUID numbered = insertCourier("K-020", "ACTIVE");
        UUID unnumbered = insertCourier("K-021", "ACTIVE");
        contacts.withNumber = Set.of(numbered);

        CourierBroadcastRow sent = broadcasts.send(TENANT, draftToAll(), AUTHOR);

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.recipientCount())
                .as("the count is the couriers who were messaged, not the couriers who were targeted")
                .isEqualTo(1);
        assertThat(port.courierSent())
                .extracting(message -> message.courierId())
                .containsExactly(numbered);
        assertThat(port.courierSent())
                .extracting(message -> message.courierId())
                .doesNotContain(unnumbered);
        assertThat(broadcasts.results(TENANT, sent.id()))
                .extracting(
                        CourierBroadcastService.CourierResult::courierReference,
                        CourierBroadcastService.CourierResult::state,
                        CourierBroadcastService.CourierResult::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("K-020", "PENDING", null),
                        org.assertj.core.groups.Tuple.tuple("K-021", "NO_CONTACT", "COURIER_HAS_NO_NUMBER"));
        assertThat(contacts.askedFor)
                .as("the identity provider is asked once, for a purpose, and for every targeted courier together")
                .containsExactly("COURIER_BROADCAST_SEND/2");
    }

    @Test
    @DisplayName("what a gateway said about each courier's message is read from its evidence, not copied")
    void theResultIsTheMessagesOwnEvidence() {
        UUID courier = insertCourier("K-022", "ACTIVE");
        CourierBroadcastRow sent = broadcasts.send(TENANT, draftToAll(), AUTHOR);
        UUID notificationId = port.courierNotificationFor("courier-broadcast:" + sent.id() + ":" + courier);

        port.withEvidence(notificationId, new DeliveryEvidence("DELIVERED", null, 1, null));
        assertThat(broadcasts.results(TENANT, sent.id()).getFirst().state()).isEqualTo("DELIVERED");

        port.withEvidence(notificationId, new DeliveryEvidence("FAILED", null, 1, "RECEIVER_UNREACHABLE"));
        var result = broadcasts.results(TENANT, sent.id()).getFirst();
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.reason()).isEqualTo("RECEIVER_UNREACHABLE");
    }

    @Test
    @DisplayName("nobody having a number fails the broadcast with that reason, and every courier is in the ledger")
    void nobodyHasANumber() {
        insertCourier("K-030", "ACTIVE");
        insertCourier("K-031", "ACTIVE");
        contacts.withNumber = Set.of();

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, draftToAll(), AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        CourierBroadcastRow row = broadcasts.list(TENANT, BRAND).getFirst();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.refusalReason()).contains(CourierBroadcastService.NO_COURIER_HAS_A_NUMBER);
        assertThat(row.recipientCount()).isZero();
        assertThat(port.courierSent()).isEmpty();
        assertThat(broadcasts.results(TENANT, row.id()))
                .extracting(CourierBroadcastService.CourierResult::state)
                .containsExactly("NO_CONTACT", "NO_CONTACT");
    }

    @Test
    @DisplayName(
            "an identity provider that cannot be asked fails the send visibly and leaves a draft, not a fleet with no numbers")
    void anIdentityOutageIsNotNoContact() {
        insertCourier("K-040", "ACTIVE");
        UUID broadcastId = draftToAll();
        contacts.unavailable = true;

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
        assertThat(failure.properties()).containsEntry("reason", CourierBroadcastService.CONTACTS_UNAVAILABLE);
        CourierBroadcastRow row = broadcasts.require(TENANT, broadcastId);
        assertThat(row.status())
                .as("a draft the dispatcher can send again when the server is back")
                .isEqualTo("DRAFT");
        assertThat(port.courierSent()).isEmpty();
        assertThat(broadcasts.results(TENANT, broadcastId)).isEmpty();

        contacts.unavailable = false;
        assertThat(broadcasts.send(TENANT, broadcastId, AUTHOR).status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a GROUP broadcast must name a targetGroupId, and ALL_ACTIVE must not")
    void targetKindShapeIsEnforced() {
        Map<String, String> values = Map.of("note", "x", "time", "y");
        assertThatThrownBy(() -> broadcasts.draft(TENANT, BRAND, "GROUP", null, "COURIER_SHIFT_CHANGE", values, AUTHOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT, BRAND, "ALL_ACTIVE", UUID.randomUUID(), "COURIER_SHIFT_CHANGE", values, AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a draft names an approved wording and supplies exactly its variables, each short and on one line")
    void aDraftIsCheckedAgainstTheWording() {
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT,
                        BRAND,
                        "ALL_ACTIVE",
                        null,
                        "A_MARKETING_WORDING",
                        Map.of("note", "x", "time", "y"),
                        AUTHOR))
                .as("only a wording the tenant authored for couriers, and the gateway approved")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No active courier wording");
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT, BRAND, "ALL_ACTIVE", null, "COURIER_SHIFT_CHANGE", Map.of("note", "x"), AUTHOR))
                .as("a variable the wording declares has no value")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("\"time\"");
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT,
                        BRAND,
                        "ALL_ACTIVE",
                        null,
                        "COURIER_SHIFT_CHANGE",
                        Map.of("note", "x", "time", "y", "extra", "z"),
                        AUTHOR))
                .as("a value for a variable the wording does not have could say something it does not")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("\"extra\"");
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT,
                        BRAND,
                        "ALL_ACTIVE",
                        null,
                        "COURIER_SHIFT_CHANGE",
                        Map.of("note", "x".repeat(81), "time", "y"),
                        AUTHOR))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("80");
        assertThatThrownBy(() -> broadcasts.draft(
                        TENANT,
                        BRAND,
                        "ALL_ACTIVE",
                        null,
                        "COURIER_SHIFT_CHANGE",
                        Map.of("note", "line one\nline two", "time", "y"),
                        AUTHOR))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("single line");
        assertThatThrownBy(() -> broadcasts.draft(TENANT, BRAND, "ALL_ACTIVE", null, " ", Map.of(), AUTHOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName(
            "send is refused, visibly, when SMS has no wired delivery path — the same isWired honesty campaigns get")
    void sendRefusesWhenSmsIsNotWired() {
        insertCourier("K-050", "ACTIVE");
        UUID broadcastId = draftToAll();
        port.unwire();

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);

        CourierBroadcastRow row = broadcasts.require(TENANT, broadcastId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.refusalReason()).contains("SMS").contains("NO_PROVIDER_BINDING");
        assertThat(contacts.askedFor)
                .as("the identity provider is not asked for a send that cannot leave")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "an account not cleared for courier traffic refuses the send with the stable code, and the courier purpose is what was asked")
    void sendRefusesWhenTheAccountIsNotClearedForCouriers() {
        insertCourier("K-051", "ACTIVE");
        UUID broadcastId = draftToAll();
        port.refusingPurpose("COURIER");

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(broadcasts.require(TENANT, broadcastId).refusalReason()).contains("SMS_PURPOSE_NOT_PERMITTED");
        assertThat(port.wiringAsked())
                .as("a courier broadcast reads the courier purpose, not the marketing one")
                .contains("SMS/COURIER")
                .doesNotContain("SMS/MARKETING");
    }

    @Test
    @DisplayName(
            "a cleared account still cannot message couriers until the owner says their number may be used, and the broadcast is not recorded as sent")
    void sendRefusesWhenCourierContactsAreNotWired() {
        insertCourier("K-052", "ACTIVE");
        UUID broadcastId = draftToAll();
        contacts.wired = false;

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        CourierBroadcastRow row = broadcasts.require(TENANT, broadcastId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.refusalReason()).contains(CourierBroadcastService.COURIERS_NOT_ADDRESSABLE);
        assertThat(row.recipientCount()).isZero();
        assertThat(port.courierSent()).isEmpty();
        assertThat(contacts.askedFor)
                .as("and nothing is read from the identity provider while the answer is no")
                .isEmpty();
    }

    @Test
    @DisplayName("a wording still waiting for the SMS gateway's approval is refused up front (ADR 0091)")
    void sendRefusesAWordingTheGatewayHasNotApproved() {
        insertCourier("K-053", "ACTIVE");
        port.withCourierTemplate("COURIER_SHIFT_CHANGE", List.of("note", "time"), true);
        UUID broadcastId = draftToAll();

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(broadcasts.require(TENANT, broadcastId).refusalReason())
                .contains(CourierBroadcastService.TEMPLATE_AWAITING_PROVIDER);
        assertThat(port.courierSent()).isEmpty();
    }

    @Test
    @DisplayName("a wording withdrawn between draft and send is refused, not sent as it stood")
    void sendRefusesAWordingThatIsGone() {
        insertCourier("K-054", "ACTIVE");
        UUID broadcastId = draftToAll();
        port.withoutCourierTemplates();

        assertThatThrownBy(() -> broadcasts.send(TENANT, broadcastId, AUTHOR)).isInstanceOf(ApiException.class);

        assertThat(broadcasts.require(TENANT, broadcastId).refusalReason())
                .contains(CourierBroadcastService.TEMPLATE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("a target with no active courier is refused, not recorded as a send to nobody")
    void sendRefusesAnEmptyTarget() {
        insertCourier("K-055", "ARCHIVED");
        UUID broadcastId = draftToAll();

        assertThatThrownBy(() -> broadcasts.send(TENANT, broadcastId, AUTHOR)).isInstanceOf(ApiException.class);

        assertThat(broadcasts.require(TENANT, broadcastId).refusalReason())
                .contains(CourierBroadcastService.NO_ACTIVE_COURIER);
    }

    @Test
    @DisplayName("a broadcast is sent once: the second send is refused and messages nobody again")
    void aBroadcastIsSentOnce() {
        insertCourier("K-056", "ACTIVE");
        UUID broadcastId = draftToAll();
        broadcasts.send(TENANT, broadcastId, AUTHOR);

        ApiException second =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(second.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(port.courierSent()).hasSize(1);
    }

    @Test
    @DisplayName(
            "drafting and sending a broadcast each write an ADR 0027 audit fact, and neither carries the dispatcher's text")
    void draftAndSendAreAudited() {
        insertCourier("K-060", "ACTIVE");
        UUID broadcastId = draftToAll();
        broadcasts.send(TENANT, broadcastId, AUTHOR);

        assertThat(audit.facts)
                .as("a dispatcher's own courier SMS is an operator-initiated action ADR 0027 requires evidence for")
                .extracting(AuditFact::actionCode)
                .contains("MARKETING_COURIER_BROADCAST_DRAFTED", "MARKETING_COURIER_BROADCAST_SENT");
        assertThat(audit.facts.stream()
                        .map(fact -> fact.changeDocument().toString())
                        .collect(Collectors.joining()))
                .as("the values are operational text the dispatcher typed; the audit trail keeps the wording's key")
                .doesNotContain("Shift change")
                .doesNotContain("18:00");
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

    /** The identity provider, as far as a broadcast sees it: who has a number, and whether it can be asked. */
    private static final class FakeCourierContacts implements CourierContactSource {

        boolean wired = true;
        boolean unavailable;
        /** Null means everyone has a number. */
        @org.jspecify.annotations.Nullable
        Set<UUID> withNumber;

        final List<String> askedFor = new CopyOnWriteArrayList<>();

        @Override
        public boolean isWired() {
            return wired;
        }

        @Override
        public Set<UUID> addressable(UUID tenantId, Collection<UUID> courierIds, String purpose) {
            askedFor.add(purpose + "/" + courierIds.size());
            if (unavailable) {
                throw new UnavailableException("the identity provider is down", new IllegalStateException());
            }
            return courierIds.stream()
                    .filter(id -> withNumber == null || withNumber.contains(id))
                    .collect(Collectors.toSet());
        }
    }
}
