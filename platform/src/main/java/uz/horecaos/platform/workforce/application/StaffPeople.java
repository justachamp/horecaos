package uz.horecaos.platform.workforce.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards.Card;

/**
 * The people a roster and a timesheet are about, through {@code iam}'s ports and nothing else (ADR
 * 0156, ADR 0139).
 *
 * <p>A person is a member id, a Keycloak subject, a non-personal display reference («S-0142») and,
 * <em>only when the caller is allowed to see names</em>, a name. A holder of {@code staff.shift.read}
 * who does not also hold {@code staff.profile.read} (finance, for one) sees the reference and no
 * name, so reading hours is not reading identity.
 *
 * <p>Who may be rostered or clocked at a branch is decided here, once: the person's employment is
 * {@code ACTIVE} and they hold an active job covering the branch. The covering check asks for the
 * capability every tenant-visible job carries ({@link Capability#STAFF_SELF_MANAGE}) at the branch's
 * scope, which {@code AuthorizationService} resolves through the grant chain (a tenant or brand grant
 * covers the branch below it). An answer of "no such member" is the same for a person who does not
 * exist, a person of another tenant and a person with no job at this branch, so none of the three is
 * an oracle for the others.
 */
@Component
public class StaffPeople {

    static final String ACTIVE = "ACTIVE";

    private final StaffMemberCards cards;
    private final StaffDirectory directory;
    private final AuthorizationService authorization;

    public StaffPeople(StaffMemberCards cards, StaffDirectory directory, AuthorizationService authorization) {
        this.cards = cards;
        this.directory = directory;
        this.authorization = authorization;
    }

    /**
     * @param name null unless names were asked for; personal data, never logged
     */
    public record Person(
            UUID memberId,
            String principalSubject,
            String displayReference,
            @Nullable String name,
            String status) {

        @Override
        public String toString() {
            return "Person[" + displayReference + ", " + status + "]";
        }
    }

    public Map<UUID, Person> peopleOf(UUID tenantId, Collection<UUID> memberIds, boolean withNames) {
        Map<UUID, Person> people = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return people;
        }
        cards.cardsOf(tenantId, memberIds).forEach((id, card) -> people.put(id, person(card, withNames)));
        return people;
    }

    /** The member behind a Keycloak subject in this tenant, if the tenant keeps a record for it. */
    public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
        return directory.memberIdOf(tenantId, subject);
    }

    /**
     * The person, only if they may be rostered or clocked at this branch: active employment and a job
     * that covers it.
     */
    public Optional<Person> eligibleAt(Branch branch, UUID memberId, boolean withNames) {
        Card card = cards.cardsOf(branch.tenantId(), List.of(memberId)).get(memberId);
        if (card == null || !ACTIVE.equals(card.status())) {
            return Optional.empty();
        }
        if (!authorization.has(card.principalSubject(), Capability.STAFF_SELF_MANAGE, branch.scope())) {
            return Optional.empty();
        }
        return Optional.of(person(card, withNames));
    }

    /** Everyone who may be rostered at this branch, for the picker. Bounded by a branch's own people. */
    public List<Person> rosterableAt(Branch branch, boolean withNames) {
        return cards.pickable(branch.tenantId()).stream()
                .filter(card -> ACTIVE.equals(card.status()))
                .filter(card ->
                        authorization.has(card.principalSubject(), Capability.STAFF_SELF_MANAGE, branch.scope()))
                .map(card -> person(card, withNames))
                .toList();
    }

    private static Person person(Card card, boolean withNames) {
        return new Person(
                card.memberId(),
                card.principalSubject(),
                card.displayReference(),
                withNames ? card.name() : null,
                card.status());
    }
}
