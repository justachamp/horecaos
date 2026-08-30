package uz.horecaos.platform.pos.infrastructure.clopos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The query string, and the encoding assumption underneath it.
 *
 * <p>Clopos binds list parameters through PHP bracket notation. The encoding is
 * pinned here because it is an inference about somebody else's framework rather
 * than something Clopos documents, and a silent change to it produces an
 * unfiltered response instead of an error.
 */
class CloposQueryTests {

    @Test
    @DisplayName("the encoded form is a valid URI, which the raw form is only by tolerance")
    void theEncodedFormNeedsNobodysGoodwill() {
        // Recorded rather than assumed: this JDK accepts a raw bracket in a query
        // and puts it on the wire unchanged, so encoding is a choice. It is the
        // right one because RFC 3986 reserves brackets for an address literal in
        // the authority and permits them nowhere else unescaped, which leaves the
        // raw form depending on the tolerance of every proxy in between.
        assertThat(URI.create("https://example.test/products?filters[0][0]=type")
                        .getRawQuery())
                .isEqualTo("filters[0][0]=type");

        String query =
                CloposQuery.create().filterIn(0, "type", List.of("GOODS")).render();

        assertThatCode(() -> URI.create("https://example.test/products" + query))
                .doesNotThrowAnyException();
        assertThat(query).doesNotContain("[").doesNotContain("]");
    }

    @Test
    @DisplayName("the bracket notation is rendered in the shape PHP parses back")
    void filtersRenderAsIndexedPairs() {
        String query = CloposQuery.create()
                .filterIn(0, "type", List.of("GOODS", "DISH"))
                .render();

        assertThat(query)
                .isEqualTo("?filters%5B0%5D%5B0%5D=type"
                        + "&filters%5B0%5D%5B1%5D%5B0%5D=GOODS"
                        + "&filters%5B0%5D%5B1%5D%5B1%5D=DISH");
    }

    @Test
    @DisplayName("paging and relations render alongside filters")
    void aFullCatalogQueryIsAssembledInOnePlace() {
        String query =
                CloposQuery.create().page(2, 100).with(0, "modifications").render();

        assertThat(query).isEqualTo("?page=2&limit=100&with%5B0%5D=modifications");
    }

    @Test
    @DisplayName("a date range is inclusive and rendered as two indexed parameters")
    void theRecoveryReadsDateRange() {
        String query =
                CloposQuery.create().dateRange("2026-08-23", "2026-08-23").render();

        assertThat(query).isEqualTo("?date%5B0%5D=2026-08-23&date%5B1%5D=2026-08-23");
    }

    @Test
    @DisplayName("an empty query renders as nothing rather than as a bare question mark")
    void nothingRendersToNothing() {
        assertThat(CloposQuery.create().render()).isEmpty();
    }
}
