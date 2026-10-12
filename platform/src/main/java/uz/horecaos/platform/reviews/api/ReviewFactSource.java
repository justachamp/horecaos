package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The reviews submitted in a window, for the day-close facts (ADR 0159, Decision 12, ADR 0043).
 *
 * <p>Implemented by {@code reviews}, read by {@code reporting} -- never a report reading {@code reviews}
 * tables (ADR 0023). The edge is {@code reporting -> reviews}. The customer is an account id here
 * because reporting hashes it into the ADR 0029 pseudonym before it reaches a fact; no comment, no tag
 * label and no courier crosses this port.
 *
 * <p>Every review counts, whatever its comment's publication state: a rating is not words, and the
 * average that leaves out the unwelcome ones is not an average (ADR 0159, Decision 5).
 */
public interface ReviewFactSource {

    List<ReviewFact> submittedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive);

    record ReviewFact(
            UUID reviewId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID customerAccountId,
            int rating,
            boolean hasComment,
            Instant submittedAt,
            List<TagFact> tags) {

        public ReviewFact {
            tags = List.copyOf(tags);
        }
    }

    /** A tag a review carries, by the two code-owned dimensions a report groups by. */
    record TagFact(UUID tagId, String category, String sentiment) {}
}
