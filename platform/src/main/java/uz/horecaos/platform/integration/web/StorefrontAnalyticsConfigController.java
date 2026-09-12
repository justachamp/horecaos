package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three public, non-secret analytics identifiers a storefront injects
 * into a customer's browser (ADR 0106, gap-map row 10.8e).
 *
 * <p>Unauthenticated by design, mirroring {@code StorefrontCatalogController}'s
 * own posture: a GTM container id, a GA4 measurement id, and a Search Console
 * verification token are exactly the kind of value a browser's view-source
 * already reveals once injected, so there is nothing here to protect behind a
 * token. What this endpoint protects is tenant isolation on the *write* side —
 * an operator installs and binds these through the authenticated operations
 * surface ({@code OperationsProviderInstallationController}); this is the one
 * read that turns a brand id in a URL into "which tenant's identifiers load
 * here", which is the only question a storefront page can answer for itself.
 *
 * <p>A tenant may register up to three {@code ANALYTICS} installations —
 * {@code GOOGLE_TAG_MANAGER}, {@code GOOGLE_ANALYTICS_4}, {@code
 * GOOGLE_SEARCH_CONSOLE} — each bound separately, each carrying exactly one
 * non-secret field. This merges whichever of the three are actively bound to
 * the brand into one response; a field with no active binding is null, which
 * the storefront reads as "inject nothing for this one."
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Storefront analytics", description = "Public, non-secret analytics identifiers for a brand")
public class StorefrontAnalyticsConfigController {

    private final JdbcClient jdbc;

    public StorefrontAnalyticsConfigController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/tenants/{tenantId}/brands/{brandId}/analytics")
    @Operation(
            summary = "This brand's analytics installs, for client-side injection",
            description = "No installation, no binding, or a suspended one: every field is null, which "
                    + "the storefront reads as 'inject nothing'. Cacheable — these values change only "
                    + "when an operator reconfigures analytics, never per request.")
    ResponseEntity<AnalyticsConfigResponse> analyticsConfig(@PathVariable UUID tenantId, @PathVariable UUID brandId) {

        Map<String, String> fields = new HashMap<>();
        jdbc.sql("""
                SELECT kv.key, kv.value
                  FROM integration.bindings b
                  JOIN integration.installations i
                    ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                  CROSS JOIN LATERAL jsonb_each_text(coalesce(i.non_sensitive_config, '{}'::jsonb)) AS kv(key, value)
                 WHERE b.tenant_id = :tenantId
                   AND b.brand_id = :brandId
                   AND b.status = 'ACTIVE'
                   AND i.provider_category = 'ANALYTICS'
                   AND i.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> Map.entry(row.getString("key"), row.getString("value")))
                .list()
                .forEach(entry -> fields.put(entry.getKey(), entry.getValue()));

        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(new AnalyticsConfigResponse(
                        fields.get("gtmContainerId"),
                        fields.get("ga4MeasurementId"),
                        fields.get("searchConsoleVerificationToken")));
    }

    public record AnalyticsConfigResponse(
            @Nullable String gtmContainerId,
            @Nullable String ga4MeasurementId,
            @Nullable String searchConsoleVerificationToken) {}
}
