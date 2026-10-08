package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code marketing.offers}: versioned references to a promotion or an accrual rule
 * (ADR 0112).
 *
 * <p>One row per version, never updated in place once published. The statements
 * that change a row are conditional on its state and its {@code row_version}, so a
 * stale form and a lost race both read as "nothing changed" rather than overwriting
 * somebody else's edit.
 */
@Repository
public class JdbcOfferStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, lineage_id, version_number, status, display_name,
            pricing_promotion_id, loyalty_accrual_rule_id, valid_from, valid_until, audience_id,
            allowed_channels, template_key, template_version, banner_image_reference,
            created_by, published_by, published_at, row_version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcOfferStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewOffer offer) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", offer.id());
        parameters.put("tenantId", offer.tenantId());
        parameters.put("brandId", offer.brandId());
        parameters.put("lineageId", offer.lineageId());
        parameters.put("versionNumber", offer.versionNumber());
        parameters.put("displayName", offer.displayName());
        parameters.put("promotionId", offer.pricingPromotionId());
        parameters.put("accrualRuleId", offer.loyaltyAccrualRuleId());
        parameters.put("validFrom", utc(offer.validFrom()));
        parameters.put("validUntil", utc(offer.validUntil()));
        parameters.put("audienceId", offer.audienceId());
        // The values are members of a closed enum the caller validated, so a Postgres
        // array literal is safe to build and avoids a driver-specific Array object.
        parameters.put("channels", "{" + String.join(",", offer.allowedChannels()) + "}");
        parameters.put("templateKey", offer.templateKey());
        parameters.put("templateVersion", offer.templateVersion());
        parameters.put("banner", offer.bannerImageReference());
        parameters.put("createdBy", offer.createdBy());
        parameters.put("now", utc(offer.createdAt()));

        jdbc.sql("""
                INSERT INTO marketing.offers (
                    id, tenant_id, brand_id, lineage_id, version_number, status, display_name,
                    pricing_promotion_id, loyalty_accrual_rule_id, valid_from, valid_until, audience_id,
                    allowed_channels, template_key, template_version, banner_image_reference,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :lineageId, :versionNumber, 'DRAFT', :displayName,
                    :promotionId, :accrualRuleId, :validFrom, :validUntil, :audienceId,
                    CAST(:channels AS varchar[]), :templateKey, :templateVersion, :banner,
                    :createdBy, :now, :now)
                """).params(parameters).update();
    }

    public Optional<OfferRow> find(UUID tenantId, UUID offerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM marketing.offers WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", offerId)
                .query(JdbcOfferStore::row)
                .optional();
    }

    /** Every version of every offer a brand has, newest first. */
    public List<OfferRow> listByBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.offers WHERE tenant_id = :tenantId AND brand_id = :brandId"
                        + " ORDER BY created_at DESC, version_number DESC")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcOfferStore::row)
                .list();
    }

    public List<OfferRow> lineage(UUID tenantId, UUID lineageId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.offers WHERE tenant_id = :tenantId AND lineage_id = :lineageId"
                        + " ORDER BY version_number")
                .param("tenantId", tenantId)
                .param("lineageId", lineageId)
                .query(JdbcOfferStore::row)
                .list();
    }

    public int latestVersionNumber(UUID tenantId, UUID lineageId) {
        Integer latest = jdbc.sql("""
                SELECT max(version_number) FROM marketing.offers
                 WHERE tenant_id = :tenantId AND lineage_id = :lineageId
                """)
                .param("tenantId", tenantId)
                .param("lineageId", lineageId)
                .query((row, number) -> row.getObject(1, Integer.class))
                .optional()
                .orElse(null);
        return latest == null ? 0 : latest;
    }

    /**
     * Rewrites a DRAFT. Conditional on the row being a draft at the version the caller
     * read, so a published version can never be edited by this path and a stale form
     * changes nothing.
     */
    public boolean rewriteDraft(
            UUID tenantId, UUID offerId, int expectedRowVersion, NewOffer replacement, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.offers
                   SET display_name = :displayName, pricing_promotion_id = :promotionId,
                       loyalty_accrual_rule_id = :accrualRuleId, valid_from = :validFrom,
                       valid_until = :validUntil, audience_id = :audienceId,
                       allowed_channels = CAST(:channels AS varchar[]), template_key = :templateKey,
                       template_version = :templateVersion, banner_image_reference = :banner,
                       row_version = row_version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT' AND row_version = :expected
                """)
                        .param("displayName", replacement.displayName())
                        .param("promotionId", replacement.pricingPromotionId())
                        .param("accrualRuleId", replacement.loyaltyAccrualRuleId())
                        .param("validFrom", utc(replacement.validFrom()))
                        .param("validUntil", utc(replacement.validUntil()))
                        .param("audienceId", replacement.audienceId())
                        .param("channels", "{" + String.join(",", replacement.allowedChannels()) + "}")
                        .param("templateKey", replacement.templateKey())
                        .param("templateVersion", replacement.templateVersion())
                        .param("banner", replacement.bannerImageReference())
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", offerId)
                        .param("expected", expectedRowVersion)
                        .update()
                == 1;
    }

    /**
     * Publishes a draft, superseding whatever version of the lineage was in force.
     *
     * <p>Two statements the caller runs in one transaction: the old published row is
     * superseded first, because the partial unique index admits only one published
     * version per lineage.
     */
    public boolean publish(
            UUID tenantId, UUID offerId, UUID lineageId, int expectedRowVersion, UUID publishedBy, Instant now) {
        jdbc.sql("""
                UPDATE marketing.offers
                   SET status = 'SUPERSEDED', row_version = row_version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND lineage_id = :lineageId AND status = 'PUBLISHED' AND id <> :id
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("lineageId", lineageId)
                .param("id", offerId)
                .update();
        return jdbc.sql("""
                UPDATE marketing.offers
                   SET status = 'PUBLISHED', published_by = :by, published_at = :now,
                       row_version = row_version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT' AND row_version = :expected
                """)
                        .param("by", publishedBy)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", offerId)
                        .param("expected", expectedRowVersion)
                        .update()
                == 1;
    }

    /**
     * Retires a version: it stops being selectable, and every scenario that already references it stops applying it.
     *
     * <p>A superseded version can be retired too. Supersession leaves it in the hands of the
     * campaigns approved with it, so retirement is the one act that takes it back from them.
     */
    public boolean retire(UUID tenantId, UUID offerId, int expectedRowVersion, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.offers
                   SET status = 'RETIRED', row_version = row_version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')
                   AND row_version = :expected
                """)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", offerId)
                        .param("expected", expectedRowVersion)
                        .update()
                == 1;
    }

    private static OfferRow row(ResultSet row, int number) throws SQLException {
        String[] channels = (String[]) row.getArray("allowed_channels").getArray();
        return new OfferRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("lineage_id", UUID.class),
                row.getInt("version_number"),
                row.getString("status"),
                row.getString("display_name"),
                row.getObject("pricing_promotion_id", UUID.class),
                row.getObject("loyalty_accrual_rule_id", UUID.class),
                row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                instant(row.getObject("valid_until", OffsetDateTime.class)),
                row.getObject("audience_id", UUID.class),
                Arrays.asList(channels),
                row.getString("template_key"),
                row.getObject("template_version", Integer.class),
                row.getString("banner_image_reference"),
                row.getObject("created_by", UUID.class),
                row.getObject("published_by", UUID.class),
                instant(row.getObject("published_at", OffsetDateTime.class)),
                row.getInt("row_version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewOffer(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID lineageId,
            int versionNumber,
            String displayName,
            @Nullable UUID pricingPromotionId,
            @Nullable UUID loyaltyAccrualRuleId,
            Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable UUID audienceId,
            List<String> allowedChannels,
            String templateKey,
            @Nullable Integer templateVersion,
            @Nullable String bannerImageReference,
            UUID createdBy,
            Instant createdAt) {}

    public record OfferRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID lineageId,
            int versionNumber,
            String status,
            String displayName,
            @Nullable UUID pricingPromotionId,
            @Nullable UUID loyaltyAccrualRuleId,
            Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable UUID audienceId,
            List<String> allowedChannels,
            String templateKey,
            @Nullable Integer templateVersion,
            @Nullable String bannerImageReference,
            UUID createdBy,
            @Nullable UUID publishedBy,
            @Nullable Instant publishedAt,
            int rowVersion,
            Instant createdAt,
            Instant updatedAt) {

        /**
         * Whether a campaign that was approved with this version still carries it.
         *
         * <p>Publishing a newer version supersedes this one for anyone <em>choosing</em> an
         * offer from now on (authoring a step still requires a published version), and does
         * nothing to a campaign approved with it: an approved scenario keeps
         * the version it was approved with until it is revised and approved again (ADR 0112),
         * for the reason an approver signs a specific offer and not a name. Only a retirement,
         * which is somebody saying this offer must stop, or the end of its own window,
         * takes it away.
         */
        public boolean carriedByApprovedCampaigns() {
            return "PUBLISHED".equals(status) || "SUPERSEDED".equals(status);
        }

        /** {@link #carriedByApprovedCampaigns} and inside the version's own validity window at {@code now}. */
        public boolean honouredAt(Instant now) {
            return carriedByApprovedCampaigns()
                    && !validFrom.isAfter(now)
                    && (validUntil == null || validUntil.isAfter(now));
        }
    }
}
