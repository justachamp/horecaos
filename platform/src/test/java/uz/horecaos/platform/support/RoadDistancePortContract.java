package uz.horecaos.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What every {@link RoadDistancePort} must do, whoever implements it (ADR 0147,
 * "Testing": the port contract).
 *
 * <p>The resolver is written against these properties and nothing else, which is what lets
 * a second adapter &mdash; the hosted API ADR 0147 names as its fallback &mdash; replace the
 * first without the resolver changing. Each implementation's own test class wires a port
 * to whatever stands in for its provider and calls every property here from a test method
 * of its own.
 *
 * <p>Static assertions rather than an abstract class with test methods, on purpose: CI's
 * shard enumeration (tools/ci/shard_tests.py) runs one class per file Surefire would pick
 * and expects a report from every such class that holds tests, and an abstract base never
 * produces one. A base that holds no test annotation cannot be mistaken for either.
 */
public final class RoadDistancePortContract {

    public static final GeoPoint BRANCH = new GeoPoint(41.311081, 69.240562);
    public static final GeoPoint DOORSTEP = new GeoPoint(41.3309, 69.2641);

    private RoadDistancePortContract() {}

    /** A tariff with no routing installation gets no answer. */
    public static void noInstallationIsNoAnswer(RoadDistancePort port) {
        assertThat(port.route(BRANCH, DOORSTEP, null)).isEmpty();
    }

    /** An installation the port has never heard of gets no answer, and not an exception. */
    public static void anUnknownInstallationIsNoAnswer(RoadDistancePort port) {
        assertThat(port.route(BRANCH, DOORSTEP, UUID.randomUUID())).isEmpty();
    }

    /**
     * An answer names its provider and dataset and carries no negative figure: the resolver
     * stores the provider and the dataset beside the metres, and a road figure without them
     * is the one thing the fee evidence cannot defend.
     */
    public static void anAnswerIsAttributedAndSane(RoadDistancePort port, @Nullable UUID answeringInstallation) {
        Assumptions.assumeTrue(answeringInstallation != null, "this port never answers");

        Optional<RoadRoute> route = port.route(BRANCH, DOORSTEP, answeringInstallation);

        assertThat(route).isPresent();
        assertThat(route.get().provider()).isNotBlank();
        assertThat(route.get().datasetVersion()).isNotBlank();
        assertThat(route.get().meters()).isGreaterThanOrEqualTo(0);
        assertThat(route.get().seconds()).isGreaterThanOrEqualTo(0);
    }

    /** The same question is answered the same way twice. */
    public static void theSameQuestionTwiceIsTheSameAnswer(RoadDistancePort port, @Nullable UUID installation) {
        assertThat(port.route(BRANCH, DOORSTEP, installation)).isEqualTo(port.route(BRANCH, DOORSTEP, installation));
    }

    /** Two points on opposite sides of the earth are a question and never a thrown exception. */
    public static void aNonsenseQuestionDoesNotThrow(RoadDistancePort port, @Nullable UUID installation) {
        assertThatCode(() -> port.route(new GeoPoint(-89.9, -179.9), new GeoPoint(89.9, 179.9), installation))
                .doesNotThrowAnyException();
    }

    /**
     * A present answer is never a placeholder: ADR 0037's "not a stub that invents a
     * plausible number". A zero-metre road between two points a kilometre apart is exactly
     * such a number.
     */
    public static void aPresentAnswerIsNeverAPlaceholder(RoadDistancePort port, @Nullable UUID answeringInstallation) {
        Assumptions.assumeTrue(answeringInstallation != null, "this port never answers");

        Optional<RoadRoute> route = port.route(BRANCH, DOORSTEP, answeringInstallation);

        assertThat(route).isPresent();
        assertThat(route.get().meters()).isPositive();
    }
}
