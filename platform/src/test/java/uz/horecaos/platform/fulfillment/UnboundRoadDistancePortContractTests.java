package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.infrastructure.routing.DeliveryRoutingConfiguration;
import uz.horecaos.platform.support.RoadDistancePortContract;

/**
 * The port a context without a routing adapter falls back to, held to the same contract
 * (ADR 0147): it never answers, and it still owes every property that is not about
 * answering. This is the rollback, so it has to be as well-behaved as the adapter it
 * stands in for.
 */
class UnboundRoadDistancePortContractTests {

    private final RoadDistancePort unbound = new DeliveryRoutingConfiguration().unboundRoadDistancePort();

    @Test
    @DisplayName("a tariff with no routing installation gets no answer")
    void noInstallationIsNoAnswer() {
        RoadDistancePortContract.noInstallationIsNoAnswer(unbound);
    }

    @Test
    @DisplayName("an installation the port has never heard of gets no answer, not an exception")
    void anUnknownInstallationIsNoAnswer() {
        RoadDistancePortContract.anUnknownInstallationIsNoAnswer(unbound);
    }

    @Test
    @DisplayName("the same question is answered the same way twice")
    void theSameQuestionTwiceIsTheSameAnswer() {
        RoadDistancePortContract.theSameQuestionTwiceIsTheSameAnswer(unbound, UUID.randomUUID());
    }

    @Test
    @DisplayName("two points on opposite sides of the earth are a question, never a thrown exception")
    void aNonsenseQuestionDoesNotThrow() {
        RoadDistancePortContract.aNonsenseQuestionDoesNotThrow(unbound, UUID.randomUUID());
    }

    @Test
    @DisplayName("it answers nothing for any installation, so every ROAD fee says RADIUS_FALLBACK")
    void itNeverAnswers() {
        assertThat(unbound.route(RoadDistancePortContract.BRANCH, RoadDistancePortContract.DOORSTEP, UUID.randomUUID()))
                .isEmpty();
    }
}
