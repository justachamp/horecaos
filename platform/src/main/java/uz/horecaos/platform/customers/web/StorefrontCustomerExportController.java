package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.customers.application.CustomerErasureService;
import uz.horecaos.platform.customers.application.CustomerExportService;
import uz.horecaos.platform.customers.application.CustomerExportService.RequestedVia;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore.ExportRequestRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A customer's own request for a copy of her data (ADR 0029), beside her erasure request on the profile.
 *
 * <p>Authorised by account ownership, never by a capability and never with an account id in the path: the caller can only
 * mean herself. She can ask, see where the request stands and the date she is owed an answer by, withdraw it while it
 * waits, and, once staff have produced the bundle, be given a link to it. She cannot produce it: gathering a person's
 * data across every module is the act that needs a person on the tenant's side to look at who is asking, so it stays a
 * staff act under {@code customer.pii.export} ({@code CustomerPrivacyController}).
 *
 * <p>Her reads of the bundle are audit facts with the purpose {@code CUSTOMER_SELF_SERVICE}, registered as personal data
 * leaving like staff's.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/me/export-request")
@Tag(
        name = "Customer self-service",
        description = "A customer's own profile, saved addresses, and the products they marked")
public class StorefrontCustomerExportController {

    /** Why the bundle is being read, when the person it belongs to reads it. */
    static final String SELF_SERVICE_PURPOSE = "CUSTOMER_SELF_SERVICE";

    /** Its own actor, so the trail tells a copy of her data apart from the profile edits and the erasure request. */
    private static final ActorRef SELF_SERVICE_ACTOR = ActorRef.service("storefront-export-request");

    private final CustomerExportService exports;
    private final CurrentCustomer currentCustomer;
    private final Clock clock;

    public StorefrontCustomerExportController(
            CustomerExportService exports, CurrentCustomer currentCustomer, Clock clock) {
        this.exports = exports;
        this.currentCustomer = currentCustomer;
        this.clock = clock;
    }

    @PostMapping
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Ask for a copy of my data",
            description = "Records the request and the date the tenant owes an answer by; it gathers nothing by itself "
                    + "(ADR 0029). Idempotent: asking again while a request is waiting returns that request.")
    public ResponseEntity<ExportRequestStatus> request(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ExportRequestStatus.of(
                            exports.request(tenantId, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR),
                            clock.instant()));
        } catch (CustomerErasureService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account");
        } catch (CustomerExportService.AccountNotExportableException gone) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, gone.getMessage());
        }
    }

    @GetMapping
    @CustomerOwned
    @Operation(
            summary = "My current request for a copy of my data, if any",
            description =
                    "The waiting request if there is one, else the most recent of any status. 204 when I have never "
                            + "asked.")
    public ResponseEntity<ExportRequestStatus> current(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        return exports.current(tenantId, accountId)
                .map(row -> ResponseEntity.ok(ExportRequestStatus.of(row, clock.instant())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/cancel")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Withdraw my waiting request for a copy of my data",
            description = "Idempotent for a request already withdrawn. Refused for one already produced.")
    public ResponseEntity<ExportRequestStatus> cancel(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        ExportRequestRow current = exports.current(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No export request on file"));
        try {
            return ResponseEntity.ok(ExportRequestStatus.of(
                    exports.cancel(tenantId, accountId, current.id(), SELF_SERVICE_ACTOR), clock.instant()));
        } catch (CustomerExportService.ExportRequestNotPendingException produced) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, produced.getMessage());
        }
    }

    @GetMapping("/bundle")
    @CustomerOwned
    @Operation(
            summary = "A short-lived link to my data, once it has been prepared",
            description =
                    "The link works for a few minutes. Each call is recorded against me as the reader. 404 until staff "
                            + "have produced the bundle, and after it has expired.")
    public ResponseEntity<BundleLink> bundle(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        ExportRequestRow current = exports.current(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No export request on file"));
        try {
            CustomerExportService.BundleLink link =
                    exports.bundleLink(tenantId, accountId, current.id(), SELF_SERVICE_PURPOSE, SELF_SERVICE_ACTOR);
            return ResponseEntity.ok(new BundleLink(link.url().toString(), link.expiresAt()));
        } catch (CustomerExportService.BundleNotAvailableException none) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Your data has not been prepared yet, or has expired");
        }
    }

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    /** Where my request stands. Nothing about who in the tenant handled it, and nothing about what is in the bundle. */
    public record ExportRequestStatus(
            UUID id,
            String status,
            Instant requestedAt,
            Instant responseDueAt,
            @Nullable Instant producedAt,
            @Nullable Instant bundleExpiresAt,
            boolean bundleAvailable,
            @Nullable Instant cancelledAt) {

        static ExportRequestStatus of(ExportRequestRow row, Instant now) {
            return new ExportRequestStatus(
                    row.id(),
                    row.status(),
                    row.requestedAt(),
                    row.responseDueAt(),
                    row.producedAt(),
                    row.bundleExpiresAt(),
                    row.bundleAvailableAt(now),
                    row.cancelledAt());
        }
    }

    public record BundleLink(String url, Instant expiresAt) {}
}
