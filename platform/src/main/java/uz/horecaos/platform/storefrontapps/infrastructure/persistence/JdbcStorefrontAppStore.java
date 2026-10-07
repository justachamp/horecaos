package uz.horecaos.platform.storefrontapps.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.storefrontapps.domain.AuthorisationStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;

/**
 * Every read and write this module owns (ADR 0070).
 *
 * <p>The registry is platform-owned, so its queries carry no tenant predicate;
 * the authorisation queries carry the tenant and brand, and a lookup for one
 * brand's row can never answer with another's.
 *
 * <p>Nothing here is cached. The identity check reads {@link #findApp} and one
 * authorisation row on every storefront request, because revoking an app has to
 * take effect on the very next one (ADR 0070) and ADR 0033 keeps correctness
 * decisions off cache state.
 */
@Component
public class JdbcStorefrontAppStore {

    private static final String APP_COLUMNS = """
            a.id, a.name, a.vendor, a.client_type, a.first_party, a.origin_allowlist, a.secret_reference,
            a.secret_rotated_at, a.status, a.conformance_status, a.conformance_contract_version,
            a.conformance_recorded_at, a.conformance_note, a.registered_by, a.version, a.created_at, a.updated_at""";

    private final JdbcClient jdbc;

    public JdbcStorefrontAppStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------------- the registry

    public void insertApp(AppRow app) {
        jdbc.sql("""
                INSERT INTO storefront_app.apps
                    (id, name, vendor, client_type, first_party, origin_allowlist, secret_reference,
                     secret_rotated_at, status, registered_by, version, created_at, updated_at)
                VALUES (:id, :name, :vendor, :clientType, :firstParty, CAST(:origins AS text[]), :secretReference,
                        :secretRotatedAt, :status, :registeredBy, 0, :now, :now)
                """)
                .param("id", app.id())
                .param("name", app.name())
                .param("vendor", app.vendor())
                .param("clientType", app.clientType().name())
                .param("firstParty", app.firstParty())
                .param("origins", app.originAllowlist().toArray(String[]::new))
                .param("secretReference", app.secretReference())
                .param("secretRotatedAt", utc(app.secretRotatedAt()))
                .param("status", app.status().name())
                .param("registeredBy", app.registeredBy())
                .param("now", utc(app.createdAt()))
                .update();
    }

    public Optional<AppRow> findApp(UUID appId) {
        return jdbc.sql("SELECT " + APP_COLUMNS + " FROM storefront_app.apps a WHERE a.id = :id")
                .param("id", appId)
                .query((row, number) -> mapApp(row))
                .optional();
    }

    /** Every registered app, with how many brands currently authorise it, for the control plane. */
    public List<AppSummary> listApps() {
        return jdbc.sql("SELECT " + APP_COLUMNS + """
                       , (SELECT count(*) FROM storefront_app.authorisations z
                           WHERE z.app_id = a.id AND z.status = 'ACTIVE') AS active_authorisations,
                         (SELECT count(DISTINCT z.tenant_id) FROM storefront_app.authorisations z
                           WHERE z.app_id = a.id AND z.status = 'ACTIVE') AS active_tenants
                  FROM storefront_app.apps a
                 ORDER BY lower(a.name), a.id
                """)
                .query((row, number) -> new AppSummary(
                        mapApp(row), row.getLong("active_authorisations"), row.getLong("active_tenants")))
                .list();
    }

    public boolean updateDetails(
            UUID appId, long expectedVersion, String name, String vendor, List<String> origins, Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.apps
                           SET name = :name, vendor = :vendor, origin_allowlist = CAST(:origins AS text[]),
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("name", name)
                        .param("vendor", vendor)
                        .param("origins", origins.toArray(String[]::new))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean updateStatus(UUID appId, long expectedVersion, StorefrontAppStatus status, Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.apps
                           SET status = :status, version = version + 1, updated_at = :now
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("status", status.name())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean replaceSecretReference(UUID appId, long expectedVersion, String reference, Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.apps
                           SET secret_reference = :reference, secret_rotated_at = :now,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND version = :expectedVersion AND client_type = 'CONFIDENTIAL'
                        """)
                        .param("id", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("reference", reference)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean recordConformance(
            UUID appId,
            long expectedVersion,
            String status,
            String contractVersion,
            @Nullable String note,
            Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.apps
                           SET conformance_status = :status, conformance_contract_version = :contractVersion,
                               conformance_recorded_at = :now, conformance_note = :note,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND version = :expectedVersion
                        """)
                        .param("id", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("status", status)
                        .param("contractVersion", contractVersion)
                        .param("note", note)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // ----------------------------------------------------------- authorisations

    public Optional<AuthorisationRow> findAuthorisation(UUID tenantId, UUID brandId, UUID appId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, app_id, status, granted_by, granted_at, revoked_by, revoked_at, version
                  FROM storefront_app.authorisations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND app_id = :appId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("appId", appId)
                .query((row, number) -> mapAuthorisation(row))
                .optional();
    }

    public void insertAuthorisation(AuthorisationRow row) {
        jdbc.sql("""
                INSERT INTO storefront_app.authorisations
                    (id, tenant_id, brand_id, app_id, status, granted_by, granted_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :appId, 'ACTIVE', :grantedBy, :grantedAt, 0, :grantedAt, :grantedAt)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("brandId", row.brandId())
                .param("appId", row.appId())
                .param("grantedBy", row.grantedBy())
                .param("grantedAt", utc(row.grantedAt()))
                .update();
    }

    /** Authorising again after a revocation reuses the row; the history is the audit trail. */
    public boolean reactivateAuthorisation(
            UUID tenantId, UUID brandId, UUID appId, long expectedVersion, String grantedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.authorisations
                           SET status = 'ACTIVE', granted_by = :grantedBy, granted_at = :now,
                               revoked_by = NULL, revoked_at = NULL, version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND brand_id = :brandId AND app_id = :appId
                           AND version = :expectedVersion AND status = 'REVOKED'
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("appId", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("grantedBy", grantedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean revokeAuthorisation(
            UUID tenantId, UUID brandId, UUID appId, long expectedVersion, String revokedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE storefront_app.authorisations
                           SET status = 'REVOKED', revoked_by = :revokedBy, revoked_at = :now,
                               version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND brand_id = :brandId AND app_id = :appId
                           AND version = :expectedVersion AND status = 'ACTIVE'
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("appId", appId)
                        .param("expectedVersion", expectedVersion)
                        .param("revokedBy", revokedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Every app a tenant could choose for one brand, beside what that brand has decided
     * about it. A retired app is not offered at all; a suspended one is shown, because the
     * brand that authorised it should see why it stopped serving.
     */
    public List<CatalogueRow> catalogueForBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + APP_COLUMNS + """
                       , z.id AS authorisation_id, z.status AS authorisation_status,
                         z.granted_by, z.granted_at, z.revoked_by, z.revoked_at,
                         z.version AS authorisation_version
                  FROM storefront_app.apps a
                  LEFT JOIN storefront_app.authorisations z
                    ON z.app_id = a.id AND z.tenant_id = :tenantId AND z.brand_id = :brandId
                 WHERE a.status <> 'RETIRED' OR z.id IS NOT NULL
                 ORDER BY lower(a.name), a.id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> {
                    UUID authorisationId = row.getObject("authorisation_id", UUID.class);
                    AuthorisationRow authorisation = authorisationId == null
                            ? null
                            : new AuthorisationRow(
                                    authorisationId,
                                    tenantId,
                                    brandId,
                                    row.getObject("id", UUID.class),
                                    AuthorisationStatus.valueOf(row.getString("authorisation_status")),
                                    row.getString("granted_by"),
                                    instant(row, "granted_at"),
                                    row.getString("revoked_by"),
                                    nullableInstant(row, "revoked_at"),
                                    row.getLong("authorisation_version"));
                    return new CatalogueRow(mapApp(row), authorisation);
                })
                .list();
    }

    /** Every brand's standing with one app, across tenants, for the control plane. */
    public List<AppAuthorisationView> authorisationsForApp(UUID appId) {
        return jdbc.sql("""
                SELECT z.id, z.tenant_id, t.display_name AS tenant_name, z.brand_id, b.display_name AS brand_name,
                       z.status, z.granted_by, z.granted_at, z.revoked_by, z.revoked_at, z.version
                  FROM storefront_app.authorisations z
                  JOIN tenant.tenants t ON t.id = z.tenant_id
                  JOIN tenant.brands b ON b.tenant_id = z.tenant_id AND b.id = z.brand_id
                 WHERE z.app_id = :appId
                 ORDER BY t.display_name, b.display_name, z.id
                """)
                .param("appId", appId)
                .query((row, number) -> new AppAuthorisationView(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("tenant_name"),
                        row.getObject("brand_id", UUID.class),
                        row.getString("brand_name"),
                        AuthorisationStatus.valueOf(row.getString("status")),
                        row.getString("granted_by"),
                        instant(row, "granted_at"),
                        row.getString("revoked_by"),
                        nullableInstant(row, "revoked_at"),
                        row.getLong("version")))
                .list();
    }

    // ----------------------------------------------------- the request-time reads

    /** What the brand has decided about the app, or empty when it never has. */
    public Optional<AuthorisationStatus> brandStanding(UUID tenantId, UUID brandId, UUID appId) {
        return jdbc.sql("""
                SELECT status FROM storefront_app.authorisations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND app_id = :appId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("appId", appId)
                .query(String.class)
                .optional()
                .map(AuthorisationStatus::valueOf);
    }

    /**
     * What a tenant has decided about the app across its brands: ACTIVE when any brand
     * authorises it, REVOKED when brands have decided and every one withdrew, and empty
     * when none ever did. For a path that names a tenant but no brand.
     */
    public Optional<AuthorisationStatus> tenantStanding(UUID tenantId, UUID appId) {
        return jdbc.sql("""
                SELECT status FROM storefront_app.authorisations
                 WHERE tenant_id = :tenantId AND app_id = :appId
                 ORDER BY (status = 'ACTIVE') DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("appId", appId)
                .query(String.class)
                .optional()
                .map(AuthorisationStatus::valueOf);
    }

    // ------------------------------------------------------------------ mapping

    private static AppRow mapApp(ResultSet row) throws SQLException {
        return new AppRow(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("vendor"),
                StorefrontAppClientType.valueOf(row.getString("client_type")),
                row.getBoolean("first_party"),
                origins(row.getArray("origin_allowlist")),
                row.getString("secret_reference"),
                nullableInstant(row, "secret_rotated_at"),
                StorefrontAppStatus.valueOf(row.getString("status")),
                row.getString("conformance_status"),
                row.getString("conformance_contract_version"),
                nullableInstant(row, "conformance_recorded_at"),
                row.getString("conformance_note"),
                row.getString("registered_by"),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static AuthorisationRow mapAuthorisation(ResultSet row) throws SQLException {
        return new AuthorisationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("app_id", UUID.class),
                AuthorisationStatus.valueOf(row.getString("status")),
                row.getString("granted_by"),
                instant(row, "granted_at"),
                row.getString("revoked_by"),
                nullableInstant(row, "revoked_at"),
                row.getLong("version"));
    }

    private static List<String> origins(@Nullable Array column) throws SQLException {
        List<String> result = new ArrayList<>();
        if (column != null) {
            for (Object element : (Object[]) column.getArray()) {
                result.add(String.valueOf(element));
            }
        }
        return List.copyOf(result);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------- rows

    /** A registered app, secret reference included: this never leaves the module. */
    public record AppRow(
            UUID id,
            String name,
            String vendor,
            StorefrontAppClientType clientType,
            boolean firstParty,
            List<String> originAllowlist,
            @Nullable String secretReference,
            @Nullable Instant secretRotatedAt,
            StorefrontAppStatus status,
            String conformanceStatus,
            @Nullable String conformanceContractVersion,
            @Nullable Instant conformanceRecordedAt,
            @Nullable String conformanceNote,
            String registeredBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record AppSummary(AppRow app, long activeAuthorisations, long activeTenants) {}

    public record AuthorisationRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID appId,
            AuthorisationStatus status,
            String grantedBy,
            Instant grantedAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            long version) {}

    /** An app beside what one brand has decided about it; the authorisation is null when it never has. */
    public record CatalogueRow(AppRow app, @Nullable AuthorisationRow authorisation) {}

    public record AppAuthorisationView(
            UUID id,
            UUID tenantId,
            String tenantName,
            UUID brandId,
            String brandName,
            AuthorisationStatus status,
            String grantedBy,
            Instant grantedAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            long version) {}
}
