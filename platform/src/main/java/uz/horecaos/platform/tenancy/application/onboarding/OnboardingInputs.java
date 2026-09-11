package uz.horecaos.platform.tenancy.application.onboarding;

import java.util.Locale;
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

    private OnboardingInputs() {}

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
