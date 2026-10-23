package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.courier.application.CourierFirstNameDisclosure;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.fulfillment.api.ActiveCourierAssignmentsPort;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * ADR 0045's «on the way, with the courier's first name» and ADR 0184's D21: the one path that reveals a
 * courier's name for a customer reveals the first word and nothing else, says why when it does, and writes
 * the fact without the name.
 */
class CourierFirstNameDisclosureTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID COURIER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private ActiveCourierAssignmentsPort assignments;
    private JdbcCourierStore couriers;
    private FieldProtection protection;
    private final List<AuditFact> audited = new ArrayList<>();
    private CourierFirstNameDisclosure disclosure;

    @BeforeEach
    void setUp() {
        assignments = mock(ActiveCourierAssignmentsPort.class);
        couriers = mock(JdbcCourierStore.class);
        protection = mock(FieldProtection.class);
        audited.clear();
        disclosure = new CourierFirstNameDisclosure(
                assignments, couriers, protection, audited::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void courierCarries(String fullName) {
        when(assignments.assignedCouriers(TENANT, Set.of(ORDER))).thenReturn(Map.of(ORDER, COURIER));
        when(couriers.findCourier(TENANT, COURIER))
                .thenReturn(Optional.of(
                        new CourierRow(COURIER, TENANT, UUID.randomUUID(), "sub", "K-014", serialised(), "ACTIVE", 1)));
        when(protection.reveal(any(), any(ProtectedValue.class), any(), anyString()))
                .thenReturn(fullName);
    }

    /** A value {@link ProtectedValue#deserialize} accepts; the protection mock never opens it. */
    private static String serialised() {
        return new ProtectedValue("key", "AES-256-GCM", new byte[] {1, 2}, new byte[] {3, 4}, 1).serialize();
    }

    @Test
    @DisplayName("it answers the first word of the courier's name, and not the surname")
    void theFirstWordOnly() {
        courierCarries("Bobur Karimov");

        assertThat(disclosure.firstNameCarrying(TENANT, ORDER)).contains("Bobur");
    }

    @Test
    @DisplayName("a one-word name is returned whole, and a blank one answers nothing")
    void edgeNames() {
        courierCarries("Bobur");
        assertThat(disclosure.firstNameCarrying(TENANT, ORDER)).contains("Bobur");

        courierCarries("   ");
        assertThat(disclosure.firstNameCarrying(TENANT, ORDER)).isEmpty();
    }

    @Test
    @DisplayName(
            "the reveal is made under a declared purpose and leaves an audit fact that names the courier and not the name")
    void theRevealIsAudited() {
        courierCarries("Bobur Karimov");

        disclosure.firstNameCarrying(TENANT, ORDER);

        verify(protection)
                .reveal(
                        any(),
                        any(ProtectedValue.class),
                        any(FieldProtection.RecordRef.class),
                        org.mockito.ArgumentMatchers.eq("CUSTOMER_ORDER_STATUS_MESSAGE"));
        assertThat(audited).singleElement().satisfies(fact -> {
            assertThat(fact.actionCode()).isEqualTo("courier.first_name.disclosed_to_customer");
            assertThat(fact.targetId()).isEqualTo(COURIER);
            assertThat(fact.toString()).doesNotContain("Bobur").doesNotContain("Karimov");
            assertThat(fact.changeDocument().toString()).doesNotContain("Bobur").doesNotContain("Karimov");
        });
    }

    @Test
    @DisplayName("an order with no in-house courier reveals nothing and audits nothing")
    void noCourierNoReveal() {
        when(assignments.assignedCouriers(TENANT, Set.of(ORDER))).thenReturn(Map.of());

        assertThat(disclosure.firstNameCarrying(TENANT, ORDER)).isEmpty();

        verify(protection, never()).reveal(any(), any(), any(), anyString());
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("a courier row that is gone reveals nothing")
    void aVanishedCourierRevealsNothing() {
        when(assignments.assignedCouriers(TENANT, Set.of(ORDER))).thenReturn(Map.of(ORDER, COURIER));
        when(couriers.findCourier(TENANT, COURIER)).thenReturn(Optional.empty());

        assertThat(disclosure.firstNameCarrying(TENANT, ORDER)).isEmpty();

        verify(protection, never()).reveal(any(), any(), any(), anyString());
        assertThat(audited).isEmpty();
    }
}
