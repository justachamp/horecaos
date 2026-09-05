package uz.horecaos.platform.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
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
import uz.horecaos.platform.loyalty.application.ReferralGrantService;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.referral.application.ReferralCodeService;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService.ProgramDraft;
import uz.horecaos.platform.referral.application.ReferralQualificationService;
import uz.horecaos.platform.referral.application.ReferralQualificationService.OrderOutcomeNotice;
import uz.horecaos.platform.referral.application.ReferralQueryService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService.RedeemCommand;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.CodeRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.ProgramAuthoringRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Referral programs, codes, redemptions, and the qualifying event that pays
 * one out (operations §6.6 Referrals, a new ADR riding on ADR 0046).
 *
 * <p>Against a real PostgreSQL, for the same reason every ADR 0046 test is:
 * whether two concurrent deliveries of the same qualifying event pay once is
 * a property of {@code SELECT ... FOR UPDATE} and a conditional {@code
 * UPDATE}, and whether self-referral and stacking are actually refused is a
 * property of a CHECK constraint and a unique index, none of which a mock can
 * stand in for.
 *
 * <p>Every payout assertion below reads the loyalty account's own {@code
 * balance_minor} and its entries, not merely the redemption row's own claim
 * about what happened — the 2026-08-26 audit's lesson that a guard checking
 * the adjacent quantity can stay green while the one that matters breaks.
 */
class ReferralProgramTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-05T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReferralStore referralStore;
    private JdbcLoyaltyStore loyaltyStore;

    private ReferralProgramAuthoringService authoring;
    private ReferralCodeService codes;
    private ReferralRedemptionService redemptions;
    private ReferralQualificationService qualification;
    private ReferralQueryService query;

    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for referral tests");
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

        jdbc.sql("TRUNCATE TABLE referral.redemptions, referral.codes, referral.programs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE loyalty.reservation_lots, loyalty.reservations, loyalty.lots, loyalty.entries, "
                        + "loyalty.accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        referralStore = new JdbcReferralStore(jdbc);
        loyaltyStore = new JdbcLoyaltyStore(jdbc);

        authoring = new ReferralProgramAuthoringService(referralStore, CLOCK);
        codes = new ReferralCodeService(referralStore, CLOCK);
        redemptions = new ReferralRedemptionService(referralStore, CLOCK);
        qualification = new ReferralQualificationService(referralStore, new ReferralGrantService(loyaltyStore));
        query = new ReferralQueryService(referralStore);

        seedTenancy();
    }

    // -------------------------------------------------------- program authoring

    @Test
    @DisplayName("a drafted program does not resolve, and therefore cannot be redeemed against, until activated")
    void draftedProgramDoesNotResolveUntilActivated() {
        authoring.draftProgram(TENANT, BRAND, bothSidesDraft(10_000, 5_000));

        String code = codes.myCode(TENANT, BRAND, newCustomer()).code();
        assertThatThrownBy(() -> redemptions.redeem(new RedeemCommand(TENANT, BRAND, newCustomer(), code)))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("activating a new program retires the old one; a brand runs exactly one at a time")
    void activatingANewProgramRetiresTheOld() {
        UUID first = authoring
                .draftProgram(TENANT, BRAND, bothSidesDraft(10_000, 5_000))
                .id();
        authoring.activateProgram(TENANT, BRAND, first);

        UUID second = authoring
                .draftProgram(TENANT, BRAND, bothSidesDraft(20_000, 8_000))
                .id();
        authoring.activateProgram(TENANT, BRAND, second);

        var listed = authoring.listPrograms(TENANT, BRAND);
        assertThat(statusOf(listed, first)).isEqualTo("RETIRED");
        assertThat(statusOf(listed, second)).isEqualTo("ACTIVE");
        assertThat(listed.stream().filter(p -> "ACTIVE".equals(p.status())).count())
                .as("a brand's live set holds exactly one ACTIVE program, never two")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a REFERRER_ONLY draft carrying a positive referee reward is refused")
    void referrerOnlyCannotCarryARefereeReward() {
        assertThatThrownBy(() -> authoring.draftProgram(
                        TENANT, BRAND, new ProgramDraft("REFERRER_ONLY", 10_000, 1, "UZS", null, 14, 90, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("retiring the live program stops new redemptions outright; it does not fall back to a default")
    void retiringTheLiveProgramStopsRedemption() {
        UUID id = authoring
                .draftProgram(TENANT, BRAND, bothSidesDraft(10_000, 5_000))
                .id();
        authoring.activateProgram(TENANT, BRAND, id);
        authoring.retireProgram(TENANT, BRAND, id);

        UUID referee = newCustomer();
        UUID referrer = newCustomer();
        String code = codes.myCode(TENANT, BRAND, referrer).code();

        assertThatThrownBy(() -> redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code)))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    // ------------------------------------------------------------------- codes

    @Test
    @DisplayName("a customer's own referral code is stable across repeated reads")
    void codeIsStableAcrossCalls() {
        UUID customer = newCustomer();
        CodeRow first = codes.myCode(TENANT, BRAND, customer);
        CodeRow second = codes.myCode(TENANT, BRAND, customer);
        assertThat(second.code()).isEqualTo(first.code());
        assertThat(second.id()).isEqualTo(first.id());
    }

    // -------------------------------------------------------------- redemption

    @Test
    @DisplayName("abuse case: a customer cannot redeem their own referral code")
    void selfReferralIsRefused() {
        activateBothSides(10_000, 5_000);
        UUID customer = newCustomer();
        String ownCode = codes.myCode(TENANT, BRAND, customer).code();

        assertThatThrownBy(() -> redemptions.redeem(new RedeemCommand(TENANT, BRAND, customer, ownCode)))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, customer))
                .as("a refused self-referral leaves no redemption row behind")
                .isEmpty();
    }

    @Test
    @DisplayName("abuse case: a customer cannot stack a second referral redemption")
    void aCustomerCannotStackTwoRedemptions() {
        activateBothSides(10_000, 5_000);
        UUID referrerA = newCustomer();
        UUID referrerB = newCustomer();
        UUID referee = newCustomer();
        String codeA = codes.myCode(TENANT, BRAND, referrerA).code();
        String codeB = codes.myCode(TENANT, BRAND, referrerB).code();

        RedemptionRow first = redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, codeA));

        assertThatThrownBy(() -> redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, codeB)))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, referee))
                .get()
                .extracting(RedemptionRow::id)
                .as("the account's one redemption is still the first code, never overwritten by the refused second")
                .isEqualTo(first.id());
    }

    // ---------------------------------------------------------- qualification

    @Test
    @DisplayName("BOTH_SIDES: the referee's first COMPLETED order credits both the referrer and the referee")
    void qualifyingEventCreditsBothSidesUnderBothSidesProgram() {
        activateBothSides(10_000, 5_000);
        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        String code = codes.myCode(TENANT, BRAND, referrer).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code));

        UUID orderId = completedOrder(referee);
        qualification.onOrderOutcome(new OrderOutcomeNotice(TENANT, BRAND, referee, orderId, "COMPLETED", NOW));

        RedemptionRow redemption =
                referralStore.findRedemptionByReferee(TENANT, BRAND, referee).orElseThrow();
        assertThat(redemption.status()).isEqualTo("REWARDED");
        assertThat(redemption.qualifyingOrderId()).isEqualTo(orderId);
        assertThat(redemption.referrerEntryId()).isNotNull();
        assertThat(redemption.refereeEntryId()).isNotNull();

        assertThat(balanceOf(referrer)).isEqualTo(10_000);
        assertThat(balanceOf(referee)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("REFERRER_ONLY: the referrer is credited and the referee receives nothing")
    void referrerOnlyProgramCreditsOnlyTheReferrer() {
        UUID programId = authoring
                .draftProgram(
                        TENANT, BRAND, new ProgramDraft("REFERRER_ONLY", 10_000, 0, "UZS", null, 14, 90, null, null))
                .id();
        authoring.activateProgram(TENANT, BRAND, programId);

        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        String code = codes.myCode(TENANT, BRAND, referrer).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code));

        UUID orderId = completedOrder(referee);
        qualification.onOrderOutcome(new OrderOutcomeNotice(TENANT, BRAND, referee, orderId, "COMPLETED", NOW));

        RedemptionRow redemption =
                referralStore.findRedemptionByReferee(TENANT, BRAND, referee).orElseThrow();
        assertThat(redemption.status()).isEqualTo("REWARDED");
        assertThat(redemption.refereeEntryId())
                .as("REFERRER_ONLY pays nothing to the referee")
                .isNull();

        assertThat(balanceOf(referrer)).isEqualTo(10_000);
        assertThat(loyaltyStore.findAccount(TENANT, BRAND, referee))
                .as("no account is even opened for a referee who was never owed anything")
                .isEmpty();
    }

    @Test
    @DisplayName("a cancelled order pays out nothing, and the redemption stays open for a real completion later")
    void cancelledOrderDoesNotPayOut() {
        activateBothSides(10_000, 5_000);
        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        redemptions.redeem(new RedeemCommand(
                TENANT, BRAND, referee, codes.myCode(TENANT, BRAND, referrer).code()));

        UUID cancelledOrder = completedOrder(referee);
        qualification.onOrderOutcome(new OrderOutcomeNotice(TENANT, BRAND, referee, cancelledOrder, "CANCELLED", NOW));

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, referee))
                .get()
                .extracting(RedemptionRow::status)
                .as("a cancelled order must never be mistaken for the qualifying event")
                .isEqualTo("PENDING");
        assertThat(loyaltyStore.findAccount(TENANT, BRAND, referrer)).isEmpty();

        // The redemption is still open, so a later real completion still pays.
        UUID completedOrder = completedOrder(referee);
        qualification.onOrderOutcome(
                new OrderOutcomeNotice(TENANT, BRAND, referee, completedOrder, "COMPLETED", NOW.plusSeconds(60)));

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, referee))
                .get()
                .extracting(RedemptionRow::status)
                .isEqualTo("REWARDED");
        assertThat(balanceOf(referrer)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a rejected order pays out nothing")
    void rejectedOrderDoesNotPayOut() {
        activateBothSides(10_000, 5_000);
        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        redemptions.redeem(new RedeemCommand(
                TENANT, BRAND, referee, codes.myCode(TENANT, BRAND, referrer).code()));

        UUID orderId = completedOrder(referee);
        qualification.onOrderOutcome(new OrderOutcomeNotice(TENANT, BRAND, referee, orderId, "REJECTED", NOW));

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, referee))
                .get()
                .extracting(RedemptionRow::status)
                .isEqualTo("PENDING");
        assertThat(loyaltyStore.findAccount(TENANT, BRAND, referrer)).isEmpty();
    }

    @Test
    @DisplayName("abuse case: a replayed delivery of the same COMPLETED order credits nothing a second time")
    void replayedQualifyingEventDoesNotDoubleCredit() {
        activateBothSides(10_000, 5_000);
        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        redemptions.redeem(new RedeemCommand(
                TENANT, BRAND, referee, codes.myCode(TENANT, BRAND, referrer).code()));

        UUID orderId = completedOrder(referee);
        OrderOutcomeNotice notice = new OrderOutcomeNotice(TENANT, BRAND, referee, orderId, "COMPLETED", NOW);

        qualification.onOrderOutcome(notice);
        qualification.onOrderOutcome(notice);
        qualification.onOrderOutcome(notice);

        assertThat(balanceOf(referrer))
                .as("three deliveries of the identical fact still credit exactly once")
                .isEqualTo(10_000);
        assertThat(balanceOf(referee)).isEqualTo(5_000);
        assertThat(loyaltyStore.entries(
                        TENANT,
                        loyaltyStore
                                .findAccount(TENANT, BRAND, referrer)
                                .orElseThrow()
                                .id(),
                        10))
                .hasSize(1);
    }

    @Test
    @DisplayName("a qualifying event arriving after the redemption window closed expires it rather than paying")
    void lateQualifyingEventExpiresRatherThanPays() {
        UUID programId = authoring
                .draftProgram(TENANT, BRAND, bothSidesDraft(10_000, 5_000, 3))
                .id();
        authoring.activateProgram(TENANT, BRAND, programId);

        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        redemptions.redeem(new RedeemCommand(
                TENANT, BRAND, referee, codes.myCode(TENANT, BRAND, referrer).code()));

        UUID orderId = completedOrder(referee);
        Instant pastTheWindow = NOW.plus(Duration.ofDays(4));
        qualification.onOrderOutcome(
                new OrderOutcomeNotice(TENANT, BRAND, referee, orderId, "COMPLETED", pastTheWindow));

        assertThat(referralStore.findRedemptionByReferee(TENANT, BRAND, referee))
                .get()
                .extracting(RedemptionRow::status)
                .isEqualTo("EXPIRED");
        assertThat(loyaltyStore.findAccount(TENANT, BRAND, referrer)).isEmpty();
    }

    @Test
    @DisplayName("a referrer past their cap is skipped, and the referee's own reward is unaffected")
    void referrerCapStopsFurtherReferrerRewardsButRefereeStillPaid() {
        UUID programId = authoring
                .draftProgram(
                        TENANT, BRAND, new ProgramDraft("BOTH_SIDES", 10_000, 5_000, "UZS", 1, 14, 90, null, null))
                .id();
        authoring.activateProgram(TENANT, BRAND, programId);

        UUID referrer = newCustomer();
        String code = codes.myCode(TENANT, BRAND, referrer).code();

        UUID refereeOne = newCustomer();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, refereeOne, code));
        qualification.onOrderOutcome(
                new OrderOutcomeNotice(TENANT, BRAND, refereeOne, completedOrder(refereeOne), "COMPLETED", NOW));
        assertThat(balanceOf(referrer)).isEqualTo(10_000);

        UUID refereeTwo = newCustomer();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, refereeTwo, code));
        qualification.onOrderOutcome(new OrderOutcomeNotice(
                TENANT, BRAND, refereeTwo, completedOrder(refereeTwo), "COMPLETED", NOW.plusSeconds(30)));

        RedemptionRow second =
                referralStore.findRedemptionByReferee(TENANT, BRAND, refereeTwo).orElseThrow();
        assertThat(second.status()).isEqualTo("REWARDED");
        assertThat(second.referrerEntryId())
                .as("the referrer's cap of one was already reached by the first referee")
                .isNull();
        assertThat(second.referrerSkipReason()).isEqualTo("REFERRER_CAP_REACHED");
        assertThat(second.refereeEntryId())
                .as("the second referee did nothing to be denied for")
                .isNotNull();

        assertThat(balanceOf(referrer))
                .as("the cap actually stops the money, not only the flag on the row")
                .isEqualTo(10_000);
        assertThat(balanceOf(refereeTwo)).isEqualTo(5_000);
    }

    // ------------------------------------------------------------------ query

    @Test
    @DisplayName("the operations read side lists every redemption and totals what was actually paid out")
    void queryServiceSummarisesRedemptions() {
        activateBothSides(10_000, 5_000);
        UUID referrer = newCustomer();
        UUID referee = newCustomer();
        redemptions.redeem(new RedeemCommand(
                TENANT, BRAND, referee, codes.myCode(TENANT, BRAND, referrer).code()));
        qualification.onOrderOutcome(
                new OrderOutcomeNotice(TENANT, BRAND, referee, completedOrder(referee), "COMPLETED", NOW));

        assertThat(query.redemptions(TENANT, BRAND)).hasSize(1);
        var summary = query.summary(TENANT, BRAND);
        assertThat(summary.codesIssued()).isEqualTo(1);
        assertThat(summary.rewardedRedemptions()).isEqualTo(1);
        assertThat(summary.pointsPaidOutMinor()).isEqualTo(15_000);
    }

    // -------------------------------------------------------------- fixtures

    private void activateBothSides(long referrerRewardMinor, long refereeRewardMinor) {
        UUID id = authoring
                .draftProgram(TENANT, BRAND, bothSidesDraft(referrerRewardMinor, refereeRewardMinor))
                .id();
        authoring.activateProgram(TENANT, BRAND, id);
    }

    private ProgramDraft bothSidesDraft(long referrerRewardMinor, long refereeRewardMinor) {
        return bothSidesDraft(referrerRewardMinor, refereeRewardMinor, 14);
    }

    private ProgramDraft bothSidesDraft(long referrerRewardMinor, long refereeRewardMinor, int redemptionWindowDays) {
        return new ProgramDraft(
                "BOTH_SIDES",
                referrerRewardMinor,
                refereeRewardMinor,
                "UZS",
                null,
                redemptionWindowDays,
                90,
                null,
                null);
    }

    private long balanceOf(UUID customerAccountId) {
        return loyaltyStore
                .findAccount(TENANT, BRAND, customerAccountId)
                .orElseThrow()
                .balanceMinor();
    }

    private static String statusOf(java.util.List<ProgramAuthoringRow> programs, UUID id) {
        return programs.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private UUID newCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID completedOrder(UUID customerAccountId) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        long totalMinor = 84_000;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("customer", customerAccountId)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "R-" + orderId.toString().substring(0, 8));
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", locationId);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("customer", customerAccountId);
        order.put("total", totalMinor);
        order.put("key", "idem-" + orderId);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :customer, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'COMPLETED',
                    'UZS', :total, 0, 0, :total, :quoteId, 'hash', :publicationId, :cartId,
                    :key, 1, now())
                """).params(order).update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'referral-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'MAIN', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }
}
