package uz.horecaos.platform.pos.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;

/**
 * ADR 0058's PII lint (see {@code
 * uz.horecaos.platform.notifications.application.TelegramOperationsMessageClassificationTests}
 * for the genre this extends) applied to {@link PosExportOperationsAlertTrigger},
 * which lives outside {@code notifications} — a module-boundary cycle
 * (documented on that class's own Javadoc) put it in {@code pos} instead.
 */
class PosExportOperationsMessageClassificationTests {

    @Test
    void theVariablesCarryNoProtectedField() {
        Map<String, String> variables = PosExportOperationsAlertTrigger.variables("EXPORT_NEEDS_OPERATOR");
        assertClean(variables, "PosExportOperationsAlertTrigger.variables");

        // A reason code is the entire vocabulary this alert ever renders
        // with — never a customer's name, phone or address, which the
        // export candidates this alert points an operator at may well
        // carry as evidence, but this message never does.
        assertThat(variables.keySet()).containsExactly("reasonCode");
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
