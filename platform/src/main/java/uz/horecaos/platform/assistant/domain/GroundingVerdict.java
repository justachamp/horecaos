package uz.horecaos.platform.assistant.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether a model's reply may be sent, and if it may not, the stable reason.
 *
 * @param citedFactIds the facts the reply is composed from, when grounded
 */
public record GroundingVerdict(boolean grounded, @Nullable RefusalReason reason, Set<String> citedFactIds) {

    public GroundingVerdict {
        citedFactIds = Set.copyOf(citedFactIds);
    }

    public static GroundingVerdict ok(Set<String> citedFactIds) {
        return new GroundingVerdict(true, null, citedFactIds);
    }

    public static GroundingVerdict refused(RefusalReason reason) {
        return new GroundingVerdict(false, reason, Set.of());
    }
}
