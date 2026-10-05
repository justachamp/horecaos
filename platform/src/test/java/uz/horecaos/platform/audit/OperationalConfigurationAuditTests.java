package uz.horecaos.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.kitchen.application.KitchenStationService;
import uz.horecaos.platform.kitchen.application.KitchenStationService.CapacityWindowEdit;
import uz.horecaos.platform.kitchen.application.KitchenStationService.NewCapacityWindow;
import uz.horecaos.platform.kitchen.application.KitchenStationService.NewRoutingRule;
import uz.horecaos.platform.kitchen.application.KitchenStationService.NewStation;
import uz.horecaos.platform.kitchen.application.KitchenStationService.RoutingRuleEdit;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationCapacityRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.AuditTrail.Fact;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Staff row {@code 9.3a}, second pass: the configuration writers the scan found beside the six
 * already covered by {@link ConfigurationAuthoringAuditTests} -- a branch's kitchen stations,
 * throughput ceilings and dish routing rules, and the wording of a notification template.
 * (Merchant bindings are covered end to end in {@code MerchantBindingControllerEndpointTests},
 * catalog publication in {@code CatalogPublicationTests}, where their fixtures already live.)
 *
 * <p>Against a real PostgreSQL and the real {@code JdbcAuditRecorder}: the assertion is the row an
 * operator's activity log reads. Every test ends in {@link #theEvidenceIsClean}, which refuses a
 * fact holding a redaction marker (a key that collided with {@code ChangeDocuments}' protected
 * terms and was silently blanked), a phone number, an e-mail address, or text the test knows
 * must not be copied into the history.
 */
class OperationalConfigurationAuditTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String AUTHOR = "7c1d7a9e-0000-4000-8000-00000000b002";
    private static final Instant NOW = Instant.parse("2026-10-05T07:00:00Z");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+998[0-9 ()-]{9,}");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private KitchenStationService stations;
    private NotificationTemplateService templates;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the operational configuration audit tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.categories, catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        AuditTrail.clear(jdbc);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var recorder = AuditTrail.recorder(jdbc);
        var actor = AuditTrail.actor(AUTHOR);
        stations = new KitchenStationService(new JdbcKitchenStore(jdbc), clock, recorder, actor);
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc), JsonMapper.builder().build(), clock, recorder, actor);

        seedTenancy();
    }

    // ------------------------------------------------------------ kitchen stations

    @Test
    @DisplayName("creating a station records the station, and the fact is scoped to its branch")
    void aStationCreationIsAudited() {
        StationRow grill = station("GRILL", StationRole.GRILL, true);

        Fact created = AuditTrail.only(jdbc, "kitchen.station.created");
        assertThat(created.actorType()).isEqualTo("USER");
        assertThat(created.actorSubject()).isEqualTo(AUTHOR);
        assertThat(created.scopeType()).isEqualTo("LOCATION");
        assertThat(created.scopeId()).isEqualTo(LOCATION);
        assertThat(created.targetType()).isEqualTo("kitchen.station");
        assertThat(created.targetId()).isEqualTo(grill.id());
        assertThat(created.reason()).isNotBlank();
        assertThat(created.before("code").isNull())
                .as("a creation has no prior value")
                .isTrue();
        assertThat(created.after("code").asText()).isEqualTo("GRILL");
        assertThat(created.after("role").asText()).isEqualTo("GRILL");
        assertThat(created.after("fallback").asBoolean()).isTrue();
        assertThat(created.after("displayNameRu").asText()).isEqualTo("Гриль");

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("a refused station creation leaves no fact: the write and its fact roll back as one")
    void aRefusedStationLeavesNoFact() {
        station("GRILL", StationRole.GRILL, true);
        long before = AuditTrail.count(jdbc);

        Throwable refused = catchThrowable(() -> station("GRILL", StationRole.GRILL, false));

        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(AuditTrail.count(jdbc)).isEqualTo(before);
        assertThat(AuditTrail.facts(jdbc, "kitchen.station.created")).hasSize(1);
    }

    @Test
    @DisplayName("a throughput ceiling's creation, correction and removal each record the window and the rate")
    void aCapacityWindowLifecycleIsAudited() {
        StationRow grill = station("GRILL", StationRole.GRILL, true);

        StationCapacityRow window = stations.createCapacityWindow(new NewCapacityWindow(
                TENANT, BRAND, LOCATION, grill.id(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 30));
        Fact created = AuditTrail.only(jdbc, "kitchen.station_capacity.created");
        assertThat(created.actorSubject()).isEqualTo(AUTHOR);
        assertThat(created.targetType()).isEqualTo("kitchen.station-capacity");
        assertThat(created.targetId()).isEqualTo(window.id());
        assertThat(created.after("stationCode").asText()).isEqualTo("GRILL");
        assertThat(created.after("portionsPerHour").asInt()).isEqualTo(30);
        assertThat(created.after("windowStart").asText()).isEqualTo("09:00");
        assertThat(created.before("portionsPerHour").isNull()).isTrue();

        StationCapacityRow corrected = stations.updateCapacityWindow(new CapacityWindowEdit(
                TENANT, LOCATION, window.id(), LocalTime.of(9, 0), LocalTime.of(14, 0), 55, window.version()));
        Fact updated = AuditTrail.only(jdbc, "kitchen.station_capacity.updated");
        assertThat(updated.before("portionsPerHour").asInt()).isEqualTo(30);
        assertThat(updated.after("portionsPerHour").asInt()).isEqualTo(55);
        assertThat(updated.before("windowEnd").asText()).isEqualTo("12:00");
        assertThat(updated.after("windowEnd").asText()).isEqualTo("14:00");
        assertThat(updated.targetVersion()).isEqualTo((long) corrected.version());

        stations.deleteCapacityWindow(TENANT, LOCATION, window.id(), corrected.version());
        Fact removed = AuditTrail.only(jdbc, "kitchen.station_capacity.deleted");
        assertThat(removed.before("portionsPerHour").asInt()).isEqualTo(55);
        assertThat(removed.after("portionsPerHour").isNull())
                .as("the ceiling is gone, so there is no value after")
                .isTrue();

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("a stale capacity edit leaves no fact")
    void aStaleCapacityEditLeavesNoFact() {
        StationRow grill = station("GRILL", StationRole.GRILL, true);
        StationCapacityRow window = stations.createCapacityWindow(new NewCapacityWindow(
                TENANT, BRAND, LOCATION, grill.id(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 30));
        long before = AuditTrail.count(jdbc);

        Throwable refused = catchThrowable(() -> stations.updateCapacityWindow(new CapacityWindowEdit(
                TENANT, LOCATION, window.id(), LocalTime.of(9, 0), LocalTime.of(14, 0), 55, window.version() + 3)));

        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(AuditTrail.count(jdbc)).isEqualTo(before);
        assertThat(AuditTrail.facts(jdbc, "kitchen.station_capacity.updated")).isEmpty();
    }

    @Test
    @DisplayName("a dish routing rule's creation and its change of department record the node and both ends")
    void aRoutingRuleLifecycleIsAudited() {
        StationRow grill = station("GRILL", StationRole.GRILL, true);
        StationRow bar = station("BAR", StationRole.BAR, false);
        UUID category = seedCategory();

        UUID brandRule =
                stations.route(new NewRoutingRule(TENANT, BRAND, null, null, null, category, StationRole.GRILL, null));
        Fact created = AuditTrail.only(jdbc, "kitchen.routing_rule.created");
        assertThat(created.scopeType()).as("a brand rule belongs to the brand").isEqualTo("BRAND");
        assertThat(created.scopeId()).isEqualTo(BRAND);
        assertThat(created.targetType()).isEqualTo("kitchen.routing-rule");
        assertThat(created.targetId()).isEqualTo(brandRule);
        assertThat(created.after("layer").asText()).isEqualTo("BRAND");
        assertThat(created.after("categoryId").asText()).isEqualTo(category.toString());
        assertThat(created.after("stationRole").asText()).isEqualTo("GRILL");
        assertThat(created.before("stationRole").isNull()).isTrue();

        stations.updateRoutingRule(new RoutingRuleEdit(TENANT, BRAND, LOCATION, brandRule, StationRole.BAR, null, 1));
        Fact changed = AuditTrail.facts(jdbc, "kitchen.routing_rule.updated").get(0);
        assertThat(changed.scopeType())
                .as("editing a brand rule from a branch's path is still a brand rule")
                .isEqualTo("BRAND");
        assertThat(changed.before("stationRole").asText()).isEqualTo("GRILL");
        assertThat(changed.after("stationRole").asText()).isEqualTo("BAR");

        UUID branchRule =
                stations.route(new NewRoutingRule(TENANT, BRAND, LOCATION, null, null, category, null, grill.id()));
        Fact branchCreated =
                AuditTrail.facts(jdbc, "kitchen.routing_rule.created").get(1);
        assertThat(branchCreated.scopeType()).isEqualTo("LOCATION");
        assertThat(branchCreated.scopeId()).isEqualTo(LOCATION);
        assertThat(branchCreated.after("stationId").asText())
                .isEqualTo(grill.id().toString());

        stations.updateRoutingRule(new RoutingRuleEdit(TENANT, BRAND, LOCATION, branchRule, null, bar.id(), 1));
        Fact moved = AuditTrail.facts(jdbc, "kitchen.routing_rule.updated").get(1);
        assertThat(moved.before("stationId").asText()).isEqualTo(grill.id().toString());
        assertThat(moved.after("stationId").asText()).isEqualTo(bar.id().toString());

        theEvidenceIsClean();
    }

    // ------------------------------------------------------ notification templates

    @Test
    @DisplayName("a template's creation, a new version and its activation are audited, and no wording is copied")
    void aTemplateLifecycleIsAudited() {
        String russian = "Ваш заказ принят. Звоните +998 90 123 45 67 или support@rayhon.uz";
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                "order.confirmed",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);

        Fact created = AuditTrail.only(jdbc, "notification.template.created");
        assertThat(created.actorSubject()).isEqualTo(AUTHOR);
        assertThat(created.scopeType()).isEqualTo("BRAND");
        assertThat(created.scopeId()).isEqualTo(BRAND);
        assertThat(created.targetType()).isEqualTo("notification.template");
        assertThat(created.targetId()).isEqualTo(templateId);
        assertThat(created.after("templateKey").asText()).isEqualTo("order.confirmed");
        assertThat(created.after("channel").asText()).isEqualTo("SMS");
        assertThat(created.before("templateKey").isNull()).isTrue();

        int version = templates.addVersion(TENANT, BRAND, templateId, wordings(russian), Map.of());
        Fact added = AuditTrail.only(jdbc, "notification.template.version_added");
        assertThat(added.after("versionNumber").asInt()).isEqualTo(version);
        assertThat(added.after("characters.ru").asInt()).isEqualTo(russian.length());
        assertThat(added.after("characters.en").asInt()).isEqualTo("Order accepted".length());
        assertThat(added.change().toString())
                .as("the history names the version and its size; the text is kept by the version rows")
                .doesNotContain("Ваш заказ")
                .doesNotContain("Order accepted");

        templates.activate(TENANT, BRAND, templateId, version, AUTHOR);
        Fact activated = AuditTrail.only(jdbc, "notification.template.version_activated");
        assertThat(activated.before("activeVersion").isNull())
                .as("nothing was sent before the first activation")
                .isTrue();
        assertThat(activated.after("activeVersion").asInt()).isEqualTo(version);
        assertThat(activated.after("templateKey").asText()).isEqualTo("order.confirmed");

        int second = templates.addVersion(TENANT, BRAND, templateId, wordings("Заказ принят"), Map.of());
        templates.activate(TENANT, BRAND, templateId, second, AUTHOR);
        Fact replacement = AuditTrail.facts(jdbc, "notification.template.version_activated")
                .get(1);
        assertThat(replacement.before("activeVersion").asInt()).isEqualTo(version);
        assertThat(replacement.after("activeVersion").asInt()).isEqualTo(second);

        theEvidenceIsClean("Ваш заказ", "support@rayhon.uz", "123 45 67");
    }

    @Test
    @DisplayName("a tenant-wide template's facts are scoped to the tenant")
    void aTenantWideTemplateIsAuditedAtTenantScope() {
        UUID templateId = templates.createTemplate(
                TENANT, null, "order.ready", NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);

        Fact created = AuditTrail.only(jdbc, "notification.template.created");
        assertThat(created.scopeType()).isEqualTo("TENANT");
        assertThat(created.scopeId()).isEqualTo(TENANT);
        assertThat(created.targetId()).isEqualTo(templateId);

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("an activation refused for a missing language leaves no fact")
    void aRefusedActivationLeavesNoFact() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "order.late", NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);
        long before = AuditTrail.count(jdbc);

        Throwable refused = catchThrowable(() -> templates.activate(TENANT, BRAND, templateId, 1, AUTHOR));

        assertThat(refused).isInstanceOf(NotificationTemplateService.IncompleteTranslationException.class);
        assertThat(AuditTrail.count(jdbc)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What would still be true if the audit were broken: a fact with a name-redacted value looks
     * like evidence and records nothing, so no stored change document may hold the redaction
     * marker; and no stored fact may hold an e-mail address, a phone number, or a string the test
     * knows must stay out of the history.
     */
    private void theEvidenceIsClean(String... forbidden) {
        List<String> documents = jdbc.sql("SELECT COALESCE(change_document::text, '') FROM audit.audit_events")
                .query(String.class)
                .list();
        assertThat(documents).isNotEmpty();
        for (String document : documents) {
            assertThat(document)
                    .as("a redacted value means a field name collided with ChangeDocuments' protected terms")
                    .doesNotContain("[redacted]");
            assertThat(EMAIL.matcher(document).find()).as(document).isFalse();
            assertThat(PHONE.matcher(document).find()).as(document).isFalse();
            for (String text : forbidden) {
                assertThat(document).doesNotContain(text);
            }
        }
        List<String> reasons = jdbc.sql("SELECT COALESCE(reason, '') FROM audit.audit_events")
                .query(String.class)
                .list();
        assertThat(reasons).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    private StationRow station(String code, StationRole role, boolean fallback) {
        return stations.create(new NewStation(
                TENANT, BRAND, LOCATION, code, role, "Гриль", "Gril", "Grill", fallback ? 1 : 2, fallback));
    }

    private static Map<MessageLocale, Wording> wordings(String russian) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        wordings.put(MessageLocale.RU, new Wording(null, russian));
        wordings.put(MessageLocale.UZ_LATN, new Wording(null, "Buyurtma qabul qilindi"));
        wordings.put(MessageLocale.EN, new Wording(null, "Order accepted"));
        return wordings;
    }

    private UUID seedCategory() {
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status, version)
                VALUES (:id, :t, :b, 'MAIN', 'Main', 'ACTIVE', 1)
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        UUID categoryId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.categories
                    (id, tenant_id, brand_id, catalog_id, code, sort_order, status, version)
                VALUES (:id, :t, :b, :c, 'MAINS', 1, 'ACTIVE', 1)
                """)
                .param("id", categoryId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("c", catalogId)
                .update();
        return categoryId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'operational-audit', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC1', 'loc-1', 'Location One', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
