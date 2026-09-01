package uz.horecaos.platform.notifications.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * How an ADR 0026 provider binding's lifecycle stays in step with {@code
 * notifications.recipient_endpoints} (ADR 0058 stage 2).
 *
 * <p>{@code integration} owns {@code integration.telegram_bindings} and decides
 * when one is created or retired; {@code notifications} owns {@code
 * recipient_endpoints} and holds the one place a binding is associated with a
 * customer account ({@code customer_account_id}; {@code telegram_bindings}
 * itself carries none). Neither module reaches into the other's table
 * directly — this is the seam, the same shape {@link OperationsSubscriptionDirectory}
 * already gives the opposite direction (notifications asking integration "who
 * is subscribed"), used here for integration telling notifications "this
 * binding changed" and asking it "which binding is this customer's".
 */
public interface CustomerProviderBindingSync {

    /**
     * A customer's own binding went live: materialize its {@code
     * PROVIDER_BINDING} endpoint row, with {@code customer_account_id} set —
     * the shape {@code ck_endpoint_owner} (V0107) admits only for a binding
     * created this way.
     */
    void onCustomerBindingLinked(UUID tenantId, UUID providerBindingId, UUID customerAccountId, Instant now);

    /**
     * The import counterpart to {@link #onCustomerBindingLinked} (ADR 0059
     * stage 3): the same endpoint materialization, plus an explicit TELEGRAM
     * preference for every class that respects one — set once, honestly,
     * rather than left to default. A live {@code /start} handshake never sets
     * one at link time and relies on absence meaning enabled; an import is
     * different, because its {@code subscribed} flag is provenance the
     * platform is choosing to trust from a source that is about to be
     * retired, not something the customer told this platform directly, and
     * the choice deserves a row rather than a silent default in either
     * direction.
     *
     * @param subscribed true writes TELEGRAM enabled for every {@link
     *                   uz.horecaos.platform.notifications.domain.NotificationClass}
     *                   that respects a preference; false writes it disabled
     *                   for the same set — the identical set {@link
     *                   #onProviderBindingRetired} already flips off, reused
     *                   rather than duplicated
     */
    void onCustomerBindingImported(
            UUID tenantId, UUID providerBindingId, UUID customerAccountId, boolean subscribed, Instant now);

    /**
     * A provider binding retired, for any audience. Retires its endpoint row
     * so a fresh lookup stops finding it {@code ACTIVE} — the mechanism
     * behind {@code NO_RECIPIENT_ENDPOINT} for a customer whose link died
     * between intent creation and delivery ("unlinked mid-flight").
     *
     * <p>When the retired binding was a customer's own (its endpoint carries
     * a {@code customer_account_id}), also flips that customer's TELEGRAM
     * preference off for every class that respects one: ADR 0058's own words,
     * "for a customer 1:1 binding, a 403 is consent revocation in effect...
     * so records match reality". A no-op past the endpoint retirement for an
     * OPERATIONS or PLATFORM binding, which never carries one.
     */
    void onProviderBindingRetired(UUID tenantId, UUID providerBindingId, String reason, Instant now);

    /**
     * The provider binding behind a customer's own active linked chat, if
     * any — what a storefront status/unlink endpoint asks
     * {@code TelegramCustomerLinkService} to answer, which asks here rather
     * than adding a {@code customer_account_id} column to {@code
     * telegram_bindings} for a fact {@code recipient_endpoints} already
     * carries.
     */
    Optional<UUID> activeBindingFor(UUID tenantId, UUID customerAccountId);
}
