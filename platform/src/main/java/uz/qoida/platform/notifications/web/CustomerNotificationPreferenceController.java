package uz.qoida.platform.notifications.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.customers.api.CurrentCustomer;
import uz.qoida.platform.customers.api.CustomerOwned;
import uz.qoida.platform.notifications.application.NotificationPreferenceService;
import uz.qoida.platform.notifications.domain.NotificationChannel;
import uz.qoida.platform.notifications.domain.NotificationClass;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.idempotency.Idempotent;

/**
 * The customer's own notification settings (ADR 0020).
 *
 * <p>Preferences, not consent. Withdrawing consent is an ADR 0015 decision
 * recorded against a policy version with its evidence, and it has its own endpoint
 * on the customers module; a toggle here must not be able to stand in for one,
 * because a legal basis created by a checkbox nobody can date is not a legal basis.
 *
 * <p>A required transactional class is refused rather than accepted and ignored.
 * An interface that lets somebody switch off their order confirmations, and then
 * sends them anyway, is worse than one that says no.
 *
 * <p>Authorised by ownership: the {@code accountId} in the path must be the
 * calling principal's own. It used to declare {@code NOTIFICATION_PREFERENCE_MANAGE},
 * which is delegated staff authority over somebody else's settings — no customer
 * principal holds it or is meant to, so a customer opening their own preferences
 * was refused, and the endpoint's only reachable caller was an agent. Worse, the
 * capability was scoped to the tenant while the account is named in the path, so
 * an agent holding it could read and rewrite any customer's preferences in the
 * tenant, and nothing in the handler compared the two.
 *
 * <p>A call-centre agent changing a customer's preferences on the phone is a real
 * need and is not this endpoint. It wants its own operations path, where the
 * capability is the point and the agent is not pretending to be the customer.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers/{accountId}/notification-preferences")
@Tag(name = "Notification preferences",
        description = "Which optional messages a customer receives, per class and channel")
public class CustomerNotificationPreferenceController {

    private final NotificationPreferenceService preferences;
    private final CurrentCustomer currentCustomer;

    public CustomerNotificationPreferenceController(NotificationPreferenceService preferences,
            CurrentCustomer currentCustomer) {
        this.preferences = preferences;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping
    @CustomerOwned
    @Operation(summary = "The customer's current preferences",
            description = "Only what has been set. An absent row means the default for that "
                    + "class, which is on rather than off: a customer who never expressed a "
                    + "preference has not opted out of anything.")
    public ResponseEntity<List<PreferenceResponse>> list(@PathVariable UUID tenantId,
            @PathVariable UUID accountId) {

        requireOwnAccount(tenantId, accountId);
        return ResponseEntity.ok(preferences.preferences(tenantId, accountId).stream()
                .map(row -> new PreferenceResponse(row.brandId(), row.notificationClass(),
                        row.channel(), row.enabled(), row.version()))
                .toList());
    }

    @PutMapping("/{notificationClass}/{channel}")
    @CustomerOwned
    @Idempotent
    @Operation(summary = "Set one preference",
            description = "Refuses a class the customer cannot switch off. Consent is separate "
                    + "and lives on the customers module; this never writes a consent decision.")
    public ResponseEntity<Void> set(@PathVariable UUID tenantId, @PathVariable UUID accountId,
            @PathVariable String notificationClass, @PathVariable String channel,
            @Valid @RequestBody SetPreferenceRequest request) {

        requireOwnAccount(tenantId, accountId);
        try {
            preferences.set(tenantId, accountId, request.brandId(),
                    parseClass(notificationClass), parseChannel(channel), request.enabled());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Refuses an account that is not the caller's, as not found.
     *
     * <p>Not forbidden. A forbidden answer confirms that the account id names a
     * real customer of this tenant to anyone who guessed it, and the ids are the
     * only thing standing between a guess and knowing that somebody is a customer
     * of a brand — which for some brands is itself the sensitive fact.
     */
    private void requireOwnAccount(UUID tenantId, UUID accountId) {
        if (!currentCustomer.owns(tenantId, accountId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer account");
        }
    }

    private static NotificationClass parseClass(String value) {
        try {
            return NotificationClass.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s is not a notification class".formatted(value));
        }
    }

    private static NotificationChannel parseChannel(String value) {
        try {
            return NotificationChannel.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s is not a notification channel".formatted(value));
        }
    }

    /**
     * @param brandId null for the customer's tenant-wide answer
     * @param enabled boxed and {@code @NotNull}, so a body that omits it is a
     *                validation failure rather than a silent opt-out
     */
    public record SetPreferenceRequest(UUID brandId, @NotNull Boolean enabled) { }

    public record PreferenceResponse(UUID brandId, String notificationClass, String channel,
            boolean enabled, int version) { }
}
