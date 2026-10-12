package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The schema half of ADR 0161: V0557 puts one row per scanned unit in {@code ordering.order_line_marks}, with
 * the constraints that make "a unit is on one live mark at a time" a fact of the database and not of the
 * code, and a grant that lets the application release a mark and never delete one.
 *
 * <p>The privileges are proved against the group role the application connects through; the rest of the
 * suite connects as the migration role, a superuser, and so cannot notice a missing GRANT. Rows are written
 * with foreign keys off for the fixture session (the {@code PhysicalAttributesMigrationTests} precedent) so a
 * test states the mark it needs and not the order behind it; the table's own constraints still run.
 */
class OrderLineMarksMigrationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    @Test
    @DisplayName(
            "the application role may insert and release a mark and read it, but never delete one or edit its code")
    void grants() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            String role = TestDatabase.APPLICATION_ROLE;
            for (String privilege : new String[] {"SELECT", "INSERT"}) {
                assertThat(tablePrivilege(jdbc, role, privilege)).as(privilege).isTrue();
            }
            assertThat(tablePrivilege(jdbc, role, "DELETE"))
                    .as("evidence is released, not removed")
                    .isFalse();
            assertThat(tablePrivilege(jdbc, role, "UPDATE"))
                    .as("no table-wide UPDATE")
                    .isFalse();
            for (String column : new String[] {"released_at", "released_reason"}) {
                assertThat(columnPrivilege(jdbc, role, column, "UPDATE"))
                        .as(column)
                        .isTrue();
            }
            for (String column :
                    new String[] {"code_encrypted", "code_lookup_hash", "gtin", "sequence", "order_line_id", "tenant_id"
                    }) {
                assertThat(columnPrivilege(jdbc, role, column, "UPDATE"))
                        .as("%s is a fact of the capture and stays read-only", column)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName(
            "a live code is unique in a tenant, can be reused after a release, and means the same text in another tenant")
    void liveUniqueness() throws SQLException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);
            UUID line = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            insert(jdbc, TENANT, order, line, 1, "hash-A");

            assertThatThrownBy(() -> insert(jdbc, TENANT, order, UUID.randomUUID(), 1, "hash-A"))
                    .as("the same live code on any line of the tenant")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_order_line_marks_live_code");
            assertThatThrownBy(() -> insert(jdbc, TENANT, order, line, 1, "hash-B"))
                    .as("two live marks at one sequence of a line")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_order_line_marks_live_sequence");

            insert(jdbc, OTHER_TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, "hash-A");

            jdbc.sql("UPDATE ordering.order_line_marks SET released_at = now(), released_reason = 'MISSCAN' "
                            + "WHERE tenant_id = :t AND code_lookup_hash = 'hash-A'")
                    .param("t", TENANT)
                    .update();
            insert(jdbc, TENANT, order, line, 1, "hash-A");
            assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE tenant_id = :t")
                            .param("t", TENANT)
                            .query(Integer.class)
                            .single())
                    .as("the released row is still there beside the live one")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the table refuses a malformed GTIN, an unknown stage, method or reason, and a half-recorded release")
    void checks() throws SQLException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);
            assertCheck(jdbc, "gtin", "1234", "ck_order_line_mark_gtin");
            assertCheck(jdbc, "capture_stage", "NOWHERE", "ck_order_line_mark_stage");
            assertCheck(jdbc, "entry_method", "FAX", "ck_order_line_mark_method");
            assertCheck(jdbc, "marking_scheme", "QR", "ck_order_line_mark_scheme");
            assertCheck(jdbc, "sequence", 0, "ck_order_line_mark_sequence");
            // A release is recorded whole: a time and a reason together, and the reason from the closed list.
            assertCheck(jdbc, "released_reason", "MISSCAN", "ck_order_line_mark_release");
            assertCheck(jdbc, "released_at", java.time.OffsetDateTime.now(), "ck_order_line_mark_release");
            java.util.Map<String, Object> badReason = validRow();
            badReason.put("released_at", java.time.OffsetDateTime.now());
            badReason.put("released_reason", "BECAUSE");
            assertThatThrownBy(() -> insertRow(jdbc, badReason))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_order_line_mark_release_reason");
            java.util.Map<String, Object> released = validRow();
            released.put("released_at", java.time.OffsetDateTime.now());
            released.put("released_reason", "UNIT_DAMAGED");
            insertRow(jdbc, released);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.Map<String, Object> validRow() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("tenant_id", TENANT);
        row.put("order_id", UUID.randomUUID());
        row.put("order_line_id", UUID.randomUUID());
        row.put("sequence", 1);
        row.put("gtin", "04600000000015");
        row.put("code_encrypted", "x");
        row.put("code_lookup_hash", UUID.randomUUID().toString());
        row.put("capture_stage", "PICK");
        row.put("entry_method", "SCANNER");
        row.put("gtin_checked", true);
        row.put("captured_by_actor_type", "USER");
        row.put("captured_by_actor_id", "op");
        row.put("captured_at", java.time.OffsetDateTime.now());
        return row;
    }

    private static void insertRow(JdbcClient jdbc, java.util.Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String placeholders =
                row.keySet().stream().map(name -> ":" + name).collect(java.util.stream.Collectors.joining(", "));
        jdbc.sql("INSERT INTO ordering.order_line_marks (" + columns + ") VALUES (" + placeholders + ")")
                .params(row)
                .update();
    }

    /** Writes a row that is valid in every column but one, and expects the named constraint to refuse it. */
    private static void assertCheck(JdbcClient jdbc, String column, Object badValue, String constraint) {
        java.util.Map<String, Object> row = validRow();
        row.put(column, badValue);
        assertThatThrownBy(() -> insertRow(jdbc, row))
                .as(column + " = " + badValue)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void insert(JdbcClient jdbc, UUID tenant, UUID order, UUID line, int sequence, String hash) {
        jdbc.sql("""
                INSERT INTO ordering.order_line_marks (id, tenant_id, order_id, order_line_id, sequence, gtin,
                    code_encrypted, code_lookup_hash, capture_stage, entry_method, gtin_checked,
                    captured_by_actor_type, captured_by_actor_id, captured_at)
                VALUES (gen_random_uuid(), :t, :o, :l, :s, '04600000000015', 'x', :h, 'PICK', 'SCANNER', true,
                    'USER', 'op', now())
                """)
                .param("t", tenant)
                .param("o", order)
                .param("l", line)
                .param("s", sequence)
                .param("h", hash)
                .update();
    }

    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    private static boolean tablePrivilege(JdbcClient jdbc, String role, String privilege) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_table_privilege(:role, 'ordering.order_line_marks', :p)")
                .param("role", role)
                .param("p", privilege)
                .query(Boolean.class)
                .single());
    }

    private static boolean columnPrivilege(JdbcClient jdbc, String role, String column, String privilege) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_column_privilege(:role, 'ordering.order_line_marks', :c, :p)")
                .param("role", role)
                .param("c", column)
                .param("p", privilege)
                .query(Boolean.class)
                .single());
    }
}
