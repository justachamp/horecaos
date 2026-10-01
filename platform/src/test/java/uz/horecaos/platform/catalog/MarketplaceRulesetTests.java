package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.ChannelProjection;
import uz.horecaos.platform.catalog.application.ChannelProjection.PriceAuthority;
import uz.horecaos.platform.catalog.application.MarketplaceRuleset;
import uz.horecaos.platform.catalog.application.MarketplaceRulesets;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.ChannelFindings;
import uz.horecaos.platform.catalog.domain.ValidationFinding;

/**
 * ADR 0138's marketplace-ruleset plug point, without a Spring context: the closed
 * set starts empty, a ruleset registers by existing, and the validator lets
 * through only findings in the family the record reserves.
 *
 * <p>The HTTP-level counterpart is {@code ChannelPreviewEndpointTests}, which
 * registers a ruleset as a bean and proves a binding naming it raises its findings
 * through the preview envelope.
 */
class MarketplaceRulesetTests {

    private static final CatalogValidator VALIDATOR = new CatalogValidator();

    private static ChannelProjection emptyProjection() {
        return new ChannelProjection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "UZUM",
                UUID.randomUUID(),
                PriceAuthority.HORECAOS,
                "UZS",
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static MarketplaceRuleset ruleset(String code, ValidationFinding... findings) {
        return new MarketplaceRuleset() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public List<ValidationFinding> check(ChannelProjection projection) {
                return List.of(findings);
            }
        };
    }

    @Test
    @DisplayName("the registry starts empty: no code is known and none resolves")
    void theClosedSetIsEmptyUntilARulesetIsAuthored() {
        MarketplaceRulesets none = MarketplaceRulesets.none();

        assertThat(none.knownCodes()).isEmpty();
        assertThat(none.find("UZUM_TEZKOR")).isEmpty();
    }

    @Test
    @DisplayName("a ruleset registers under its own code, and two claiming one code are refused")
    void aRulesetRegistersByExisting() {
        MarketplaceRuleset uzum = ruleset("UZUM_V1");

        MarketplaceRulesets registry = new MarketplaceRulesets(List.of(uzum, ruleset("WOLT_V1")));

        assertThat(registry.knownCodes()).containsExactlyInAnyOrder("UZUM_V1", "WOLT_V1");
        assertThat(registry.find("UZUM_V1")).containsSame(uzum);
        assertThatThrownBy(() -> new MarketplaceRulesets(List.of(ruleset("UZUM_V1"), ruleset("UZUM_V1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UZUM_V1");
    }

    @Test
    @DisplayName("a binding with no ruleset yields no marketplace findings, so the four reserved codes cannot fire")
    void noRulesetMeansNoMarketplaceFindings() {
        assertThat(VALIDATOR.marketplaceFindings(null, emptyProjection())).isEmpty();
    }

    @Test
    @DisplayName("a ruleset's findings come through, in its own words")
    void aRulesetsFindingsComeThrough() {
        ValidationFinding unmet = ValidationFinding.blocker(
                ChannelFindings.MARKETPLACE_IMAGE_REQUIREMENT_UNMET, EntityType.PRODUCT, UUID.randomUUID(), "BURGER", "needs a picture");

        List<ValidationFinding> raised = VALIDATOR.marketplaceFindings(ruleset("UZUM_V1", unmet), emptyProjection());

        assertThat(raised).containsExactly(unmet);
    }

    @Test
    @DisplayName("a ruleset cannot impersonate a universal rule: a code outside MARKETPLACE_ is refused")
    void aRulesetStaysInItsOwnFamily() {
        ValidationFinding impostor = ValidationFinding.blocker(
                "PRODUCT_HAS_NO_ACTIVE_VARIANT", EntityType.PRODUCT, UUID.randomUUID(), "BURGER", "not mine to raise");

        assertThatThrownBy(() -> VALIDATOR.marketplaceFindings(ruleset("UZUM_V1", impostor), emptyProjection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UZUM_V1")
                .hasMessageContaining("PRODUCT_HAS_NO_ACTIVE_VARIANT");
    }

    @Test
    @DisplayName("the reserved family is exactly the four codes the record names")
    void theReservedFamilyIsTheRecordsFour() {
        assertThat(ChannelFindings.RESERVED_MARKETPLACE_CODES)
                .containsExactly(
                        "MARKETPLACE_IMAGE_REQUIREMENT_UNMET",
                        "MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET",
                        "MARKETPLACE_CATEGORY_DEPTH_EXCEEDED",
                        "MARKETPLACE_PRICE_PARITY_VIOLATION")
                .allSatisfy(code -> assertThat(code).startsWith(ChannelFindings.MARKETPLACE_PREFIX));
    }
}
