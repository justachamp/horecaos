package uz.horecaos.platform.reviews.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards.Card;

/**
 * Staff members as a case sees them (ADR 0159, Decision 8; ADR 0139).
 *
 * <p>A case stores a staff member's <em>id</em>, never a name: the owner is "resolved to a name only when
 * read", so a person who renames themselves or leaves is not left behind in a table. An event, a log line
 * and an audit change document carry the non-personal {@code display_reference} ({@code S-0142}) instead.
 *
 * <p>The name is a person's name (ADR 0029 PERSONAL) and goes only into a response the caller's own
 * capability has already authorised.
 */
@Component
public class StaffReferences {

    private final StaffDirectory directory;
    private final StaffMemberCards cards;

    public StaffReferences(StaffDirectory directory, StaffMemberCards cards) {
        this.directory = directory;
        this.cards = cards;
    }

    /**
     * One person, for a response.
     *
     * @param name {@code null} when the member has no name on file; callers then show the reference
     */
    public record Person(
            UUID memberId,
            String displayReference,
            @Nullable String name,
            boolean active) {

        /** What a screen prints: the name when there is one, else the non-personal reference. */
        public String label() {
            return name == null || name.isBlank() ? displayReference : name;
        }

        @Override
        public String toString() {
            return "Person[memberId=" + memberId + ", displayReference=" + displayReference + "]";
        }
    }

    /** The staff member id of the signed-in subject in this tenant, or empty (a support session has none). */
    public Optional<UUID> memberOf(UUID tenantId, String subject) {
        return directory.memberIdOf(tenantId, subject);
    }

    /** These members of this tenant. An id of another tenant, or none, is absent. */
    public Map<UUID, Person> peopleOf(UUID tenantId, Collection<UUID> memberIds) {
        Map<UUID, Person> people = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return people;
        }
        Map<UUID, Card> found = cards.cardsOf(tenantId, new LinkedHashSet<>(memberIds));
        found.forEach((id, card) ->
                people.put(id, new Person(id, card.displayReference(), card.name(), !"ENDED".equals(card.status()))));
        return people;
    }

    public Optional<Person> person(UUID tenantId, UUID memberId) {
        return Optional.ofNullable(
                peopleOf(tenantId, java.util.List.of(memberId)).get(memberId));
    }
}
