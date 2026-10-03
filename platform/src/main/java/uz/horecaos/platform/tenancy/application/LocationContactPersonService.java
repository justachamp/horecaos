package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards.Card;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLocationContactPersonStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLocationContactPersonStore.ContactRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A branch's named contact persons (ADR 0139, gap map row {@code 9.2b}): who, by
 * name and role, a colleague or the head office calls about the branch -- the
 * manager, the landlord, the security desk, the maintenance contractor.
 *
 * <p>The published line customers and couriers see is still {@code
 * tenant.locations.contact_phone}, stored in clear on purpose and untouched by
 * this. A contact person is a different thing: an internal fact, readable by
 * anyone who can read the branch ({@code location.read}) and changed by whoever
 * can write it ({@code location.write}), both at the branch's own scope.
 *
 * <p><strong>Two shapes, exactly one per row.</strong> A <em>colleague</em> is
 * stored as a staff member id and nothing else -- no copy of anyone's phone, so
 * the number cannot drift from the person's own record, and the screen reads the
 * name and number back through {@link StaffMemberCards}. An <em>outside
 * person</em> is a protected name and phone, a third party's data sealed under
 * ADR 0029 with the row's own id in the associated data. The lawful basis for
 * holding that third party's details is a legal question ADR 0139 leaves open;
 * if it cannot be answered, the outside-person shape is the first thing to go.
 *
 * <p>The set is replaced as a whole and guarded by the <em>location's</em>
 * version, which the replace moves: the contacts are part of the branch
 * aggregate. The audit fact is the ADR's {@code iam.location_contact.updated},
 * and carries which roles are listed and how many contacts there are -- never a
 * name or a number.
 */
@Service
public class LocationContactPersonService {

    static final String UPDATED = "iam.location_contact.updated";

    static final int MAX_CONTACTS = 10;
    static final Set<String> RELATIONSHIPS = Set.of("MANAGER", "OWNER", "LANDLORD", "SECURITY", "MAINTENANCE", "OTHER");

    /** {@link Card#status()} of a member whose employment has ended. */
    private static final String ENDED = "ENDED";

    private static final String TABLE = "tenant.location_contact_persons";
    private static final String PURPOSE = "tenancy.location.contact_person";

    private final JdbcLocationContactPersonStore store;
    private final FieldProtection protection;
    private final StaffMemberCards staff;
    private final AuditRecorder audit;
    private final Clock clock;

    public LocationContactPersonService(
            JdbcLocationContactPersonStore store,
            FieldProtection protection,
            StaffMemberCards staff,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.protection = protection;
        this.staff = staff;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What a screen shows for one contact.
     *
     * @param staffMemberId set for a colleague, null for an outside person
     * @param staffMemberReference the colleague's non-personal reference
     * @param name the contact's name; for a colleague, the name the tenant keeps
     *     for them, or null when it keeps none, or when they have left
     * @param phone the number; for a colleague, their contact phone as it is now,
     *     and never once their employment has ended
     * @param formerColleague true for a colleague whose employment has ended: the
     *     row stays so a manager can see and remove it, but it carries the
     *     non-personal reference alone
     */
    public record ContactView(
            UUID id,
            String relationshipCode,
            @Nullable UUID staffMemberId,
            @Nullable String staffMemberReference,
            @Nullable String name,
            @Nullable String phone,
            boolean formerColleague) {

        @Override
        public String toString() {
            return "ContactView[id=" + id + ", relationshipCode=" + relationshipCode + "]";
        }
    }

    /** What a manager submits for one row: a colleague or an outside person, never both. */
    public record ContactEdit(
            String relationshipCode,
            @Nullable UUID staffMemberId,
            @Nullable String name,
            @Nullable String phone) {

        @Override
        public String toString() {
            return "ContactEdit[relationshipCode=" + relationshipCode + "]";
        }
    }

    /** The branch's contacts and the location version the next replace must carry. */
    public record ContactSet(List<ContactView> contacts, long version) {}

    @Transactional(readOnly = true)
    public ContactSet list(UUID tenantId, UUID brandId, UUID locationId) {
        long version = store.locationVersion(tenantId, brandId, locationId).orElseThrow(this::noSuchLocation);
        return new ContactSet(views(tenantId, store.forLocation(tenantId, locationId)), version);
    }

    @Transactional
    public ContactSet replace(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            long expectedVersion,
            List<ContactEdit> edits,
            String actorSubject,
            @Nullable String reason,
            String correlationId) {
        long current = store.lockLocationVersion(tenantId, brandId, locationId).orElseThrow(this::noSuchLocation);
        if (current != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, current);
        }
        if (edits.size() > MAX_CONTACTS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "At most %d contact persons can be listed for a branch".formatted(MAX_CONTACTS),
                    Map.of("field", "contacts"));
        }

        Map<UUID, Card> colleagues = requireColleagues(tenantId, edits);
        Set<String> seenColleagueRoles = new LinkedHashSet<>();
        List<ContactRow> rows = new ArrayList<>();
        for (ContactEdit edit : edits) {
            String relationship = edit.relationshipCode() == null
                    ? ""
                    : edit.relationshipCode().strip();
            if (!RELATIONSHIPS.contains(relationship)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "relationshipCode must be one of MANAGER, OWNER, LANDLORD, SECURITY, MAINTENANCE, OTHER",
                        Map.of("field", "relationshipCode"));
            }
            if (edit.staffMemberId() != null) {
                if (!seenColleagueRoles.add(relationship + "|" + edit.staffMemberId())) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "A colleague is listed once per role",
                            Map.of("field", "staffMemberId"));
                }
                rows.add(colleagueRow(tenantId, locationId, relationship, colleagues, edit));
            } else {
                rows.add(outsideRow(tenantId, locationId, relationship, edit));
            }
        }

        Instant now = clock.instant();
        List<ContactRow> before = store.forLocation(tenantId, locationId);
        store.deleteAll(tenantId, locationId);
        for (ContactRow row : rows) {
            store.insert(row, now);
        }
        if (!store.bumpLocationVersion(tenantId, locationId, expectedVersion, now)) {
            throw ApiException.staleVersion(expectedVersion, current + 1);
        }

        audit.record(AuditFact.of(UPDATED, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("tenant.location", locationId)
                .because(reason == null || reason.isBlank() ? "Branch contact persons were changed" : reason.strip())
                // Which roles are listed and how many, never who: a third party's
                // name and number do not belong in an audit trail read by more
                // people than the record is (ADR 0029).
                .changed(ChangeDocuments.diff(summary(before), summary(rows)))
                .usingCapability(Capability.LOCATION_WRITE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new ContactSet(views(tenantId, rows), current + 1);
    }

    // ------------------------------------------------------------------ rows

    private ContactRow colleagueRow(
            UUID tenantId, UUID locationId, String relationship, Map<UUID, Card> colleagues, ContactEdit edit) {
        UUID memberId = edit.staffMemberId();
        if (memberId == null || !colleagues.containsKey(memberId)) {
            // One answer for a colleague of another tenant and for one that
            // does not exist, so this endpoint is no oracle for member ids.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That staff member is not available in this tenant",
                    Map.of("field", "staffMemberId"));
        }
        if (hasLeft(colleagues.get(memberId))) {
            // Somebody who left is not who a cook or a cashier should ring about
            // the branch, and listing them would publish their personal number to
            // everyone who can read it. A row already on the branch is refused
            // the same way, so a save that keeps a leaver makes the manager look
            // at the row and remove it.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That staff member no longer works here and cannot be listed as a contact",
                    Map.of("field", "staffMemberId"));
        }
        if (edit.name() != null || edit.phone() != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A colleague is listed by staff member alone; no name or phone is copied",
                    Map.of("field", "name"));
        }
        return new ContactRow(Ids.newId(), tenantId, locationId, relationship, memberId, null, null, 1);
    }

    private ContactRow outsideRow(UUID tenantId, UUID locationId, String relationship, ContactEdit edit) {
        String name = edit.name() == null ? "" : edit.name().strip();
        String phone = edit.phone() == null ? "" : edit.phone().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A contact person needs a name of up to 100 characters",
                    Map.of("field", "name"));
        }
        String normalised = normalisedPhone(phone);
        UUID id = Ids.newId();
        return new ContactRow(
                id,
                tenantId,
                locationId,
                relationship,
                null,
                protection
                        .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, "protected_name", id), name)
                        .serialize(),
                protection
                        .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, "protected_phone", id), normalised)
                        .serialize(),
                1);
    }

    private static String normalisedPhone(String raw) {
        String stripped = raw.replaceAll("[\\s().\\-]", "");
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (!digits.matches("[0-9]{7,15}")) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A phone number has seven to fifteen digits",
                    Map.of("field", "phone"));
        }
        return stripped;
    }

    private Map<UUID, Card> requireColleagues(UUID tenantId, List<ContactEdit> edits) {
        Set<UUID> ids = edits.stream()
                .map(ContactEdit::staffMemberId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ids.isEmpty() ? Map.of() : staff.cardsOf(tenantId, ids);
    }

    private List<ContactView> views(UUID tenantId, List<ContactRow> rows) {
        Set<UUID> memberIds = rows.stream()
                .map(ContactRow::staffMemberId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Card> cards = memberIds.isEmpty() ? Map.of() : staff.cardsOf(tenantId, memberIds);
        List<ContactView> views = new ArrayList<>();
        for (ContactRow row : rows) {
            UUID memberId = row.staffMemberId();
            if (memberId != null) {
                Card card = cards.get(memberId);
                // The row follows the person's own record, and that record says
                // they left: their name and number are personal data the branch
                // had a reason to read only while they worked here, and ending
                // employment never touches this table. So the leaver is hidden at
                // the one place every read passes through, and stays hidden after
                // the retention sweeper anonymises them as well.
                boolean former = card != null && hasLeft(card);
                views.add(new ContactView(
                        row.id(),
                        row.relationshipCode(),
                        memberId,
                        card == null ? null : card.displayReference(),
                        card == null || former ? null : card.name(),
                        card == null || former ? null : card.phone(),
                        former));
            } else {
                views.add(new ContactView(
                        row.id(),
                        row.relationshipCode(),
                        null,
                        null,
                        open(tenantId, row, "protected_name", row.protectedName()),
                        open(tenantId, row, "protected_phone", row.protectedPhone()),
                        false));
            }
        }
        return views;
    }

    private static boolean hasLeft(@Nullable Card card) {
        return card != null && ENDED.equals(card.status());
    }

    private @Nullable String open(UUID tenantId, ContactRow row, String column, @Nullable String stored) {
        if (stored == null) {
            return null;
        }
        return protection.reveal(
                tenantId, ProtectedValue.deserialize(stored), new RecordRef(TABLE, column, row.id()), PURPOSE);
    }

    private static Map<String, Object> summary(List<ContactRow> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contactCount", rows.size());
        summary.put(
                "relationships",
                rows.stream().map(ContactRow::relationshipCode).sorted().collect(Collectors.joining(",")));
        return summary;
    }

    private ApiException noSuchLocation() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such location in this brand");
    }
}
