package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_TENANT_BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.condition;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.pricing.api.PromotionActivated;
import uz.horecaos.platform.pricing.api.PromotionSuspended;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0140: a promotion's lifecycle, its immutable history, its approval and its
 * isolation.
 *
 * <p>Authoring is where a mistyped operand is caught, so most of what is here is
 * about what <em>cannot</em> happen: a promotion cannot be edited while live, a
 * recorded definition version cannot be rewritten, a large discount cannot be
 * activated by the person who asked for it, and one brand's rules cannot be read,
 * written or referenced from another.
 */
class PromotionAuthoringTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for promotion authoring");
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
        fixture = new PromotionDbFixture(db, TASHKENT_LUNCH);
    }

    private static uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.PromotionRow promotionOf(
            PromotionAuthoringService.ActivationResult result) {
        return Objects.requireNonNull(result.promotion());
    }

    private static UUID requestOf(PromotionAuthoringService.ActivationResult result) {
        return Objects.requireNonNull(result.pendingApprovalRequestId());
    }

    private static PromotionDefinition tenPercent(String code) {
        return definition(
                code,
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName(
            "a promotion moves DRAFT, VALIDATED, ACTIVE, SUSPENDED, ACTIVE, ARCHIVED and every step is audited and published")
    void theLifecycleIsAuditedAndPublished() {
        var drafted = fixture.authoring.create(TENANT, BRAND, tenPercent("LIFE10"));
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.definitionVersion()).isEqualTo(1);

        var validated = fixture.authoring.validate(TENANT, BRAND, drafted.id(), drafted.version());
        assertThat(validated.report().isValid()).isTrue();
        assertThat(validated.promotion().status()).isEqualTo("VALIDATED");

        var activated = fixture.authoring.activate(
                TENANT, BRAND, drafted.id(), validated.promotion().version(), "go live");
        assertThat(activated.isPending()).isFalse();
        assertThat(promotionOf(activated).status()).isEqualTo("ACTIVE");
        assertThat(promotionOf(activated).activatedBy()).isEqualTo("marketer");
        assertThat(promotionOf(activated).activatedAt()).isNotNull();

        var suspended = fixture.authoring.suspend(
                TENANT, BRAND, drafted.id(), promotionOf(activated).version());
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        var resumed = fixture.authoring.resume(TENANT, BRAND, drafted.id(), suspended.version());
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        var archived = fixture.authoring.archive(TENANT, BRAND, drafted.id(), resumed.version());
        assertThat(archived.status()).isEqualTo("ARCHIVED");

        assertThat(fixture.audits)
                .extracting(AuditFact::actionCode)
                .containsSubsequence(
                        "pricing.promotion.drafted",
                        "pricing.promotion.validated",
                        "pricing.promotion.activated",
                        "pricing.promotion.suspended",
                        "pricing.promotion.resumed",
                        "pricing.promotion.archived");
        assertThat(fixture.published)
                .as("activation, resume (the same definition live again) and nothing for the draft or the validate")
                .filteredOn(PromotionActivated.class::isInstance)
                .hasSize(2);
        assertThat(fixture.published)
                .as("the suspension, and archiving a promotion that was live (it stops applying the same way)")
                .filteredOn(PromotionSuspended.class::isInstance)
                .hasSize(2);
        PromotionActivated event = fixture.published.stream()
                .filter(PromotionActivated.class::isInstance)
                .map(PromotionActivated.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(event.promotionId()).isEqualTo(drafted.id());
        assertThat(event.definitionVersion()).isEqualTo(1);
        assertThat(event.payload().toString())
                .as("ids, version, scope, kind and window only -- never an operand or an amount")
                .doesNotContain("1000")
                .doesNotContain("basisPoints");
    }

    @Test
    @DisplayName("a live promotion is never edited in place, and a stale version is refused")
    void aLivePromotionIsNeverEditedInPlace() {
        var live = fixture.activate(tenPercent("LIVE10"));

        assertThatThrownBy(
                        () -> fixture.authoring.update(TENANT, BRAND, live.id(), live.version(), tenPercent("LIVE10")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        var suspended = fixture.authoring.suspend(TENANT, BRAND, live.id(), live.version());
        assertThatThrownBy(
                        () -> fixture.authoring.update(TENANT, BRAND, live.id(), live.version(), tenPercent("LIVE10")))
                .as("the version the editor read is no longer the version stored")
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(fixture.authoring
                        .update(TENANT, BRAND, live.id(), suspended.version(), tenPercent("LIVE10"))
                        .status())
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("editing a validated promotion takes a new definition version and leaves the recorded one untouched")
    void editingTakesANewVersionAndTheHistoryIsImmutable() {
        var drafted = fixture.authoring.create(TENANT, BRAND, tenPercent("EDIT10"));
        var validated = fixture.authoring.validate(TENANT, BRAND, drafted.id(), drafted.version());
        assertThat(validated.promotion().definitionVersion()).isEqualTo(1);

        PromotionDefinition twenty = definition(
                "EDIT10",
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 2_000L)));
        var edited = fixture.authoring.update(
                TENANT, BRAND, drafted.id(), validated.promotion().version(), twenty);
        assertThat(edited.status()).as("an edit sends it back to DRAFT").isEqualTo("DRAFT");
        assertThat(edited.definitionVersion())
                .as("version 1 was recorded, so the edit is version 2")
                .isEqualTo(2);
        var revalidated = fixture.authoring.validate(TENANT, BRAND, drafted.id(), edited.version());

        var detail = fixture.authoring.get(TENANT, BRAND, drafted.id());
        assertThat(detail.versions()).extracting(v -> v.definitionVersion()).containsExactly(2, 1);
        assertThat(detail.versions().get(1).definition().actions().get(0).operands())
                .as("version 1 still says 10%")
                .containsEntry("basisPoints", 1_000);
        assertThat(detail.versions().get(0).definition().actions().get(0).operands())
                .containsEntry("basisPoints", 2_000);
        assertThat(revalidated.promotion().status()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("an invalid definition stays a draft, and the refusal is a stable code with the sequence it concerns")
    void aRefusalLeavesTheDraftAndNamesTheCondition() {
        var unbounded = definition(
                "GIFT",
                Promotion.Scope.ITEM,
                "gift",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.CATEGORY,
                        "categoryIds",
                        List.of(fixture.pizzaCategory.toString()))),
                List.of(action(
                        1, Promotion.Action.Type.FREE_ITEM, "variantIds", List.of(fixture.colaVariant.toString()))));
        var drafted = fixture.authoring.create(TENANT, BRAND, unbounded);

        var result = fixture.authoring.validate(TENANT, BRAND, drafted.id(), drafted.version());

        assertThat(result.report().isValid()).isFalse();
        assertThat(result.report().refusals()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FREE_ITEM_UNBOUNDED");
            assertThat(issue.sequence()).isEqualTo(1);
        });
        assertThat(result.promotion().status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> fixture.authoring.activate(TENANT, BRAND, drafted.id(), drafted.version(), "x"))
                .as("a promotion that never passed validation cannot reach a customer by any route")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an item promotion cannot share a stacking group with an order promotion")
    void aGroupCannotMixScopes() {
        fixture.activate(definition(
                "ITEMS",
                Promotion.Scope.ITEM,
                "shared",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.CATEGORY,
                        "categoryIds",
                        List.of(fixture.pizzaCategory.toString()))),
                List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
        var order = fixture.authoring.create(
                TENANT,
                BRAND,
                definition(
                        "ORDERS",
                        Promotion.Scope.ORDER,
                        "shared",
                        List.of(),
                        List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 500L))));

        var result = fixture.authoring.validate(TENANT, BRAND, order.id(), order.version());

        assertThat(result.report().refusals())
                .extracting(issue -> issue.code())
                .contains("STACKING_GROUP_MIXES_SCOPES");
    }

    @Test
    @DisplayName("two promotions cannot share a handle within a brand")
    void aHandleIsUniquePerBrand() {
        fixture.authoring.create(TENANT, BRAND, tenPercent("SAME"));

        assertThatThrownBy(() -> fixture.authoring.create(TENANT, BRAND, tenPercent("SAME")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    // -------------------------------------------------------------- approval

    @Test
    @DisplayName(
            "a markup, or a discount over the configured percentage, waits for a second person; a small one does not")
    void activationAboveTheThresholdsNeedsASecondPerson() {
        var large = fixture.authoring.create(
                TENANT,
                BRAND,
                definition(
                        "BIG35",
                        Promotion.Scope.ORDER,
                        "big",
                        List.of(),
                        List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 3_500L))));
        var validated = fixture.authoring.validate(TENANT, BRAND, large.id(), large.version());

        var pending = fixture.authoring.activate(
                TENANT, BRAND, large.id(), validated.promotion().version(), "35% off");
        assertThat(pending.isPending()).as("35% is over the 30% default").isTrue();
        assertThat(fixture.promotionStore
                        .find(TENANT, BRAND, large.id())
                        .orElseThrow()
                        .status())
                .as("nothing is activated until somebody else decides")
                .isEqualTo("VALIDATED");

        assertThatThrownBy(() -> fixture.approvals.decide(
                        requestOf(pending), ApprovalService.Decision.APPROVE, ActorRef.user("marketer", null), "mine"))
                .as("the maker may never approve their own request")
                .isInstanceOf(ApprovalService.SelfApprovalException.class);

        fixture.approvals.decide(
                requestOf(pending), ApprovalService.Decision.APPROVE, ActorRef.user("finance-lead", null), "approved");
        var activated = fixture.inTransaction(() -> fixture.authoring.activate(
                TENANT, BRAND, large.id(), validated.promotion().version(), "35% off"));
        assertThat(activated.isPending()).isFalse();
        assertThat(promotionOf(activated).approvalId()).isEqualTo(requestOf(pending));

        var small = fixture.activate(tenPercent("SMALL10"));
        assertThat(small.approvalId()).as("10% activates directly").isNull();
    }

    @Test
    @DisplayName("an approval is spent by the activation it authorised")
    void anApprovalIsSingleUse() {
        var large = fixture.authoring.create(
                TENANT,
                BRAND,
                definition(
                        "ONCE",
                        Promotion.Scope.ORDER,
                        "once",
                        List.of(),
                        List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 150_000L))));
        var validated = fixture.authoring.validate(TENANT, BRAND, large.id(), large.version());
        var pending = fixture.authoring.activate(
                TENANT, BRAND, large.id(), validated.promotion().version(), "150 000 off");
        fixture.approvals.decide(
                requestOf(pending), ApprovalService.Decision.APPROVE, ActorRef.user("finance-lead", null), "ok");
        var activated = fixture.inTransaction(() -> fixture.authoring.activate(
                TENANT, BRAND, large.id(), validated.promotion().version(), "again"));
        assertThat(promotionOf(activated).status()).isEqualTo("ACTIVE");

        // Suspend, edit to the identical rule, validate: the same parameters would match a spent approval.
        var suspended = fixture.authoring.suspend(
                TENANT, BRAND, large.id(), promotionOf(activated).version());
        var edited = fixture.authoring.update(
                TENANT,
                BRAND,
                large.id(),
                suspended.version(),
                definition(
                        "ONCE",
                        Promotion.Scope.ORDER,
                        "once",
                        List.of(),
                        List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 150_000L))));
        var revalidated = fixture.authoring.validate(TENANT, BRAND, large.id(), edited.version());
        var second = fixture.authoring.activate(
                TENANT, BRAND, large.id(), revalidated.promotion().version(), "again");

        assertThat(second.isPending())
                .as("the definition version moved on, so the old signature no longer covers it")
                .isTrue();
    }

    @Test
    @DisplayName("with no approval policy at all the action fails closed rather than letting one person decide")
    void withoutAPolicyALargePromotionCannotBeActivated() {
        fixture.jdbc
                .sql(
                        "UPDATE audit.approval_policies SET valid_until = valid_from WHERE action_code = 'pricing.promotion.activate'")
                .update();
        try {
            var large = fixture.authoring.create(
                    TENANT,
                    BRAND,
                    definition(
                            "NOPOLICY",
                            Promotion.Scope.ORDER,
                            "nopolicy",
                            List.of(),
                            List.of(action(
                                    1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 5_000L))));
            var validated = fixture.authoring.validate(TENANT, BRAND, large.id(), large.version());

            assertThatThrownBy(() -> fixture.authoring.activate(
                            TENANT, BRAND, large.id(), validated.promotion().version(), "x"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("pricing.promotion.activate");
            assertThat(fixture.promotionStore
                            .find(TENANT, BRAND, large.id())
                            .orElseThrow()
                            .status())
                    .isEqualTo("VALIDATED");
        } finally {
            fixture.jdbc
                    .sql(
                            "UPDATE audit.approval_policies SET valid_until = NULL WHERE action_code = 'pricing.promotion.activate'")
                    .update();
        }
    }

    // ------------------------------------------------------------ reordering

    @Test
    @DisplayName("reordering priorities breaks a tie the other way, takes a new definition version and records it")
    void reorderingBreaksTiesAndIsVersioned() {
        var first = fixture.activate(definition(
                "TIE-A",
                Promotion.Scope.ORDER,
                "tie",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 5_000L))));
        var second = fixture.activate(definition(
                "TIE-B",
                Promotion.Scope.ORDER,
                "tie",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 5_000L))));

        var changed = fixture.authoring.reorder(TENANT, BRAND, "tie", List.of(second.id(), first.id()));
        assertThat(changed).hasSize(2);
        assertThat(fixture.promotionStore
                        .find(TENANT, BRAND, second.id())
                        .orElseThrow()
                        .definition()
                        .priority())
                .isEqualTo(2);
        assertThat(fixture.promotionStore
                        .find(TENANT, BRAND, first.id())
                        .orElseThrow()
                        .definition()
                        .priority())
                .isEqualTo(1);
        assertThat(fixture.authoring.get(TENANT, BRAND, second.id()).versions())
                .extracting(version -> version.reason())
                .contains("REPRIORITISED");

        var quote = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));
        assertThat(quote.adjustments())
                .filteredOn(adjustment -> adjustment.sourceType().equals("PROMOTION"))
                .singleElement()
                .satisfies(adjustment -> assertThat(adjustment.sourceId())
                        .as("equal benefit, so the higher priority wins")
                        .isEqualTo(second.id()));

        assertThatThrownBy(() -> fixture.authoring.reorder(TENANT, BRAND, "tie", List.of(UUID.randomUUID())))
                .as("an id that is not a live promotion of the group")
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("a promotion cannot be read from, or reference something in, another tenant or brand")
    void authoringIsTenantAndBrandScoped() {
        var mine = fixture.authoring.create(TENANT, BRAND, tenPercent("MINE"));

        assertThatThrownBy(() -> fixture.authoring.get(OTHER_TENANT, BRAND, mine.id()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> fixture.authoring.get(TENANT, UUID.randomUUID(), mine.id()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(fixture.authoring.list(OTHER_TENANT, OTHER_TENANT_BRAND)).isEmpty();

        // A variant that belongs to another tenant's brand is not a reference this brand may make.
        UUID foreignProduct = UUID.randomUUID();
        UUID foreignVariant = UUID.randomUUID();
        fixture.jdbc
                .sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'FOREIGN', 'ACTIVE')
                """)
                .param("id", foreignProduct)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_TENANT_BRAND)
                .update();
        fixture.jdbc
                .sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-FOREIGN', 'ACTIVE')
                """)
                .param("id", foreignVariant)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_TENANT_BRAND)
                .param("productId", foreignProduct)
                .update();
        var referencing = fixture.authoring.create(
                TENANT,
                BRAND,
                definition(
                        "FOREIGNREF",
                        Promotion.Scope.ITEM,
                        "foreign",
                        List.of(condition(
                                1, Promotion.Condition.Type.VARIANT, "variantIds", List.of(foreignVariant.toString()))),
                        List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        var result = fixture.authoring.validate(TENANT, BRAND, referencing.id(), referencing.version());

        assertThat(result.report().refusals()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("UNKNOWN_REFERENCE");
            assertThat(issue.sequence()).isEqualTo(1);
        });
    }
}
