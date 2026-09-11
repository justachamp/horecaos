package uz.horecaos.platform.tenancy.application.onboarding;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;

/**
 * The names of the onboarding inputs the start endpoint writes and the owner
 * step reads, so the two cannot disagree about a key.
 */
public final class OnboardingInputs {

    /**
     * The owner's address, encrypted (ADR 0029). Every step's input is stored,
     * so a plain address would sit in a dozen rows for the life of the run.
     */
    public static final String OWNER_EMAIL_PROTECTED = "ownerEmailProtected";

    /** Runs started before V0210 kept the address in clear under this key; still read, never written. */
    public static final String LEGACY_OWNER_EMAIL = "ownerEmail";

    /** The language the owner's invitation is written in. */
    public static final String OWNER_LOCALE = "ownerLocale";

    /**
     * Whether this run was asked for a sample menu (ADR 0099).
     *
     * <p>Absent means no. A caller that predates the field — {@code
     * tools/seed-horecaos-tenant}, {@code tools/proving-run}, any integration —
     * must not start creating sample catalogs in tenants that already have real
     * menus; the console is what defaults the checkbox to on.
     */
    public static final String SAMPLE_MENU = "sampleMenu";

    private OnboardingInputs() {}

    /** Whether the stored input asked for a sample menu. Anything but a true is a no. */
    public static boolean sampleMenuRequested(Map<String, Object> input) {
        Object requested = input.get(SAMPLE_MENU);
        if (requested instanceof Boolean flag) {
            return flag;
        }
        // A run's input survives a JSON round trip through input_snapshot, and a
        // caller may have sent the string. Both read the same way here so a step
        // and the materialisation that created it cannot disagree.
        return requested != null && Boolean.parseBoolean(String.valueOf(requested));
    }

    /**
     * What the encrypted address is bound to. The run's id does not exist when
     * the address is protected, so it is bound to the tenant: the ciphertext
     * cannot be moved to another tenant's run and read there.
     */
    public static RecordRef ownerEmailRecord(UUID tenantId) {
        return new RecordRef("tenant.onboarding_steps", "input_snapshot.ownerEmailProtected", tenantId);
    }

    /** uz, ru or en; anything else, or nothing, is Russian. */
    public static String locale(@Nullable String requested) {
        if (requested == null) {
            return "ru";
        }
        String language = requested.strip().toLowerCase(Locale.ROOT);
        if (language.startsWith("uz")) {
            return "uz";
        }
        return OwnerInvitationService.LOCALES.contains(language) ? language : "ru";
    }
}
