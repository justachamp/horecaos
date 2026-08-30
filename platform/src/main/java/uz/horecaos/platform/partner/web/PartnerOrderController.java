package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.partner.api.PartnerBound;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService.PartnerOrderPush;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService.PushLine;
import uz.horecaos.platform.partner.application.PartnerAuthenticationService;
import uz.horecaos.platform.partner.domain.DiscountFunding;
import uz.horecaos.platform.partner.domain.ExternalTotals;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.NaturallyIdempotent;

/**
 * The sixth HTTP surface (ADR 0031, ADR 0040).
 *
 * <p>{@code /api/v1/partner/**} joins ADR 0031's five, and it exists so that any
 * aggregator can integrate without a HorecaOS release. Provider-specific adapters
 * are the compatibility layer over this contract, never a replacement for it:
 * per-provider adapters alone make every new partner a release, which is exactly
 * why the legacy estate ended up building a generic dynamic integration as an
 * escape hatch after the fact.
 *
 * <p>The tenant is in the path as ADR 0031 requires of every surface, and it is
 * matched against the credential's own tenant rather than trusted. A partner is
 * the principal least able to be trusted with a path parameter, because a bug in
 * its client that iterates identifiers is indistinguishable from an attempt to
 * enumerate somebody else's order book, and both must be refused the same way.
 *
 * <p><strong>Idempotency.</strong> ADR 0031 requires a client-supplied
 * {@code Idempotency-Key} on every effectful mutation. This endpoint does not,
 * and the exception is documented rather than accidental: partners will not send
 * one, and the key derived from {@code (binding, external order id)} is stronger
 * than a header HorecaOS does not control the generation of. A retried order that
 * creates a second one is a restaurant cooking twice, so the guarantee here is
 * a unique constraint rather than a client's discipline.
 *
 * <p><strong>Rate limits are per partner, not per tenant.</strong> One
 * aggregator polling aggressively must not exhaust a budget shared with another,
 * and a burst allowance is granted because aggregators poll — a limiter tuned
 * for a human's click rate refuses a perfectly ordinary partner integration.
 */
@RestController
@RequestMapping("/api/v1/partner/tenants/{tenantId}")
@Tag(name = "Partner API", description = "Inbound aggregator orders (ADR 0040)")
public class PartnerOrderController {

    /**
     * Generous, and per client. An aggregator retries hard when it thinks a
     * venue is unreachable, and the failure mode of a tight limit here is the
     * partner marking the restaurant offline — which costs the restaurant its
     * whole evening rather than costing HorecaOS a few requests.
     */
    private static final RateLimiter.Policy ORDER_PUSH_LIMIT =
            new RateLimiter.Policy(600, Duration.ofMinutes(1), false);

    private final PartnerAuthenticationService authentication;
    private final MarketplaceIngestionService ingestion;
    private final PartnerTokenReader tokens;
    private final RateLimiter rateLimiter;

    public PartnerOrderController(
            PartnerAuthenticationService authentication,
            MarketplaceIngestionService ingestion,
            PartnerTokenReader tokens,
            RateLimiter rateLimiter) {
        this.authentication = authentication;
        this.ingestion = ingestion;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/orders")
    @PartnerBound(Capability.MARKETPLACE_ORDER_RECEIVE)
    @NaturallyIdempotent
    @Operation(
            summary = "Push an order",
            description = "Idempotent on (venue, externalOrderId). A repeat returns the order the "
                    + "first push created, with 200 rather than 201, and never creates a second.")
    public ResponseEntity<PushResponse> push(@PathVariable UUID tenantId, @Valid @RequestBody PushRequest body) {

        PartnerPrincipal principal = authentication.authenticate(tokens.clientId(), tenantId);

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("partner.order.push", tenantId.toString(), principal.rateLimitSubject()),
                ORDER_PUSH_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many pushes on this partner credential",
                    java.util.Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }

        MarketplaceIngestionService.Outcome outcome = ingestion.receive(principal, body.toPush());

        if (!outcome.accepted()) {
            // 422 rather than 400: the request was well formed and HorecaOS
            // understood it, and the partner's engineer needs the code to tell
            // "your JSON is wrong" from "your totals do not add up".
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(PushResponse.rejected(outcome.rejectionCode().name()));
        }
        return ResponseEntity.status(outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PushResponse.accepted(outcome));
    }

    /**
     * @param handoverCode the code the courier will read out, if the partner
     *                     issues one. It is hashed on arrival and never stored,
     *                     returned, logged, or traced in the clear.
     */
    public record PushRequest(
            @NotBlank @Size(max = 128) String venueReference,
            @NotBlank @Size(max = 128) String externalOrderId,
            @Size(max = 128) String displayCode,
            @NotBlank @Size(max = 16) String fulfillmentMode,
            @NotNull @Valid Totals totals,
            String discountFunding,
            @NotNull @Size(min = 1) List<@Valid Line> lines,
            @Size(max = 64) String handoverCode,
            Instant pickupExpectedAt) {

        PartnerOrderPush toPush() {
            ExternalTotals external = new ExternalTotals(
                    totals.currency(),
                    totals.totalMinor(),
                    totals.subtotalMinor(),
                    totals.discountMinor() == null ? 0L : totals.discountMinor(),
                    totals.feeMinor() == null ? 0L : totals.feeMinor(),
                    totals.taxMinor());

            return new PartnerOrderPush(
                    venueReference,
                    externalOrderId,
                    displayCode,
                    fulfillmentMode,
                    external,
                    discountFunding == null ? DiscountFunding.UNKNOWN : DiscountFunding.valueOf(discountFunding),
                    lines.stream()
                            .map(line -> new PushLine(
                                    line.externalItemReference(),
                                    line.name(),
                                    line.quantity(),
                                    line.unitAmountMinor(),
                                    line.lineAmountMinor(),
                                    line.taxMinor()))
                            .toList(),
                    handoverCode,
                    pickupExpectedAt,
                    // The raw body is staged encrypted for evidence. It is
                    // reconstructed rather than captured here so the plain
                    // handover code cannot reach the staging row.
                    "{}",
                    "{}");
        }
    }

    /**
     * @param taxMinor omitted when the partner reports no tax. Omitted and zero
     *                 are different claims and are stored differently.
     */
    public record Totals(
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Long totalMinor,
            @NotNull Long subtotalMinor,
            Long discountMinor,
            Long feeMinor,
            Long taxMinor) {}

    public record Line(
            @NotBlank @Size(max = 128) String externalItemReference,
            @NotBlank @Size(max = 255) String name,
            @Positive int quantity,
            @NotNull Long unitAmountMinor,
            @NotNull Long lineAmountMinor,
            Long taxMinor) {}

    /**
     * @param unmappedItems non-empty means the order was accepted with items the
     *                      catalogue does not carry. Reported back so the
     *                      partner's own console can show the venue what to fix,
     *                      and raised as a branch-visible exception here.
     */
    public record PushResponse(
            String status, UUID orderId, String publicOrderNumber, String rejectionCode, List<String> unmappedItems) {

        static PushResponse accepted(MarketplaceIngestionService.Outcome outcome) {
            return new PushResponse(
                    outcome.duplicate() ? "DUPLICATE" : "ACCEPTED",
                    outcome.orderId(),
                    outcome.publicOrderNumber(),
                    null,
                    outcome.unmappedItems());
        }

        static PushResponse rejected(String code) {
            return new PushResponse("REJECTED", null, null, code, List.of());
        }
    }
}
