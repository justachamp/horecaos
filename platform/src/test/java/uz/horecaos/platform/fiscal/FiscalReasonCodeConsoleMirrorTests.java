package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;

/**
 * The operations console's blocked-worklist filter is a hand-kept copy of {@link FiscalReasonCode#BLOCKING}
 * ({@code BLOCKING_REASON_CODES} in {@code fiscal-page.ts}), written so that «the filter offers exactly the same
 * set». It was missing {@code LINES_NOT_BUILT} from the day that reason shipped, which is the reason every Click
 * receipt outside a sandbox is blocked for until the real line builder exists: the one reason most documents wait
 * on could not be filtered for. A copy nothing compares is a copy that drifts, so this compares it.
 *
 * <p>Reads the console's source from the monorepo checkout. It is skipped, and says so, in a checkout that has only
 * the platform; the console's own spec holds the other half (it lists the codes the select must offer).
 */
class FiscalReasonCodeConsoleMirrorTests {

    private static final Path CONSOLE_FILTER =
            Path.of("../frontend/operations/src/app/features/finance/fiscal/fiscal-page.ts");

    private static final Pattern LIST =
            Pattern.compile("BLOCKING_REASON_CODES\\s*=\\s*\\[(.*?)]\\s*as const", Pattern.DOTALL);

    private static final Pattern CODE = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    @DisplayName("the console's blocked-worklist filter offers exactly the reasons a document can be blocked for")
    void theConsoleFilterOffersExactlyTheBlockingReasons() throws IOException {
        Assumptions.assumeTrue(
                Files.exists(CONSOLE_FILTER), "the operations console is not in this checkout: " + CONSOLE_FILTER);

        Matcher list = LIST.matcher(Files.readString(CONSOLE_FILTER));
        assertThat(list.find())
                .as("BLOCKING_REASON_CODES is declared in %s", CONSOLE_FILTER)
                .isTrue();
        Set<String> offered = CODE.matcher(list.group(1))
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toSet());

        assertThat(offered)
                .as("the console mirrors FiscalReasonCode.BLOCKING, no more and no fewer")
                .containsExactlyInAnyOrderElementsOf(FiscalReasonCode.BLOCKING);
    }
}
