package uz.horecaos.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalAction.Worklist;

/**
 * The approvals worklist shows each request under a plain-language label for its action
 * ({@code approval-action-labels.ts}, operations row 9.4); an action it does not name reaches the checker as a
 * dotted code. Twelve codes were named when four more tenant producers had been registered without one
 * ({@code ordering.amendment.decrease}, {@code courier.run-pay.change}, {@code
 * reporting.business-day-boundary.change}, {@code iam.platform-grant.manage}): the console falls back to the raw
 * code for a code it does not know, so nothing failed. This is the test that makes the next one fail.
 *
 * <p>Every action that is listed on a tenant's worklist must be named. A platform-worklist action is decided on
 * the control plane's own queue and is not required here (one is named anyway, so the list may be a superset).
 *
 * <p>Reads the console's source from the monorepo checkout; skipped, and says so, in a checkout that has only the
 * platform. The console's spec holds the other half: that each label exists in all three languages.
 */
class ApprovalActionLabelMirrorTests {

    private static final Path LABELS =
            Path.of("../frontend/operations/src/app/features/staff/approval-action-labels.ts");

    private static final Pattern MAP = Pattern.compile("ACTION_LABEL_KEYS[^=]*=\\s*\\{(.*?)\\n};", Pattern.DOTALL);

    private static final Pattern CODE = Pattern.compile("^\\s*'([a-z][a-z0-9.\\-]*)'\\s*:", Pattern.MULTILINE);

    @Test
    @DisplayName("every action a tenant's worklist can show has a label in the console")
    void everyTenantWorklistActionIsLabelled() throws IOException {
        Assumptions.assumeTrue(Files.exists(LABELS), "the operations console is not in this checkout: " + LABELS);

        Matcher map = MAP.matcher(Files.readString(LABELS));
        assertThat(map.find()).as("ACTION_LABEL_KEYS is declared in %s", LABELS).isTrue();
        Set<String> labelled = CODE.matcher(map.group(1))
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toSet());

        Set<String> tenantWorklist = Arrays.stream(ApprovalAction.values())
                .filter(action -> action.worklist() == Worklist.TENANT)
                .map(ApprovalAction::code)
                .collect(Collectors.toSet());

        assertThat(tenantWorklist)
                .as("a tenant worklist action exists to be scanned")
                .isNotEmpty();
        assertThat(labelled)
                .as("a checker must be shown words, not a dotted code: add the missing action to "
                        + "approval-action-labels.ts and to the staff catalogues in all three languages")
                .containsAll(tenantWorklist);
    }

    @Test
    @DisplayName("every code the console labels is a registered action, so a retired one does not linger")
    void noLabelOutlivesItsAction() throws IOException {
        Assumptions.assumeTrue(Files.exists(LABELS), "the operations console is not in this checkout: " + LABELS);

        Matcher map = MAP.matcher(Files.readString(LABELS));
        assertThat(map.find()).isTrue();
        Set<String> labelled = CODE.matcher(map.group(1))
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toSet());

        assertThat(labelled)
                .as("each labelled code is declared in ApprovalAction")
                .allSatisfy(code -> assertThat(ApprovalAction.find(code)).isPresent());
    }
}
