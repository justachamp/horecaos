package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import uz.horecaos.platform.support.TestDatabase;

/** Proves the local-only Flyway location yields a coherent, browseable demo tenant. */
class LocalFixtureMigrationTests {

    private static final String TENANT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String BRAND_ID = "10000000-0000-0000-0000-000000000002";
    private static final String LOCATION_ID = "10000000-0000-0000-0000-000000000003";

    @Test
    void localFixtureSeedsAPublishedMenuServiceabilityAndDeliveryZone() {
        try (TestDatabase.Handle database = TestDatabase.migrated()) {
            Flyway.configure()
                    .dataSource(database.dataSource())
                    .locations("classpath:db/migration", "classpath:db/local-fixtures")
                    .load()
                    .migrate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

            assertThat(count(jdbc, """
                    SELECT count(*) FROM tenant.tenants
                    WHERE id = '%s'::uuid AND status = 'ACTIVE'
                    """.formatted(TENANT_ID))).isOne();
            assertThat(count(jdbc, """
                    SELECT count(*) FROM catalog.publications
                    WHERE tenant_id = '%s'::uuid AND brand_id = '%s'::uuid
                      AND status = 'PUBLISHED' AND channel = 'STOREFRONT'
                    """.formatted(TENANT_ID, BRAND_ID))).isOne();
            assertThat(count(jdbc, """
                    SELECT count(*) FROM catalog.location_offerings
                    WHERE tenant_id = '%s'::uuid AND location_id = '%s'::uuid
                    """.formatted(TENANT_ID, LOCATION_ID))).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT ST_Covers(area, ST_SetSRID(ST_MakePoint(69.2410, 41.3120), 4326)::geography)
                    FROM fulfillment.service_zone_versions
                    WHERE tenant_id = '%s'::uuid AND status = 'ACTIVE'
                    """.formatted(TENANT_ID), Boolean.class))
                    .isTrue();
        }
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        // COUNT(*) always yields exactly one row and is never SQL NULL; the
        // fallback is unreachable in practice and only satisfies the checker's
        // honest reading of JdbcTemplate's declared-nullable return.
        return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class), 0L);
    }
}
