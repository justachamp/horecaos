package uz.horecaos.platform.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog.Variable;

/** ADR 0184, D21: which personal values may be merge variables, and where. */
class NotificationVariableCatalogTests {

    private static List<String> names(List<Variable> variables) {
        return variables.stream().map(Variable::name).toList();
    }

    @Test
    @DisplayName("the order classes offer the thirteen-variable set's order facts, and no phone or surname anywhere")
    void theOrderClassesOfferTheOrderFacts() {
        for (NotificationClass notificationClass :
                List.of(NotificationClass.TRANSACTIONAL_REQUIRED, NotificationClass.TRANSACTIONAL_OPTIONAL)) {
            assertThat(names(NotificationVariableCatalog.forClass(notificationClass)))
                    .contains(
                            "orderNumber",
                            "amount",
                            "currency",
                            "restaurantName",
                            "deliveryTime",
                            "preparationTime",
                            "productList",
                            "discountAmount",
                            "deliveryFee",
                            "customerName");
        }
        for (NotificationClass notificationClass : NotificationClass.values()) {
            assertThat(names(NotificationVariableCatalog.forClass(notificationClass)))
                    .as("%s", notificationClass)
                    .doesNotContainAnyElementsOf(NotificationVariableCatalog.DECLINED);
        }
    }

    @Test
    @DisplayName("courierFirstName is offered for the on-the-way message and for no other key")
    void courierFirstNameIsOfferedForOneKey() {
        assertThat(names(NotificationVariableCatalog.forTemplate(
                        NotificationClass.TRANSACTIONAL_OPTIONAL, "ORDER_FULFILLING")))
                .contains("courierFirstName");
        assertThat(names(NotificationVariableCatalog.forTemplate(
                        NotificationClass.TRANSACTIONAL_REQUIRED, "ORDER_CONFIRMED")))
                .doesNotContain("courierFirstName");
        assertThat(names(NotificationVariableCatalog.forClass(NotificationClass.TRANSACTIONAL_OPTIONAL)))
                .doesNotContain("courierFirstName");
        assertThat(names(NotificationVariableCatalog.forTemplate(
                        NotificationClass.OPERATIONS_ALERT, "ORDER_FULFILLING")))
                .as("a group chat is not a customer")
                .doesNotContain("courierFirstName");
    }

    @Test
    @DisplayName("SECURITY has nothing to offer: its wording is the platform's (D19)")
    void securityOffersNothing() {
        assertThat(NotificationVariableCatalog.forClass(NotificationClass.SECURITY))
                .isEmpty();
        assertThat(NotificationVariableCatalog.all()).doesNotContainKey(NotificationClass.SECURITY);
    }

    @Test
    @DisplayName("an operations alert offers no customer name")
    void anOperationsAlertHasNoCustomerName() {
        assertThat(names(NotificationVariableCatalog.forClass(NotificationClass.OPERATIONS_ALERT)))
                .doesNotContain("customerName");
    }

    @Test
    @DisplayName("the two personal values are exactly the two resolved at render")
    void thePersonalValuesAreExactlyTheRenderTimeOnes() {
        assertThat(NotificationVariableCatalog.RESOLVED_AT_RENDER)
                .containsExactlyInAnyOrder("customerName", "courierFirstName");
    }

    @Test
    @DisplayName("a declined variable is refused in every class and key, naming it")
    void declinedVariablesAreRefusedEverywhere() {
        for (String declined : NotificationVariableCatalog.DECLINED) {
            for (NotificationClass notificationClass : NotificationClass.values()) {
                assertThat(NotificationVariableCatalog.refusal(notificationClass, "ORDER_FULFILLING", Set.of(declined)))
                        .as("%s in %s", declined, notificationClass)
                        .get()
                        .asString()
                        .contains(declined);
            }
        }
        assertThat(NotificationVariableCatalog.DECLINED)
                .containsExactlyInAnyOrder("customerPhone", "courierPhone", "courierSurname");
    }

    @Test
    @DisplayName("customerName is refused where the message goes to a group, and welcome where it goes to the customer")
    void customerNameOnlyForCustomerAddressedClasses() {
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.OPERATIONS_ALERT, "ORDER_CONFIRMED", Set.of("customerName")))
                .isPresent();
        for (NotificationClass allowed : List.of(
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationClass.MARKETING)) {
            assertThat(NotificationVariableCatalog.refusal(allowed, "ORDER_CONFIRMED", Set.of("customerName")))
                    .as("%s", allowed)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("courierFirstName is refused outside ORDER_FULFILLING and outside the transactional classes")
    void courierFirstNameOnlyInTheOnTheWayMessage() {
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.TRANSACTIONAL_REQUIRED, "ORDER_CONFIRMED", Set.of("courierFirstName")))
                .isPresent();
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.OPERATIONS_ALERT, "ORDER_FULFILLING", Set.of("courierFirstName")))
                .isPresent();
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.MARKETING, "ORDER_FULFILLING", Set.of("courierFirstName")))
                .isPresent();
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.TRANSACTIONAL_OPTIONAL, "ORDER_FULFILLING", Set.of("courierFirstName")))
                .isEmpty();
    }

    @Test
    @DisplayName("an ordinary variable is never refused")
    void ordinaryVariablesPass() {
        assertThat(NotificationVariableCatalog.refusal(
                        NotificationClass.TRANSACTIONAL_REQUIRED,
                        "ORDER_CONFIRMED",
                        Set.of("orderNumber", "amount", "currency", "code")))
                .isEmpty();
    }
}
