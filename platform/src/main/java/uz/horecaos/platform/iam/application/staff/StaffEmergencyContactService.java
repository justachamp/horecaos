package uz.horecaos.platform.iam.application.staff;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffEmergencyContactStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffEmergencyContactStore.ContactRow;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.GrantPlace;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A staff member's emergency contacts (ADR 0139, gap map row 9.2b): a third
 * party's name and phone, kept for the employer's benefit about a person who
 * never dealt with the platform.
 *
 * <p>That is why this is its own service with its own capability and not a
 * field on the profile. A read needs {@code staff.emergency-contact.read} and
 * <em>writes an ADR 0027 fact every time</em> -- the fact is the point, and
 * there is no bulk read, no export and no list of every contact in a tenant. A
 * change needs {@code staff.profile.manage}, with the same rule the profile
 * has at a branch: only a person whose every active job is inside that branch.
 *
 * <p>The set is replaced as a whole, at most three contacts, and the member's
 * version moves with it: the contacts are part of the member aggregate, so a
 * second tab editing the profile and one editing the contacts conflict instead
 * of overwriting one another.
 *
 * <p>Name and phone are sealed under ADR 0029 with the contact's own id in the
 * associated data, so a ciphertext copied to another contact or another member
 * fails to open.
 */
@Service
public class StaffEmergencyContactService {

    static final String READ = "staff.emergency_contact.read";
    static final String UPDATED = "staff.emergency_contact.updated";

    static final int MAX_CONTACTS = 3;
    static final Set<String> RELATIONSHIPS = Set.of("SPOUSE", "PARENT", "CHILD", "SIBLING", "FRIEND", "OTHER");

    private static final String TABLE = "iam.staff_emergency_contacts";
    private static final String PURPOSE = "staff.emergency_contact";

    private final JdbcStaffMemberStore members;
    private final JdbcStaffEmergencyContactStore contacts;
    private final FieldProtection protection;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StaffEmergencyContactService(
            JdbcStaffMemberStore members,
            JdbcStaffEmergencyContactStore contacts,
            FieldProtection protection,
            ApplicationEventPublisher events,
            Clock clock) {
        this.members = members;
        this.contacts = contacts;
        this.protection = protection;
        this.events = events;
        this.clock = clock;
    }

    /** One emergency contact in full. Never printed. */
    public record ContactView(UUID id, String relationshipCode, String name, String phone, int slot) {

        @Override
        public String toString() {
            return "ContactView[id=" + id + ", relationshipCode=" + relationshipCode + ", slot=" + slot + "]";
        }
    }

    /** What a manager submits for one slot. */
    public record ContactEdit(String relationshipCode, String name, String phone) {

        @Override
        public String toString() {
            return "ContactEdit[relationshipCode=" + relationshipCode + "]";
        }
    }

    /** The set after a replace, with the member's new version. */
    public record Replaced(List<ContactView> contacts, int memberVersion) {}

    /** The contacts and the member's version, so the next replace can carry it. */
    public record Read(List<ContactView> contacts, int memberVersion) {}

    /**
     * Reads the contacts and records that somebody did, in the same transaction:
     * a read that could succeed without leaving evidence would defeat the
     * capability.
     */
    @Transactional
    public Read read(UUID tenantId, Reach reach, UUID memberId, String actorSubject, String correlationId) {
        MemberRow member = members.find(tenantId, memberId).orElseThrow(StaffEmergencyContactService::noSuchMember);
        if (!reach.matches(placesOf(tenantId, member.principalSubject()))) {
            throw noSuchMember();
        }
        List<ContactView> opened = open(tenantId, contacts.forMember(tenantId, memberId));
        Instant now = clock.instant();
        publish(
                READ,
                tenantId,
                actorSubject,
                memberId,
                "An emergency contact was read",
                Capability.STAFF_EMERGENCY_CONTACT_READ.code(),
                (long) member.version(),
                new LinkedHashMap<>(),
                fields("contactCount", opened.size()),
                correlationId,
                now);
        return new Read(opened, member.version());
    }

    @Transactional
    public Replaced replace(
            UUID tenantId,
            Reach reach,
            UUID memberId,
            int expectedVersion,
            List<ContactEdit> edits,
            String actorSubject,
            @Nullable String reason,
            String correlationId) {
        MemberRow member =
                members.findForUpdate(tenantId, memberId).orElseThrow(StaffEmergencyContactService::noSuchMember);
        List<GrantPlace> held = placesOf(tenantId, member.principalSubject());
        if (!reach.matches(held)) {
            throw noSuchMember();
        }
        if (!reach.mayChange(held)) {
            throw ApiException.insufficientCapability(Capability.STAFF_PROFILE_MANAGE.code(), "TENANT");
        }
        if (member.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, member.version());
        }
        if (edits.size() > MAX_CONTACTS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "At most three emergency contacts can be kept",
                    Map.of("field", "contacts"));
        }

        Instant now = clock.instant();
        List<ContactRow> before = contacts.forMember(tenantId, memberId);
        List<ContactRow> rows = new ArrayList<>();
        int slot = 1;
        for (ContactEdit edit : edits) {
            rows.add(seal(tenantId, memberId, edit, slot++));
        }

        contacts.deleteAll(tenantId, memberId);
        for (ContactRow row : rows) {
            contacts.insert(row, now);
        }
        if (!members.touch(tenantId, memberId, expectedVersion, now)) {
            throw ApiException.staleVersion(expectedVersion, member.version() + 1);
        }

        publish(
                UPDATED,
                tenantId,
                actorSubject,
                memberId,
                reason == null || reason.isBlank()
                        ? "A manager changed the staff member's emergency contacts"
                        : reason.strip(),
                Capability.STAFF_PROFILE_MANAGE.code(),
                (long) member.version() + 1,
                fields("contactCount", before.size(), "emergencyContacts", before.isEmpty() ? null : "set"),
                fields("contactCount", rows.size(), "emergencyContacts", rows.isEmpty() ? null : "set"),
                correlationId,
                now);
        return new Replaced(open(tenantId, rows), member.version() + 1);
    }

    private ContactRow seal(UUID tenantId, UUID memberId, ContactEdit edit, int slot) {
        String relationship =
                edit.relationshipCode() == null ? "" : edit.relationshipCode().strip();
        if (!RELATIONSHIPS.contains(relationship)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "relationshipCode must be one of SPOUSE, PARENT, CHILD, SIBLING, FRIEND, OTHER",
                    Map.of("field", "relationshipCode"));
        }
        String name = edit.name() == null ? "" : edit.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A contact needs a name of up to 100 characters",
                    Map.of("field", "name"));
        }
        String phone;
        try {
            phone = StaffPhones.parse(edit.phone())
                    .orElseThrow(() -> new IllegalArgumentException("A contact needs a phone number"))
                    .stored();
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage(), Map.of("field", "phone"));
        }
        UUID id = Ids.newId();
        return new ContactRow(
                id,
                tenantId,
                memberId,
                relationship,
                protection
                        .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, "protected_name", id), name)
                        .serialize(),
                protection
                        .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, "protected_phone", id), phone)
                        .serialize(),
                slot,
                1);
    }

    private List<ContactView> open(UUID tenantId, List<ContactRow> rows) {
        List<ContactView> views = new ArrayList<>();
        for (ContactRow row : rows) {
            views.add(new ContactView(
                    row.id(),
                    row.relationshipCode(),
                    protection.reveal(
                            tenantId,
                            ProtectedValue.deserialize(row.protectedName()),
                            new RecordRef(TABLE, "protected_name", row.id()),
                            PURPOSE),
                    protection.reveal(
                            tenantId,
                            ProtectedValue.deserialize(row.protectedPhone()),
                            new RecordRef(TABLE, "protected_phone", row.id()),
                            PURPOSE),
                    row.sortOrder()));
        }
        return views;
    }

    private List<GrantPlace> placesOf(UUID tenantId, String subject) {
        return members.activeGrantPlaces(tenantId, List.of(subject), clock.instant(), StaffMembers.MACHINE_ROLE_CODES)
                .getOrDefault(subject, List.of());
    }

    private void publish(
            String actionCode,
            UUID tenantId,
            String actorSubject,
            UUID memberId,
            String reason,
            String capability,
            Long targetVersion,
            Map<String, Object> before,
            Map<String, Object> after,
            String correlationId,
            Instant occurredAt) {
        events.publishEvent(new StaffMemberChanged(
                actionCode,
                tenantId,
                actorSubject,
                null,
                memberId,
                reason,
                capability,
                targetVersion,
                before,
                after,
                correlationId,
                occurredAt));
    }

    private static Map<String, Object> fields(@Nullable Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static ApiException noSuchMember() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such staff member");
    }
}
