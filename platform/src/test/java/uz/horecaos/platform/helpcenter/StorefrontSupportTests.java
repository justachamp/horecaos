package uz.horecaos.platform.helpcenter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.helpcenter.domain.SupportContent.FaqCategory;
import uz.horecaos.platform.helpcenter.domain.SupportContent.SocialLink;
import uz.horecaos.platform.helpcenter.infrastructure.persistence.JdbcSupportStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A brand's published help (V0094).
 *
 * <p>Most of what is asserted here is what does <em>not</em> come back: a draft
 * answer, another brand's answer, an archived link. A test that only proved the
 * FAQ loads would pass just as happily against a query with no status predicate
 * and no brand predicate, and those are the two ways this leaks.
 */
class StorefrontSupportTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcSupportStore store;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for help centre tests");
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
        jdbc.sql("TRUNCATE TABLE support.faq_translations, support.faq_entries, "
                        + "support.faq_categories, support.social_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();
        store = new JdbcSupportStore(jdbc);
    }

    @Test
    @DisplayName("published categories come back with their entries, in authored order")
    void publishedFaqLoadsInOrder() {
        UUID payment = category(BRAND, "PAYMENT", 20, "PUBLISHED", "To'lov");
        UUID delivery = category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");
        entry(delivery, "SECOND", 20, "PUBLISHED", "Ikkinchi?", "Ha.");
        entry(delivery, "FIRST", 10, "PUBLISHED", "Birinchi?", "Yo'q.");

        List<FaqCategory> faq = store.faq(TENANT, BRAND, "uz");

        assertThat(faq)
                .extracting(FaqCategory::code)
                .as("sort_order decides, not insertion order")
                .containsExactly("DELIVERY", "PAYMENT");
        assertThat(faq.get(0).entries()).extracting(e -> e.code()).containsExactly("FIRST", "SECOND");
        assertThat(faq.get(0).entries().get(0).answer()).isEqualTo("Yo'q.");
        assertThat(payment).isNotNull();
    }

    @Test
    @DisplayName("a draft category and a draft entry never reach a customer")
    void draftContentIsNotServed() {
        UUID delivery = category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");
        category(BRAND, "SECRET", 20, "DRAFT", "Yozilmagan");
        entry(delivery, "LIVE", 10, "PUBLISHED", "Savol?", "Javob.");
        entry(delivery, "HALF_WRITTEN", 20, "DRAFT", "Tugallanmagan?", "...");

        List<FaqCategory> faq = store.faq(TENANT, BRAND, "uz");

        assertThat(faq).extracting(FaqCategory::code).containsExactly("DELIVERY");
        assertThat(faq.get(0).entries())
                .extracting(e -> e.code())
                .as("half-written help is worse than none")
                .containsExactly("LIVE");
    }

    @Test
    @DisplayName("another brand's FAQ is not this brand's")
    void faqIsScopedToItsBrand() {
        category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");
        category(OTHER_BRAND, "OTHER", 10, "PUBLISHED", "Boshqa");

        assertThat(store.faq(TENANT, BRAND, "uz")).extracting(FaqCategory::code).containsExactly("DELIVERY");
        assertThat(store.faq(TENANT, OTHER_BRAND, "uz"))
                .extracting(FaqCategory::code)
                .containsExactly("OTHER");
    }

    @Test
    @DisplayName("an untranslated locale falls back to a published one, never to the code")
    void localeFallsBackRatherThanShowingACode() {
        UUID delivery = category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");
        entry(delivery, "ONLY_UZ", 10, "PUBLISHED", "Savol?", "Javob.");

        List<FaqCategory> english = store.faq(TENANT, BRAND, "en");

        assertThat(english.get(0).name())
                .as("the Uzbek heading beats the string DELIVERY")
                .isEqualTo("Yetkazish");
        assertThat(english.get(0).entries().get(0).question()).isEqualTo("Savol?");
    }

    @Test
    @DisplayName("the requested locale wins when there is one")
    void therequestedLocaleWins() {
        UUID delivery = category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");
        translate("CATEGORY", delivery, "ru", "Доставка", null);
        entry(delivery, "Q", 10, "PUBLISHED", "Savol?", "Javob.");

        assertThat(store.faq(TENANT, BRAND, "ru").get(0).name()).isEqualTo("Доставка");
        assertThat(store.faq(TENANT, BRAND, "uz").get(0).name()).isEqualTo("Yetkazish");
    }

    @Test
    @DisplayName("a published category with no entries keeps its heading")
    void anEmptyCategoryStillAppears() {
        category(BRAND, "DELIVERY", 10, "PUBLISHED", "Yetkazish");

        List<FaqCategory> faq = store.faq(TENANT, BRAND, "uz");

        assertThat(faq).hasSize(1);
        assertThat(faq.get(0).entries())
                .as("an operator published this section; hiding it would be a surprise")
                .isEmpty();
    }

    @Test
    @DisplayName("only published links are offered, in authored order")
    void socialLinksAreFilteredAndOrdered() {
        socialLink("PHONE", "tel:+998000000000", 20, "PUBLISHED", null);
        socialLink("TELEGRAM", "https://t.me/horecaos", 10, "PUBLISHED", null);
        socialLink("INSTAGRAM", "https://instagram.com/horecaos", 30, "ARCHIVED", null);

        List<SocialLink> links = store.socialLinks(TENANT, BRAND);

        assertThat(links).extracting(SocialLink::platform).containsExactly("TELEGRAM", "PHONE");
        assertThat(links.get(0).imageUrl())
                .as("no override, so the storefront uses its own artwork")
                .isNull();
    }

    @Test
    @DisplayName("an operator's own icon resolves through the storefront media path")
    void anAssetOverrideBecomesAUrl() {
        UUID assetId = UUID.randomUUID();
        socialLink("TELEGRAM", "https://t.me/horecaos", 10, "PUBLISHED", assetId);

        assertThat(store.socialLinks(TENANT, BRAND).get(0).imageUrl())
                .isEqualTo("/api/v1/storefront/tenants/%s/media/%s".formatted(TENANT, assetId));
    }

    // ------------------------------------------------------------------ fixtures

    private UUID category(UUID brandId, String code, int sort, String status, String uzName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO support.faq_categories (id, tenant_id, brand_id, code, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :code, :sort, :status)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("sort", sort)
                .param("status", status)
                .update();
        translate("CATEGORY", id, "uz", uzName, null);
        return id;
    }

    private UUID entry(UUID categoryId, String code, int sort, String status, String question, String answer) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO support.faq_entries (
                    id, tenant_id, brand_id, category_id, code, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :categoryId, :code, :sort, :status)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("categoryId", categoryId)
                .param("code", code)
                .param("sort", sort)
                .param("status", status)
                .update();
        translate("ENTRY", id, "uz", question, answer);
        return id;
    }

    private void translate(String type, UUID entityId, String locale, String title, String body) {
        jdbc.sql("""
                INSERT INTO support.faq_translations (
                    tenant_id, brand_id, entity_type, entity_id, locale, title, body)
                VALUES (:tenantId, :brandId, :type, :entityId, :locale, :title, :body)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("type", type)
                .param("entityId", entityId)
                .param("locale", locale)
                .param("title", title)
                .param("body", body)
                .update();
    }

    private void socialLink(String platform, String url, int sort, String status, UUID assetId) {
        jdbc.sql("""
                INSERT INTO support.social_links (
                    id, tenant_id, brand_id, platform, url, media_asset_id, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :platform, :url, :assetId, :sort, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("platform", platform)
                .param("url", url)
                .param("assetId", assetId)
                .param("sort", sort)
                .param("status", status)
                .update();
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'help-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
    }
}
