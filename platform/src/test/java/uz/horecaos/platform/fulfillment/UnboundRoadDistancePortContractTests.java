package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
class UnboundRoadDistancePortContractTests extends RoadDistancePortContract {

    private final RoadDistancePort unbound = new DeliveryRoutingConfiguration().unboundRoadDistancePort();

    @Override
    protected RoadDistancePort port() {
        return unbound;
    }

    @Override
    protected @Nullable UUID answeringInstallation() {
        return null;
    }

    @Test
    @DisplayName("it answers nothing for any installation, so every ROAD fee says RADIUS_FALLBACK")
    void itNeverAnswers() {
        assertThat(unbound.route(BRANCH, DOORSTEP, UUID.randomUUID())).isEmpty();
    }
}
