package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcPromotionReferences;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The quote simulator (ADR 0140): the real engine with a different sink.
 *
 * <p>It runs the same price book, tax profile, delivery resolution, promotion
 * input resolution and {@link PricingEngine} a real quote runs -- through {@link
 * QuoteService#simulate} -- and writes no quote, no redemption and no counter. The
 * customer is synthetic facts, never an account id: a read-only screen has no
 * business holding anybody's personal history. A candidate definition that has
 * not been saved can be tried against a real cart, and a stored definition
 * version can be replayed in place of the current one, which is how a marketer
 * explains an old quote against the rule that priced it.
 *
 * <p>The decision trace names every promotion in the brand and says whether it
 * applied or the reason it did not. That trace is the reason a rule set with no OR
 * stays survivable: duplicating a promotion to express "or" is a support burden
 * only while nobody can see why a rule did not fire.
 */
@Service
public class PromotionSimulationService {

    private final QuoteService quotes;
    private final ConfigurationResolver configuration;
    private final JdbcPromotionReferences references;
    private final java.time.Clock clock;

    public PromotionSimulationService(
            QuoteService quotes,
            ConfigurationResolver configuration,
            JdbcPromotionReferences references,
            java.time.Clock clock) {
        this.quotes = quotes;
        this.configuration = configuration;
        this.references = references;
        this.clock = clock;
    }

    /** One synthetic cart line. */
    public record Line(String lineId, UUID variantId, int quantity, List<UUID> modifierOptionIds) {

        public Line {
            modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
        }
    }

    /** The customer as facts: where the order would sit in their history and which audiences they are in. */
    public record Facts(
            @Nullable Integer brandOrderPosition, @Nullable Integer channelOrderPosition, Set<String> segments) {

        public Facts {
            segments = segments == null ? Set.of() : Set.copyOf(segments);
        }
    }

    public record Request(
            UUID locationId,
            String channelCode,
            @Nullable String fulfillmentMode,
            @Nullable String paymentMethodCode,
            @Nullable Instant serviceInstant,
            List<Line> lines,
            @Nullable String presentedCouponCode,
            @Nullable GeoPoint destination,
            @Nullable Facts facts,
            @Nullable PromotionDefinition candidate,
            Map<UUID, Integer> definitionVersions) {

        public Request {
            lines = lines == null ? List.of() : List.copyOf(lines);
            definitionVersions = definitionVersions == null ? Map.of() : Map.copyOf(definitionVersions);
        }
    }

    @Transactional(readOnly = true)
    public QuoteService.Priced simulate(UUID tenantId, UUID brandId, Request request) {
        int maxLines = Objects.requireNonNull(
                configuration.value(
                        PricingConfigurationKeys.PROMOTION_SIMULATE_MAX_LINES, ResourceScope.brand(tenantId, brandId)),
                "declares a code default and never terminates on explicit null");
        if (request.lines().isEmpty() || request.lines().size() > maxLines) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A simulated cart needs between 1 and " + maxLines + " lines");
        }

        PromotionDefinition candidate = request.candidate();
        if (candidate != null) {
            PromotionValidator.Report report = PromotionValidator.validate(
                    null, candidate, List.of(), references.forBrand(tenantId, brandId, clock.instant()));
            if (!report.isValid()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "The candidate promotion does not validate: "
                                + report.refusals().get(0).code());
            }
        }

        var frame = new QuoteRequest.Frame(
                request.serviceInstant(), request.paymentMethodCode(), request.fulfillmentMode(), null, null);
        var quoteRequest = new QuoteRequest(
                tenantId,
                brandId,
                request.locationId(),
                // Never an account: the simulator reads no personal history.
                null,
                request.channelCode(),
                request.lines().stream()
                        .map(line -> new QuoteRequest.Line(
                                line.lineId(), line.variantId(), line.quantity(), line.modifierOptionIds()))
                        .toList(),
                null,
                request.destination() == null
                        ? null
                        : new QuoteRequest.Delivery(request.destination(), PricingAuthority.HORECAOS),
                request.presentedCouponCode(),
                null,
                frame);

        Facts facts = request.facts();
        var overrides = new PromotionInputResolver.Overrides(
                request.serviceInstant(),
                request.fulfillmentMode(),
                request.paymentMethodCode(),
                facts == null ? null : facts.brandOrderPosition(),
                facts == null ? null : facts.channelOrderPosition(),
                facts == null ? Set.of() : facts.segments(),
                candidate == null ? List.of() : List.of(candidate),
                request.definitionVersions());
        return quotes.simulate(quoteRequest, overrides);
    }
}
