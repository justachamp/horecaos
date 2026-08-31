package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.ordering.api.OrderDirectory.ApprovalDeadlineWarning;

/**
 * ADR 0058's PII lint, extending {@code EventPayloadClassificationTests}' genre
 * to the telegram operations audience: "no customer phone, address, or note in
 * an operations or control-plane message — a deep link into the authorized app
 * carries the reader the rest of the way."
 *
 * <p>An operations Telegram message is not a Kafka event payload, so the
 * existing reflection-based scan over {@code OrderingEvent}/{@code TenancyEvent}
 * payloads does not reach it. What does reach it is
 * {@link uz.horecaos.platform.iam.api.protection.ClassificationScanner}'s own
 * name heuristic, applied directly to the variable maps
 * {@link OrderNotificationTrigger} and {@link ApprovalDeadlineWarningSweeper}
 * actually hand to a template — the entire allowlisted vocabulary an operations
 * message may render with, which is exactly what ADR 0020's "only allowlisted
 * typed variables from a versioned schema can render" makes closed enough to
 * assert about completely.
 */
class TelegramOperationsMessageClassificationTests {

    @Test
    void theRejectionReasonVariablesCarryNoProtectedField() {
        Map<String, String> variables = OrderNotificationTrigger.reasonVariables("KITCHEN_CLOSED");
        assertClean(variables, "OrderNotificationTrigger.reasonVariables");
    }

    @Test
    void theApprovalDeadlineWarningVariablesCarryNoProtectedField() {
        ApprovalDeadlineWarning warning = new ApprovalDeadlineWarning(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "A-1042", Instant.now());

        Map<String, String> variables = ApprovalDeadlineWarningSweeper.variablesFor(warning);
        assertClean(variables, "ApprovalDeadlineWarningSweeper.variablesFor");

        // Named explicitly rather than only scanned: an order number and a
        // deadline instant are the entire vocabulary this warning ever renders
        // with, and a third key appearing here — however innocuous-sounding — is
        // exactly the kind of change that deserves this test failing loudly
        // rather than silently passing a name-heuristic scan that happened not
        // to trigger on it.
        assertThat(variables.keySet()).containsExactlyInAnyOrder("orderNumber", "approvalDeadlineAt");
    }

    @Test
    void theOrderSummaryVariablesEligibilitySharesWithEveryChannelCarryNoProtectedField() {
        // NotificationEligibilityService.variablesFor merges these three onto
        // every notification regardless of channel or audience — including a
        // Telegram operations alert. Asserted here as the literal constants
        // that method writes, since the method itself is private and this is
        // the vocabulary a reviewer changing it has to keep clean.
        assertClean(
                Map.of("orderNumber", "A-1", "amount", "1000 UZS", "currency", "UZS"),
                "NotificationEligibilityService.variablesFor");
    }

    @Test
    void theCheckActuallyDetectsAProtectedVariableName() {
        assertThat(ClassificationScanner.isProtectedName("customerPhone")).isTrue();
        assertThat(ClassificationScanner.isProtectedName("deliveryAddress")).isTrue();
        assertThat(ClassificationScanner.isProtectedName("courierNote")).isTrue();
    }

    private static void assertClean(Map<String, String> variables, String source) {
        Set<String> protectedNames = variables.keySet().stream()
                .filter(ClassificationScanner::isProtectedName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(protectedNames)
                .as("%s must not hand a protected-looking variable to a group message template", source)
                .isEmpty();
    }
}
