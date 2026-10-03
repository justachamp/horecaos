package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.ChannelProjection;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.MarketplaceRuleset;
import uz.horecaos.platform.catalog.application.MarketplaceRulesets;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.ChannelFindings;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0138's marketplace projection preview, end to end through the real Spring
 * context: the {@code GET .../channels/{id}/preview} read, the channel media
 * override write, and the plug point a marketplace ruleset registers into.
 *
 * <p>What these prove, each against the failure that would still pass a weaker
 * test:
 *
 * <ul>
 *   <li><strong>Equivalence.</strong> For a non-marketplace channel the projection
 *       is field-for-field the menu a customer is served after publishing the same
 *       draft. A preview that dropped a gate, or composed them in another order,
 *       would still return a plausible menu — but not <em>this</em> one.
 *   <li><strong>Precedence.</strong> Exclusion removes an item from one channel and
 *       not another; a CHANNEL price book outranks a LOCATION one outranks a BRAND
 *       one; a channel's image outranks its per-channel relation outranks the
 *       default — and a price is not touched by an image override, nor an image by a
 *       price.
 *   <li><strong>Findings.</strong> Each code the preview raises is asserted
 *       <em>by what it names</em>, including that a marketplace binding with no
 *       ruleset says so rather than reading as clean.
 *   <li><strong>Isolation.</strong> Another tenant's channel, binding, branch or
 *       catalog is "not found", and its owner is refused on this tenant's path.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubJwtIssuer.class, ChannelPreviewEndpointTests.TestRulesets.class})
class ChannelPreviewEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LOCALE = "uz";
    private static final String TEST_RULESET = "TEST_NEEDS_IMAGES";

    /** The plug point, exercised with a ruleset that exists only here: production registers none. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestRulesets {

        @Bean
        MarketplaceRuleset needsImagesRuleset() {
            return new MarketplaceRuleset() {
                @Override
                public String code() {
                    return TEST_RULESET;
                }

                @Override
                public List<ValidationFinding> check(ChannelProjection projection) {
                    return projection.products().stream()
                            .filter(product -> product.mediaAssetIds().isEmpty())
                            .map(product -> ValidationFinding.blocker(
                                    ChannelFindings.MARKETPLACE_IMAGE_REQUIREMENT_UNMET,
                                    EntityType.PRODUCT,
                                    product.productId(),
                                    product.code(),
                                    "This marketplace wants a picture"))
                            .toList();
                }
            };
        }
    }

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the channel preview tests");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CatalogAuthoringService authoring;

    @Autowired
    private CatalogPublicationService publication;

    @Autowired
    private CompositeProductAuthoringService composites;

    @Autowired
    private JdbcCatalogStore store;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private MarketplaceRulesets rulesets;

    private World w;

    @BeforeEach
    void seed() {
        roleRegistry.synchronize();
        w = new World();
    }

    // ----------------------------------------------------------- equivalence

    @Test
    @DisplayName(
            "for a non-marketplace channel the projection is the menu a customer is served after publishing the draft")
    void projectionEqualsTheLiveStorefrontMenu() throws Exception {
        // Everything the storefront gates on, so a preview that skipped one fails:
        // an 86'd dish (shown, not orderable), a dish this branch does not offer
        // (absent), an image, a modifier group, two categories, a price per variant.
        w.offer(w.lagman, OfferingStatus.AVAILABLE);
        w.offer(w.plov, OfferingStatus.AVAILABLE);
        w.offer(w.samsa, OfferingStatus.UNAVAILABLE);
        // w.tea is offered nowhere at l1.
        w.attachDefaultImage(w.lagman.productId(), 0, w.asset());

        assertThat(publication
                        .publish(w.tenant, w.brand, w.catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);

        JsonNode live = json(mvc.perform(
                get("/api/v1/storefront/tenants/%s/brands/%s/locations/%s/menu".formatted(w.tenant, w.brand, w.l1))
                        .queryParam("channel", "STOREFRONT")
                        .queryParam("locale", LOCALE)));
        JsonNode preview = previewAll(w.storefront, "locationId", w.l1.toString());

        assertThat(shape(preview.path("items"), "productId", true))
                .as("the same products, with the same names, images, prices and orderability")
                .isEqualTo(shape(live.path("products"), "productId", true));
        assertThat(shape(preview.path("categories"), "categoryId", false))
                .as("the same shelves, holding only what this branch serves")
                .isEqualTo(shape(live.path("categories"), "categoryId", false));
        assertThat(shape(preview.path("modifierGroups"), "modifierGroupId", true))
                .isEqualTo(shape(live.path("modifierGroups"), "modifierGroupId", true));
        assertThat(preview.path("pricing").path("currency").asText())
                .isEqualTo(live.path("currency").asText());

        // And the fixture is not vacuous: the gates really did remove and mark things.
        Set<String> served = ids(live.path("products"), "productId");
        assertThat(served)
                .contains(
                        w.lagman.productId().toString(),
                        w.plov.productId().toString(),
                        w.samsa.productId().toString())
                .doesNotContain(w.tea.productId().toString());
        assertThat(variantOf(live, w.samsa).path("orderable").asBoolean()).isFalse();
        assertThat(variantOf(live, w.lagman).path("amountMinor").asLong()).isEqualTo(30_000L);
        assertThat(preview.path("channelReady").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------ precedence

    @Test
    @DisplayName("a channel exclusion removes the dish from that channel at that branch and from no other")
    void exclusionIsPerChannelAndPerBranch() throws Exception {
        w.offerEverythingAt(w.l1);
        w.offerEverythingAt(w.l2);
        w.bindChannelToLocation(w.uzum, w.l2);
        w.bindChannelToLocation(w.storefront, w.l2);

        // Brand-wide on Uzum for lagman; at l2 only on Uzum for plov.
        authoring.setChannelOffering(
                w.tenant, w.brand, w.uzum, w.lagman.defaultVariantId(), null, false, "SEASONAL", "t");
        authoring.setChannelOffering(
                w.tenant, w.brand, w.uzum, w.plov.defaultVariantId(), w.l2, false, "SEASONAL", "t");

        Set<String> uzumAtL1 =
                ids(previewAll(w.uzum, "locationId", w.l1.toString()).path("items"), "productId");
        Set<String> uzumAtL2 =
                ids(previewAll(w.uzum, "locationId", w.l2.toString()).path("items"), "productId");
        Set<String> storefrontAtL1 =
                ids(previewAll(w.storefront, "locationId", w.l1.toString()).path("items"), "productId");

        assertThat(uzumAtL1)
                .as("brand-wide exclusion: gone at l1; plov's exclusion names l2, so it stays here")
                .doesNotContain(w.lagman.productId().toString())
                .contains(w.plov.productId().toString());
        assertThat(uzumAtL2)
                .as("gone at l2 too, plus plov's branch-narrowed exclusion")
                .doesNotContain(
                        w.lagman.productId().toString(), w.plov.productId().toString());
        assertThat(storefrontAtL1)
                .as("an exclusion names its channel: the storefront still serves the dish")
                .contains(w.lagman.productId().toString(), w.plov.productId().toString());
    }

    @Test
    @DisplayName("price resolves CHANNEL over LOCATION over BRAND, and a variant the channel's book lacks is a finding")
    void priceBookPrecedence() throws Exception {
        w.offerEverythingAt(w.l1);
        w.priceBook("LOCATION_BOOK", "LOCATION", w.l1, Map.of(w.lagman, 20_000L, w.plov, 21_000L));

        // BRAND book is 30 000 / 25 000 / ... (World). With only a LOCATION book assigned,
        // that book answers for the branch -- and for the storefront channel too, since
        // no CHANNEL book exists yet.
        JsonNode storefrontBefore = previewAll(w.storefront, "locationId", w.l1.toString());
        assertThat(variantOf(storefrontBefore, w.lagman).path("amountMinor").asLong())
                .isEqualTo(20_000L);

        // A CHANNEL book for Uzum prices lagman only. It outranks the LOCATION book for
        // Uzum and for nobody else.
        w.priceBook("UZUM_BOOK", "CHANNEL", w.uzum, Map.of(w.lagman, 35_000L));
        JsonNode uzum = previewAll(w.uzum, "locationId", w.l1.toString());
        JsonNode storefront = previewAll(w.storefront, "locationId", w.l1.toString());

        assertThat(variantOf(uzum, w.lagman).path("amountMinor").asLong())
                .as("the channel's own book wins on its channel")
                .isEqualTo(35_000L);
        assertThat(variantOf(storefront, w.lagman).path("amountMinor").asLong())
                .as("and does not leak to a channel that has no book of its own")
                .isEqualTo(20_000L);

        // The CHANNEL book is the one book that answers, whole: plov is priced
        // elsewhere in the brand but not on this plane. Nothing is silently priced
        // from a lower book, and the gap is a finding that names the variant.
        assertThat(variantOf(uzum, w.plov).path("amountMinor").isNull()
                        || !variantOf(uzum, w.plov).has("amountMinor"))
                .isTrue();
        assertThat(findings(uzum, ChannelFindings.CHANNEL_PRICE_MISSING))
                .extracting(f -> f.path("entityId").asText())
                .contains(w.plov.defaultVariantId().toString())
                .doesNotContain(w.lagman.defaultVariantId().toString());
        assertThat(uzum.path("channelReady").asBoolean()).isFalse();
        assertThat(uzum.path("publishable").asBoolean())
                .as("publishing is unchanged: no universal blocker, so the real publish would proceed")
                .isTrue();

        // A channel that takes another's price plane resolves the same book (ADR 0036).
        UUID kiosk = w.channel("KIOSK", "KIOSK", false, w.uzum, null);
        w.bindChannelToLocation(kiosk, w.l1);
        assertThat(variantOf(previewAll(kiosk, "locationId", w.l1.toString()), w.lagman)
                        .path("amountMinor")
                        .asLong())
                .as("price_plane_channel_id: the kiosk reads Uzum's CHANNEL book")
                .isEqualTo(35_000L);
    }

    @Test
    @DisplayName("a channel whose aggregator sets the price carries no amount at all, and no price finding")
    void externallyPricedChannelCarriesNoPrice() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID wolt = w.channel("WOLT", "AGGREGATOR", true, null, null);
        w.bindChannelToLocation(wolt, w.l1);

        JsonNode preview = previewAll(wolt, "locationId", w.l1.toString());

        assertThat(preview.path("pricing").path("authority").asText()).isEqualTo("EXTERNAL");
        assertThat(preview.path("pricing").path("currency").isNull()
                        || !preview.path("pricing").has("currency"))
                .as("no currency either: Qoida states no number the aggregator will actually set")
                .isTrue();
        assertThat(preview.path("items")).isNotEmpty();
        for (JsonNode product : preview.path("items")) {
            for (JsonNode variant : product.path("variants")) {
                assertThat(variant.has("amountMinor")
                                && !variant.path("amountMinor").isNull())
                        .as("amountMinor of %s"
                                .formatted(variant.path("variantId").asText()))
                        .isFalse();
            }
        }
        assertThat(findings(preview, ChannelFindings.CHANNEL_PRICE_MISSING)).isEmpty();
        assertThat(findings(preview, ChannelFindings.CHANNEL_PRICE_BOOK_MISSING))
                .isEmpty();
        // The same draft on a priced channel does carry the amounts: the omission is the channel's, not the data's.
        assertThat(variantOf(previewAll(w.storefront, "locationId", w.l1.toString()), w.lagman)
                        .path("amountMinor")
                        .asLong())
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a branch bound to a named menu is projected from that menu, not from its offerings")
    void boundNamedMenuReplacesTheBranchOfferings() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID menu = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status) VALUES (:id, :t, :b, 'Delivery only', 'ACTIVE')")
                .param("id", menu)
                .param("t", w.tenant)
                .param("b", w.brand)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.menu_items (id, tenant_id, brand_id, menu_id, variant_id, availability_default)
                VALUES (:id, :t, :b, :menu, :variant, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("menu", menu)
                .param("variant", w.lagman.defaultVariantId())
                .update();
        // Bound for Uzum alone at l1: the storefront keeps reading the branch's offerings.
        jdbc.sql("""
                INSERT INTO catalog.branch_menu_bindings (id, tenant_id, brand_id, location_id, channel_id, menu_id)
                VALUES (:id, :t, :b, :loc, :channel, :menu)
                """)
                .param("id", UUID.randomUUID())
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("loc", w.l1)
                .param("channel", w.uzum)
                .param("menu", menu)
                .update();

        assertThat(ids(previewAll(w.uzum, "locationId", w.l1.toString()).path("items"), "productId"))
                .containsExactly(w.lagman.productId().toString());
        assertThat(ids(previewAll(w.storefront, "locationId", w.l1.toString()).path("items"), "productId"))
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName(
            "a channel's image outranks its per-channel relation, which outranks the default -- and price is untouched")
    void mediaPrecedenceAndIndependenceFromPrice() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID wolt = w.channel("WOLT", "AGGREGATOR", false, null, null);
        w.bindChannelToLocation(wolt, w.l1);

        UUID universal = w.asset();
        UUID uzumCrop = w.asset();
        UUID woltCrop = w.asset();
        UUID override = w.asset();
        // lagman: a default, a Uzum override. plov: a default and a V0223 relation naming Uzum.
        // samsa: a default only.
        w.attachDefaultImage(w.lagman.productId(), 0, universal);
        w.attachDefaultImage(w.plov.productId(), 0, universal);
        w.attachChannelRelation(w.plov.productId(), 0, uzumCrop, "UZUM");
        w.attachChannelRelation(w.plov.productId(), 0, woltCrop, "WOLT");
        w.attachDefaultImage(w.samsa.productId(), 0, universal);

        MvcResult written = mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), """
                        {"images":[{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":0}]}
                        """.formatted(override)))
                .andReturn();
        assertThat(written.getResponse().getStatus()).as(body(written)).isEqualTo(200);

        JsonNode uzum = previewAll(w.uzum, "locationId", w.l1.toString());
        JsonNode woltPreview = previewAll(wolt, "locationId", w.l1.toString());
        JsonNode storefront = previewAll(w.storefront, "locationId", w.l1.toString());

        assertThat(productOf(uzum, w.lagman).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .containsExactly(override.toString());
        assertThat(productOf(uzum, w.lagman).path("mediaSource").asText()).isEqualTo("CHANNEL_OVERRIDE");
        assertThat(productOf(uzum, w.plov).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .containsExactly(uzumCrop.toString());
        assertThat(productOf(uzum, w.plov).path("mediaSource").asText()).isEqualTo("CHANNEL_RELATION");
        assertThat(productOf(uzum, w.samsa).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .containsExactly(universal.toString());
        assertThat(productOf(uzum, w.samsa).path("mediaSource").asText()).isEqualTo("DEFAULT");
        assertThat(productOf(uzum, w.lagman).path("imageUrls").get(0).asText())
                .as("the URL is built from the override asset, so the picture a customer fetches is the channel's")
                .endsWith("/media/" + override);

        assertThat(productOf(woltPreview, w.plov).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .as("Wolt gets its own crop, never Uzum's")
                .containsExactly(woltCrop.toString());
        assertThat(productOf(woltPreview, w.lagman).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .as("an override names its channel: Wolt still shows the dish's own image")
                .containsExactly(universal.toString());
        assertThat(productOf(storefront, w.plov).path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .as("the storefront does not inherit any aggregator's crop")
                .containsExactly(universal.toString());

        // Independent axes: the image override did not move a price, and the price did not gate the image.
        assertThat(variantOf(uzum, w.lagman).path("amountMinor").asLong())
                .isEqualTo(variantOf(storefront, w.lagman).path("amountMinor").asLong())
                .isEqualTo(30_000L);

        // Removing the override puts the default back.
        MvcResult cleared = mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), "{\"images\":[]}"))
                .andReturn();
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        assertThat(productOf(previewAll(w.uzum, "locationId", w.l1.toString()), w.lagman)
                        .path("mediaAssetIds"))
                .extracting(JsonNode::asText)
                .containsExactly(universal.toString());
    }

    @Test
    @DisplayName(
            "what publishing serves as a channel's images is what its preview showed: a relation naming another channel is never published here")
    void publishedImagesAreThePreviewedImages() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID universal = w.asset();
        UUID uzumCrop = w.asset();
        UUID onlyUzum = w.asset();
        // lagman: its own image and a crop that is Uzum's. plov: nothing of its own, only a crop that is
        // Uzum's. samsa: its own image alone.
        w.attachDefaultImage(w.lagman.productId(), 0, universal);
        w.attachChannelRelation(w.lagman.productId(), 1, uzumCrop, "UZUM");
        w.attachChannelRelation(w.plov.productId(), 0, onlyUzum, "UZUM");
        w.attachDefaultImage(w.samsa.productId(), 0, universal);

        Map<String, JsonNode> previews = new java.util.LinkedHashMap<>();
        Map<String, JsonNode> lives = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, UUID> channel :
                Map.of("STOREFRONT", w.storefront, "UZUM", w.uzum).entrySet()) {
            previews.put(channel.getKey(), previewAll(channel.getValue(), "locationId", w.l1.toString()));
            assertThat(publication
                            .publish(w.tenant, w.brand, w.catalogId, channel.getKey(), null)
                            .status())
                    .isEqualTo(PublicationStatus.PUBLISHED);
            lives.put(channel.getKey(), liveMenu(channel.getKey()));
        }

        for (String channel : previews.keySet()) {
            for (ProductRef product : List.of(w.lagman, w.plov, w.samsa)) {
                assertThat(imagesOf(at(lives, channel), product))
                        .as("%s: the images a customer is served for %s are the ones its preview drew"
                                .formatted(channel, product.productId()))
                        .isEqualTo(imagesOf(at(previews, channel), product));
            }
        }

        // Pinned, so the loop above is not satisfied by two lists that are wrong in the same way.
        assertThat(imagesOf(at(lives, "STOREFRONT"), w.lagman))
                .as("the storefront does not wear Uzum's crop")
                .containsExactly(universal.toString());
        assertThat(imagesOf(at(lives, "STOREFRONT"), w.plov))
                .as("a dish whose only picture is Uzum's has none on the storefront")
                .isEmpty();
        assertThat(imagesOf(at(lives, "STOREFRONT"), w.samsa)).containsExactly(universal.toString());
        assertThat(imagesOf(at(lives, "UZUM"), w.lagman))
                .as("Uzum is served its own crop instead of the dish's picture")
                .containsExactly(uzumCrop.toString());
        assertThat(imagesOf(at(lives, "UZUM"), w.plov)).containsExactly(onlyUzum.toString());
        assertThat(imagesOf(at(lives, "UZUM"), w.samsa)).containsExactly(universal.toString());
    }

    @Test
    @DisplayName(
            "the draft is compared with each channel's own live hash, because an image that belongs to a channel is published to that channel alone")
    void draftIsComparedWithTheChannelsOwnHash() throws Exception {
        w.offerEverythingAt(w.l1);
        w.attachDefaultImage(w.lagman.productId(), 0, w.asset());
        w.attachChannelRelation(w.lagman.productId(), 1, w.asset(), "UZUM");

        String storefrontHash = publication
                .publish(w.tenant, w.brand, w.catalogId, "STOREFRONT", null)
                .contentHash();
        String uzumHash = publication
                .publish(w.tenant, w.brand, w.catalogId, "UZUM", null)
                .contentHash();
        assertThat(uzumHash)
                .as("the fixture is not vacuous: the two channels publish different menus")
                .isNotEqualTo(storefrontHash);

        CatalogPublicationService.DraftPreview draft = publication.previewDraft(w.tenant, w.brand, w.catalogId);
        assertThat(draft.contentHashFor("STOREFRONT"))
                .as("nothing was edited since: each channel's card says the draft matches what is live")
                .isEqualTo(storefrontHash);
        assertThat(draft.contentHashFor("UZUM")).isEqualTo(uzumHash);

        JsonNode wire = json(mvc.perform(
                get(base() + "/catalogs/" + w.catalogId + "/draft-preview").with(owner())));
        assertThat(wire.path("channelContentHashes").path("UZUM").asText()).isEqualTo(uzumHash);
        assertThat(wire.path("channelContentHashes").path("STOREFRONT").asText())
                .isEqualTo(storefrontHash);
        assertThat(wire.path("contentHash").asText())
                .as("the channel-agnostic draft is still there for a channel with no live menu")
                .isNotBlank();
    }

    @Test
    @DisplayName(
            "a channel's image override is published with that channel and with no other, so a customer is served the picture its preview drew")
    void anOverrideIsPublishedToItsChannel() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID universal = w.asset();
        UUID override = w.asset();
        w.attachDefaultImage(w.lagman.productId(), 0, universal);
        w.attachDefaultImage(w.plov.productId(), 0, universal);

        MvcResult written = mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), """
                        {"images":[{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":0}]}
                        """.formatted(override)))
                .andReturn();
        assertThat(written.getResponse().getStatus()).as(body(written)).isEqualTo(200);

        JsonNode uzumPreview = previewAll(w.uzum, "locationId", w.l1.toString());
        assertThat(imagesOf(uzumPreview, w.lagman)).containsExactly(override.toString());
        for (String channel : List.of("UZUM", "STOREFRONT")) {
            assertThat(publication
                            .publish(w.tenant, w.brand, w.catalogId, channel, null)
                            .status())
                    .isEqualTo(PublicationStatus.PUBLISHED);
        }

        JsonNode uzumLive = liveMenu("UZUM");
        JsonNode storefrontLive = liveMenu("STOREFRONT");
        assertThat(imagesOf(uzumLive, w.lagman))
                .as("Uzum's menu serves the photo the operator set for Uzum")
                .containsExactly(override.toString())
                .isEqualTo(imagesOf(uzumPreview, w.lagman));
        assertThat(productOf(uzumLive, w.lagman).path("imageUrls").get(0).asText())
                .as("and the URL a customer fetches is built from that asset")
                .endsWith("/media/" + override);
        assertThat(imagesOf(uzumLive, w.plov))
                .as("a dish with no override keeps its own picture")
                .containsExactly(universal.toString());
        assertThat(imagesOf(storefrontLive, w.lagman))
                .as("an override names its channel: the storefront still serves the dish's own picture")
                .containsExactly(universal.toString());

        // Removing the override and publishing again puts the dish's own picture back.
        mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), "{\"images\":[]}"))
                .andReturn();
        publication.publish(w.tenant, w.brand, w.catalogId, "UZUM", null);
        assertThat(imagesOf(liveMenu("UZUM"), w.lagman)).containsExactly(universal.toString());
    }

    @Test
    @DisplayName(
            "publishing refuses a channel whose image was withdrawn after it was chosen, rather than serving a broken picture")
    void publishRefusesAWithdrawnChannelImage() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID pending = w.asset();
        jdbc.sql("UPDATE media.assets SET status = 'UPLOADED' WHERE asset_id = :id")
                .param("id", pending)
                .update();
        // Written past the service (which would refuse it) to model an asset withdrawn after it was attached.
        jdbc.sql("""
                INSERT INTO catalog.channel_media_overrides (
                    tenant_id, brand_id, channel_id, entity_type, entity_id, role, media_asset_id, sort_order)
                VALUES (:t, :b, :c, 'PRODUCT', :p, 'PRIMARY', :asset, 0)
                """)
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("c", w.uzum)
                .param("p", w.lagman.productId())
                .param("asset", pending)
                .update();

        CatalogPublicationService.PublicationResult uzum =
                publication.publish(w.tenant, w.brand, w.catalogId, "UZUM", null);

        assertThat(uzum.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(uzum.report().blockers())
                .as("the refusal names the item whose channel image is gone")
                .anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo(ChannelFindings.CHANNEL_MEDIA_NOT_AVAILABLE);
                    assertThat(finding.entityId()).isEqualTo(w.lagman.productId());
                });
        assertThat(publication
                        .publish(w.tenant, w.brand, w.catalogId, "STOREFRONT", null)
                        .status())
                .as("the override names Uzum alone: the storefront is not held up by it")
                .isEqualTo(PublicationStatus.PUBLISHED);

        assertThat(previewAll(w.uzum, "locationId", w.l1.toString())
                        .path("publishable")
                        .asBoolean())
                .as("the preview says what publish would decide")
                .isFalse();
        assertThat(previewAll(w.storefront, "locationId", w.l1.toString())
                        .path("publishable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName(
            "a modifier option or a combo component the brand prices but the channel's price plane does not is a blocker, as a variant is")
    void optionsAndComponentsMissingFromThePlaneAreBlockers() throws Exception {
        w.offerEverythingAt(w.l1);
        ProductRef lunch = w.product("LUNCH", "Lunch", w.mainsCategory);
        ProductRef burger = w.product("BURGER", "Burger", w.mainsCategory);
        ProductRef cola = w.product("COLA", "Cola", w.mainsCategory);
        for (ProductRef product : List.of(lunch, burger, cola)) {
            w.offer(product, OfferingStatus.AVAILABLE);
        }
        ComboGroup choice = composites.createComboGroup(
                new CompositeProductAuthoringService.NewComboGroup(
                        w.tenant, w.brand, lunch.defaultVariantId(), "MAIN", "Choose", LOCALE, 1, 1, false, 0),
                "tester");
        ComboComponent burgerPairing =
                composites.addComponent(w.tenant, w.brand, choice.id(), burger.defaultVariantId(), 1, 0, "tester");
        ComboComponent colaPairing =
                composites.addComponent(w.tenant, w.brand, choice.id(), cola.defaultVariantId(), 1, 1, "tester");
        // An option nobody has priced anywhere: a modifier not priced yet, which is not this channel's gap.
        UUID unpriced = authoring.addModifierOption(
                w.tenant,
                w.brand,
                w.extras,
                "SAUCE",
                "Sous",
                LOCALE,
                null,
                1,
                2,
                FiscalClassification.unclassified(),
                null);

        // The brand book prices every dish, the paid option and both pairings...
        w.addPrice(w.brandBook, "VARIANT", burger.defaultVariantId(), 20_000L);
        w.addPrice(w.brandBook, "VARIANT", cola.defaultVariantId(), 6_000L);
        w.addPrice(w.brandBook, "MODIFIER_OPTION", w.onion, 2_000L);
        w.addPrice(w.brandBook, "COMBO_COMPONENT", burgerPairing.id(), 18_000L);
        w.addPrice(w.brandBook, "COMBO_COMPONENT", colaPairing.id(), 5_000L);
        // ...and Uzum's own book prices every dish and the burger pairing, but not the cola pairing
        // and not the onion: a CHANNEL book answers whole, so those two are priced elsewhere only.
        UUID uzumBook = w.priceBook(
                "UZUM_BOOK",
                "CHANNEL",
                w.uzum,
                Map.of(
                        w.lagman, 31_000L, w.plov, 26_000L, w.samsa, 8_500L, w.tea, 5_500L, burger, 21_000L, cola,
                        6_500L));
        w.addPrice(uzumBook, "COMBO_COMPONENT", burgerPairing.id(), 19_000L);

        JsonNode uzum = previewAll(w.uzum, "locationId", w.l1.toString());

        assertThat(findings(uzum, ChannelFindings.CHANNEL_PRICE_MISSING))
                .as("exactly the two gaps: the paid option and the cola pairing, each named by what it is")
                .extracting(finding -> finding.path("entityType").asText() + ":"
                        + finding.path("entityId").asText())
                .containsExactlyInAnyOrder("MODIFIER_OPTION:" + w.onion, "COMBO_COMPONENT:" + colaPairing.id())
                .doesNotContain("MODIFIER_OPTION:" + unpriced, "COMBO_COMPONENT:" + burgerPairing.id());
        assertThat(findings(uzum, ChannelFindings.CHANNEL_PRICE_MISSING).stream()
                        .filter(finding -> finding.path("entityType").asText().equals("COMBO_COMPONENT"))
                        .map(finding -> finding.path("productId").asText()))
                .as(
                        "a component is authored on the product whose variant is the combo's container, so that is where the finding links")
                .containsExactly(lunch.productId().toString());
        assertThat(uzum.path("channelReady").asBoolean())
                .as("a null price would reach the aggregator: the channel is not ready")
                .isFalse();

        JsonNode storefront = previewAll(w.storefront, "locationId", w.l1.toString());
        assertThat(findings(storefront, ChannelFindings.CHANNEL_PRICE_MISSING))
                .as("the storefront resolves the brand book, which prices both")
                .isEmpty();
        assertThat(storefront.path("channelReady").asBoolean()).isTrue();
    }

    // -------------------------------------------------------------- findings

    @Test
    @DisplayName(
            "the preview raises the universal blockers, the projection facts and the binding's own bookkeeping, each naming its entity")
    void findingsAreEmittedAndNamed() throws Exception {
        w.offerEverythingAt(w.l1);
        // Universal: a product with no active variant is a blocker that would stop the real publish.
        UUID hollow = authoring
                .createProduct(
                        w.tenant,
                        w.brand,
                        w.catalogId,
                        "HOLLOW",
                        "Bo'sh",
                        null,
                        LOCALE,
                        "SKU-HOLLOW",
                        "PIECE",
                        FiscalClassification.unclassified(),
                        null)
                .productId();
        jdbc.sql("UPDATE catalog.variants SET status = 'ARCHIVED', is_default = false WHERE product_id = :p")
                .param("p", hollow)
                .update();
        UUID binding = w.marketplaceBinding(w.uzum, null);

        JsonNode preview = preview(w.uzum, "bindingId", binding.toString());

        assertThat(preview.path("publishable").asBoolean()).isFalse();
        assertThat(preview.path("channelReady").asBoolean()).isFalse();
        JsonNode noVariant = findings(preview, "PRODUCT_HAS_NO_ACTIVE_VARIANT").getFirst();
        assertThat(noVariant.path("severity").asText()).isEqualTo("BLOCKER");
        assertThat(noVariant.path("source").asText()).isEqualTo("CATALOG");
        assertThat(noVariant.path("entityId").asText()).isEqualTo(hollow.toString());

        // The preview's universal findings ARE the validation endpoint's: one rule set, two doors.
        JsonNode validation = json(mvc.perform(
                get(base() + "/catalogs/" + w.catalogId + "/validation").with(owner())));
        assertThat(validation.path("publishable").asBoolean())
                .isEqualTo(preview.path("publishable").asBoolean());
        Set<String> viaValidation = new LinkedHashSet<>();
        validation
                .path("findings")
                .forEach(f -> viaValidation.add(
                        f.path("code").asText() + "|" + f.path("entityId").asText()));
        Set<String> viaPreview = new LinkedHashSet<>();
        preview.path("findings").forEach(f -> {
            if (f.path("source").asText().equals("CATALOG")) {
                viaPreview.add(
                        f.path("code").asText() + "|" + f.path("entityId").asText());
            }
        });
        assertThat(viaPreview)
                .as("same findings, same entities")
                .isEqualTo(viaValidation)
                .isNotEmpty();

        // The binding names no ruleset: that is itself a finding, because nothing marketplace-specific ran.
        JsonNode notAssigned = findings(preview, ChannelFindings.MARKETPLACE_RULESET_NOT_ASSIGNED)
                .getFirst();
        assertThat(notAssigned.path("severity").asText()).isEqualTo("WARNING");
        assertThat(notAssigned.path("source").asText()).isEqualTo("MARKETPLACE");
        assertThat(preview.path("binding").path("bindingId").asText()).isEqualTo(binding.toString());

        // ...and none of the four reserved partner codes can fire for a binding with no ruleset.
        for (String reserved : ChannelFindings.RESERVED_MARKETPLACE_CODES) {
            assertThat(findings(preview, reserved)).as(reserved).isEmpty();
        }

        // A code this build does not carry is reported, not silently passed.
        jdbc.sql("UPDATE integration.bindings SET marketplace_ruleset_code = 'NO_SUCH_RULESET' WHERE id = :id")
                .param("id", binding)
                .update();
        JsonNode unknown = preview(w.uzum, "bindingId", binding.toString());
        assertThat(findings(unknown, ChannelFindings.MARKETPLACE_RULESET_UNKNOWN))
                .hasSize(1);
        assertThat(findings(unknown, ChannelFindings.MARKETPLACE_RULESET_NOT_ASSIGNED))
                .isEmpty();

        // A branch the channel does not sell at receives nothing, and says so.
        UUID l3 = w.location("L3");
        JsonNode elsewhere = preview(w.uzum, "locationId", l3.toString());
        assertThat(findings(elsewhere, ChannelFindings.CHANNEL_NOT_ENABLED_AT_LOCATION))
                .hasSize(1);
        assertThat(elsewhere.path("items")).isEmpty();
        assertThat(elsewhere.path("channelReady").asBoolean()).isFalse();

        // An archived channel is refused publication: a blocker.
        jdbc.sql("UPDATE tenant.sales_channels SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", w.uzum)
                .update();
        assertThat(findings(preview(w.uzum, "bindingId", binding.toString()), ChannelFindings.CHANNEL_ARCHIVED))
                .hasSize(1);
    }

    @Test
    @DisplayName("a variant-scoped finding carries the product that owns it, so a console can deep-link to the editor")
    void variantFindingsNameTheirProduct() throws Exception {
        w.offerEverythingAt(w.l1);
        // lagman's variant loses its only price anywhere: VARIANT_HAS_NO_ACTIVE_PRICE, scoped to the variant.
        jdbc.sql("DELETE FROM pricing.prices WHERE priceable_id = :v")
                .param("v", w.lagman.defaultVariantId())
                .update();

        JsonNode preview = preview(w.storefront, "locationId", w.l1.toString());

        JsonNode unpriced = findings(preview, "VARIANT_HAS_NO_ACTIVE_PRICE").getFirst();
        assertThat(unpriced.path("entityType").asText()).isEqualTo("VARIANT");
        assertThat(unpriced.path("entityId").asText())
                .isEqualTo(w.lagman.defaultVariantId().toString());
        assertThat(unpriced.path("productId").asText())
                .isEqualTo(w.lagman.productId().toString());
    }

    @Test
    @DisplayName(
            "a channel image that is not a verified asset is a blocker on the preview, not a broken picture on the aggregator")
    void unverifiedChannelImageIsABlocker() throws Exception {
        w.offerEverythingAt(w.l1);
        UUID pending = w.asset();
        jdbc.sql("UPDATE media.assets SET status = 'UPLOADED' WHERE asset_id = :id")
                .param("id", pending)
                .update();
        // Written past the service (which would refuse it) to model an asset withdrawn after it was attached.
        jdbc.sql("""
                INSERT INTO catalog.channel_media_overrides (
                    tenant_id, brand_id, channel_id, entity_type, entity_id, role, media_asset_id, sort_order)
                VALUES (:t, :b, :c, 'PRODUCT', :p, 'PRIMARY', :asset, 0)
                """)
                .param("t", w.tenant)
                .param("b", w.brand)
                .param("c", w.uzum)
                .param("p", w.lagman.productId())
                .param("asset", pending)
                .update();

        JsonNode preview = previewAll(w.uzum, "locationId", w.l1.toString());

        JsonNode finding =
                findings(preview, ChannelFindings.CHANNEL_MEDIA_NOT_AVAILABLE).getFirst();
        assertThat(finding.path("entityId").asText())
                .isEqualTo(w.lagman.productId().toString());
        assertThat(finding.path("source").asText()).isEqualTo("PROJECTION");
        assertThat(preview.path("channelReady").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------- plug point

    @Test
    @DisplayName(
            "production registers no marketplace ruleset; a ruleset a binding names raises its findings through the same envelope")
    void rulesetPlugPoint() throws Exception {
        assertThat(rulesets.knownCodes())
                .as("the only code is this class's own test ruleset: the mechanism ships with no partner rules")
                .containsExactly(TEST_RULESET);

        w.offerEverythingAt(w.l1);
        // plov and lagman have no image; samsa has one.
        w.attachDefaultImage(w.samsa.productId(), 0, w.asset());
        UUID binding = w.marketplaceBinding(w.uzum, TEST_RULESET);

        JsonNode preview = preview(w.uzum, "bindingId", binding.toString());

        List<JsonNode> unmet = findings(preview, ChannelFindings.MARKETPLACE_IMAGE_REQUIREMENT_UNMET);
        assertThat(unmet)
                .extracting(f -> f.path("entityId").asText())
                .contains(w.lagman.productId().toString(), w.plov.productId().toString())
                .doesNotContain(w.samsa.productId().toString());
        assertThat(unmet).allSatisfy(f -> {
            assertThat(f.path("source").asText()).isEqualTo("MARKETPLACE");
            assertThat(f.path("severity").asText()).isEqualTo("BLOCKER");
        });
        assertThat(findings(preview, ChannelFindings.MARKETPLACE_RULESET_NOT_ASSIGNED))
                .as("a binding that names a ruleset is not 'unassigned'")
                .isEmpty();
        assertThat(preview.path("publishable").asBoolean())
                .as("the universal verdict is untouched by a partner rule")
                .isTrue();
        assertThat(preview.path("channelReady").asBoolean())
                .as("but the channel is not ready: the marketplace would refuse")
                .isFalse();

        // Fix the findings and the same ruleset goes quiet.
        w.attachDefaultImage(w.lagman.productId(), 0, w.asset());
        w.attachDefaultImage(w.plov.productId(), 0, w.asset());
        w.attachDefaultImage(w.tea.productId(), 0, w.asset());
        assertThat(findings(
                        preview(w.uzum, "bindingId", binding.toString()),
                        ChannelFindings.MARKETPLACE_IMAGE_REQUIREMENT_UNMET))
                .isEmpty();
    }

    // ------------------------------------------------------------ pagination

    @Test
    @DisplayName(
            "products are cursor-paginated: pages are disjoint, cover the menu, and a cursor is pinned to its question")
    void productsPaginate() throws Exception {
        w.offerEverythingAt(w.l1);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request =
                    previewRequest(w.storefront, "locationId", w.l1.toString()).queryParam("limit", "2");
            if (cursor != null) {
                request.queryParam("cursor", cursor);
            }
            JsonNode page = json(mvc.perform(request));
            for (JsonNode item : page.path("items")) {
                seen.add(item.path("productId").asText());
            }
            assertThat(page.path("items").size()).isLessThanOrEqualTo(2);
            if (cursor == null) {
                assertThat(page.path("findings"))
                        .as("whole-menu parts ride the first page")
                        .isNotNull();
                assertThat(page.path("categories")).isNotEmpty();
            } else {
                assertThat(page.path("categories")).as("and only the first").isEmpty();
            }
            cursor = page.path("nextCursor").isNull() || !page.has("nextCursor")
                    ? null
                    : page.path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen)
                .as("no product twice, none missing")
                .doesNotHaveDuplicates()
                .hasSize(4);
        assertThat(pages).isEqualTo(2);

        // A cursor minted for the storefront is not a window onto Uzum's menu.
        JsonNode firstPage = json(mvc.perform(
                previewRequest(w.storefront, "locationId", w.l1.toString()).queryParam("limit", "2")));
        MvcResult mismatched = mvc.perform(previewRequest(w.uzum, "locationId", w.l1.toString())
                        .queryParam("cursor", firstPage.path("nextCursor").asText()))
                .andReturn();
        assertThat(mismatched.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(mismatched)).contains("INVALID_REQUEST");
    }

    @Test
    @DisplayName("a channel that sells at several branches needs one named; at exactly one it is taken")
    void branchIsNamedOrUnambiguous() throws Exception {
        w.offerEverythingAt(w.l1);
        // Uzum sells at l1 only (World): no branch named is fine.
        assertThat(mvc.perform(previewRequest(w.uzum)).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        w.bindChannelToLocation(w.uzum, w.l2);
        MvcResult ambiguous = mvc.perform(previewRequest(w.uzum)).andReturn();
        assertThat(ambiguous.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(ambiguous)).contains("locationId or bindingId");

        UUID binding = w.marketplaceBinding(w.uzum, TEST_RULESET);
        JsonNode listed = json(mvc.perform(
                get(base() + "/channels/" + w.uzum + "/preview-targets").with(owner())));
        assertThat(listed)
                .extracting(n -> n.path("locationId").asText())
                .containsExactlyInAnyOrder(w.l1.toString(), w.l2.toString());
        // The marketplace binding is offered with the branch it covers, and only there.
        for (JsonNode target : listed) {
            if (target.path("locationId").asText().equals(w.l1.toString())) {
                assertThat(target.path("binding").path("bindingId").asText()).isEqualTo(binding.toString());
                assertThat(target.path("binding").path("rulesetCode").asText()).isEqualTo(TEST_RULESET);
                assertThat(target.path("binding").path("displayName").asText()).isEqualTo("Uzum Tezkor");
            } else {
                assertThat(target.path("binding").isNull() || !target.has("binding"))
                        .isTrue();
            }
        }

        // The binding names the branch itself: no locationId needed (the channel sells at two).
        JsonNode viaBinding = preview(w.uzum, "bindingId", binding.toString());
        assertThat(viaBinding.path("locationId").asText()).isEqualTo(w.l1.toString());
        assertThat(viaBinding.path("binding").path("bindingId").asText()).isEqualTo(binding.toString());
    }

    // ------------------------------------------------------ capability, tenant

    @Test
    @DisplayName("the preview needs catalog.read and the override write needs catalog.author")
    void capabilitiesAreDeclared() throws Exception {
        assertRefused(
                previewRequest(w.uzum, "locationId", w.l1.toString()).with(token("nobody")), Capability.CATALOG_READ);
        assertRefused(
                put(overridePath(w.uzum, "PRODUCT", w.lagman.productId()))
                        .with(token("nobody"))
                        .header(
                                IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER,
                                UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[]}"),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                get(base() + "/channels/" + w.uzum + "/preview-targets").with(token("nobody")),
                Capability.CATALOG_READ);
    }

    @Test
    @DisplayName(
            "another tenant's owner is refused here, and another tenant's channel, branch, binding and catalog are not found")
    void tenantIsolation() throws Exception {
        World other = new World();
        other.offerEverythingAt(other.l1);
        UUID otherBinding = other.marketplaceBinding(other.uzum, null);

        // Their owner on our path: refused by the capability check, which is scoped to their own tenant.
        MvcResult refused = mvc.perform(
                        previewRequest(w.uzum, "locationId", w.l1.toString()).with(other.owner()))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);

        // Our owner, naming their rows on our path: not found, never their data.
        w.offerEverythingAt(w.l1);
        assertThat(status(get(previewPath(other.uzum)).with(owner()).queryParam("locationId", w.l1.toString())))
                .as("their channel")
                .isEqualTo(404);
        assertThat(status(previewRequest(w.uzum, "locationId", other.l1.toString())))
                .as("their branch")
                .isEqualTo(404);
        assertThat(status(previewRequest(w.uzum, "bindingId", otherBinding.toString())))
                .as("their binding")
                .isEqualTo(404);
        assertThat(status(get(base() + "/catalogs/" + other.catalogId + "/channels/" + w.uzum + "/preview")
                        .with(owner())
                        .queryParam("locationId", w.l1.toString())))
                .as("their catalog")
                .isEqualTo(404);
        assertThat(status(get(base() + "/channels/" + other.uzum + "/preview-targets")
                        .with(owner())))
                .as("their channel's targets")
                .isEqualTo(404);
        assertThat(status(get(base() + "/channels/" + other.uzum + "/media-overrides")
                        .with(owner())))
                .as("their channel's image overrides")
                .isEqualTo(404);
    }

    // ------------------------------------------------------ the override write

    @Test
    @DisplayName(
            "the override write validates its set, refuses another tenant's image and entity, audits the change, and replays")
    void overrideWrite() throws Exception {
        UUID a = w.asset();
        UUID b = w.asset();

        String set = """
                {"images":[{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":0},
                           {"mediaAssetId":"%s","role":"GALLERY","sortOrder":1}]}
                """.formatted(a, b);
        MvcResult ok = mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), set))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(json(ok).path("images"))
                .extracting(n -> n.path("mediaAssetId").asText())
                .containsExactly(a.toString(), b.toString());

        JsonNode listed = json(mvc.perform(get(base() + "/channels/" + w.uzum + "/media-overrides")
                .with(owner())
                .queryParam("entityType", "PRODUCT")
                .queryParam("entityId", w.lagman.productId().toString())));
        assertThat(listed.path("images")).hasSize(2);

        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'catalog.channelMediaOverride.replaced' AND tenant_id = :t")
                        .param("t", w.tenant)
                        .query(Long.class)
                        .single())
                .as("a change writes one audit fact; the same set again writes none")
                .isEqualTo(1L);
        assertThat(jdbc.sql(
                                "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'catalog.channelMediaOverride.replaced' AND tenant_id = :t")
                        .param("t", w.tenant)
                        .query(String.class)
                        .single())
                .as("the fact says what changed: both images, and which channel")
                .contains(a.toString())
                .contains(b.toString())
                .contains(w.uzum.toString());
        mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), set)).andReturn();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'catalog.channelMediaOverride.replaced' AND tenant_id = :t")
                        .param("t", w.tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);

        // Two primaries, a duplicate, an unknown role: all refused as validation, nothing stored.
        assertThat(overrideStatus("""
                {"images":[{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":0},{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":1}]}
                """.formatted(a, b))).as("two primaries").isEqualTo(400);
        assertThat(overrideStatus("""
                {"images":[{"mediaAssetId":"%s","role":"GALLERY","sortOrder":0},{"mediaAssetId":"%s","role":"GALLERY","sortOrder":1}]}
                """.formatted(a, a)))
                .as("the same asset twice")
                .isEqualTo(400);
        assertThat(overrideStatus("""
                {"images":[{"mediaAssetId":"%s","role":"BANNER","sortOrder":0}]}
                """.formatted(a))).as("an unknown role").isEqualTo(400);
        assertThat(json(mvc.perform(get(base() + "/channels/" + w.uzum + "/media-overrides")
                                .with(owner())))
                        .path("images"))
                .as("the refused writes stored nothing")
                .hasSize(2);

        // An image of another tenant, and an entity of another brand, are refused.
        World other = new World();
        UUID foreignAsset = other.asset();
        assertThat(overrideStatus("""
                {"images":[{"mediaAssetId":"%s","role":"PRIMARY","sortOrder":0}]}
                """.formatted(foreignAsset)))
                .as("another tenant's asset")
                .isEqualTo(400);
        assertThat(status(overridePut(w.uzum, "PRODUCT", other.lagman.productId(), "{\"images\":[]}")))
                .as("another tenant's product")
                .isEqualTo(404);
        assertThat(status(overridePut(other.uzum, "PRODUCT", w.lagman.productId(), "{\"images\":[]}")))
                .as("another tenant's channel")
                .isEqualTo(404);
        assertThat(status(overridePut(w.uzum, "MODIFIER_GROUP", w.lagman.productId(), "{\"images\":[]}")))
                .as("a modifier group cannot carry a channel image")
                .isEqualTo(400);

        // Idempotency-Key is required on the write.
        assertThat(status(put(overridePath(w.uzum, "PRODUCT", w.lagman.productId()))
                        .with(owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[]}")))
                .isEqualTo(400);

        // Removing them: the empty set.
        mvc.perform(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), "{\"images\":[]}"))
                .andReturn();
        assertThat(json(mvc.perform(get(base() + "/channels/" + w.uzum + "/media-overrides")
                                .with(owner())))
                        .path("images"))
                .isEmpty();
    }

    // ================================================================ plumbing

    private String base() {
        return "/api/v1/control-plane/tenants/%s/brands/%s/catalog".formatted(w.tenant, w.brand);
    }

    private String previewPath(UUID channelId) {
        return base() + "/catalogs/" + w.catalogId + "/channels/" + channelId + "/preview";
    }

    private MockHttpServletRequestBuilder previewRequest(UUID channelId, String... params) {
        MockHttpServletRequestBuilder request = get(previewPath(channelId)).with(owner());
        for (int i = 0; i < params.length; i += 2) {
            request.queryParam(params[i], params[i + 1]);
        }
        return request;
    }

    private JsonNode preview(UUID channelId, String... params) throws Exception {
        return json(mvc.perform(previewRequest(channelId, params)));
    }

    /** Every page of a preview, products concatenated, the first page's whole-menu parts kept. */
    private JsonNode previewAll(UUID channelId, String... params) throws Exception {
        JsonNode first = preview(channelId, params);
        ObjectNode merged = ((ObjectNode) first).deepCopy();
        ArrayNode items = JSON.createArrayNode();
        first.path("items").forEach(items::add);
        String cursor = first.has("nextCursor") && !first.path("nextCursor").isNull()
                ? first.path("nextCursor").asText()
                : null;
        while (cursor != null) {
            MockHttpServletRequestBuilder request =
                    previewRequest(channelId, params).queryParam("cursor", cursor);
            JsonNode page = json(mvc.perform(request));
            page.path("items").forEach(items::add);
            cursor = page.has("nextCursor") && !page.path("nextCursor").isNull()
                    ? page.path("nextCursor").asText()
                    : null;
        }
        merged.set("items", items);
        return merged;
    }

    private MockHttpServletRequestBuilder overridePut(UUID channelId, String entityType, UUID entityId, String body) {
        return put(overridePath(channelId, entityType, entityId))
                .with(owner())
                .header(
                        IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String overridePath(UUID channelId, String entityType, UUID entityId) {
        return base() + "/channels/" + channelId + "/media-overrides/" + entityType + "/" + entityId;
    }

    private int overrideStatus(String body) throws Exception {
        return status(overridePut(w.uzum, "PRODUCT", w.lagman.productId(), body));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return JSON.readTree(body(result));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(body(result));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(UTF_8);
    }

    private void assertRefused(MockHttpServletRequestBuilder request, Capability expected) throws Exception {
        MvcResult refused = mvc.perform(request).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused)).contains("INSUFFICIENT_CAPABILITY").contains(expected.code());
    }

    private RequestPostProcessor owner() {
        return w.owner();
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ---- shaping: compare a preview to the storefront field for field

    /**
     * Reduces a list of menu entries to the fields both responses carry, keyed and
     * ordered so two equal menus compare equal whatever order they were built in.
     * The preview's additions (where an image came from, per-variant images, a
     * category's images) are deliberately not in the shape: they are not on the
     * storefront, so they are not part of the claim.
     *
     * @param withImages whether the entries carry images the storefront also carries (products do;
     *     categories do not, and the preview adds some)
     */
    private static Map<String, JsonNode> shape(JsonNode entries, String key, boolean withImages) {
        Set<String> skipped = withImages
                ? Set.of("mediaSource", "commentPresets")
                : Set.of("mediaSource", "commentPresets", "mediaAssetIds", "imageUrls");
        Map<String, JsonNode> shaped = new java.util.TreeMap<>();
        for (JsonNode entry : entries) {
            ObjectNode node = JSON.createObjectNode();
            entry.properties().forEach(property -> {
                String name = property.getKey();
                if (skipped.contains(name)) {
                    return;
                }
                if (name.equals("variants")) {
                    List<JsonNode> variants = new ArrayList<>();
                    for (JsonNode variant : property.getValue()) {
                        ObjectNode v = JSON.createObjectNode();
                        for (String field : List.of(
                                "variantId",
                                "sku",
                                "unitCode",
                                "isDefault",
                                "orderable",
                                "onSaleNow",
                                "amountMinor",
                                "remainingQuantity")) {
                            v.set(field, variant.has(field) ? variant.get(field) : JSON.nullNode());
                        }
                        variants.add(v);
                    }
                    variants.sort(Comparator.comparing(v -> v.path("variantId").asText()));
                    ArrayNode sorted = JSON.createArrayNode();
                    variants.forEach(sorted::add);
                    node.set(name, sorted);
                } else {
                    node.set(name, property.getValue());
                }
            });
            shaped.put(entry.path(key).asText(), node);
        }
        return shaped;
    }

    private static <T> T at(Map<String, T> map, String key) {
        return java.util.Objects.requireNonNull(map.get(key), key);
    }

    /** The menu a customer is served on a channel at {@code l1} from its live publication. */
    private JsonNode liveMenu(String channelCode) throws Exception {
        return json(mvc.perform(
                get("/api/v1/storefront/tenants/%s/brands/%s/locations/%s/menu".formatted(w.tenant, w.brand, w.l1))
                        .queryParam("channel", channelCode)
                        .queryParam("locale", LOCALE)));
    }

    /** A product's images in the order the menu lists them. */
    private static List<String> imagesOf(JsonNode menu, ProductRef product) {
        List<String> images = new ArrayList<>();
        productOf(menu, product).path("mediaAssetIds").forEach(id -> images.add(id.asText()));
        return images;
    }

    private static Set<String> ids(JsonNode entries, String key) {
        Set<String> ids = new LinkedHashSet<>();
        entries.forEach(entry -> ids.add(entry.path(key).asText()));
        return ids;
    }

    private static JsonNode productOf(JsonNode menu, ProductRef product) {
        String field = menu.has("items") ? "items" : "products";
        for (JsonNode candidate : menu.path(field)) {
            if (candidate.path("productId").asText().equals(product.productId().toString())) {
                return candidate;
            }
        }
        throw new AssertionError("product %s is not in this menu".formatted(product.productId()));
    }

    private static JsonNode variantOf(JsonNode menu, ProductRef product) {
        for (JsonNode variant : productOf(menu, product).path("variants")) {
            if (variant.path("variantId")
                    .asText()
                    .equals(product.defaultVariantId().toString())) {
                return variant;
            }
        }
        throw new AssertionError("variant of %s is not in this menu".formatted(product.productId()));
    }

    private static List<JsonNode> findings(JsonNode preview, String code) {
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode finding : preview.path("findings")) {
            if (finding.path("code").asText().equals(code)) {
                matching.add(finding);
            }
        }
        return matching;
    }

    // ================================================================== world

    private record ProductRef(UUID productId, UUID defaultVariantId) {}

    /**
     * One tenant's worth of fixture, minted fresh per instance so a test never
     * collides with another's rows (and a second instance is "another tenant").
     */
    private final class World {
        final UUID tenant = UUID.randomUUID();
        final UUID brand = UUID.randomUUID();
        final UUID l1;
        final UUID l2;
        final UUID storefront;
        final UUID uzum;
        final UUID catalogId;
        final UUID mainsCategory;
        final UUID brandBook;
        final UUID extras;
        final UUID onion;
        final ProductRef lagman;
        final ProductRef plov;
        final ProductRef samsa;
        final ProductRef tea;
        final UUID ownerGrantSubject = UUID.randomUUID();
        private final Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        private int assetSequence;

        World() {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant).param("slug", "w3-" + tenant).update();
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                    """).param("id", brand).param("tenantId", tenant).update();
            l1 = location("L1");
            l2 = location("L2");
            storefront = channel("STOREFRONT", "WEB", false, null, null);
            uzum = channel("UZUM", "AGGREGATOR", false, null, null);
            bindChannelToLocation(storefront, l1);
            bindChannelToLocation(uzum, l1);

            catalogId = authoring.createCatalog(tenant, brand, "MAIN", "Main menu", LOCALE);
            UUID mains = authoring.createCategory(tenant, brand, catalogId, null, "MAINS", "Asosiy", LOCALE, 1);
            mainsCategory = mains;
            UUID drinks = authoring.createCategory(tenant, brand, catalogId, null, "DRINKS", "Ichimlik", LOCALE, 2);
            lagman = product("LAGMAN", "Lagman", mains);
            plov = product("PLOV", "Osh", mains);
            samsa = product("SAMSA", "Somsa", mains);
            tea = product("TEA", "Choy", drinks);

            extras = authoring.createModifierGroup(tenant, brand, "EXTRAS", "Qo'shimcha", LOCALE, false, 0, 2, true);
            onion = authoring.addModifierOption(
                    tenant,
                    brand,
                    extras,
                    "ONION",
                    "Piyoz",
                    LOCALE,
                    null,
                    2,
                    1,
                    FiscalClassification.unclassified(),
                    null);
            authoring.attachModifierGroup(tenant, brand, lagman.productId(), extras, 1);

            // The BRAND book prices every variant; narrower books are added per test.
            Map<ProductRef, Long> brandPrices = new java.util.LinkedHashMap<>();
            brandPrices.put(lagman, 30_000L);
            brandPrices.put(plov, 25_000L);
            brandPrices.put(samsa, 8_000L);
            brandPrices.put(tea, 5_000L);
            brandBook = priceBook("BRAND_BOOK", "BRAND", null, brandPrices);

            jdbc.sql("""
                    INSERT INTO iam.grants
                        (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                         status, granted_by, reason, valid_from)
                    VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                            'ACTIVE', 'test-fixture', 'channel preview test', :validFrom)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenant)
                    .param("subject", ownerGrantSubject.toString())
                    .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_OWNER))
                    .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                    .update();
        }

        RequestPostProcessor owner() {
            return token(ownerGrantSubject.toString());
        }

        UUID location(String code) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", id)
                    .param("tenantId", tenant)
                    .param("brandId", brand)
                    .param("code", code)
                    .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                    .update();
            return id;
        }

        UUID channel(
                String code,
                String systemType,
                boolean externallyPriced,
                @Nullable UUID pricePlane,
                @Nullable UUID installation) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO tenant.sales_channels (
                        id, tenant_id, code, system_type, display_name, status,
                        externally_priced, price_plane_channel_id, provider_installation_id)
                    VALUES (:id, :tenantId, :code, :type, :code, 'ACTIVE', :external, :plane, :installation)
                    """)
                    .param("id", id)
                    .param("tenantId", tenant)
                    .param("code", code)
                    .param("type", systemType)
                    .param("external", externallyPriced)
                    .param("plane", pricePlane)
                    .param("installation", installation)
                    .update();
            return id;
        }

        void bindChannelToLocation(UUID channelId, UUID locationId) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                    VALUES (:t, :c, :l, 'ACTIVE') ON CONFLICT DO NOTHING
                    """)
                    .param("t", tenant)
                    .param("c", channelId)
                    .param("l", locationId)
                    .update();
        }

        ProductRef product(String code, String name, UUID category) {
            CatalogAuthoringService.ProductCreated created = authoring.createProduct(
                    tenant,
                    brand,
                    catalogId,
                    code,
                    name,
                    null,
                    LOCALE,
                    "SKU-" + code,
                    "PIECE",
                    FiscalClassification.unclassified(),
                    null);
            authoring.placeProductInCategory(tenant, brand, category, created.productId(), 1);
            return new ProductRef(created.productId(), created.defaultVariantId());
        }

        void offer(ProductRef product, OfferingStatus status) {
            authoring.setOffering(tenant, brand, l1, product.defaultVariantId(), status, List.of("DELIVERY", "PICKUP"));
        }

        void offerEverythingAt(UUID locationId) {
            for (ProductRef product : List.of(lagman, plov, samsa, tea)) {
                authoring.setOffering(
                        tenant,
                        brand,
                        locationId,
                        product.defaultVariantId(),
                        OfferingStatus.AVAILABLE,
                        List.of("DELIVERY", "PICKUP"));
            }
        }

        /** A price book assigned at a scope and holding the given prices; scope id null for BRAND. */
        UUID priceBook(String name, String scopeType, @Nullable UUID scopeId, Map<ProductRef, Long> prices) {
            UUID book = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                    VALUES (:id, :t, :b, :name, 'UZS', 'ACTIVE', :from, 0)
                    """)
                    .param("id", book)
                    .param("t", tenant)
                    .param("b", brand)
                    .param("name", name)
                    .param("from", yesterday.atOffset(ZoneOffset.UTC))
                    .update();
            jdbc.sql("""
                    INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                        scope_type, scope_id, valid_from, priority)
                    VALUES (:id, :t, :b, :book, :scopeType, :scopeId, :from, 0)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", tenant)
                    .param("b", brand)
                    .param("book", book)
                    .param("scopeType", scopeType)
                    .param("scopeId", scopeId)
                    .param("from", yesterday.atOffset(ZoneOffset.UTC))
                    .update();
            prices.forEach((product, amount) -> jdbc.sql("""
                            INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id,
                                priceable_type, priceable_id, amount_minor, valid_from)
                            VALUES (:id, :t, :b, :book, 'VARIANT', :variant, :amount, :from)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("t", tenant)
                    .param("b", brand)
                    .param("book", book)
                    .param("variant", product.defaultVariantId())
                    .param("amount", amount)
                    .param("from", yesterday.atOffset(ZoneOffset.UTC))
                    .update());
            return book;
        }

        /** One more price row in a book that already exists: a modifier option's or a combo component's. */
        void addPrice(UUID book, String priceableType, UUID priceableId, long amount) {
            jdbc.sql("""
                    INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id,
                        priceable_type, priceable_id, amount_minor, valid_from)
                    VALUES (:id, :t, :b, :book, :type, :priceable, :amount, :from)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", tenant)
                    .param("b", brand)
                    .param("book", book)
                    .param("type", priceableType)
                    .param("priceable", priceableId)
                    .param("amount", amount)
                    .param("from", yesterday.atOffset(ZoneOffset.UTC))
                    .update();
        }

        /** A verified, public image of this tenant. */
        UUID asset() {
            UUID assetId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO media.assets (
                        asset_id, tenant_id, owner_scope, owner_id, object_key, bucket, status, visibility,
                        declared_content_type, declared_size_bytes,
                        verified_content_type, verified_size_bytes, verified_checksum_sha256)
                    VALUES (:assetId, :tenantId, 'BRAND', :brandId, :objectKey, 'catalog-media', 'AVAILABLE', 'PUBLIC',
                        'image/jpeg', 1024, 'image/jpeg', 1024, 'deadbeef')
                    """)
                    .param("assetId", assetId)
                    .param("tenantId", tenant)
                    .param("brandId", brand)
                    .param("objectKey", "tenants/" + tenant + "/media/" + assetId + "-" + (assetSequence++))
                    .update();
            return assetId;
        }

        void attachDefaultImage(UUID productId, int sortOrder, UUID assetId) {
            store.attachMedia(tenant, brand, EntityType.PRODUCT, productId, assetId, "PRIMARY", sortOrder);
        }

        void attachChannelRelation(UUID productId, int sortOrder, UUID assetId, String channelCode) {
            store.attachMedia(tenant, brand, EntityType.PRODUCT, productId, assetId, "PRIMARY", sortOrder, channelCode);
        }

        /**
         * A MARKETPLACE installation and one binding of it at {@code l1}, with the channel pointed at the
         * installation — the shape an aggregator channel has once it is onboarded.
         */
        UUID marketplaceBinding(UUID channelId, @Nullable String rulesetCode) {
            String env = "w3-preview-" + UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO integration.provider_environments (
                        code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                    VALUES (:env, 'MARKETPLACE', 'UZUM_TEZKOR', 'https://example.test', false, 'example.test')
                    """).param("env", env).update();
            UUID installation = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO integration.installations (
                        id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                    VALUES (:id, :t, 'MARKETPLACE', 'UZUM_TEZKOR', :env, 'Uzum Tezkor', 'ACTIVE')
                    """)
                    .param("id", installation)
                    .param("t", tenant)
                    .param("env", env)
                    .update();
            UUID binding = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO integration.bindings (
                        id, tenant_id, installation_id, brand_id, location_id, status, effective_from,
                        marketplace_ruleset_code)
                    VALUES (:id, :t, :installation, :b, :loc, 'ACTIVE', now(), :ruleset)
                    """)
                    .param("id", binding)
                    .param("t", tenant)
                    .param("installation", installation)
                    .param("b", brand)
                    .param("loc", l1)
                    .param("ruleset", rulesetCode)
                    .update();
            jdbc.sql("UPDATE tenant.sales_channels SET provider_installation_id = :i WHERE id = :c AND tenant_id = :t")
                    .param("i", installation)
                    .param("c", channelId)
                    .param("t", tenant)
                    .update();
            return binding;
        }
    }
}
