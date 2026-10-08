package uz.horecaos.platform.iam.application.staff;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;

/**
 * The few facts about a staff member the terminals need that live behind the staff record's envelope encryption (ADR 0139,
 * ADR 0029): how a colleague reads on a shared screen, and which digits a PIN must not equal.
 *
 * <p>Deliberately narrow, and the only door the terminals have into the personal columns. It returns a first name and the
 * initial of a last name for a roster, and a set of digit strings for the PIN entry rule; it never returns a phone, an
 * employee number, a photo or the Keycloak subject to a caller that is going to put it on a screen. {@code
 * StaffMemberCodec} stays package-private for the same reason.
 */
@Component
public class StaffTerminalFacts {

    private final JdbcStaffMemberStore store;
    private final StaffMemberCodec codec;

    StaffTerminalFacts(JdbcStaffMemberStore store, StaffMemberCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    /**
     * One member as the terminals see them.
     *
     * @param subject          the Keycloak subject, for authorization checks only; never rendered
     * @param displayReference the non-personal handle {@code S-0142}
     * @param firstName        null when the account holds no name yet; the roster then reads the reference
     */
    public record Person(
            UUID memberId,
            String subject,
            String displayReference,
            @Nullable String firstName,
            @Nullable String lastName,
            String employmentStatus) {

        public boolean active() {
            return StaffMembers.ACTIVE.equals(employmentStatus);
        }

        @Override
        public String toString() {
            return "Person[" + displayReference + "]";
        }
    }

    public Optional<Person> person(UUID tenantId, UUID memberId) {
        return store.find(tenantId, memberId).map(this::personOf);
    }

    public Map<UUID, Person> persons(UUID tenantId, Collection<UUID> memberIds) {
        Map<UUID, Person> persons = new LinkedHashMap<>();
        store.findByIds(tenantId, memberIds).forEach(row -> persons.put(row.id(), personOf(row)));
        return persons;
    }

    public Optional<Person> personBySubject(UUID tenantId, String subject) {
        return store.findBySubject(tenantId, subject).map(this::personOf);
    }

    /**
     * The last {@code length} digits of the person's contact phone and of their employee number: the values a PIN may not
     * equal (ADR 0155, Decision 3). A phone or number with fewer digits than the PIN has none to offer. Computed per call and
     * kept nowhere.
     */
    public Set<String> pinTails(UUID tenantId, UUID memberId, int length) {
        Set<String> tails = new HashSet<>();
        store.find(tenantId, memberId).ifPresent(row -> {
            StaffMemberCodec.Plain plain = codec.openAll(row);
            addTail(tails, plain.phone(), length);
            addTail(tails, plain.employeeNumber(), length);
        });
        return tails;
    }

    /** "Aziza K." -- a first name and the initial of the last, which is all a shared screen shows a colleague. */
    public static @Nullable String nameWithInitial(@Nullable String firstName, @Nullable String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return null;
        }
        String first = firstName.strip();
        if (lastName == null || lastName.isBlank()) {
            return first;
        }
        return first + " " + lastName.strip().substring(0, lastName.strip().offsetByCodePoints(0, 1)) + ".";
    }

    private Person personOf(MemberRow row) {
        StaffMemberCodec.Plain plain = codec.openAll(row);
        return new Person(
                row.id(),
                row.principalSubject(),
                row.displayReference(),
                plain.firstName(),
                plain.lastName(),
                row.employmentStatus());
    }

    private static void addTail(Set<String> tails, @Nullable String value, int length) {
        if (value == null) {
            return;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() >= length) {
            tails.add(digits.substring(digits.length() - length));
        }
    }
}
