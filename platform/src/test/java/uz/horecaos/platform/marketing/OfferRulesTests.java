package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.marketing.api.OfferPublished;
import uz.horecaos.platform.marketing.application.OfferService;
import uz.horecaos.platform.marketing.application.OfferService.OfferDraft;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.OfferRow;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * An offer is a versioned reference to something pricing or loyalty owns, never a discount
 * of its own (ADR 0112): exactly one reference, in force at most one version at a time, and
 * a published version that is only ever superseded or retired.
 */
class OfferRulesTests {

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for offer tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        h = new ScenarioHarness(db);
    }

    // -------------------------------------------------------------- the reference

    @Test
    @DisplayName("an offer references exactly one promotion or accrual rule: neither and both are refused")
    void exactlyOneReference() {
        UUID promotion = h.seedPromotion("ACTIVE");
        UUID rule = h.seedAccrualRule("ACTIVE");

        assertInvalid(() -> create(draft(null, null)), "exactly one");
        assertInvalid(() -> create(draft(promotion, rule)), "exactly one");

        OfferRow byPromotion = create(draft(promotion, null));
        OfferRow byRule = create(draft(null, rule));
        assertThat(byPromotion.pricingPromotionId()).isEqualTo(promotion);
        assertThat(byPromotion.loyaltyAccrualRuleId()).isNull();
        assertThat(byRule.loyaltyAccrualRuleId()).isEqualTo(rule);
        assertThat(byRule.pricingPromotionId()).isNull();
        assertThat(byPromotion.status()).isEqualTo("DRAFT");
        assertThat(byPromotion.versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("the database holds the same line: a row with neither reference, or both, cannot be written")
    void theDatabaseRefusesAnOfferWithoutExactlyOneReference() {
        UUID promotion = h.seedPromotion("ACTIVE");
        UUID rule = h.seedAccrualRule("ACTIVE");

        for (String references : List.of("NULL, NULL", ":promotion, :rule")) {
            assertThatThrownBy(() -> h.jdbc.sql("""
                                    INSERT INTO marketing.offers (id, tenant_id, brand_id, lineage_id, version_number,
                                        display_name, pricing_promotion_id, loyalty_accrual_rule_id, valid_from,
                                        allowed_channels, template_key, created_by)
                                    VALUES (gen_random_uuid(), :tenant, :brand, gen_random_uuid(), 1, 'Direct',
                                    """ + references + """
                                    , now(), ARRAY['SMS'], 'T', gen_random_uuid())
                                    """)
                            .param("tenant", TENANT)
                            .param("brand", BRAND)
                            .param("promotion", promotion)
                            .param("rule", rule)
                            .update())
                    .as(references)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_offer_exactly_one_reference");
        }
    }

    @Test
    @DisplayName("the referenced promotion or rule must exist for this brand and be in use")
    void theReferenceMustBeUsable() {
        assertInvalid(() -> create(draft(UUID.randomUUID(), null)), "No promotion");
        assertInvalid(() -> create(draft(null, UUID.randomUUID())), "No accrual rule");
        assertInvalid(() -> create(draft(h.seedPromotion("ARCHIVED"), null)), "archived");
        assertInvalid(() -> create(draft(null, h.seedAccrualRule("RETIRED"))), "retired");
        // A sibling brand's promotion is not this brand's, even by its id.
        assertInvalid(() -> create(draft(h.seedPromotion(OTHER_BRAND, "ACTIVE"), null)), "No promotion");
    }

    @Test
    @DisplayName("a promotion archived between authoring and publication stops the publication")
    void aStaleReferenceIsRefusedAtPublication() {
        UUID promotion = h.seedPromotion("ACTIVE");
        OfferRow offer = create(draft(promotion, null));
        h.jdbc.sql("UPDATE pricing.promotions SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", promotion)
                .update();

        assertInvalid(() -> publish(offer), "archived");
        assertThat(h.offerService.require(TENANT, BRAND, offer.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("an offer has a name, a window that ends after it starts, channels from the closed set and a template")
    void offerShapeIsValidated() {
        UUID promotion = h.seedPromotion("ACTIVE");
        Instant from = START.minus(Duration.ofDays(1));
        assertInvalid(
                () -> create(new OfferDraft(" ", promotion, null, from, null, null, List.of("SMS"), "T", null, null)),
                "name");
        assertInvalid(
                () -> create(new OfferDraft(
                        "Ends first", promotion, null, from, from, null, List.of("SMS"), "T", null, null)),
                "end after it starts");
        assertInvalid(
                () -> create(
                        new OfferDraft("No channels", promotion, null, from, null, null, List.of(), "T", null, null)),
                "at least one channel");
        assertInvalid(
                () -> create(new OfferDraft(
                        "Pigeon", promotion, null, from, null, null, List.of("PIGEON"), "T", null, null)),
                "PIGEON");
        assertInvalid(
                () -> create(new OfferDraft(
                        "No template", promotion, null, from, null, null, List.of("SMS"), " ", null, null)),
                "template");
        assertInvalid(
                () -> create(new OfferDraft(
                        "Foreign audience",
                        promotion,
                        null,
                        from,
                        null,
                        UUID.randomUUID(),
                        List.of("SMS"),
                        "T",
                        null,
                        null)),
                "No audience");
    }

    // ----------------------------------------------------------------- versions

    @Test
    @DisplayName("publishing a new version supersedes the old one: one version of a lineage is in force at a time")
    void oneVersionIsInForceAtATime() {
        OfferRow first = publish(create(draft(h.seedPromotion("ACTIVE"), null)));
        assertThat(first.status()).isEqualTo("PUBLISHED");
        assertThat(first.publishedBy()).isEqualTo(UUID.fromString(h.approver.subject()));

        OfferRow second = h.offerService.newVersion(
                TENANT,
                BRAND,
                first.id(),
                draft(h.seedPromotion("ACTIVE"), null),
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        assertThat(second.lineageId()).isEqualTo(first.lineageId());
        assertThat(second.versionNumber()).isEqualTo(2);
        // The draft does not displace what is in force.
        assertThat(h.offerService.require(TENANT, BRAND, first.id()).status()).isEqualTo("PUBLISHED");

        publish(second);

        assertThat(h.offerService.require(TENANT, BRAND, first.id()).status()).isEqualTo("SUPERSEDED");
        assertThat(h.offerService.require(TENANT, BRAND, second.id()).status()).isEqualTo("PUBLISHED");
        assertThat(h.offerService.lineage(TENANT, BRAND, first.id()))
                .extracting(OfferRow::versionNumber)
                .containsExactlyInAnyOrder(1, 2);

        // And the database will not hold two in force, whoever asks.
        assertThatThrownBy(() -> h.jdbc.sql("UPDATE marketing.offers SET status = 'PUBLISHED' WHERE id = :id")
                        .param("id", first.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_offer_one_published_version");
    }

    @Test
    @DisplayName("a published version is never rewritten in place: only a draft is")
    void aPublishedVersionIsNotRewritten() {
        OfferRow draft = create(draft(h.seedPromotion("ACTIVE"), null));
        OfferRow rewritten = h.offerService.rewriteDraft(
                TENANT,
                BRAND,
                draft.id(),
                draft.rowVersion(),
                new OfferDraft(
                        "Renamed",
                        draft.pricingPromotionId(),
                        null,
                        draft.validFrom(),
                        draft.validUntil(),
                        null,
                        List.of("SMS", "IN_APP"),
                        "MARKETING_PROMOTION",
                        null,
                        null),
                h.author,
                "corr");
        assertThat(rewritten.displayName()).isEqualTo("Renamed");
        assertThat(rewritten.rowVersion()).isGreaterThan(draft.rowVersion());

        OfferRow published = publish(rewritten);
        assertThatThrownBy(() -> h.offerService.rewriteDraft(
                        TENANT,
                        BRAND,
                        published.id(),
                        published.rowVersion(),
                        draft(h.seedPromotion("ACTIVE"), null),
                        h.author,
                        "corr"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refused.getMessage()).contains("make a new version");
                });
        assertThat(h.offerService.require(TENANT, BRAND, published.id()).displayName())
                .isEqualTo("Renamed");
    }

    @Test
    @DisplayName("a stale row version is refused on publish and on retire, with the version it lost to")
    void staleVersionsAreRefused() {
        OfferRow draft = create(draft(h.seedPromotion("ACTIVE"), null));

        assertThatThrownBy(() -> h.offerService.publish(
                        TENANT,
                        BRAND,
                        draft.id(),
                        draft.rowVersion() + 5,
                        h.approver,
                        UUID.fromString(h.approver.subject()),
                        "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(h.offerService.require(TENANT, BRAND, draft.id()).status()).isEqualTo("DRAFT");

        OfferRow published = publish(draft);
        assertThatThrownBy(() -> h.offerService.retire(
                        TENANT, BRAND, published.id(), published.rowVersion() + 5, h.author, "Ended", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(h.offerService.require(TENANT, BRAND, draft.id()).status()).isEqualTo("PUBLISHED");

        OfferRow retired = h.offerService.retire(
                TENANT, BRAND, published.id(), published.rowVersion(), h.author, "The promotion ended early", "corr");
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> h.offerService.retire(
                        TENANT, BRAND, published.id(), retired.rowVersion(), h.author, "Again", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
        assertInvalid(() -> publish(retired), "Only a draft");
    }

    @Test
    @DisplayName("an SMS offer is not published without wording: the template must have an active version")
    void publicationNeedsWording() {
        OfferService withoutTemplates = new OfferService(
                h.offerStore,
                new uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionReferenceAdapter(h.jdbc),
                new uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcAccrualRuleReferenceAdapter(h.jdbc),
                h.audiences,
                new FakeCampaignMessagePort(),
                h.audit,
                h.events::add,
                h.clock);
        OfferRow draft = withoutTemplates.create(
                TENANT,
                BRAND,
                draft(h.seedPromotion("ACTIVE"), null),
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");

        assertThatThrownBy(() -> withoutTemplates.publish(
                        TENANT,
                        BRAND,
                        draft.id(),
                        draft.rowVersion(),
                        h.approver,
                        UUID.fromString(h.approver.subject()),
                        "corr"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refused.getMessage())
                            .contains("no active wording")
                            .contains("SMS");
                });
        assertThat(h.offerService.require(TENANT, BRAND, draft.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("publication is audited with the status change and announced once, in identifiers only")
    void publicationIsAuditedAndAnnounced() {
        OfferRow published = publish(create(draft(h.seedPromotion("ACTIVE"), null)));

        AuditTrail.Fact fact = AuditTrail.only(h.jdbc, "MARKETING_OFFER_PUBLISHED");
        assertThat(fact.targetId()).isEqualTo(published.id());
        assertThat(fact.before("status").asText()).isEqualTo("DRAFT");
        assertThat(fact.after("status").asText()).isEqualTo("PUBLISHED");
        assertThat(AuditTrail.facts(h.jdbc, "MARKETING_OFFER_CREATED")).hasSize(1);

        assertThat(h.events.stream().filter(OfferPublished.class::isInstance))
                .singleElement()
                .satisfies(event -> {
                    var payload = ((OfferPublished) event).payload();
                    assertThat(payload.toString())
                            .contains(published.id().toString())
                            .doesNotContain("Autumn");
                });
    }

    @Test
    @DisplayName("an offer of another brand or another tenant is not found, and the brand's list holds only its own")
    void offersAreScopedToTheirBrandAndTenant() {
        OfferRow mine = create(draft(h.seedPromotion("ACTIVE"), null));

        assertThatThrownBy(() -> h.offerService.require(TENANT, OTHER_BRAND, mine.id()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(h.offerService.list(TENANT, OTHER_BRAND)).isEmpty();
        assertThat(h.offerService.list(OTHER_TENANT, BRAND)).isEmpty();
        assertThat(h.offerService.list(TENANT, BRAND)).extracting(OfferRow::id).containsExactly(mine.id());
    }

    @Test
    @DisplayName("a published offer carries who published it and when: the constraint ties the two together")
    void publicationFactsGoTogether() {
        for (String publication :
                List.of("'PUBLISHED', NULL, NULL", "'DRAFT', now(), gen_random_uuid()", "'SUPERSEDED', NULL, NULL")) {
            assertThatThrownBy(() -> h.jdbc.sql("""
                                    INSERT INTO marketing.offers (id, tenant_id, brand_id, lineage_id, version_number,
                                        display_name, pricing_promotion_id, valid_from, allowed_channels, template_key,
                                        created_by, status, published_at, published_by)
                                    VALUES (gen_random_uuid(), :tenant, :brand, gen_random_uuid(), 1, 'Direct',
                                        :promotion, now(), ARRAY['SMS'], 'T', gen_random_uuid(),
                                    """ + publication + ")")
                            .param("tenant", TENANT)
                            .param("brand", BRAND)
                            .param("promotion", h.seedPromotion("ACTIVE"))
                            .update())
                    .as(publication)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_offer_publication");
        }
    }

    // ----------------------------------------------------------------- helpers

    private OfferDraft draft(@Nullable UUID promotion, @Nullable UUID rule) {
        return new OfferDraft(
                "Autumn ten",
                promotion,
                rule,
                START.minus(Duration.ofDays(1)),
                START.plus(Duration.ofDays(30)),
                null,
                List.of("SMS", "IN_APP"),
                "MARKETING_PROMOTION",
                null,
                null);
    }

    private OfferRow create(OfferDraft draft) {
        return h.offerService.create(TENANT, BRAND, draft, h.author, UUID.fromString(h.author.subject()), "corr");
    }

    private OfferRow publish(OfferRow draft) {
        return h.offerService.publish(
                TENANT,
                BRAND,
                draft.id(),
                h.offerService.require(TENANT, BRAND, draft.id()).rowVersion(),
                h.approver,
                UUID.fromString(h.approver.subject()),
                "corr");
    }

    private static void assertInvalid(Runnable action, String mentions) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isIn(ErrorCode.VALIDATION_FAILED, ErrorCode.UNPROCESSABLE_STATE);
            assertThat(refused.getMessage()).containsIgnoringCase(mentions);
        });
    }
}
