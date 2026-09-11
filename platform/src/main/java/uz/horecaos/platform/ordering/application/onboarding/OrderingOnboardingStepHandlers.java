package uz.horecaos.platform.ordering.application.onboarding;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.SampleMenuPort;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.StockListingPort;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.SampleMenuPricingPort;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;

/**
 * The two ADR 0008 step handlers that cannot live beside the rest.
 *
 * <p>{@code tenancy.application.onboarding.OnboardingStepHandlers} holds every
 * other unblocked handler, each reading another module's schema directly to
 * avoid a module cycle (see that file's class javadoc). This step cannot use
 * that trick: it has to call {@code pricing.api.CartPricingPort}, and pricing
 * is real business logic — price books, tax profiles, promotions — not a
 * lookup a raw SQL query could reproduce without duplicating the engine. And
 * {@code pricing} already depends on {@code tenancy.api} (through {@code
 * SalesChannelLookup} and {@code fulfillment.api}), so {@code tenancy}
 * depending on {@code pricing.api} in turn would close {@code tenancy ->
 * pricing -> tenancy}, exactly the cycle {@code ModularArchitectureTests}
 * exists to catch.
 *
 * <p>{@code ordering} is where this lives instead: it already depends on both
 * {@code tenancy.api} and {@code pricing.api} with no dependency running the
 * other way — it already calls {@code CartPricingPort.priceCart} itself, for
 * the same reason a smoke test does, to find out whether a location can
 * actually be sold from. {@code tenancy.api.onboarding} carries its own
 * {@code @NamedInterface} precisely so a handler can live outside {@code
 * tenancy}; {@code OnboardingService} discovers it the same way it discovers
 * every other handler, through ordinary Spring bean collection.
 *
 * <p>{@code SAMPLE_MENU_PUBLISH} (ADR 0099) is here for a stronger version of
 * the same reason. It has to write {@code catalog}, {@code pricing} and {@code
 * inventory} — and writing another module's tables through raw SQL is a second
 * implementation of that module's rules, not the boundary compromise reading
 * them is. {@code ordering} is the only module that already depends on all
 * three exported interfaces plus {@code tenancy.api}, so putting the handler
 * here adds no module edge whatsoever.
 */
public final class OrderingOnboardingStepHandlers {

    /**
     * {@code StorefrontChannelSeeder.STOREFRONT_CODE} (tenancy, not importable
     * from here): every tenant gets this channel on creation, and it is the
     * channel both handlers below care about.
     */
    private static final String STOREFRONT_CHANNEL = "STOREFRONT";

    /**
     * The currency {@code SampleMenuContent}'s amounts are authored in (ADR
     * 0099). Whole som, at the platform's UZS exponent of zero — not ISO 4217's
     * two, which both money modules deliberately refuse to use. Nothing converts
     * them, so this is the only currency the sample menu can honestly be priced
     * in; see {@code SampleMenuPublish.execute}'s refusal.
     */
    private static final String SAMPLE_CURRENCY = "UZS";

    private OrderingOnboardingStepHandlers() {}

    /**
     * Plants and publishes the sample menu a run may have asked for (ADR 0099).
     *
     * <p>The one optional step. A run that did not ask for a sample menu carries
     * this step {@code SKIPPED} from the moment it was materialised, so this
     * handler never sees it: {@code claimNextStep} takes only {@code PENDING}.
     *
     * <p>Orchestration only. Each of the three ports below is idempotent on its
     * own and each runs in its own transaction, deliberately: a step that dies
     * between the catalog and the prices has to be able to run again from the
     * top and find what it already made, and one long transaction spanning three
     * modules' writes would buy atomicity this step does not need at the cost of
     * a lock held across a publication's validation pass.
     *
     * <p>Does nothing at all when the brand already has a published menu that is
     * not the sample's. A tenant that authored a real menu between starting a
     * run and this step running must not have a sample published over it — and
     * the honest outcome for that is {@code COMPLETED}, because the thing this
     * step exists to guarantee (a published, sellable menu) is true.
     */
    @Component
    public static class SampleMenuPublish implements OnboardingStepHandler {

        private final JdbcClient jdbc;
        private final SampleMenuPort catalog;
        private final SampleMenuPricingPort pricing;
        private final StockListingPort stock;
        private final SalesChannelLookup channels;

        public SampleMenuPublish(
                JdbcClient jdbc,
                SampleMenuPort catalog,
                SampleMenuPricingPort pricing,
                StockListingPort stock,
                SalesChannelLookup channels) {
            this.jdbc = jdbc;
            this.catalog = catalog;
            this.pricing = pricing;
            this.stock = stock;
            this.channels = channels;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.SAMPLE_MENU_PUBLISH;
        }

        @Override
        public StepResult execute(StepContext context) {
            UUID tenantId = context.tenantId();

            Optional<BrandRow> brand = firstBrand(tenantId);
            if (brand.isEmpty()) {
                return StepResult.failed("NO_BRAND", "The tenant has no brand to hang a sample menu on");
            }
            UUID brandId = brand.get().id();

            List<UUID> locationIds = locationsOfBrand(tenantId, brandId);
            if (locationIds.isEmpty()) {
                return StepResult.failed(
                        "NO_LOCATION",
                        "Brand %s has no location to offer a sample menu at"
                                .formatted(brand.get().code()));
            }

            // Checked here rather than left to the publication, which throws an
            // IllegalArgumentException for an unregistered channel — and a thrown
            // handler is mapped to RETRY/TRANSIENT_INFRASTRUCTURE, so a tenant
            // whose storefront channel was never seeded would retry a permanent
            // condition until a human noticed. Named the same way
            // ACTIVATION_SMOKE_TEST names it, because it is the same gap.
            //
            // Existence is not enough: a channel exists after it is archived, and
            // publish refuses an archived one by throwing — the very shape this
            // check exists to avoid. sellable() is ACTIVATION_SMOKE_TEST's own
            // predicate, and it is also stricter than publish (which tolerates
            // INACTIVE): a storefront the tenant has switched off should fail
            // here, once and honestly, rather than pass CATALOG_READINESS_VALIDATE
            // on a menu published to a dead channel and fail at step 12.
            Optional<SalesChannel> storefront = channels.byCode(tenantId, STOREFRONT_CHANNEL);
            if (storefront.isEmpty() || !storefront.get().sellable()) {
                return StepResult.failed(
                        "NO_CHANNEL",
                        "The tenant has no active %s channel to publish a sample menu to"
                                .formatted(STOREFRONT_CHANNEL));
            }

            Optional<UUID> sampleCatalogId = catalog.sampleCatalogId(tenantId, brandId);
            Optional<UUID> publishedCatalogId = catalog.publishedCatalogId(tenantId, brandId, STOREFRONT_CHANNEL);
            if (publishedCatalogId.isPresent() && !publishedCatalogId.equals(sampleCatalogId)) {
                // A real menu is already live on the storefront. Publishing a
                // sample over it would retire the tenant's own work.
                return StepResult.completed(
                        Map.of("channel", STOREFRONT_CHANNEL, "created", false, "reason", "MENU_ALREADY_PUBLISHED"),
                        null);
            }

            // The sample's prices are whole som, copied from
            // tools/seed-data/horecaos-tenant.json, and nothing converts them.
            // Stamping them onto another currency would publish a menu that is
            // wrong by an exchange rate — 38 000 GEL for a plate of plov — as the
            // platform's own proof that the tenant works, so a tenant that trades
            // in anything else is refused rather than served a wrong menu. It also
            // keeps the hard-coded Uzbek VAT profile SampleMenuPricing writes off
            // tenants it does not describe. A per-market price table is the way to
            // lift this, when KZ or GE actually onboards (ADR 0099).
            String currency = currencyOf(tenantId);
            if (!SAMPLE_CURRENCY.equals(currency)) {
                return StepResult.failed(
                        "SAMPLE_MENU_UNSUPPORTED_CURRENCY",
                        "The sample menu's prices are authored in %s; this tenant trades in %s"
                                .formatted(SAMPLE_CURRENCY, currency));
            }

            SampleMenuPort.SampleMenu menu = catalog.installSample(tenantId, brandId, locationIds);

            SampleMenuPricingPort.SamplePricing priced;
            try {
                priced = pricing.priceSample(
                        tenantId,
                        brandId,
                        currency,
                        menu.variants().stream()
                                .map(variant -> new SampleMenuPricingPort.SampleVariantPrice(
                                        variant.variantId(), variant.amountMinor()))
                                .toList());
            } catch (SampleMenuPricingPort.SamplePricingRefusedException refused) {
                // Permanent: pricing will refuse the same way on every attempt.
                // Without this the thrown handler becomes RETRY, and the operator
                // is told by TRANSIENT_INFRASTRUCTURE's own hint that it retries
                // on its own — which it does, five times, and then stops.
                return StepResult.failed("SAMPLE_PRICING_REFUSED", refused.getMessage());
            }

            // An item with no stock row reads as unavailable rather than
            // available, so a menu that is published, offered and priced still
            // cannot be sold until something lists it — which is exactly what
            // ACTIVATION_SMOKE_TEST checks two steps later.
            int listed = 0;
            for (UUID locationId : locationIds) {
                for (SampleMenuPort.SampleVariant variant : menu.variants()) {
                    if (stock.ensureListed(tenantId, brandId, locationId, variant.variantId())) {
                        listed++;
                    }
                }
            }

            SampleMenuPort.SamplePublication publication =
                    catalog.publishSample(tenantId, brandId, menu.catalogId(), STOREFRONT_CHANNEL);
            if (!publication.blockers().isEmpty()) {
                return StepResult.failed(
                        "SAMPLE_MENU_REJECTED",
                        "The sample menu did not pass catalog validation: %s"
                                .formatted(String.join(", ", publication.blockers())));
            }

            // Ids and counts only (ADR 0029): no item name, no price, nothing
            // about a person. The catalog id is also the external reference, so
            // a re-run reconciles against what it made rather than looking again.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("catalogId", menu.catalogId().toString());
            result.put("catalogCode", menu.catalogCode());
            result.put("publicationId", publication.publicationId().toString());
            if (priced.priceBookId() != null) {
                // Absent rather than null when the tenant's own prices already
                // covered the sample and no sample book was needed.
                result.put("priceBookId", priced.priceBookId().toString());
            }
            result.put("categories", menu.categories());
            result.put("products", menu.products());
            result.put("variants", menu.variants().size());
            result.put("locations", locationIds.size());
            // Beside stockItemsListed and for the same reason: both ports create
            // only what is missing, so a run record that says nothing about them
            // cannot tell "there was nothing to do" from "something was
            // overwritten". Zero on a retry that found every offering already
            // there, including ones an operator had hidden.
            result.put("offeringsCreated", menu.offeringsCreated());
            result.put("stockItemsListed", listed);
            result.put("pricesSet", priced.priced());
            result.put("channel", STOREFRONT_CHANNEL);
            result.put("created", menu.created());
            return StepResult.completed(result, menu.catalogId().toString());
        }

        /**
         * The brand the sample hangs on: lowest code, which is stable across
         * runs. Ordering by {@code created_at} would depend on clock resolution
         * for two brands created in the same millisecond by the same import.
         */
        private Optional<BrandRow> firstBrand(UUID tenantId) {
            return jdbc.sql("""
                    SELECT id, code FROM tenant.brands
                     WHERE tenant_id = :tenantId AND status <> 'ARCHIVED'
                     ORDER BY code
                     LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .query((row, n) -> new BrandRow(row.getObject("id", UUID.class), row.getString("code")))
                    .optional();
        }

        private List<UUID> locationsOfBrand(UUID tenantId, UUID brandId) {
            return jdbc.sql("""
                    SELECT id FROM tenant.locations
                     WHERE tenant_id = :tenantId AND brand_id = :brandId
                     ORDER BY code
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .query(UUID.class)
                    .list();
        }

        /** The sample is priced in the tenant's own money, never in a currency this step chose. */
        private String currencyOf(UUID tenantId) {
            return Objects.requireNonNull(
                    jdbc.sql("SELECT default_currency FROM tenant.tenants WHERE id = :tenantId")
                            .param("tenantId", tenantId)
                            .query(String.class)
                            .single(),
                    "A tenant row always carries a currency: the column is NOT NULL");
        }

        private record BrandRow(UUID id, String code) {}
    }

    /**
     * A read-only dry run: does the location have a working serviceability
     * answer, and can a representative published item actually be quoted.
     *
     * <p>Deliberately does not require the resolved {@code Serviceability} to
     * be {@code available}. Being closed right now — outside trading hours,
     * mid manual-close — is a legitimate, transient state, not a
     * configuration gap; failing the smoke test for it would make onboarding
     * depend on the time of day it happened to run. What this checks is
     * whether the resolver can answer at all (a channel and a fulfilment mode
     * exist to ask about) and whether pricing can actually produce a quote —
     * both of which are real, durable configuration questions rather than a
     * clock reading.
     *
     * <p>Produces a {@code pricing.quotes} row, never a cart or an order —
     * ADR 0008 forbids this step from creating either — and reuses the same
     * idempotency key on every attempt (the onboarding run id plus the
     * location id) so a retried step finds its own quote rather than
     * accumulating a new row every time the scheduler tries again.
     *
     * <p><strong>Being quotable is not being sellable.</strong> A published,
     * priced item with no {@code inventory.stock_items} row (or one marked
     * sold out) priced clean for the exact reason {@code CartPricingPort}
     * never touches inventory — and reached {@code READY} that way until this
     * check existed, confirmed live: a fresh tenant activated with a menu no
     * real checkout could actually complete, refused
     * {@code 409 ITEMS_UNAVAILABLE}/{@code NOT_STOCKED_AT_LOCATION} on its
     * first real order. This calls {@link InventoryReservationPort#checkAvailability},
     * the exact read {@link InventoryReservationPort#reserveForQuote} takes
     * atomically before it holds anything — never the reservation itself,
     * because a smoke test that held real stock during onboarding would take
     * inventory away from a location that has not even opened.
     */
    @Component
    public static class ActivationSmokeTest implements OnboardingStepHandler {

        private final JdbcClient jdbc;
        private final SalesChannelLookup channels;
        private final ServiceabilityResolver serviceability;
        private final CartPricingPort pricing;
        private final InventoryReservationPort inventory;
        private final Clock clock;

        public ActivationSmokeTest(
                JdbcClient jdbc,
                SalesChannelLookup channels,
                ServiceabilityResolver serviceability,
                CartPricingPort pricing,
                InventoryReservationPort inventory,
                Clock clock) {
            this.jdbc = jdbc;
            this.channels = channels;
            this.serviceability = serviceability;
            this.pricing = pricing;
            this.inventory = inventory;
            this.clock = clock;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.ACTIVATION_SMOKE_TEST;
        }

        @Override
        public StepResult execute(StepContext context) {
            Instant now = clock.instant();

            for (LocationRow location : locationsOf(context.tenantId())) {
                Optional<SalesChannel> channel = channels.byCode(context.tenantId(), STOREFRONT_CHANNEL);
                if (channel.isEmpty() || !channel.get().sellable()) {
                    return StepResult.failed(
                            "NO_CHANNEL",
                            "Location %s has no active %s channel to sell through"
                                    .formatted(location.code(), STOREFRONT_CHANNEL));
                }

                Optional<FulfillmentMode> mode =
                        anyEnabledMode(context.tenantId(), channel.get().id(), location.id());
                if (mode.isEmpty()) {
                    return StepResult.failed(
                            "NO_FULFILLMENT_MODE",
                            "Location %s has no fulfilment mode enabled on %s"
                                    .formatted(location.code(), STOREFRONT_CHANNEL));
                }

                try {
                    serviceability.resolve(
                            context.tenantId(),
                            location.brandId(),
                            location.id(),
                            channel.get().id(),
                            mode.get(),
                            now);
                } catch (RuntimeException failure) {
                    return StepResult.retry("SERVICEABILITY_UNAVAILABLE", failure.getMessage());
                }

                Optional<RepresentativeItem> item =
                        representativeItem(context.tenantId(), location.brandId(), location.id());
                if (item.isEmpty()) {
                    return StepResult.failed(
                            "NO_AVAILABLE_ITEM",
                            "Location %s has no item available to quote".formatted(location.code()));
                }
                UUID variantId = item.get().variantId();

                try {
                    pricing.priceCart(new CartPricingPort.PricingCommand(
                            context.tenantId(),
                            location.brandId(),
                            location.id(),
                            null,
                            STOREFRONT_CHANNEL,
                            List.of(new CartPricingPort.PricingCommand.Item("smoke-test", variantId, 1, List.of())),
                            "onboarding-smoke:%s:%s".formatted(context.runId(), location.id())));
                } catch (CartPricingPort.PricingRefusedException refused) {
                    return StepResult.failed(
                            "QUOTE_REFUSED",
                            "Location %s: %s (%s)".formatted(location.code(), refused.getMessage(), refused.code()));
                }

                AvailabilityDecision availability =
                        inventory.checkAvailability(context.tenantId(), location.id(), Set.of(variantId));
                if (!availability.available()) {
                    String reasons = availability.unavailableItems().stream()
                            .map(AvailabilityDecision.Unavailable::reason)
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(", "));
                    return StepResult.failed(
                            "ITEM_NOT_AVAILABLE_TO_SELL",
                            "Location %s: item %s (%s) prices cleanly but is not actually available to sell (%s)"
                                    .formatted(location.code(), item.get().sku(), variantId, reasons));
                }
            }
            return StepResult.completed(Map.of(), null);
        }

        private List<LocationRow> locationsOf(UUID tenantId) {
            return jdbc.sql("""
                    SELECT id, brand_id, code FROM tenant.locations WHERE tenant_id = :tenantId
                    """)
                    .param("tenantId", tenantId)
                    .query((row, n) -> new LocationRow(
                            row.getObject("id", UUID.class),
                            row.getObject("brand_id", UUID.class),
                            row.getString("code")))
                    .list();
        }

        private Optional<FulfillmentMode> anyEnabledMode(UUID tenantId, UUID channelId, UUID locationId) {
            return jdbc.sql("""
                    SELECT cfm.fulfillment_mode
                      FROM tenant.channel_fulfillment_modes cfm
                      JOIN tenant.sales_channel_locations scl
                        ON scl.tenant_id = cfm.tenant_id AND scl.channel_id = cfm.channel_id
                     WHERE cfm.tenant_id = :tenantId AND cfm.channel_id = :channelId
                       AND scl.location_id = :locationId AND cfm.enabled AND scl.status = 'ACTIVE'
                     ORDER BY cfm.fulfillment_mode
                     LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("locationId", locationId)
                    .query(String.class)
                    .optional()
                    .map(FulfillmentMode::valueOf);
        }

        private Optional<RepresentativeItem> representativeItem(UUID tenantId, UUID brandId, UUID locationId) {
            return jdbc.sql("""
                    SELECT o.variant_id, v.sku FROM catalog.location_offerings o
                      JOIN catalog.variants v ON v.id = o.variant_id AND v.tenant_id = o.tenant_id
                     WHERE o.tenant_id = :tenantId AND o.brand_id = :brandId AND o.location_id = :locationId
                       AND o.status = 'AVAILABLE'
                     LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("locationId", locationId)
                    .query((row, n) ->
                            new RepresentativeItem(row.getObject("variant_id", UUID.class), row.getString("sku")))
                    .optional();
        }

        private record LocationRow(UUID id, UUID brandId, String code) {}

        /** The item {@link #execute} quotes and then checks for real availability, named for the failure message. */
        private record RepresentativeItem(UUID variantId, String sku) {}
    }
}
