package uz.horecaos.platform.integration.provider;

import java.util.Optional;
import uz.horecaos.platform.integration.api.provider.BindingRef;

/**
 * The non-secret half of an SMS gateway account (ADR 0026, ADR 0028).
 *
 * <p>Exists because this provider puts three things in every request body and
 * only one of them is a secret. {@code key} is an ADR 0028 reference resolved at
 * call time and never stored on a row; {@code login} and {@code sender} are the
 * partner's account name and its registered sender string, which are
 * configuration and belong on the ADR 0026 installation.
 *
 * <p>Narrow on purpose. {@code InstallationSnapshot} could have carried the
 * configuration map instead, but that record is shared with delivery, payments
 * and POS, and widening a shared contract for one caller is how it accumulates
 * optional fields nobody sets.
 */
public interface SmsAccountLookup {

    /**
     * The account one binding sends as, or empty when the installation has not
     * been configured with one.
     *
     * <p>Tenant-scoped through the {@link BindingRef}, which carries the tenant
     * the caller was authorised against. A binding id alone is never proof of
     * ownership.
     */
    Optional<SmsAccount> forBinding(BindingRef binding);

    /**
     * What the provider needs beside the credential.
     *
     * @param login  the partner account name. Not a secret, and not sufficient to
     *               send anything on its own
     * @param sender the registered sender string, typically a short code. There
     *               is no registration API, so an unrecognised value comes back
     *               as {@code 16 wrong sender} at call time and nowhere earlier
     */
    record SmsAccount(String login, String sender) {

        public boolean isComplete() {
            return login != null && !login.isBlank() && sender != null && !sender.isBlank();
        }
    }
}
