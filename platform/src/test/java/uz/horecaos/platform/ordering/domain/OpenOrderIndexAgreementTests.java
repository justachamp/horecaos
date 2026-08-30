package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The partial index behind the operations board carries a hard-coded list of
 * statuses, and {@link OrderStatus} carries the same idea as a flag. Two copies of
 * one fact drift, and this one drifts silently and expensively: adding a status
 * without touching V0023 leaves every order in it outside the index, so the board's
 * first query falls back to a sequential scan over the whole order history — on the
 * screen a restaurant reloads every thirty seconds during service.
 *
 * <p>Reads the migration text rather than the live catalog so the check runs
 * without Docker, alongside the other unit tests, and fails in the pull request
 * that introduces the drift rather than in whichever environment first has enough
 * rows to notice.
 */
class OpenOrderIndexAgreementTests {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V0023__order_promise_and_location_place.sql");

    private static final Pattern INDEX_PREDICATE = Pattern.compile(
            "CREATE INDEX ix_orders_open_promise.*?WHERE status IN \\((.*?)\\);",
            Pattern.DOTALL);

    @Test
    void theOpenOrderIndexCoversExactlyTheNonTerminalStatuses() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher matcher = INDEX_PREDICATE.matcher(sql);
        assertThat(matcher.find())
                .as("ix_orders_open_promise and its status predicate still exist in V0023")
                .isTrue();

        Set<String> indexed = Arrays.stream(matcher.group(1).split(","))
                .map(entry -> entry.replace("'", "").strip())
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> nonTerminal = Arrays.stream(OrderStatus.values())
                .filter(status -> !status.terminal())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(indexed)
                .as("every open status is indexed and no terminal status is")
                .containsExactlyInAnyOrderElementsOf(nonTerminal);
    }

    /**
     * The bases the schema accepts and the enum the application writes are the same
     * list. A value added to one and not the other is a constraint violation at the
     * end of a checkout transaction — the most expensive possible place to find it,
     * since everything before it has already happened.
     */
    @Test
    void theSchemaAcceptsExactlyThePromiseBasesTheCodeCanWrite() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher matcher = Pattern
                .compile("ck_order_promise_basis CHECK \\(promise_basis IN \\((.*?)\\)\\);",
                        Pattern.DOTALL)
                .matcher(sql);
        assertThat(matcher.find()).as("ck_order_promise_basis still exists in V0023").isTrue();

        // Strip the SQL comments before splitting: the constraint is documented
        // inline, and a comma inside a sentence would otherwise read as a value.
        String values = matcher.group(1).replaceAll("--[^\\n]*", "");
        Set<String> accepted = Arrays.stream(values.split(","))
                .map(entry -> entry.replace("'", "").strip())
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(accepted).containsExactlyInAnyOrder(
                Arrays.stream(PromiseBasis.values()).map(Enum::name).toArray(String[]::new));
    }
}
