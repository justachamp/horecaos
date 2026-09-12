package uz.horecaos.platform.fiscal.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory;
import uz.horecaos.platform.fiscal.domain.FiscalTerminal;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalHealth;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalKind;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalStatus;

/**
 * {@code fiscal.fiscal_terminals} in SQL (ADR 0038 lines 503-513).
 *
 * <p>Implements {@link FiscalTerminalDirectory} directly, the same shape
 * {@code JdbcLegalEntityStore implements LegalEntityDirectory} uses: one
 * store, one query for "is this location fiscal-capable", so
 * {@code CheckoutSettlementPlanner} and the coverage view can never come to
 * disagree about what "capable" means.
 */
@Repository
public class JdbcFiscalTerminalStore implements FiscalTerminalDirectory {

    private static final TypeReference<Map<String, Boolean>> SNAPSHOT_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, tenant_id, brand_id, location_id, legal_entity_id, terminal_kind,
            provider_binding_id, terminal_reference, capability_snapshot::text AS capability_snapshot,
            status, last_health_check_at, last_health_status, version
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcFiscalTerminalStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The exact predicate the migration's own partial index carries, so this
     * is an index lookup rather than a scan of every terminal a tenant has ever
     * registered.
     */
    @Override
    public boolean hasCapableTerminal(UUID tenantId, UUID locationId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM fiscal.fiscal_terminals
                     WHERE tenant_id = :tenantId AND location_id = :locationId
                       AND status = 'ACTIVE'
                       AND capability_snapshot @> '{"IssueFiscalReceipt": true}'::jsonb
                )
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(Boolean.class)
                .single());
    }

    public void insert(FiscalTerminal terminal, Instant now) {
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_terminals (
                    id, tenant_id, brand_id, location_id, legal_entity_id, terminal_kind,
                    provider_binding_id, terminal_reference, capability_snapshot,
                    status, last_health_check_at, last_health_status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, :kind,
                    :providerBindingId, :reference, cast(:snapshot AS jsonb),
                    :status, :checkedAt, :health, :version, :now, :now)
                """)
                .param("id", terminal.id())
                .param("tenantId", terminal.tenantId())
                .param("brandId", terminal.brandId())
                .param("locationId", terminal.locationId())
                .param("legalEntityId", terminal.legalEntityId())
                .param("kind", terminal.kind().name())
                .param("providerBindingId", terminal.providerBindingId())
                .param("reference", terminal.terminalReference())
                .param("snapshot", objectMapper.writeValueAsString(terminal.capabilitySnapshot()))
                .param("status", terminal.status().name())
                .param("checkedAt", offset(terminal.lastHealthCheckAt()))
                .param(
                        "health",
                        terminal.lastHealthStatus() == null
                                ? null
                                : terminal.lastHealthStatus().name())
                .param("version", terminal.version())
                .param("now", offset(now))
                .update();
    }

    public Optional<FiscalTerminal> find(UUID tenantId, UUID terminalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fiscal.fiscal_terminals WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", terminalId)
                .query(this::toTerminal)
                .optional();
    }

    /**
     * A brand's terminals, ordered the way settings.md's Tab 2 asks: failing
     * health (0) first, never checked (1) next, healthy (2) last — an operator
     * opens this screen to find what is broken, not to browse alphabetically.
     */
    public List<FiscalTerminal> listForBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fiscal.fiscal_terminals
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY CASE
                              WHEN last_health_status = 'UNHEALTHY' THEN 0
                              WHEN last_health_status IS NULL THEN 1
                              ELSE 2
                          END,
                          terminal_reference
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(this::toTerminal)
                .list();
    }

    /**
     * Writes back a status or health-check change under its expected version.
     *
     * @return false when somebody else moved the row first
     */
    public boolean update(FiscalTerminal terminal, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE fiscal.fiscal_terminals
                   SET status = :status,
                       capability_snapshot = cast(:snapshot AS jsonb),
                       last_health_check_at = :checkedAt,
                       last_health_status = :health,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("id", terminal.id())
                        .param("tenantId", terminal.tenantId())
                        .param("status", terminal.status().name())
                        .param("snapshot", objectMapper.writeValueAsString(terminal.capabilitySnapshot()))
                        .param("checkedAt", offset(terminal.lastHealthCheckAt()))
                        .param(
                                "health",
                                terminal.lastHealthStatus() == null
                                        ? null
                                        : terminal.lastHealthStatus().name())
                        .param("now", offset(now))
                        .param("expectedVersion", expectedVersion)
                        .update()
                > 0;
    }

    private FiscalTerminal toTerminal(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        String json = row.getString("capability_snapshot");
        Map<String, Boolean> snapshot =
                json == null || json.isBlank() ? new LinkedHashMap<>() : objectMapper.readValue(json, SNAPSHOT_TYPE);
        String healthStatus = row.getString("last_health_status");
        OffsetDateTime checkedAt = row.getObject("last_health_check_at", OffsetDateTime.class);
        return FiscalTerminal.reconstitute(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                FiscalTerminalKind.valueOf(row.getString("terminal_kind")),
                row.getObject("provider_binding_id", UUID.class),
                row.getString("terminal_reference"),
                snapshot,
                FiscalTerminalStatus.valueOf(row.getString("status")),
                checkedAt == null ? null : checkedAt.toInstant(),
                healthStatus == null ? null : FiscalTerminalHealth.valueOf(healthStatus),
                row.getInt("version"));
    }

    private static @Nullable OffsetDateTime offset(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
