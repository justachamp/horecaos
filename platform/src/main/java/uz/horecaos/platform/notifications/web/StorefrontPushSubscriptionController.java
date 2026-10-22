package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.NotificationTransport.Readiness;
import uz.horecaos.platform.notifications.application.PushSubscriptionService;
import uz.horecaos.platform.notifications.application.PushSubscriptionService.Refused;
import uz.horecaos.platform.notifications.application.PushSubscriptionService.Subscribe;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A customer's own Web Push opt-in, from the storefront's service worker (ADR 0182, D14).
 *
 * <p>The customer taps an explicit prompt in the storefront, the browser asks its own permission
 * question, and the storefront sends the resulting subscription here. That sequence is the consent:
 * the subscription and the grant of {@code MARKETING_PROMOTIONS} on the {@code PUSH} channel are
 * recorded together, and turning push off ends both.
 *
 * <p>Authorised by ownership, as the other customer surfaces are: the account is never in the path, it
 * is resolved from the caller's verified token, and a guest (signed in, no account at this brand yet) is
 * answered not-found rather than forbidden. The body is the browser's own
 * {@code PushSubscription.toJSON()}, so the storefront forwards it unchanged.
 *
 * <p>Nothing here returns the subscription's URL or keys, and a refusal says why in a bounded code and
 * never echoes what was sent: the URL names a device.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/push")
@Tag(name = "Web Push", description = "A customer's opt-in to promotional notifications in their own browser")
public class StorefrontPushSubscriptionController {

    private final PushSubscriptionService subscriptions;
    private final CurrentCustomer currentCustomer;
    private final NotificationTransport transport;
    private final String publicKey;

    public StorefrontPushSubscriptionController(
            PushSubscriptionService subscriptions,
            CurrentCustomer currentCustomer,
            NotificationTransport transport,
            @Value("${horecaos.push.vapid.public-key:}") String publicKey) {
        this.subscriptions = subscriptions;
        this.currentCustomer = currentCustomer;
        this.transport = transport;
        this.publicKey = publicKey.strip();
    }

    @GetMapping
    @CustomerOwned
    @Operation(
            summary = "Whether push can be offered, and whether this customer has turned it on",
            description = "`available` is false until the platform's push identity is configured, and then the "
                    + "storefront does not show the prompt. `publicKey` is the application server key "
                    + "(VAPID, base64url) the browser must subscribe under; it is not a secret. `subscribed` is "
                    + "true when the customer has a live subscription at this brand.")
    public ResponseEntity<PushStatusResponse> status(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        Readiness readiness = transport.readiness(tenantId, brandId, "PUSH", "MARKETING");
        boolean available = readiness.ready() && !publicKey.isEmpty();
        return ResponseEntity.ok(new PushStatusResponse(
                available, available ? publicKey : null, subscriptions.subscribed(tenantId, brandId, accountId)));
    }

    @PutMapping("/subscription")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Turn push on in this browser",
            description = "The browser's own `PushSubscription.toJSON()`. Replaces the customer's earlier "
                    + "subscription at this brand, and ends any other customer's subscription for this "
                    + "browser. Records the customer's consent to promotional push in the same step. 400 with a "
                    + "bounded reason (`PUSH_ENDPOINT_NOT_ALLOWED`, `PUSH_KEYS_INVALID`, ...) when the "
                    + "subscription is not one the platform will send to.")
    public ResponseEntity<Void> subscribe(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody PushSubscribeRequest request) {
        UUID accountId = accountId(tenantId, brandId);
        try {
            subscriptions.subscribe(
                    tenantId,
                    brandId,
                    accountId,
                    new Subscribe(
                            request.endpoint(),
                            request.keys().p256dh(),
                            request.keys().auth(),
                            request.expirationTime() == null ? null : Instant.ofEpochMilli(request.expirationTime())));
        } catch (Refused refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.code());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscription")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Turn push off",
            description = "Ends the customer's subscription at this brand and withdraws their consent to "
                    + "promotional push. 204 whether or not it was on: turning off what is off is the state that was "
                    + "asked for.")
    public ResponseEntity<Void> unsubscribe(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        subscriptions.unsubscribe(tenantId, brandId, accountId(tenantId, brandId));
        return ResponseEntity.noContent().build();
    }

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account"));
    }

    /**
     * @param available whether the platform can send push at all right now
     * @param publicKey the VAPID application server key, or null when {@code available} is false
     * @param subscribed whether this customer has a live subscription at this brand
     */
    public record PushStatusResponse(
            boolean available, @Nullable String publicKey, boolean subscribed) {}

    /** A browser's {@code PushSubscription.toJSON()}: the URL, an optional expiry in epoch milliseconds, and keys. */
    public record PushSubscribeRequest(
            @NotBlank @Size(max = 2048) String endpoint,
            @Nullable Long expirationTime,
            @NotNull @Valid PushSubscriptionKeys keys) {

        /** The URL names a device and the keys decrypt for it. */
        @Override
        public String toString() {
            return "PushSubscribeRequest[<redacted>]";
        }
    }

    public record PushSubscriptionKeys(
            @NotBlank @Size(max = 256) String p256dh,
            @NotBlank @Size(max = 256) String auth) {

        @Override
        public String toString() {
            return "PushSubscriptionKeys[<redacted>]";
        }
    }
}
