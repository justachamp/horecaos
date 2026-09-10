package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0086: the webhook log — every call a payment provider made to HorecaOS,
 * across tenants, newest first.
 *
 * <p>Read from {@code payments.provider_callbacks}, which has recorded each
 * call since payments shipped: whether its signature was valid, what HorecaOS
 * answered in the provider's own codes, and whether it matched a payment. The
 * request and response bodies stay in the protected evidence store and are
 * not returned; a provider's reference and our answer are what an argument
 * with the provider's support is settled with.
 */
@RestController
@Validated
@Tag(name = "Webhook log", description = "ADR 0086: payment providers' calls across tenants")
public class PlatformWebhookLogController {

    private final JdbcClient jdbc;

    public PlatformWebhookLogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/control-plane/webhooks")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Payment providers' calls to HorecaOS, newest first",
            description = "Filter by provider, or to calls whose signature did not check out — "
                    + "someone probing, or a key rotation one side missed.")
    List<WebhookDelivery> deliveries(
            @RequestParam(required = false) @Nullable String provider,
            @RequestParam(defaultValue = "false") boolean invalidSignatureOnly,
            @RequestParam(defaultValue = "100") @Max(500) int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.tenant_id, t.display_name AS tenant_name, c.provider_type, c.callback_kind,
                               c.provider_reference, c.signature_valid, c.response_code, c.received_at,
                               c.attempt_id IS NOT NULL AS matched_payment
                          FROM payments.provider_callbacks c
                          JOIN tenant.tenants t ON t.id = c.tenant_id
                         WHERE (CAST(:provider AS varchar) IS NULL OR c.provider_type = CAST(:provider AS varchar))
                           AND (NOT :invalidOnly OR NOT c.signature_valid)
                         ORDER BY c.received_at DESC
                         LIMIT :limit
                        """)
                .param("provider", provider == null || provider.isBlank() ? null : provider.strip())
                .param("invalidOnly", invalidSignatureOnly)
                .param("limit", limit)
                .query((row, number) -> new WebhookDelivery(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("tenant_name"),
                        row.getString("provider_type"),
                        row.getString("callback_kind"),
                        row.getString("provider_reference"),
                        row.getBoolean("signature_valid"),
                        row.getString("response_code"),
                        row.getObject("received_at", OffsetDateTime.class)
                                .toInstant()
                                .toString(),
                        row.getBoolean("matched_payment")))
                .list();
    }

    /**
     * One call a provider made.
     *
     * @param responseCode what HorecaOS answered, in the provider's own codes
     * @param matchedPayment whether the call was tied to one of our payment attempts
     */
    public record WebhookDelivery(
            UUID id,
            UUID tenantId,
            String tenantName,
            String provider,
            String kind,
            String providerReference,
            boolean signatureValid,
            String responseCode,
            String receivedAt,
            boolean matchedPayment) {}
}
