package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.catalog.api.ChannelMenuPort;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.Category;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.Image;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.Item;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.ModifierGroup;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.ModifierOption;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.Variant;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.partner.api.PartnerBound;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.application.MarketplaceMenuPullService;
import uz.horecaos.platform.partner.application.MarketplaceMenuPullService.PulledMenu;
import uz.horecaos.platform.partner.application.MarketplaceMenuPullService.Restaurant;
import uz.horecaos.platform.partner.application.PartnerAuthenticationService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The partner pull of a branch's menu (ADR 0040 "Menu is pull-first", ADR 0138): the restaurants an
 * aggregator is bound to, and for each the menu its channel serves.
 *
 * <p>A read, so no {@code Idempotency-Key} and no version (ADR 0031 asks for them on mutations).
 * Authorised exactly as the availability pull is ({@link PartnerBound}): the aggregator's own client
 * credential and the bindings its installation holds, with the tenant in the path matched against the
 * credential's. The menu is the channel's assembled projection -- {@link ChannelMenuPort} -- and an
 * {@code ETag} over its content lets a partner that polls every few minutes send {@code If-None-Match}
 * and read a {@code 304} while nothing changed, instead of the whole menu each time. The tag covers
 * prices, stops and the pictures too, so any of them changing is a new tag; it deliberately covers no
 * clock, so an unchanged menu keeps its tag across minutes.
 *
 * <p>Items are identified by the platform's ids and, where the partner has mapped one ({@code MENU_ITEM},
 * ADR 0026), by the partner's own item id beside it. Prices are integer minor units with the menu's
 * currency; a missing price is absent, never zero.
 */
@RestController
@RequestMapping("/api/v1/partner/tenants/{tenantId}")
@Tag(name = "Partner API", description = "Inbound aggregator orders, shift events and availability reads (ADR 0040)")
public class PartnerMenuController {

    /** 120 menu reads a minute per partner credential: a menu is large and changes rarely, and 304 is cheap. */
    private static final RateLimiter.Policy MENU_PULL_LIMIT = new RateLimiter.Policy(120, Duration.ofMinutes(1), false);

    private final PartnerAuthenticationService authentication;
    private final MarketplaceMenuPullService pull;
    private final PartnerTokenReader tokens;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public PartnerMenuController(
            PartnerAuthenticationService authentication,
            MarketplaceMenuPullService pull,
            PartnerTokenReader tokens,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.authentication = authentication;
        this.pull = pull;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/restaurants")
    @PartnerBound(Capability.MARKETPLACE_MENU_READ)
    @Operation(
            summary = "List the restaurants this credential is bound to",
            description = "ADR 0040. Each branch the partner's installation is bound to through a live binding, "
                    + "by name, with whether its integration has a single active channel yet (a restaurant "
                    + "whose channelConfigured is false answers 409 CHANNEL_NOT_CONFIGURED on its menu).")
    public ResponseEntity<PartnerRestaurantsResponse> partnerRestaurants(@PathVariable UUID tenantId) {
        PartnerPrincipal principal = authentication.authenticate(tokens.clientId(), tenantId);
        limit(principal, tenantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PartnerRestaurantsResponse(pull.restaurants(principal).stream()
                        .map(PartnerRestaurantResponse::of)
                        .toList()));
    }

    @GetMapping("/restaurants/{locationId}/menu")
    @PartnerBound(Capability.MARKETPLACE_MENU_READ)
    @Operation(
            summary = "Read a restaurant's menu as its channel serves it",
            description = "ADR 0040 and ADR 0138. The channel's assembled menu at this restaurant: categories, "
                    + "dishes with their sizes (variants), modifier groups, prices in integer minor units, "
                    + "orderability and the pictures the channel shows for each dish, size and category. "
                    + "Every variant carries the platform's variantId and, where the partner has mapped it, "
                    + "its own partnerItemId. The response has an ETag over the whole menu: send it back in "
                    + "If-None-Match to read a 304 while nothing has changed. locale is ru, uz or en; omitted, "
                    + "the brand's own default. 404 when the restaurant is not one this credential is bound "
                    + "to; 409 CHANNEL_NOT_CONFIGURED when the integration has no single active channel yet; "
                    + "409 MENU_NOT_PUBLISHED when nothing has been published to the channel.")
    public ResponseEntity<PartnerMenuResponse> partnerMenu(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable String locale,
            WebRequest request) {
        PartnerPrincipal principal = authentication.authenticate(tokens.clientId(), tenantId);
        limit(principal, tenantId);

        PartnerMenuResponse body = PartnerMenuResponse.of(pull.menu(principal, locationId, locale));
        String etag = etagOf(body);
        if (matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                // A partner may keep the menu but must revalidate it: a price or a stop is only true as of its tag.
                .cacheControl(CacheControl.noCache())
                .body(body);
    }

    /** RFC 9110 weak comparison of an {@code If-None-Match} list against the menu's tag. */
    private static boolean matches(@Nullable String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.strip();
            if (tag.equals("*") || tag.equals(etag) || tag.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private void limit(PartnerPrincipal principal, UUID tenantId) {
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("partner.menu.pull", tenantId.toString(), principal.rateLimitSubject()),
                MENU_PULL_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many menu reads on this partner credential",
                    Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }
    }

    /** A strong validator: the digest of the menu exactly as serialised, so any visible change is a new tag. */
    private String etagOf(PartnerMenuResponse body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (JacksonException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "The partner menu cannot be fingerprinted: "
                            + failure.getClass().getSimpleName(),
                    failure);
        }
    }

    public record PartnerRestaurantsResponse(List<PartnerRestaurantResponse> restaurants) {}

    /** @param channelConfigured false while the integration has no single active channel */
    public record PartnerRestaurantResponse(UUID locationId, String name, boolean channelConfigured) {

        static PartnerRestaurantResponse of(Restaurant restaurant) {
            return new PartnerRestaurantResponse(
                    restaurant.locationId(), restaurant.name(), restaurant.channelConfigured());
        }
    }

    /**
     * @param publicationId the exact snapshot served
     * @param currency the menu's currency; null when no price resolves, and every amount is then null too
     */
    public record PartnerMenuResponse(
            UUID locationId,
            UUID publicationId,
            String locale,
            @Nullable String currency,
            List<PartnerMenuCategoryResponse> categories,
            List<PartnerMenuItemResponse> items,
            List<PartnerMenuModifierGroupResponse> modifierGroups) {

        static PartnerMenuResponse of(PulledMenu pulled) {
            ChannelMenuPort.ChannelMenu menu = pulled.menu();
            return new PartnerMenuResponse(
                    pulled.locationId(),
                    menu.publicationId(),
                    menu.locale(),
                    menu.currency(),
                    menu.categories().stream()
                            .map(PartnerMenuCategoryResponse::of)
                            .toList(),
                    menu.items().stream()
                            .map(item -> PartnerMenuItemResponse.of(item, pulled.partnerItemIds()))
                            .toList(),
                    menu.modifierGroups().stream()
                            .map(PartnerMenuModifierGroupResponse::of)
                            .toList());
        }
    }

    public record PartnerMenuImageResponse(String assetId, String url) {

        static PartnerMenuImageResponse of(Image image) {
            return new PartnerMenuImageResponse(image.assetId(), image.url());
        }
    }

    public record PartnerMenuCategoryResponse(
            UUID categoryId,
            String code,
            String name,
            @Nullable UUID parentCategoryId,
            int sortOrder,
            List<UUID> productIds,
            List<PartnerMenuImageResponse> images) {

        static PartnerMenuCategoryResponse of(Category category) {
            return new PartnerMenuCategoryResponse(
                    category.categoryId(),
                    category.code(),
                    category.name(),
                    category.parentCategoryId(),
                    category.sortOrder(),
                    category.productIds(),
                    category.images().stream().map(PartnerMenuImageResponse::of).toList());
        }
    }

    public record PartnerMenuItemResponse(
            UUID productId,
            String code,
            String name,
            @Nullable String description,
            List<PartnerMenuImageResponse> images,
            List<PartnerMenuVariantResponse> variants,
            List<UUID> modifierGroupIds) {

        static PartnerMenuItemResponse of(Item item, Map<UUID, String> partnerItemIds) {
            return new PartnerMenuItemResponse(
                    item.productId(),
                    item.code(),
                    item.name(),
                    item.description(),
                    item.images().stream().map(PartnerMenuImageResponse::of).toList(),
                    item.variants().stream()
                            .map(variant ->
                                    PartnerMenuVariantResponse.of(variant, partnerItemIds.get(variant.variantId())))
                            .toList(),
                    item.modifierGroupIds());
        }
    }

    /**
     * @param partnerItemId the partner's own id for this variant where a MENU_ITEM mapping exists, else null
     * @param orderable false means sold out or stopped on this channel here now
     * @param amountMinor null when no price resolves; never zero for "no price"
     */
    public record PartnerMenuVariantResponse(
            UUID variantId,
            @Nullable String partnerItemId,
            @Nullable String sku,
            @Nullable String unitCode,
            @Nullable String name,
            boolean isDefault,
            boolean orderable,
            boolean onSaleNow,
            @Nullable Long amountMinor,
            List<PartnerMenuImageResponse> images) {

        static PartnerMenuVariantResponse of(Variant variant, @Nullable String partnerItemId) {
            return new PartnerMenuVariantResponse(
                    variant.variantId(),
                    partnerItemId,
                    variant.sku(),
                    variant.unitCode(),
                    variant.name(),
                    variant.isDefault(),
                    variant.orderable(),
                    variant.onSaleNow(),
                    variant.amountMinor(),
                    variant.images().stream().map(PartnerMenuImageResponse::of).toList());
        }
    }

    public record PartnerMenuModifierGroupResponse(
            UUID modifierGroupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            List<PartnerMenuModifierOptionResponse> options) {

        static PartnerMenuModifierGroupResponse of(ModifierGroup group) {
            return new PartnerMenuModifierGroupResponse(
                    group.modifierGroupId(),
                    group.code(),
                    group.name(),
                    group.required(),
                    group.minimumSelections(),
                    group.maximumSelections(),
                    group.allowSameOptionMultipleTimes(),
                    group.options().stream()
                            .map(PartnerMenuModifierOptionResponse::of)
                            .toList());
        }
    }

    public record PartnerMenuModifierOptionResponse(
            UUID optionId,
            String code,
            @Nullable String name,
            int maximumQuantity,
            @Nullable Long amountMinor) {

        static PartnerMenuModifierOptionResponse of(ModifierOption option) {
            return new PartnerMenuModifierOptionResponse(
                    option.optionId(), option.code(), option.name(), option.maximumQuantity(), option.amountMinor());
        }
    }
}
