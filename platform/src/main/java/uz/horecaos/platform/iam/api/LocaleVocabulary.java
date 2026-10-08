package uz.horecaos.platform.iam.api;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The languages a staff member and the emails sent to staff can be in (ADR 0149).
 *
 * <p>The registry that says which languages exist is {@code tenancy.api.PlatformLocales}, and
 * identity cannot import it: tenancy depends on identity (it grants roles and reads principals),
 * and a module cycle is a boundary violation, not a style choice. So identity states what it needs
 * from the registry as a question and tenancy answers it, the way it answers "what does this
 * tenant require of its staff" ({@link TenantStaffMfaRequirements}). Nothing here lists a language:
 * a language activated in the registry is offered here without a change to identity.
 */
public interface LocaleVocabulary {

    /**
     * The tag stored for a staff member's interface language when a client sent this, or empty when
     * the console does not speak it. A bare {@code uz} is read as {@code uz-Latn}, in any casing, and
     * is never stored (ADR 0149, Decision 1).
     */
    Optional<String> staffInterface(@Nullable String input);

    /** The tags a staff console speaks, in the registry's order, for a refusal that names them. */
    List<String> staffInterfaceTags();

    /**
     * The language a message to a person is written in: the tag for what was stored or asked, or the
     * registry's fallback when the platform does not send in it (or nothing was said).
     */
    String message(@Nullable String input);
}
