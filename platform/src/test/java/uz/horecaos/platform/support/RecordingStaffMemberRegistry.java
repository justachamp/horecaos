package uz.horecaos.platform.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.staff.StaffMemberRegistry;

/**
 * A {@link StaffMemberRegistry} that remembers what it was asked and can be told
 * to fail, for suites that exercise the invitation flows without a database
 * (ADR 0139).
 *
 * <p>It records the subject and the tenant and nothing about the person, which
 * is the same discipline the real registry keeps: a test double that kept a name
 * would be the first place a name leaked into a failure message.
 */
public final class RecordingStaffMemberRegistry implements StaffMemberRegistry {

    public record Invited(UUID tenantId, String subject, String actorSubject) {}

    public record Activated(UUID tenantId, String subject) {}

    public final List<Invited> invited = new ArrayList<>();
    public final List<Activated> activated = new ArrayList<>();

    /** When set, the next {@link #registerInvited} throws it -- the member insert failing inside the invitation's transaction. */
    public @Nullable RuntimeException failInvite;

    /** When set, {@link #activate} throws it. */
    public @Nullable RuntimeException failActivate;

    @Override
    public void registerInvited(
            UUID tenantId,
            String principalSubject,
            String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            String actorSubject,
            String correlationId) {
        if (failInvite != null) {
            throw failInvite;
        }
        invited.add(new Invited(tenantId, principalSubject, actorSubject));
    }

    @Override
    public void activate(
            UUID tenantId, String principalSubject, String firstName, @Nullable String lastName, String correlationId) {
        if (failActivate != null) {
            throw failActivate;
        }
        activated.add(new Activated(tenantId, principalSubject));
    }
}
