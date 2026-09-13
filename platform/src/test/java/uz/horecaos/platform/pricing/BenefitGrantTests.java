package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.pricing.application.BenefitGrantService;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintRequest;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintedGrant;
import uz.horecaos.platform.pricing.application.BenefitGrantService.RedemptionOutcome;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Operations §6.2a: a one-off code redeemable by exactly one named customer —
 * the late-order apology a shared promo code cannot express. Runs against
 * V0265's real, migrated {@code pricing.benefit_grants}, the table's first
 * exerciser.
 *
 * <p>{@code benefit_grants} carries no foreign key to {@code tenant.tenants},
 * {@code tenant.brands} or {@code customer.customer_accounts} — the same
 * choice {@code pricing.coupon_redemptions} already made for
 * {@code customer_account_id} — so these tests need no tenancy or customer
 * fixture at all, unlike {@code PromoCodeTests}, which additionally exercises
 * real pricing and so seeds a full catalog.
 */
class BenefitGrantTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private BenefitGrantService grants;
    private RecordingAuditRecorder audit;
    private final ActorRef actor = ActorRef.user(UUID.randomUUID().toString(), "Operator");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for benefit grant tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE pricing.benefit_grants").update();

        audit = new RecordingAuditRecorder();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        grants = new BenefitGrantService(new JdbcBenefitGrantStore(jdbc), audit, clock);
    }

    @Test
    @DisplayName("a minted grant is redeemable by exactly the customer it was minted for")
    void mintedGrantRedeemableByItsOwnCustomer() {
        UUID customer = UUID.randomUUID();
        MintedGrant minted = mint(customer);

        RedemptionOutcome outcome = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());

        assertThat(outcome.redeemed()).isTrue();
        assertThat(outcome.grantId()).isEqualTo(minted.grantId());
        assertThat(outcome.shape()).isEqualTo(DiscountShape.PERCENTAGE_OFF_ORDER);
        assertThat(outcome.value()).isEqualTo(1_500L);
    }

    @Test
    @DisplayName("a second redemption of the same code is refused, even by its own customer")
    void aSecondRedemptionIsRefused() {
        UUID customer = UUID.randomUUID();
        MintedGrant minted = mint(customer);

        RedemptionOutcome first = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());
        assertThat(first.redeemed()).isTrue();

        RedemptionOutcome second = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());
        assertThat(second.redeemed()).isFalse();
        assertThat(second.reason()).isEqualTo(RedemptionOutcome.Reason.ALREADY_USED);
    }

    @Test
    @DisplayName("a code minted for one customer refuses a different one, and does not consume the real owner's grant")
    void wrongCustomerIsRefusedWithoutConsumingTheGrant() {
        UUID customer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        MintedGrant minted = mint(customer);

        RedemptionOutcome strangerAttempt = grants.redeem(TENANT, minted.plaintextCode(), stranger, UUID.randomUUID());
        assertThat(strangerAttempt.redeemed()).isFalse();
        assertThat(strangerAttempt.reason()).isEqualTo(RedemptionOutcome.Reason.WRONG_CUSTOMER);

        RedemptionOutcome ownerAttempt = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());
        assertThat(ownerAttempt.redeemed())
                .as("the stranger's refused attempt must not have spent the owner's own grant")
                .isTrue();
    }

    @Test
    @DisplayName("a code that was never minted is refused as CODE_NOT_FOUND")
    void unknownCodeIsRefused() {
        RedemptionOutcome outcome = grants.redeem(TENANT, "NOSUCHCODE1", UUID.randomUUID(), UUID.randomUUID());

        assertThat(outcome.redeemed()).isFalse();
        assertThat(outcome.reason()).isEqualTo(RedemptionOutcome.Reason.CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("an expired grant is refused rather than redeemed")
    void anExpiredGrantIsRefused() {
        UUID customer = UUID.randomUUID();
        MintedGrant minted = grants.mint(
                TENANT,
                BRAND,
                new MintRequest(
                        customer,
                        DiscountShape.FIXED_AMOUNT_OFF_ORDER,
                        5_000L,
                        null,
                        "UZS",
                        0L,
                        "OPERATOR_MANUAL",
                        null,
                        NOW.minusSeconds(3_600),
                        NOW.minusSeconds(60)),
                actor,
                "corr-expired");

        RedemptionOutcome outcome = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());

        assertThat(outcome.redeemed()).isFalse();
        assertThat(outcome.reason()).isEqualTo(RedemptionOutcome.Reason.EXPIRED);
    }

    @Test
    @DisplayName("minting is audited with the customer and its source, never the plaintext code")
    void mintIsAuditedWithoutThePlaintext() {
        UUID customer = UUID.randomUUID();
        MintedGrant minted = mint(customer);

        assertThat(audit.facts()).hasSize(1);
        AuditFact fact = audit.facts().getFirst();
        assertThat(fact.changeDocument()).containsEntry("customerAccountId", customer);
        assertThat(fact.changeDocument().values())
                .as("the bearer secret itself must never enter the audit trail")
                .noneMatch(value -> value.equals(minted.plaintextCode()));
    }

    @Test
    @DisplayName("a free-delivery grant refuses a non-zero value, and an out-of-range percentage is refused too")
    void mintValidatesTheDiscountShape() {
        assertThatThrownBy(() -> grants.mint(
                        TENANT,
                        BRAND,
                        new MintRequest(
                                UUID.randomUUID(),
                                DiscountShape.FREE_DELIVERY,
                                500L,
                                null,
                                null,
                                0L,
                                "OPERATOR_MANUAL",
                                null,
                                null,
                                null),
                        actor,
                        "corr-bad-1"))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> grants.mint(
                        TENANT,
                        BRAND,
                        new MintRequest(
                                UUID.randomUUID(),
                                DiscountShape.PERCENTAGE_OFF_ORDER,
                                20_000L,
                                null,
                                "UZS",
                                0L,
                                "OPERATOR_MANUAL",
                                null,
                                null,
                                null),
                        actor,
                        "corr-bad-2"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a customer's grant under one brand does not appear when read through a sibling brand")
    void forCustomerExcludesASiblingBrand() {
        UUID customer = UUID.randomUUID();
        mint(customer);

        assertThat(grants.forCustomer(TENANT, BRAND, customer)).hasSize(1);
        assertThat(grants.forCustomer(TENANT, OTHER_BRAND, customer))
                .as("brand scoping happens in the query, not merely by convention -- pin it with a test")
                .isEmpty();
    }

    @Test
    @DisplayName("a customer's grant is invisible through another tenant even with the same brand id")
    void forCustomerExcludesAnotherTenant() {
        UUID customer = UUID.randomUUID();
        mint(customer);

        assertThat(grants.forCustomer(OTHER_TENANT, BRAND, customer))
                .as("brand ids are not guaranteed globally unique to one tenant")
                .isEmpty();
    }

    @Test
    @DisplayName("a code minted under one tenant cannot be redeemed by naming another tenant")
    void redeemRefusesAnotherTenantWithTheSameCodeHash() {
        UUID customer = UUID.randomUUID();
        MintedGrant minted = mint(customer);

        RedemptionOutcome outcome = grants.redeem(OTHER_TENANT, minted.plaintextCode(), customer, UUID.randomUUID());

        assertThat(outcome.redeemed()).isFalse();
        assertThat(outcome.reason()).isEqualTo(RedemptionOutcome.Reason.CODE_NOT_FOUND);

        // The real owner can still redeem it -- the cross-tenant attempt above
        // must not have consumed or otherwise disturbed the grant.
        RedemptionOutcome ownerAttempt = grants.redeem(TENANT, minted.plaintextCode(), customer, UUID.randomUUID());
        assertThat(ownerAttempt.redeemed()).isTrue();
    }

    private MintedGrant mint(UUID customer) {
        return grants.mint(
                TENANT,
                BRAND,
                new MintRequest(
                        customer,
                        DiscountShape.PERCENTAGE_OFF_ORDER,
                        1_500L,
                        null,
                        "UZS",
                        0L,
                        "OPERATOR_MANUAL",
                        null,
                        null,
                        null),
                actor,
                "corr-" + UUID.randomUUID());
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        List<AuditFact> facts() {
            return facts;
        }
    }
}
