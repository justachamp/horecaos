package uz.horecaos.platform.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The reference browse endpoint (control-plane IA 6.2), against a real
 * {@code catalog.mxik_reference} table — the join and the currently-valid
 * filter are exactly the kind of predicate a stubbed store would agree with
 * itself about.
 */
class FiscalReferenceControllerTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FiscalReferenceController controller;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE catalog.mxik_reference CASCADE").update();
        controller = new FiscalReferenceController(
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build()));
    }

    @Test
    void statusReportsWhetherTheOfficialListWasEverImported() {
        assertThat(controller.status().loaded()).isFalse();

        mxik("07131100010000000", "Plov ingredient, generic", "2020-01-01", null);

        assertThat(controller.status().loaded()).isTrue();
    }

    @Test
    void searchMatchesCodeOrAnyLabelAndExcludesExpiredRows() {
        mxik("07131100010000000", "Свежие овощи", "2020-01-01", null);
        mxik("07131100020000000", "Сушёные овощи", "2020-01-01", null);
        mxik("09999999990000000", "Withdrawn item", "2015-01-01", "2020-01-01");

        var byLabel = controller.search("овощи", null);
        assertThat(byLabel.items())
                .as("the expired row must not appear even though its own label does not match here")
                .hasSize(2);

        var byCode = controller.search("07131100010000000", null);
        assertThat(byCode.items())
                .singleElement()
                .satisfies(row -> assertThat(row.labelRu()).isEqualTo("Свежие овощи"));

        var expired = controller.search("Withdrawn", null);
        assertThat(expired.items())
                .as("a lapsed classification is not offered for a new receipt")
                .isEmpty();
    }

    @Test
    void refusesAQueryShorterThanTwoCharacters() {
        assertThatThrownBy(() -> controller.search("a", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.search("  ", null)).isInstanceOf(ApiException.class);
    }

    private void mxik(String code, String labelRu, String validFrom, @Nullable String validUntil) {
        jdbc.sql("""
                INSERT INTO catalog.mxik_reference (code, label_ru, label_uz, valid_from, valid_until)
                VALUES (:code, :labelRu, :labelRu, :validFrom, :validUntil)
                """)
                .param("code", code)
                .param("labelRu", labelRu)
                .param("validFrom", LocalDate.parse(validFrom))
                .param("validUntil", validUntil == null ? null : LocalDate.parse(validUntil))
                .update();
    }
}
