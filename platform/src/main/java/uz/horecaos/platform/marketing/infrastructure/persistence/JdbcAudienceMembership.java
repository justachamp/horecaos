package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.api.AudienceMembershipPort;

/**
 * The audiences a customer belongs to, for the {@code CUSTOMER_SEGMENT}
 * promotion condition (ADR 0140, ADR 0044).
 *
 * <p>A segment is an audience, named by its id. Membership is read from each
 * audience's most recent {@code READY} snapshot and counts every candidate the
 * predicates matched, included or excluded alike: the five subtractions of an
 * audience snapshot (lifecycle, consent, suppression, frequency cap, endpoint)
 * decide whether a customer may be <em>written to</em>, and price eligibility is
 * not a message. Nothing about the customer leaves this module but the audience
 * ids they match.
 */
@Component
public class JdbcAudienceMembership implements AudienceMembershipPort {

    private final JdbcClient jdbc;

    public JdbcAudienceMembership(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> segmentsOf(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT latest.audience_id::text
                FROM (
                    SELECT DISTINCT ON (s.audience_id) s.audience_id, s.id AS snapshot_id
                    FROM marketing.audience_snapshots s
                    JOIN marketing.audiences a ON a.id = s.audience_id AND a.tenant_id = s.tenant_id
                    WHERE s.tenant_id = :tenantId AND s.brand_id = :brandId
                      AND s.status = 'READY' AND a.status = 'ACTIVE'
                    ORDER BY s.audience_id, s.built_at DESC
                ) latest
                JOIN marketing.audience_snapshot_members m
                  ON m.snapshot_id = latest.snapshot_id AND m.tenant_id = :tenantId
                 AND m.customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> knownAudiences(UUID tenantId, UUID brandId, Collection<String> audienceIds) {
        Set<UUID> parsed = new HashSet<>();
        for (String id : audienceIds) {
            try {
                parsed.add(UUID.fromString(id));
            } catch (IllegalArgumentException notAnId) {
                // Not an audience id, so not a known audience.
            }
        }
        if (parsed.isEmpty()) {
            return Set.of();
        }
        return jdbc
                .sql("""
                SELECT id::text FROM marketing.audiences
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'ACTIVE' AND id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", parsed.toArray(UUID[]::new))
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
    }
}
