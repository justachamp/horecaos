package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcCourierJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.sourcing.JdbcDeliveryCompletionAdapter;
import uz.horecaos.platform.ordering.api.PaymentStatusFacts;
import uz.horecaos.platform.ordering.infrastructure.JdbcDeliveryOrderPort;
import uz.horecaos.platform.support.TestDatabase;

/**
 * "Money is held" has one definition for the modules that can share one and two private copies for the one
 * that cannot (ADR 0168 Decision 2), and nothing but this test stops the copies drifting.
 *
 * <p>{@code fulfillment} may not import {@code ordering}: {@code fulfillment -> ordering -> pricing ->
 * fulfillment} is a cycle {@code ModularArchitectureTests} refuses, so {@code JdbcCourierJobStore} and
 * {@code JdbcDeliveryCompletionAdapter} each keep their own {@code AUTHORIZED}-or-{@code CAPTURED}
 * predicate. This drives every copy over <em>every value the column allows</em> (read from the CHECK
 * constraint itself, so a value added to the column and to no predicate fails here) and fails when any two
 * disagree about which are held.
 */
class PaymentStatusParityTests {

    private static TestDatabase.Handle db;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("every copy of 'money is held' agrees on every value ordering.orders.payment_status_projection allows")
    void allCopiesAgreeOverEveryProjectionValue() throws Exception {
        List<String> values = projectionValues();
        assertThat(values)
                .as("the column's set, which the predicates must cover value by value")
                .containsExactlyInAnyOrder(
                        "NOT_REQUIRED", "PENDING", "AUTHORIZED", "CAPTURED", "FAILED", "VOIDED", "REFUNDED");

        Method ordering = predicate(JdbcDeliveryOrderPort.class);
        Method courierJobs = predicate(JdbcCourierJobStore.class);
        Method completion = predicate(JdbcDeliveryCompletionAdapter.class);

        List<String> disagreements = new ArrayList<>();
        for (String value : values) {
            boolean shared = PaymentStatusFacts.moneyIsHeld(value);
            check(disagreements, "JdbcDeliveryOrderPort", value, shared, (Boolean) ordering.invoke(null, value));
            check(disagreements, "JdbcCourierJobStore", value, shared, (Boolean) courierJobs.invoke(null, value));
            check(disagreements, "JdbcDeliveryCompletionAdapter", value, shared, (Boolean)
                    completion.invoke(null, value));
        }
        assertThat(disagreements)
                .as("copies that disagree with PaymentStatusFacts")
                .isEmpty();
        assertThat(values.stream().filter(PaymentStatusFacts::moneyIsHeld))
                .containsExactlyInAnyOrder("AUTHORIZED", "CAPTURED");
    }

    @Test
    @DisplayName("required-and-absent is PENDING and FAILED only, disjoint from held, and never NOT_REQUIRED")
    void requiredAndAbsentIsExactlyPendingAndFailed() {
        for (String value : projectionValues()) {
            boolean expected = value.equals("PENDING") || value.equals("FAILED");
            assertThat(PaymentStatusFacts.moneyRequiredAndAbsent(value))
                    .as(value)
                    .isEqualTo(expected);
            assertThat(PaymentStatusFacts.moneyRequiredAndAbsent(value) && PaymentStatusFacts.moneyIsHeld(value))
                    .as("%s is never both held and awaited", value)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a value this build does not know gates nothing and holds nothing (the fail-open rule)")
    void anUnknownValueFailsOpen() {
        assertThat(PaymentStatusFacts.moneyRequiredAndAbsent("SOME_FUTURE_STATUS"))
                .isFalse();
        assertThat(PaymentStatusFacts.moneyIsHeld("SOME_FUTURE_STATUS")).isFalse();
        assertThat(PaymentStatusFacts.moneyRequiredAndAbsent(null)).isFalse();
        assertThat(PaymentStatusFacts.moneyIsHeld(null)).isFalse();
    }

    private static void check(List<String> disagreements, String copy, String value, boolean shared, boolean own) {
        if (shared != own) {
            disagreements.add("%s says %s for %s; PaymentStatusFacts says %s".formatted(copy, own, value, shared));
        }
    }

    private static Method predicate(Class<?> owner) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod("isPrepaid", String.class);
        method.setAccessible(true);
        return method;
    }

    private static List<String> projectionValues() {
        String definition =
                JdbcClient.create(db.dataSource()).sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conname = 'ck_order_payment_projection'
                        """).query(String.class).single();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
