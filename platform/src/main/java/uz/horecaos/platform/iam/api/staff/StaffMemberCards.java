package uz.horecaos.platform.iam.api.staff;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;

/**
 * How another module shows a staff member it has been handed by id, or offers
 * the tenant's people in a picker (ADR 0139).
 *
 * <p>A second port beside {@link StaffDirectory}, not a fourth method on it,
 * because it answers a different question and the first port's whole value is
 * that it answers only three. The two callers are the POS mapping pane
 * (gap map row {@code 9.2c}: "which of my people is till operator 17"), which
 * must list the tenant's people by name, and a branch's contact persons
 * ({@code 9.2b}), where a colleague is stored as a member id and never as a
 * copy of their phone, so the screen has to read the name and number back.
 *
 * <p>Both sit behind a capability of their own (the pane behind {@code
 * pos.sync.manage}, held by the owner and the administrator, who also hold
 * {@code staff.profile.read}; the contact persons behind {@code location.read}
 * at the branch). This port performs no authorization, exactly as {@link
 * StaffDirectory} performs none; the caller's route does, and a caller must not
 * hand the result to anybody that route did not authorize.
 */
public interface StaffMemberCards {

    /** The cards for these member ids in this tenant. An id of another tenant, or none, is absent. */
    Map<UUID, Card> cardsOf(UUID tenantId, Collection<UUID> memberIds);

    /**
     * Every member of this tenant who is not {@code ENDED}, for a picker. Names
     * are decrypted in the application, so the caller sorts and filters; this
     * is bounded by the hundreds of people a tenant has.
     */
    List<Card> pickable(UUID tenantId);

    /**
     * @param name {@code null} when the member has no name on file; callers
     *             then show {@code displayReference}
     * @param phone the member's contact phone in full, {@code null} when none
     * @param status {@code PENDING}, {@code ACTIVE}, {@code ON_LEAVE} or {@code ENDED}
     */
    record Card(
            UUID memberId,
            String principalSubject,
            String displayReference,
            @Classified(DataClass.PERSONAL) @Nullable String name,
            @Classified(DataClass.PERSONAL) @Nullable String phone,
            String status) {

        /** A record's generated {@code toString} would print the name and phone (ADR 0029). */
        @Override
        public String toString() {
            return "Card[memberId=" + memberId + ", displayReference=" + displayReference + ", status=" + status + "]";
        }
    }
}
