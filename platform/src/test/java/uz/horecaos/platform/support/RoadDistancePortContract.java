package uz.horecaos.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What every {@link RoadDistancePort} must do, whoever implements it (ADR 0147,
 * "Testing": the port contract).
 *
 * <p>The resolver is written against these properties and nothing else, which is what
 * lets a second adapter &mdash; the hosted API ADR 0147 names as its fallback &mdash;
 * replace the first without the resolver changing. A subclass supplies a port wired to
 * whatever stands in for its provider; the properties below are then proved against it.
 *
 * <p>Not named {@code *Tests} on purpose: it is abstract, and CI's shard enumeration
 * (tools/ci/shard_tests.py) runs a class per file that Surefire would pick.
 */
public abstract class RoadDistancePortContract {

    protected static final GeoPoint BRANCH = new GeoPoint(41.311081, 69.240562);
    protected static final GeoPoint DOORSTEP = new GeoPoint(41.3309, 69.2641);

    /** The port under test, ready to be asked. */
    protected abstract RoadDistancePort port();

    /**
     * An installation the port can answer for, or null when this port never answers (the
     * unbound default). A port that never answers still owes every other property.
     */
    protected abstract @Nullable UUID answeringInstallation();

    @Test
    @DisplayName("a tariff with no routing installation gets no answer")
    void noInstallationIsNoAnswer() {
        assertThat(port().route(BRANCH, DOORSTEP, null)).isEmpty();
    }

    @Test
    @DisplayName("an installation the port has never heard of gets no answer, not an exception")
    void anUnknownInstallationIsNoAnswer() {
        assertThat(port().route(BRANCH, DOORSTEP, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("an answer names its provider and dataset and carries no negative figure")
    void anAnswerIsAttributedAndSane() {
        UUID installation = answeringInstallation();
        Assumptions.assumeTrue(installation != null, "this port never answers");

        Optional<RoadRoute> route = port().route(BRANCH, DOORSTEP, installation);

        // The resolver stores the provider and the dataset beside the metres, and a
        // road figure without them is the one thing the fee evidence cannot defend.
        assertThat(route).isPresent();
        assertThat(route.get().provider()).isNotBlank();
        assertThat(route.get().datasetVersion()).isNotBlank();
        assertThat(route.get().meters()).isGreaterThanOrEqualTo(0);
        assertThat(route.get().seconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("the same question is answered the same way twice")
    void theSameQuestionTwiceIsTheSameAnswer() {
        UUID installation = answeringInstallation();

        assertThat(port().route(BRANCH, DOORSTEP, installation))
                .isEqualTo(port().route(BRANCH, DOORSTEP, installation));
    }

    @Test
    @DisplayName("two points on opposite sides of the earth are a question, never a thrown exception")
    void aNonsenseQuestionDoesNotThrow() {
        UUID installation = answeringInstallation();

        assertThatCode(() -> port().route(new GeoPoint(-89.9, -179.9), new GeoPoint(89.9, 179.9), installation))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty answer is the only way to say 'do not know': a present one is never a placeholder")
    void aPresentAnswerIsNeverAPlaceholder() {
        UUID installation = answeringInstallation();
        Assumptions.assumeTrue(installation != null, "this port never answers");

        Optional<RoadRoute> route = port().route(BRANCH, DOORSTEP, installation);

        // ADR 0037: "not a stub that invents a plausible number". A zero-metre road
        // between two points a kilometre apart is exactly such a number.
        assertThat(route).isPresent();
        assertThat(route.get().meters()).isPositive();
    }
}
