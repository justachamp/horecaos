package uz.horecaos.platform.content;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;

/**
 * Rows the content tests need, written the way the platform's own tests write them: straight into
 * the tables other modules own, because the content module reaches those modules through ports and
 * a test that goes through the same ports proves nothing about the ports.
 *
 * <p>What is deliberately <em>not</em> faked: the plan and subscription that entitle a tenant are
 * made with the real {@code PlanCatalogService} and {@code SubscriptionService}, so the
 * {@code EntitlementGate} the content module asks is the real one answering from a real plan.
 */
public final class ContentFixture {

    public static final String STOREFRONT = "STOREFRONT";
    public static final String IOS = "IOS_APP";
    public static final String QR = "TABLE_QR";
    public static final String WOLT = "WOLT";

    private final JdbcClient jdbc;

    public ContentFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------------- tenancy

    public void tenant(UUID tenant, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", slug).update();
    }

    public void brand(UUID tenant, UUID brand, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("t", tenant)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    public void location(UUID tenant, UUID brand, UUID location, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    public UUID channel(UUID tenant, String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, :code, :type, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("code", code)
                .param("type", systemType)
                .update();
        return id;
    }

    public void channelHostname(UUID tenant, UUID channel, String hostname, boolean verified) {
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:t, :c, :host, :verified)
                ON CONFLICT (tenant_id, channel_id) DO UPDATE SET hostname = :host, verified = :verified
                """)
                .param("t", tenant)
                .param("c", channel)
                .param("host", hostname)
                .param("verified", verified)
                .update();
    }

    /** The brand's languages, the first being its default. */
    public void brandLocales(UUID tenant, UUID brand, String... locales) {
        for (int i = 0; i < locales.length; i++) {
            jdbc.sql("""
                    INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                    VALUES (:t, :b, :locale, :isDefault)
                    """)
                    .param("t", tenant)
                    .param("b", brand)
                    .param("locale", locales[i])
                    .param("isDefault", i == 0)
                    .update();
        }
    }

    // ---------------------------------------------------------------- the menu

    /** An active publication on a channel, holding these products and categories. */
    public UUID publication(UUID tenant, UUID brand, String channel, List<UUID> products, List<UUID> categories) {
        UUID catalog = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, :code, 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("t", tenant)
                .param("b", brand)
                .param("code", "C" + channel.hashCode())
                .update();
        UUID publication = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :t, :b, :catalog, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("t", tenant)
                .param("b", brand)
                .param("catalog", catalog)
                .param("channel", channel)
                .update();
        for (UUID product : products) {
            publicationItem(tenant, brand, publication, "PRODUCT", product);
        }
        for (UUID category : categories) {
            publicationItem(tenant, brand, publication, "CATEGORY", category);
        }
        return publication;
    }

    public void publicationItem(UUID tenant, UUID brand, UUID publication, String type, UUID id) {
        jdbc.sql("""
                INSERT INTO catalog.publication_items
                    (publication_id, tenant_id, brand_id, entity_type, entity_id, entity_version, immutable_content_json)
                VALUES (:p, :t, :b, :type, :id, 1, '{}'::jsonb)
                """)
                .param("p", publication)
                .param("t", tenant)
                .param("b", brand)
                .param("type", type)
                .param("id", id)
                .update();
    }

    public void dropFromPublication(UUID publication, UUID entity) {
        // The application role cannot delete a publication item and the test owner can: this is the
        // menu republished without the dish, which is what a retired product looks like to content.
        jdbc.sql("DELETE FROM catalog.publication_items WHERE publication_id = :p AND entity_id = :id")
                .param("p", publication)
                .param("id", entity)
                .update();
    }

    // ------------------------------------------------------------------- media

    /** An asset the way a verified upload leaves it, plus the renditions named (width by variant). */
    public UUID image(
            UUID tenant,
            UUID brand,
            int width,
            int height,
            String visibility,
            String status,
            Map<String, Integer> renditions) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO media.assets
                    (asset_id, tenant_id, owner_scope, owner_id, object_key, bucket, status, visibility,
                     declared_content_type, declared_size_bytes, verified_content_type, verified_size_bytes,
                     verified_checksum_sha256, width_px, height_px, available_at, rendition_profile)
                VALUES (:id, :t, 'BRAND', :b, :key, 'content-test', :status, :visibility,
                        'image/jpeg', 100000, 'image/jpeg', 100000, :checksum, :w, :h, now(), 'CREATIVE')
                """)
                .param("id", asset)
                .param("t", tenant)
                .param("b", brand)
                .param("key", "content/" + asset)
                .param("status", status)
                .param("visibility", visibility)
                .param("checksum", "a".repeat(64))
                .param("w", width)
                .param("h", height)
                .update();
        renditions.forEach((variant, widthPx) ->
                rendition(tenant, asset, variant, widthPx, height * widthPx / Math.max(1, width)));
        return asset;
    }

    /**
     * A public MP4 the way a verified upload leaves it: a 3:1 frame, twelve seconds, no renditions (a
     * video has none; its poster is a separate image asset).
     */
    public UUID video(UUID tenant, UUID brand) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO media.assets
                    (asset_id, tenant_id, owner_scope, owner_id, object_key, bucket, status, visibility,
                     declared_content_type, declared_size_bytes, verified_content_type, verified_size_bytes,
                     verified_checksum_sha256, width_px, height_px, duration_ms, available_at, rendition_profile)
                VALUES (:id, :t, 'BRAND', :b, :key, 'content-test', 'AVAILABLE', 'PUBLIC',
                        'video/mp4', 5000000, 'video/mp4', 5000000, :checksum, 1800, 600, 12000, now(), 'CREATIVE')
                """)
                .param("id", asset)
                .param("t", tenant)
                .param("b", brand)
                .param("key", "content/" + asset)
                .param("checksum", "c".repeat(64))
                .update();
        return asset;
    }

    /** A public, available image with the usual renditions up to 800 px. */
    public UUID goodBanner(UUID tenant, UUID brand) {
        return image(
                tenant,
                brand,
                1800,
                600,
                "PUBLIC",
                "AVAILABLE",
                Map.of("THUMBNAIL", 200, "CARD", 400, "DETAIL", 800, "HERO", 1600));
    }

    public UUID goodCard(UUID tenant, UUID brand) {
        return image(
                tenant, brand, 1200, 800, "PUBLIC", "AVAILABLE", Map.of("THUMBNAIL", 200, "CARD", 400, "DETAIL", 800));
    }

    public void rendition(UUID tenant, UUID asset, String variant, int width, int height) {
        jdbc.sql("""
                INSERT INTO media.derivatives
                    (derivative_id, tenant_id, asset_id, variant, object_key, bucket, content_type, size_bytes,
                     checksum_sha256, width_px, height_px, processor_version)
                VALUES (:id, :t, :asset, :variant, :key, 'content-test', 'image/jpeg', 1000, :checksum, :w, :h, 'test-1')
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenant)
                .param("asset", asset)
                .param("variant", variant)
                .param("key", "content/" + asset + "-" + variant)
                .param("checksum", "b".repeat(64))
                .param("w", width)
                .param("h", Math.max(1, height))
                .update();
    }

    // --------------------------------------------------------------- promotions

    public UUID promotion(
            UUID tenant, UUID brand, String code, String status, Instant validFrom, @Nullable Instant validUntil) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.promotions
                    (id, tenant_id, brand_id, code, name, scope, status, stacking_group, currency,
                     valid_from, valid_until, validated_at, activated_at)
                VALUES (:id, :t, :b, :code, :code, 'ORDER', :status, 'G', 'UZS', :from, :until,
                        CASE WHEN :status IN ('ACTIVE', 'SUSPENDED') THEN now() END,
                        CASE WHEN :status = 'ACTIVE' THEN now() END)
                """)
                .param("id", id)
                .param("t", tenant)
                .param("b", brand)
                .param("code", code)
                .param("status", status)
                .param("from", validFrom.atOffset(ZoneOffset.UTC))
                .param("until", validUntil == null ? null : validUntil.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    public void promotionStatus(UUID id, String status) {
        jdbc.sql("""
                UPDATE pricing.promotions
                SET status = :status, validated_at = COALESCE(validated_at, now()),
                    activated_at = CASE WHEN :status = 'ACTIVE' THEN COALESCE(activated_at, now()) ELSE activated_at END
                WHERE id = :id
                """).param("id", id).param("status", status).update();
    }

    // ----------------------------------------------------------------- identity

    public void grant(UUID tenant, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'content test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((tenant + subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                // Backdated: the grant is compared with this JVM's clock and a container's can skew.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    // -------------------------------------------------------------- entitlements

    /**
     * Puts the tenant on a plan that switches the named content features on, with the real plan and
     * subscription services.
     *
     * <p>Only the features switched <em>on</em> are put in the plan. A tenant with no plan, or a plan
     * that does not name a key, sits on the catalogue's own safe default, which for both content keys
     * is off and refused; naming a key with {@code false} would put it in meter-only mode, where a
     * tenant outside its plan is counted and allowed (ADR 0021) -- not the state a test of a refusal
     * wants.
     */
    public void entitle(
            PlanCatalogService plans,
            SubscriptionService subscriptions,
            UUID tenant,
            boolean editorial,
            boolean video) {
        ActorRef author = ActorRef.user("content-plan-author", null);
        ActorRef approver = ActorRef.user("content-plan-approver", null);
        UUID plan = plans.createPlan(
                "CT" + tenant.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                "Content " + tenant,
                author,
                "content test",
                "corr");
        java.util.Map<String, PlanEntitlement> entitlements = new java.util.LinkedHashMap<>();
        if (editorial) {
            entitlements.put(
                    EntitlementKeys.CONTENT_EDITORIAL_ENABLED.code(),
                    PlanEntitlement.feature(
                            EntitlementKeys.CONTENT_EDITORIAL_ENABLED.code(), true, EnforcementMode.METER_ONLY));
        }
        if (video) {
            entitlements.put(
                    EntitlementKeys.CONTENT_VIDEO_ENABLED.code(),
                    PlanEntitlement.feature(
                            EntitlementKeys.CONTENT_VIDEO_ENABLED.code(), true, EnforcementMode.METER_ONLY));
        }
        UUID version = plans.draftVersion(plan, "UZS", 0, "NONE", null, entitlements, author, "content plan", "corr");
        plans.activate(version, approver, "approved", "corr");
        subscriptions.start(tenant, version, null, author, "content onboarding", "corr");
    }
}
