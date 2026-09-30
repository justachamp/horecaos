package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The board's {@code reference} parameter, at the validation edge
 * ({@code OrderBoardFilters#query}).
 *
 * <p>{@code JdbcOrderStore.normalisedExternalReference} returns {@code null} for
 * a reference that normalises to nothing (its own Javadoc names {@code "#"} and
 * {@code " - "} as the risk), and {@code OrderListQuery#normalisedReference()}
 * propagates that {@code null} unchanged. Inside the board's SQL, a {@code null}
 * bound parameter reads as "no reference filter was requested" — so, left
 * unguarded, a nonsense reference like {@code "#"} would silently hand back the
 * location's entire unfiltered board instead of an empty result, exactly the
 * ambiguity {@code normalisedExternalReference}'s own comment says it exists to
 * avoid. {@code OrderBoardFilters#query} refuses such input before it ever reaches the query,
 * the same way it already refuses an unrecognised status or fulfilment mode.
 *
 * <p>The factory is called directly, not through {@code
 * OperationsOrderController#board}, because validation happens before any of the
 * controller's collaborators are touched — no database, no Spring context, and no
 * risk of a null service field masking what this test is actually about.
 */
class OperationsOrderBoardReferenceValidationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    @Test
    @DisplayName("a reference that normalises to nothing is refused, not silently dropped")
    void anUnmatchableReferenceIsRefused() {
        for (String raw : List.of("#", " - ", "---", "   #   ")) {
            assertThatThrownBy(() -> boardQuery(raw))
                    .as("\"%s\" has nothing searchable in it", raw)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown ->
                            assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("no reference at all is still \"no filter\", not an error")
    void anAbsentReferenceIsNotRefused() {
        for (String raw : new String[] {null, "", "   "}) {
            assertThatCode(() -> boardQuery(raw))
                    .as("the caller never asked for a reference filter; that is not the same "
                            + "thing as asking for one that matches nothing")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a reference that does normalise is accepted")
    void aSearchableReferenceIsAccepted() {
        assertThatCode(() -> boardQuery("0911-142")).doesNotThrowAnyException();
    }

    /**
     * Builds the board query through {@link OrderBoardFilters#query} — the one
     * validating factory both the branch board and the brand-scoped board use —
     * with every filter but {@code reference} absent.
     */
    private static Object boardQuery(String reference) {
        return OrderBoardFilters.query(
                TENANT,
                BRAND,
                List.of(LOCATION),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                reference,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
