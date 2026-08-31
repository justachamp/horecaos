package uz.horecaos.platform.payments.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;

/**
 * ADR 0058's PII lint (see {@code
 * uz.horecaos.platform.notifications.application.TelegramOperationsMessageClassificationTests}
 * for the genre this extends) applied to {@link PaymentOperationsAlertTrigger},
 * which lives outside {@code notifications} — a module-boundary cycle
 * (documented on that class's own Javadoc) put it in {@code payments}
 * instead, and a package-private helper method is only reachable from a
 * test in the same package.
 */
class PaymentOperationsMessageClassificationTests {

    @Test
    void theReasonVariablesCarryNoProtectedField() {
        Map<String, String> variables = PaymentOperationsAlertTrigger.reasonVariables("DECLINED_INSUFFICIENT_FUNDS");
        assertClean(variables, "PaymentOperationsAlertTrigger.reasonVariables");

        // A reason code is the entire vocabulary either payments alert ever
        // renders with, named explicitly rather than only scanned: a second
        // key appearing here — a masked card number, a provider message —
        // is exactly the drift this test exists to catch even when it
        // happens not to trigger the scanner's own name heuristic.
        assertThat(variables.keySet()).containsExactly("reasonCode");
    }

    @Test
    void aNullReasonCodeRendersAsUnspecifiedRatherThanNull() {
        Map<String, String> variables = PaymentOperationsAlertTrigger.reasonVariables(null);
        assertThat(variables).containsEntry("reasonCode", "UNSPECIFIED");
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
