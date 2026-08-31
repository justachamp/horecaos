package uz.horecaos.platform.migration.infrastructure.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.migration.api.ExtractionSpec;

/**
 * The one field on an extraction spec that reaches SQL unchecked (ADR 0024).
 *
 * <p>{@code ExtractionSpec} pattern-checks the table, the stable key, the
 * watermark and every selected column, and cannot check the filter the same way:
 * a predicate is not an identifier. That is a reason to check it differently, not
 * a reason to concatenate it into a statement that runs against the production
 * legacy database.
 */
class LegacyFilterTests {

    @Test
    @DisplayName("an ordinary migration predicate passes")
    void realPredicatesAreAccepted() {
        assertThat(JdbcLegacySourceReader.requireSafePredicate("deleted_at IS NULL"))
                .isEqualTo("deleted_at IS NULL");
        assertThat(JdbcLegacySourceReader.requireSafePredicate("status IN ('DONE', 'PAID')"))
                .isEqualTo("status IN ('DONE', 'PAID')");
        assertThat(JdbcLegacySourceReader.requireSafePredicate("vendor_id = 42 AND price > 0"))
                .isEqualTo("vendor_id = 42 AND price > 0");
    }

    @Test
    @DisplayName("a filter that could end the statement or start another is refused")
    void aFilterCannotCarryASecondStatement() {
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("1=1; DROP TABLE orders")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("1=1 -- and the ORDER BY is gone")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("1=1 /* and so is the LIMIT */")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("id = $$x$$")))
                .as("a dollar-quote opens a literal the allowlist cannot see the end of")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unterminated literal is refused rather than swallowing the ORDER BY")
    void anOpenQuoteIsRefused() {
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("status = 'DONE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The check has to happen where the string becomes SQL, not merely be
     * available. The reader below has no database at all: a filter that reached
     * the statement would fail trying to use one, and a filter that is refused
     * first fails as an argument.
     */
    // Deliberately passes a null JdbcClient below: this test's whole point is that
    // the filter check fails before the reader ever touches it, so a real one is
    // unnecessary. JdbcClient's own type is genuinely non-null everywhere else. The
    // suppression has to cover the whole method because NullAway's local-variable
    // nullability tracks the value from its declaration through to where it is
    // finally passed as an argument inside the lambda below.
    @SuppressWarnings("NullAway")
    @Test
    @DisplayName("the reader refuses the spec before it opens a connection")
    void theReaderChecksTheFilterBeforeItReadsAnything() {
        var spec = new ExtractionSpec("ORDER", "orders", "id", null, List.of("id"), "1=1; DROP TABLE orders");

        JdbcClient neverUsed = null;
        assertThat(catchThrowable(() -> new JdbcLegacySourceReader(neverUsed).readPage(spec, null, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a filter long enough to be a query of its own is refused")
    void aFilterIsAPredicateAndNotAQuery() {
        assertThat(catchThrowable(() -> JdbcLegacySourceReader.requireSafePredicate("a = 1 AND ".repeat(60) + "b = 2")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
