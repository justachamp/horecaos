package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Offboarding a tenant does not shorten the retention of its fiscal evidence (ADR 0166 Decision 10, ADR 0029).
 *
 * <p>The obligation to keep a fiscal receipt is the tenant's, and the platform answers for having kept what it was
 * handed. The retention key is policy (a figure), but what makes the period hold is structure, and structure is what
 * offboarding would have to defeat to shorten it. There is no offboarding workflow that erases anything today, and
 * this pins that nothing could be added by accident: the application role may not delete a fiscal row, a tenant row
 * cannot be removed while a fiscal document still names it, and no statement in the platform's sources deletes from
 * a fiscal table. A feature that needs to erase evidence has to change one of these on purpose, and fail this test
 * by name.
 */
class TenantOffboardingKeepsFiscalEvidenceTests {

    private static final List<String> EVIDENCE_TABLES = List.of(
            "fiscal.fiscal_documents",
            "fiscal.fiscal_sent_requests",
            "fiscal.fiscal_correction_requests",
            "payments.fiscal_documents",
            "payments.fiscal_sent_requests");

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the schema checks");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("the role the application runs as holds no DELETE on any table that keeps fiscal evidence")
    void noRoleTheApplicationUsesMayEraseEvidence() {
        for (String table : EVIDENCE_TABLES) {
            boolean mayDelete =
                    Boolean.TRUE.equals(jdbc.sql("SELECT has_table_privilege('horecaos_application', :table, 'DELETE')")
                            .param("table", table)
                            .query(Boolean.class)
                            .single());
            assertThat(mayDelete)
                    .as(
                            "%s: a fiscal document is evidence, and retention holds by there being no way to erase it",
                            table)
                    .isFalse();
            boolean mayTruncate = Boolean.TRUE.equals(
                    jdbc.sql("SELECT has_table_privilege('horecaos_application', :table, 'TRUNCATE')")
                            .param("table", table)
                            .query(Boolean.class)
                            .single());
            assertThat(mayTruncate).as("%s: nor truncate it", table).isFalse();
        }
    }

    @Test
    @DisplayName("a tenant row cannot be removed while a fiscal document still names it")
    void aTenantWithEvidenceCannotBeDeleted() {
        // NO ACTION: deleting the parent fails while a child exists. CASCADE or SET NULL would let removing a
        // tenant remove or orphan its receipts, which is exactly the shortening this test exists to refuse.
        for (String constraint :
                List.of("fk_fiscal_document_tenant", "fk_fiscal_correction_tenant", "fk_fiscal_sent_request_tenant")) {
            String onDelete = jdbc.sql("SELECT confdeltype::text FROM pg_constraint WHERE conname = :name")
                    .param("name", constraint)
                    .query(String.class)
                    .single();
            assertThat(onDelete)
                    .as("%s must be ON DELETE NO ACTION ('a'), not CASCADE ('c') or SET NULL ('n')", constraint)
                    .isEqualTo("a");
        }
    }

    @Test
    @DisplayName("no statement in the platform's sources deletes from a fiscal table")
    void noSourceStatementErasesEvidence() throws IOException {
        Path root = Path.of("src/main");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(path ->
                            path.toString().endsWith(".java") || path.toString().endsWith(".sql"))
                    .filter(path -> !path.toString().contains("/db/migration/"))
                    .filter(path -> {
                        try {
                            String text = Files.readString(path, StandardCharsets.UTF_8)
                                    .replaceAll("\\s+", " ")
                                    .toLowerCase(java.util.Locale.ROOT);
                            return text.contains("delete from fiscal.")
                                    || text.contains("delete from payments.fiscal_")
                                    || text.contains("truncate table fiscal.")
                                    || text.contains("truncate fiscal.");
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("offboarding must not be able to shorten the retention of fiscal evidence (ADR 0166 Decision 10)")
                .isEmpty();
    }
}
