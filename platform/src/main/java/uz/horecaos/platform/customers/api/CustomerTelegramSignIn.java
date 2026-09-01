package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The one door the Telegram share-contact handshake uses to reach the
 * {@code customers} module (ADR 0063) — the same narrow-SPI-port discipline
 * {@link CustomerImportDirectory} already applies to the SendPulse import, and
 * for the same reason: {@code integration} must resolve, create and sign in an
 * account without importing {@code customers.application}'s internals
 * (identity resolution, field protection, session storage), and this interface
 * is the whole of what it may do instead.
 *
 * <p>Two steps rather than one, because they run at two different moments on
 * two different sides of Telegram's own asynchrony:
 *
 * <ul>
 *   <li>{@link #resolveAccount} runs inside {@code TelegramUpdateHandler}, the
 *       instant the contact message clears its own-contact and pattern checks.
 *       It resolves-or-creates the account and records the phone as a verified
 *       {@code TELEGRAM_CONTACT} contact — but it mints no session, because
 *       nothing is listening for one yet: the storefront tab is still polling,
 *       not receiving a response to this call.</li>
 *   <li>{@link #establishSession} runs later, inside the storefront's own poll
 *       endpoint, the first time it observes the redemption. It mints the ADR
 *       0051 session and is the only place a session bearer's plaintext ever
 *       exists — {@code CustomerSessionService}'s own doctrine, "returned once,
 *       in the response that established it, and never again", held exactly:
 *       the poll response <em>is</em> that one response, simply arriving later
 *       than the request that will turn out to have caused it.</li>
 * </ul>
 *
 * <p>Nothing recoverable crosses between the two calls. What survives from the
 * first to the second is an account id — not a secret — which
 * {@code integration.telegram_pending_links.auth_account_id} is free to store
 * in the clear, the same way {@code customer_account_id} already does for a
 * CUSTOMER-audience row.
 */
public interface CustomerTelegramSignIn {

    /**
     * Resolves-or-creates the account for a phone Telegram itself attested,
     * and records a verified {@code TELEGRAM_CONTACT} contact point for it.
     *
     * @param rawPhone the E.164-ish number Telegram's {@code contact} payload
     *                 carried, already passed the own-contact and configured
     *                 allowed-pattern checks by the caller
     */
    Resolved resolveAccount(UUID tenantId, UUID brandId, String rawPhone);

    /**
     * Mints the ADR 0051 session for an account {@link #resolveAccount} already
     * resolved. Called at most once per redemption — the caller (the storefront
     * poll endpoint) is responsible for the single-claim guard that makes that
     * true; this method does not itself enforce it, the same way
     * {@code CustomerSessionService.establish} does not re-check that a grant
     * was not already spent by the time it is called with one.
     */
    Session establishSession(UUID tenantId, UUID brandId, UUID accountId, boolean accountCreated);

    /** @param created true when this contact brought the account into existence */
    record Resolved(UUID accountId, boolean created) {}

    /**
     * @param token     the session bearer. Returned once, by the poll response
     *                  that carries this record, and never again
     * @param expiresAt when the storefront must ask the customer to sign in again
     */
    record Session(String token, Instant expiresAt, UUID accountId, boolean accountCreated) {

        /** A record's generated {@code toString} would print the session token. */
        @Override
        public String toString() {
            return "Session[accountId=%s, expiresAt=%s, accountCreated=%s]"
                    .formatted(accountId, expiresAt, accountCreated);
        }
    }
}
