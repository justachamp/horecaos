package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Listing;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.TemplateRenderer.TemplateContractException;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;

/**
 * ADR 0184's authoring rules (D19, D21) and the list Tab 1 reads, against the migrated schema.
 *
 * <p>Every refusal here is a refusal while somebody is writing. The alternative the old console had was
 * to accept the wording and discover at send time that nothing ever sends it (a SECURITY template) or that
 * it names a value no code supplies.
 */
class TemplateGovernanceTests {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateService templates;
    private UUID tenantId;
    private UUID brandId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                new JdbcBrandLocaleLookup(jdbc));
        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'governance', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'governance-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
        for (String tag : List.of("ru", "uz-Latn")) {
            locale(tag, "ru".equals(tag));
        }
    }

    // --------------------------------------------------------------------- D19

    @Test
    @DisplayName("D19: a SECURITY template cannot be created; the one-time-code sentence is the platform's")
    void aSecurityTemplateCannotBeCreated() {
        assertThatThrownBy(() -> templates.createTemplate(
                        tenantId, brandId, "OTP_CODE", NotificationClass.SECURITY, NotificationChannel.SMS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SECURITY")
                .hasMessageContaining("platform");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.templates")
                        .query(Long.class)
                        .single())
                .as("nothing was written")
                .isZero();
    }

    @Test
    @DisplayName("the other four classes are still creatable")
    void theOtherClassesAreStillCreatable() {
        templates.createTemplate(
                tenantId, brandId, "K1", NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);
        templates.createTemplate(
                tenantId, brandId, "K2", NotificationClass.TRANSACTIONAL_OPTIONAL, NotificationChannel.SMS, "P");
        templates.createTemplate(tenantId, brandId, "K3", NotificationClass.MARKETING, NotificationChannel.SMS, "P");
        templates.createTemplate(
                tenantId, brandId, "K4", NotificationClass.OPERATIONS_ALERT, NotificationChannel.TELEGRAM, null);
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.templates")
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
    }

    // --------------------------------------------------------------------- D21

    @Test
    @DisplayName("D21: a version naming the customer's phone, or the courier's phone or surname, is refused naming it")
    void declinedVariablesAreRefusedAtAuthoring() {
        UUID template = create("ORDER_CONFIRMED", NotificationClass.TRANSACTIONAL_REQUIRED, null);

        for (String declined : List.of("customerPhone", "courierPhone", "courierSurname")) {
            assertThatThrownBy(() -> templates.addVersion(
                            tenantId,
                            brandId,
                            template,
                            wordings("Call {{" + declined + "}}"),
                            Map.of(declined, "string")))
                    .as(declined)
                    .isInstanceOf(TemplateContractException.class)
                    .hasMessageContaining(declined);
        }
        assertThat(versionCount(template)).as("nothing was saved").isZero();
    }

    @Test
    @DisplayName("D21: customerName is refused in a message that goes to a group")
    void customerNameIsRefusedInAnOperationsAlert() {
        UUID alert = create("ORDER_CONFIRMED", NotificationClass.OPERATIONS_ALERT, null);

        assertThatThrownBy(() -> templates.addVersion(
                        tenantId,
                        brandId,
                        alert,
                        wordings("{{customerName}} ordered"),
                        Map.of("customerName", "string")))
                .isInstanceOf(TemplateContractException.class)
                .hasMessageContaining("customerName");

        UUID customer = create("ORDER_CONFIRMED_B", NotificationClass.TRANSACTIONAL_REQUIRED, null);
        assertThat(templates.addVersion(
                        tenantId,
                        brandId,
                        customer,
                        wordings("{{customerName}} ordered"),
                        Map.of("customerName", "string")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("D21: courierFirstName is accepted in the on-the-way message and refused anywhere else")
    void courierFirstNameIsForTheOnTheWayMessageOnly() {
        UUID confirmed = create("ORDER_CONFIRMED", NotificationClass.TRANSACTIONAL_REQUIRED, null);
        assertThatThrownBy(() -> templates.addVersion(
                        tenantId,
                        brandId,
                        confirmed,
                        wordings("{{courierFirstName}}"),
                        Map.of("courierFirstName", "string")))
                .isInstanceOf(TemplateContractException.class)
                .hasMessageContaining("ORDER_FULFILLING");

        UUID onTheWay = create("ORDER_FULFILLING", NotificationClass.TRANSACTIONAL_OPTIONAL, "ORDER_PROGRESS_UPDATES");
        assertThat(templates.addVersion(
                        tenantId,
                        brandId,
                        onTheWay,
                        wordings("{{courierFirstName}} is coming"),
                        Map.of("courierFirstName", "string")))
                .isEqualTo(1);
    }

    // ------------------------------------------------------- show item prices

    @Test
    @DisplayName("the price switch is the version's: written on every language row, and part of the proof only when on")
    void theSwitchIsPerVersion() {
        UUID template = create("ORDER_CONFIRMED", NotificationClass.TRANSACTIONAL_REQUIRED, null);
        Map<String, String> schema = Map.of("productList", "string");

        int plain = templates.addVersion(tenantId, brandId, template, wordings("{{productList}}"), schema);
        int priced = templates.addVersion(tenantId, brandId, template, wordings("{{productList}}"), schema, true);

        assertThat(switchesOf(template, plain)).containsExactly(false, false);
        assertThat(switchesOf(template, priced))
                .as("the same value on every locale row")
                .containsExactly(true, true);
        assertThat(hashOf(template, plain, "ru"))
                .as("a version authored without the switch keeps the hash it had before the switch existed")
                .isEqualTo(ContentHashes.ofVariables(Map.of("locale", "ru", "subject", "", "body", "{{productList}}")));
        assertThat(hashOf(template, priced, "ru"))
                .as("the same words with prices on are not the same message, and the proof says so")
                .isNotEqualTo(hashOf(template, plain, "ru"));
    }

    @Test
    @DisplayName("a price switch on a wording that never prints the list is refused: it would change nothing")
    void thePriceSwitchNeedsTheProductList() {
        UUID template = create("ORDER_CONFIRMED", NotificationClass.TRANSACTIONAL_REQUIRED, null);

        assertThatThrownBy(() -> templates.addVersion(tenantId, brandId, template, wordings("Thanks"), Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productList");
    }

    // ------------------------------------------------------------------ the list

    @Test
    @DisplayName(
            "Tab 1's list: languages the shown version has, languages it should have, and the worst moderation state")
    void theListSummarisesTheShownVersion() {
        UUID template = create("ORDER_CONFIRMED", NotificationClass.TRANSACTIONAL_REQUIRED, null);
        int version = templates.addVersion(tenantId, brandId, template, wordings("Hello"), Map.of());
        templates.activate(tenantId, brandId, template, version, "tester");
        // The brand adds a language after the template went live, and the gateway refuses one wording.
        locale("en", false);
        jdbc.sql("""
                UPDATE notifications.template_versions
                   SET provider_review = 'REJECTED', provider_review_updated_by = 'gateway',
                       provider_review_updated_at = now()
                 WHERE locale = 'uz-Latn'
                """).update();
        UUID draftOnly = create("ORDER_CANCELLED", NotificationClass.TRANSACTIONAL_REQUIRED, null);
        templates.addVersion(tenantId, brandId, draftOnly, wordings("Bye"), Map.of());
        UUID empty = create("ORDER_EXPIRED", NotificationClass.TRANSACTIONAL_REQUIRED, null);

        Map<UUID, Listing> byId = new LinkedHashMap<>();
        templates
                .listing(tenantId, brandId)
                .forEach(row -> byId.put(row.template().id(), row));

        Listing live = java.util.Objects.requireNonNull(byId.get(template));
        assertThat(live.shownVersion()).isEqualTo(1);
        assertThat(live.locales()).containsExactly("ru", "uz-Latn");
        assertThat(live.requiredLocales()).containsExactlyInAnyOrder("ru", "uz-Latn", "en");
        assertThat(live.providerReview()).as("worst of the languages").isEqualTo("REJECTED");

        Listing draft = java.util.Objects.requireNonNull(byId.get(draftOnly));
        assertThat(draft.shownVersion())
                .as("a template with no active version shows its newest draft")
                .isEqualTo(1);
        assertThat(draft.locales())
                .as("written after the brand added English, so it carries all three")
                .containsExactly("en", "ru", "uz-Latn");
        assertThat(draft.template().activeVersion()).isNull();

        Listing bare = java.util.Objects.requireNonNull(byId.get(empty));
        assertThat(bare.shownVersion()).isNull();
        assertThat(bare.locales()).isEmpty();
        assertThat(bare.providerReview()).isEqualTo("NOT_REQUIRED");
    }

    @Test
    @DisplayName("a tenant-wide template is listed with the languages the whole tenant serves")
    void aTenantWideTemplateIsListed() {
        templates.createTemplate(
                tenantId,
                null,
                "ORDER_CONFIRMED",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);

        List<Listing> listing = templates.listing(tenantId, brandId);

        assertThat(listing).hasSize(1);
        assertThat(listing.getFirst().template().isTenantWide()).isTrue();
        assertThat(listing.getFirst().requiredLocales()).containsExactly("ru", "uz-Latn");
    }

    // -------------------------------------------------------------- fixtures

    private UUID create(String key, NotificationClass notificationClass, @Nullable String purpose) {
        return templates.createTemplate(tenantId, brandId, key, notificationClass, NotificationChannel.SMS, purpose);
    }

    private Map<MessageLocale, Wording> wordings(String body) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        templates.requiredLocales(tenantId, brandId).forEach(locale -> wordings.put(locale, new Wording(null, body)));
        return wordings;
    }

    private long versionCount(UUID template) {
        return jdbc.sql("SELECT count(*) FROM notifications.template_versions WHERE template_id = :id")
                .param("id", template)
                .query(Long.class)
                .single();
    }

    private List<Boolean> switchesOf(UUID template, int version) {
        return jdbc.sql("""
                        SELECT show_item_prices FROM notifications.template_versions
                        WHERE template_id = :id AND version_number = :version ORDER BY locale
                        """)
                .param("id", template)
                .param("version", version)
                .query(Boolean.class)
                .list();
    }

    private String hashOf(UUID template, int version, String locale) {
        return jdbc.sql("""
                        SELECT content_hash FROM notifications.template_versions
                        WHERE template_id = :id AND version_number = :version AND locale = :locale
                        """)
                .param("id", template)
                .param("version", version)
                .param("locale", locale)
                .query(String.class)
                .single();
    }

    private void locale(String tag, boolean isDefault) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenantId, :brandId, :locale, :isDefault)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locale", tag)
                .param("isDefault", isDefault)
                .update();
    }
}
