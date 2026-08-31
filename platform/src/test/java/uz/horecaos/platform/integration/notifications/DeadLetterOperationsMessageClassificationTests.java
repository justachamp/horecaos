package uz.horecaos.platform.integration.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;

/**
 * ADR 0058's PII lint (see {@code
 * uz.horecaos.platform.notifications.application.TelegramOperationsMessageClassificationTests}
 * for the genre this extends) applied to {@link DeadLetterOperationsAlertTrigger},
 * which lives outside {@code notifications} — a module-boundary cycle
 * (documented on that class's own Javadoc) put it in {@code integration}
 * instead.
 */
class DeadLetterOperationsMessageClassificationTests {

    @Test
    void theVariablesCarryNoProtectedField() {
        Map<String, String> variables = DeadLetterOperationsAlertTrigger.variables("OUTBOX", "RETRY_EXHAUSTED");
        assertClean(variables, "DeadLetterOperationsAlertTrigger.variables");

        // A source and a reason code are the entire vocabulary this alert
        // ever renders with — never the payload that dead-lettered, which
        // ADR 0029/0032 keep out of a dead-letter summary precisely because
        // an operator's authorization to work the queue does not imply
        // authorization to see the underlying record.
        assertThat(variables.keySet()).containsExactlyInAnyOrder("source", "reasonCode");
    }

    private static void assertClean(Map<String, String> variables, String source) {
        Set<String> protectedNames = variables.keySet().stream()
                .filter(ClassificationScanner::isProtectedName)
                .collect(Collectors.toSet());
        assertThat(protectedNames)
                .as("%s must not hand a protected-looking variable to a group message template", source)
                .isEmpty();
    }
}
