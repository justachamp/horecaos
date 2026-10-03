package uz.horecaos.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.AccrualRuleDraft;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.RedemptionPolicyDraft;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService.ProgramDraft;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.AuditTrail.Fact;
import uz.horecaos.platform.support.FakeDnsTxtResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.application.ChannelPageService;
import uz.horecaos.platform.tenancy.application.ChannelSetupService;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.tenancy.application.TenantResourceConflictException;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageSlug;
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenge;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelPageStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelSetupStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Staff row {@code 9.3a}: the six configuration-authoring services that wrote to their store and
 * left no audit fact -- promo codes, the sales channel registry and its matrices, a channel's
 * hostname and search presentation, a channel's static pages, the loyalty accrual rule and
 * redemption policy, and the referral program.
 *
 * <p>Against a real PostgreSQL and the real {@code JdbcAuditRecorder}, because what is being
 * asserted is the row an operator's activity log reads: its actor, scope, target, reason, and the
 * per-field before and after. Every test ends by running {@link #theEvidenceIsClean}, which
 * refuses a fact whose change document holds a person's contact detail or whose name-based
 * redaction ({@code ChangeDocuments}) silently blanked a field this test wanted written.
 */
class ConfigurationAuthoringAuditTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String AUTHOR = "7c1d7a9e-0000-4000-8000-00000000a001";
    private static final String BASE_DOMAIN = "stores.test";
    private static final Instant NOW = Instant.parse("2026-10-03T07:00:00Z");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+998[0-9 ()-]{9,}");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private Clock clock;
    private CurrentActor actor;

    private SalesChannelService channels;
    private ChannelSetupService setup;
    private ChannelPageService pages;
    private PromoCodeAuthoringService promoCodes;
    private LoyaltyPolicyAuthoringService loyalty;
    private ReferralProgramAuthoringService referrals;
    private FakeDnsTxtResolver dns;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the configuration authoring audit tests");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        AuditTrail.clear(jdbc);

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        actor = AuditTrail.actor(AUTHOR);
        var recorder = AuditTrail.recorder(jdbc);
        dns = new FakeDnsTxtResolver();

        channels = new SalesChannelService(new JdbcSalesChannelStore(jdbc), clock, recorder, actor);
        setup = new ChannelSetupService(
                new JdbcChannelSetupStore(jdbc), channels, clock, recorder, actor, dns, BASE_DOMAIN);
        pages = new ChannelPageService(new JdbcChannelPageStore(jdbc), channels, recorder, clock);
        promoCodes = new PromoCodeAuthoringService(
                new JdbcPromoCodeStore(jdbc, JsonMapper.builder().build()), clock, recorder, actor);
        loyalty = new LoyaltyPolicyAuthoringService(new JdbcLoyaltyStore(jdbc), clock, recorder, actor);
        referrals = new ReferralProgramAuthoringService(new JdbcReferralStore(jdbc), clock, recorder, actor);

        seedTenancy();
    }

    // ------------------------------------------------------------- promo codes

    @Test
    @DisplayName("drafting, activating and retiring a promo code each leave a fact, and none carries the code word")
    void promoCodeLifecycleIsAudited() {
        var drafted = promoCodes.draft(TENANT, BRAND, promoDraft("SUMMER2026", 1_500));

        Fact draft = AuditTrail.only(jdbc, "promo.code.drafted");
        assertThat(draft.actorType()).isEqualTo("USER");
        assertThat(draft.actorSubject()).isEqualTo(AUTHOR);
        assertThat(draft.scopeType()).isEqualTo("BRAND");
        assertThat(draft.scopeId()).isEqualTo(BRAND);
        assertThat(draft.targetType()).isEqualTo("pricing.promo-code");
        assertThat(draft.targetId()).isEqualTo(drafted.couponId());
        assertThat(draft.reason()).isNotBlank();
        assertThat(draft.before("value").isNull())
                .as("a creation has no prior value")
                .isTrue();
        assertThat(draft.after("value").asLong()).isEqualTo(1_500L);
        assertThat(draft.after("discount").asText()).isEqualTo("ORDER_PERCENTAGE_DISCOUNT");
        assertThat(draft.after("status").asText()).isEqualTo("SUSPENDED");
        assertThat(draft.after("codeHint").asText()).isEqualTo("2026");
        assertThat(draft.change().toString())
                .as("the code word is a bearer credential whose store keeps only a hash; the history keeps less")
                .doesNotContain("SUMMER2026");

        promoCodes.activate(TENANT, BRAND, drafted.couponId());
        Fact activated = AuditTrail.only(jdbc, "promo.code.activated");
        assertThat(activated.targetId()).isEqualTo(drafted.couponId());
        assertThat(activated.before("status").asText()).isEqualTo("SUSPENDED");
        assertThat(activated.after("status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.after("codeHint").asText()).isEqualTo("2026");

        promoCodes.retire(TENANT, BRAND, drafted.couponId());
        Fact retired = AuditTrail.only(jdbc, "promo.code.retired");
        assertThat(retired.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(retired.after("status").asText()).isEqualTo("ARCHIVED");

        theEvidenceIsClean("SUMMER2026");
    }

    @Test
    @DisplayName("a refused promo code write leaves no fact: the write and its fact roll back as one")
    void aRefusedPromoCodeWriteLeavesNoFact() {
        var drafted = promoCodes.draft(TENANT, BRAND, promoDraft("ONCEONLY1", 500));
        promoCodes.activate(TENANT, BRAND, drafted.couponId());
        long before = AuditTrail.count(jdbc);

        Throwable refused = catchThrowable(() -> promoCodes.activate(TENANT, BRAND, drafted.couponId()));

        assertThat(refused).isNotNull();
        assertThat(AuditTrail.count(jdbc)).isEqualTo(before);
        assertThat(AuditTrail.facts(jdbc, "promo.code.activated")).hasSize(1);
    }

    // ----------------------------------------------------------- sales channels

    @Test
    @DisplayName("creating, editing and moving a channel through its lifecycle each leave a before/after fact")
    void channelLifecycleIsAudited() {
        SalesChannel channel = channels.create(TENANT, command("WEBSITE", "WEB", "Website"));

        Fact created = AuditTrail.only(jdbc, "channel.created");
        assertThat(created.actorSubject()).isEqualTo(AUTHOR);
        assertThat(created.scopeType()).isEqualTo("TENANT");
        assertThat(created.scopeId()).isEqualTo(TENANT);
        assertThat(created.targetType()).isEqualTo("tenancy.sales-channel");
        assertThat(created.targetId()).isEqualTo(channel.id());
        assertThat(created.reason()).isNotBlank();
        assertThat(created.after("code").asText()).isEqualTo("WEBSITE");
        assertThat(created.after("systemType").asText()).isEqualTo("WEB");
        assertThat(created.after("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.before("displayName").isNull()).isTrue();

        SalesChannel edited = channels.update(
                TENANT,
                channel.id(),
                new SalesChannelService.UpdateChannelCommand(
                        "Main website", null, true, false, null, "globe", "#112233", null),
                channel.version());
        Fact updated = AuditTrail.only(jdbc, "channel.updated");
        assertThat(updated.before("displayName").asText()).isEqualTo("Website");
        assertThat(updated.after("displayName").asText()).isEqualTo("Main website");
        assertThat(updated.before("guestOrdersAllowed").asBoolean()).isTrue();
        assertThat(updated.after("guestOrdersAllowed").asBoolean()).isFalse();
        assertThat(updated.after("externallyPriced").asBoolean()).isTrue();
        assertThat(updated.after("brandColorPrimary").asText()).isEqualTo("#112233");
        assertThat(updated.targetVersion()).isEqualTo(edited.version());

        SalesChannel paused = channels.deactivate(TENANT, channel.id(), edited.version());
        Fact deactivated = AuditTrail.only(jdbc, "channel.deactivated");
        assertThat(deactivated.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(deactivated.after("status").asText()).isEqualTo("INACTIVE");

        SalesChannel resumed = channels.reactivate(TENANT, channel.id(), paused.version());
        Fact reactivated = AuditTrail.only(jdbc, "channel.reactivated");
        assertThat(reactivated.before("status").asText()).isEqualTo("INACTIVE");
        assertThat(reactivated.after("status").asText()).isEqualTo("ACTIVE");

        channels.archive(TENANT, channel.id(), resumed.version());
        Fact archived = AuditTrail.only(jdbc, "channel.archived");
        assertThat(archived.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(archived.after("status").asText()).isEqualTo("ARCHIVED");

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("the payment, fulfilment, location and social matrices record what was on and what is on now")
    void channelMatricesAreAudited() {
        SalesChannel channel = channels.create(TENANT, command("KIOSK1", "KIOSK", "Kiosk"));
        registerPaymentMethod("CASH");
        registerPaymentMethod("CARD_TERMINAL");

        int version = channel.version();
        channels.replacePaymentMethods(TENANT, channel.id(), Map.of("CASH", true, "CARD_TERMINAL", false), version++);
        Fact payments = AuditTrail.only(jdbc, "channel.payment_methods.replaced");
        assertThat(payments.before("enabledPaymentMethods").size()).isZero();
        assertThat(payments.after("enabledPaymentMethods").get(0).asText()).isEqualTo("CASH");
        assertThat(payments.after("disabledPaymentMethods").get(0).asText()).isEqualTo("CARD_TERMINAL");
        assertThat(payments.targetVersion()).isEqualTo((long) version);
        assertThat(payments.reason()).isNotBlank();

        channels.replacePaymentMethods(TENANT, channel.id(), Map.of("CASH", false, "CARD_TERMINAL", true), version++);
        Fact flipped =
                AuditTrail.facts(jdbc, "channel.payment_methods.replaced").get(1);
        assertThat(flipped.before("enabledPaymentMethods").get(0).asText()).isEqualTo("CASH");
        assertThat(flipped.after("enabledPaymentMethods").get(0).asText()).isEqualTo("CARD_TERMINAL");

        channels.replaceFulfillmentModes(
                TENANT, channel.id(), Map.of(FulfillmentMode.PICKUP, true, FulfillmentMode.DELIVERY, false), version++);
        Fact modes = AuditTrail.only(jdbc, "channel.fulfillment_modes.replaced");
        assertThat(modes.after("enabledFulfillmentModes").get(0).asText()).isEqualTo("PICKUP");
        assertThat(modes.after("disabledFulfillmentModes").get(0).asText()).isEqualTo("DELIVERY");
        assertThat(modes.before("enabledFulfillmentModes").size()).isZero();

        channels.replaceLocations(TENANT, channel.id(), List.of(LOCATION), version++);
        Fact locations = AuditTrail.only(jdbc, "channel.locations.replaced");
        assertThat(locations.before("locationIds").size()).isZero();
        assertThat(locations.after("locationIds").get(0).asText()).isEqualTo(LOCATION.toString());

        Map<String, String> links = new LinkedHashMap<>();
        links.put("INSTAGRAM", "https://instagram.com/rayhon.private.handle");
        links.put("TELEGRAM", "https://t.me/rayhon_private_handle");
        channels.replaceSocialLinks(TENANT, channel.id(), links, version++);
        Fact social = AuditTrail.only(jdbc, "channel.social_links.replaced");
        assertThat(social.before("socialPlatforms").size()).isZero();
        assertThat(social.after("socialPlatforms").get(0).asText()).isEqualTo("INSTAGRAM");
        assertThat(social.after("socialPlatforms").get(1).asText()).isEqualTo("TELEGRAM");
        assertThat(social.change().toString())
                .as("a link can point at a person's own profile; the fact names the platform and not the handle")
                .doesNotContain("private.handle")
                .doesNotContain("private_handle")
                .doesNotContain("https://");

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("a channel write refused for a stale version leaves no fact")
    void aStaleChannelWriteLeavesNoFact() {
        SalesChannel channel = channels.create(TENANT, command("STALE1", "WEB", "Stale"));
        long before = AuditTrail.count(jdbc);

        Throwable refused = catchThrowable(
                () -> channels.replaceLocations(TENANT, channel.id(), List.of(LOCATION), channel.version() + 5));

        assertThat(refused).isInstanceOf(TenantResourceConflictException.class);
        assertThat(AuditTrail.count(jdbc)).isEqualTo(before);

        // The same write at the right version is recorded exactly once: the refusal above was
        // quiet because nothing was written, not because nothing is ever recorded.
        channels.replaceLocations(TENANT, channel.id(), List.of(LOCATION), channel.version());
        assertThat(AuditTrail.facts(jdbc, "channel.locations.replaced")).hasSize(1);
    }

    // ----------------------------------------------- channel hostname and pages

    @Test
    @DisplayName("claiming, verifying, rotating and clearing a hostname leave facts that never carry the DNS token")
    void hostnameLifecycleIsAudited() {
        SalesChannel channel = channels.create(TENANT, command("SHOP1", "WEB", "Shop"));

        setup.setSubdomain(TENANT, channel.id(), "rayhon", channel.version());
        Fact subdomain = AuditTrail.only(jdbc, "channel.hostname.claimed");
        assertThat(subdomain.actorSubject()).isEqualTo(AUTHOR);
        assertThat(subdomain.scopeId()).isEqualTo(TENANT);
        assertThat(subdomain.targetId()).isEqualTo(channel.id());
        assertThat(subdomain.before("hostname").isNull()).isTrue();
        assertThat(subdomain.after("hostname").asText()).isEqualTo("rayhon." + BASE_DOMAIN);
        assertThat(subdomain.after("hostnameVerified").asBoolean()).isTrue();

        int version = currentVersion(channel);
        setup.setCustomHostname(TENANT, channel.id(), "orders.rayhon.uz", version);
        Fact custom = AuditTrail.facts(jdbc, "channel.hostname.claimed").get(1);
        assertThat(custom.before("hostname").asText()).isEqualTo("rayhon." + BASE_DOMAIN);
        assertThat(custom.after("hostname").asText()).isEqualTo("orders.rayhon.uz");
        assertThat(custom.after("hostnameVerified").asBoolean()).isFalse();

        HostnameChallenge challenge = setup.challenge(TENANT, channel.id()).orElseThrow();
        dns.publish(challenge.recordName(), challenge.token());
        setup.verifyCustomHostname(TENANT, channel.id(), currentVersion(channel));
        Fact verified = AuditTrail.only(jdbc, "channel.hostname.verified");
        assertThat(verified.before("hostnameVerified").asBoolean()).isFalse();
        assertThat(verified.after("hostnameVerified").asBoolean()).isTrue();

        HostnameChallenge rotated = setup.rotateChallenge(TENANT, channel.id(), currentVersion(channel));
        Fact rotation = AuditTrail.only(jdbc, "channel.hostname.challenge_rotated");
        assertThat(rotation.before("hostnameVerified").asBoolean()).isTrue();
        assertThat(rotation.after("hostnameVerified").asBoolean())
                .as("a fresh challenge withdraws the proof the hostname was verified on")
                .isFalse();
        assertThat(rotation.after("challengeIssuedAt").asText()).isEqualTo(NOW.toString());
        assertThat(rotation.change().toString())
                .as("the challenge token is the proof of control; only its issue is recorded")
                .doesNotContain(challenge.token())
                .doesNotContain(rotated.token());

        setup.clearHostname(TENANT, channel.id(), currentVersion(channel));
        Fact cleared = AuditTrail.only(jdbc, "channel.hostname.cleared");
        assertThat(cleared.before("hostname").asText()).isEqualTo("orders.rayhon.uz");
        assertThat(cleared.after("hostname").isNull()).isTrue();

        theEvidenceIsClean(challenge.token(), rotated.token());
    }

    @Test
    @DisplayName("editing a channel's search presentation records the title and description before and after")
    void presentationIsAudited() {
        SalesChannel channel = channels.create(TENANT, command("SEO1", "WEB", "Seo"));

        setup.setPresentation(TENANT, channel.id(), "Rayhon", "Uzbek cuisine, delivered", null, channel.version());
        int version = currentVersion(channel);
        setup.setPresentation(TENANT, channel.id(), "Rayhon Osh", "Uzbek cuisine, delivered", null, version);

        List<Fact> facts = AuditTrail.facts(jdbc, "channel.presentation.updated");
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).before("seoTitle").isNull()).isTrue();
        assertThat(facts.get(0).after("seoTitle").asText()).isEqualTo("Rayhon");
        assertThat(facts.get(1).before("seoTitle").asText()).isEqualTo("Rayhon");
        assertThat(facts.get(1).after("seoTitle").asText()).isEqualTo("Rayhon Osh");
        assertThat(facts.get(1).actorSubject()).isEqualTo(AUTHOR);

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("publishing a channel page records the version and the size of each language, never the text")
    void pagePublicationIsAudited() {
        SalesChannel channel = channels.create(TENANT, command("PAGES1", "WEB", "Pages"));
        String russian = "О нас: мы готовим плов с 1998 года.";

        pages.publish(TENANT, channel.id(), ChannelPageSlug.ABOUT, Map.of("ru", russian), AUTHOR);
        pages.publish(
                TENANT,
                channel.id(),
                ChannelPageSlug.ABOUT,
                Map.of("ru", russian, "en", "About us: pilaf since 1998."),
                AUTHOR);

        List<Fact> facts = AuditTrail.facts(jdbc, "channel.page.published");
        assertThat(facts).hasSize(2);
        Fact first = facts.get(0);
        assertThat(first.actorSubject()).isEqualTo(AUTHOR);
        assertThat(first.targetType()).isEqualTo("tenancy.channel-page");
        assertThat(first.targetVersion()).isEqualTo(1L);
        assertThat(first.before("version").asInt()).isZero();
        assertThat(first.after("version").asInt()).isEqualTo(1);
        assertThat(first.after("page").asText()).isEqualTo("about");
        assertThat(first.after("characters.ru").asInt()).isEqualTo(russian.length());

        Fact second = facts.get(1);
        assertThat(second.before("version").asInt()).isEqualTo(1);
        assertThat(second.after("version").asInt()).isEqualTo(2);
        assertThat(second.before("characters.en").isNull()).isTrue();
        assertThat(second.after("characters.en").asInt()).isEqualTo("About us: pilaf since 1998.".length());
        assertThat(second.change().toString()).doesNotContain("pilaf").doesNotContain("плов");

        theEvidenceIsClean("pilaf", "плов");
    }

    // ------------------------------------------------------------------ loyalty

    @Test
    @DisplayName(
            "an accrual rule's draft, activation and retirement are audited, and activation names the rate it replaced")
    void accrualRuleLifecycleIsAudited() {
        UUID first = loyalty.draftAccrualRule(TENANT, BRAND, accrual(300)).id();
        Fact drafted = AuditTrail.only(jdbc, "loyalty.accrual_rule.drafted");
        assertThat(drafted.actorSubject()).isEqualTo(AUTHOR);
        assertThat(drafted.scopeType()).isEqualTo("BRAND");
        assertThat(drafted.scopeId()).isEqualTo(BRAND);
        assertThat(drafted.targetType()).isEqualTo("loyalty.accrual-rule");
        assertThat(drafted.targetId()).isEqualTo(first);
        assertThat(drafted.after("rateBasisPoints").asInt()).isEqualTo(300);
        assertThat(drafted.after("status").asText()).isEqualTo("DRAFT");
        assertThat(drafted.before("rateBasisPoints").isNull()).isTrue();

        loyalty.activateAccrualRule(TENANT, BRAND, first);
        Fact firstActivation = AuditTrail.only(jdbc, "loyalty.accrual_rule.activated");
        assertThat(firstActivation.before("rateBasisPoints").isNull())
                .as("nothing was live before the first activation")
                .isTrue();
        assertThat(firstActivation.after("rateBasisPoints").asInt()).isEqualTo(300);
        assertThat(firstActivation.after("status").asText()).isEqualTo("ACTIVE");

        UUID second = loyalty.draftAccrualRule(TENANT, BRAND, accrual(500)).id();
        loyalty.activateAccrualRule(TENANT, BRAND, second);
        Fact replacement =
                AuditTrail.facts(jdbc, "loyalty.accrual_rule.activated").get(1);
        assertThat(replacement.before("rateBasisPoints").asInt()).isEqualTo(300);
        assertThat(replacement.after("rateBasisPoints").asInt()).isEqualTo(500);
        assertThat(replacement.before("ruleId").asText()).isEqualTo(first.toString());
        assertThat(replacement.after("ruleId").asText()).isEqualTo(second.toString());

        loyalty.retireAccrualRule(TENANT, BRAND, second);
        Fact retired = AuditTrail.only(jdbc, "loyalty.accrual_rule.retired");
        assertThat(retired.targetId()).isEqualTo(second);
        assertThat(retired.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(retired.after("status").asText()).isEqualTo("RETIRED");

        theEvidenceIsClean();
    }

    @Test
    @DisplayName("a redemption policy's draft, activation and retirement are audited with the cap before and after")
    void redemptionPolicyLifecycleIsAudited() {
        UUID first =
                loyalty.draftRedemptionPolicy(TENANT, BRAND, redemption(2_000)).id();
        Fact drafted = AuditTrail.only(jdbc, "loyalty.redemption_policy.drafted");
        assertThat(drafted.targetType()).isEqualTo("loyalty.redemption-policy");
        assertThat(drafted.after("maxShareBasisPoints").asInt()).isEqualTo(2_000);
        assertThat(drafted.after("excludesDeliveryFee").asBoolean()).isTrue();

        loyalty.activateRedemptionPolicy(TENANT, BRAND, first);
        UUID second =
                loyalty.draftRedemptionPolicy(TENANT, BRAND, redemption(4_000)).id();
        loyalty.activateRedemptionPolicy(TENANT, BRAND, second);

        Fact replacement =
                AuditTrail.facts(jdbc, "loyalty.redemption_policy.activated").get(1);
        assertThat(replacement.before("maxShareBasisPoints").asInt()).isEqualTo(2_000);
        assertThat(replacement.after("maxShareBasisPoints").asInt()).isEqualTo(4_000);

        loyalty.retireRedemptionPolicy(TENANT, BRAND, second);
        Fact retired = AuditTrail.only(jdbc, "loyalty.redemption_policy.retired");
        assertThat(retired.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(retired.after("status").asText()).isEqualTo("RETIRED");

        theEvidenceIsClean();
    }

    // ----------------------------------------------------------------- referral

    @Test
    @DisplayName(
            "a referral program's draft, activation and retirement are audited, and activation names the rewards it replaced")
    void referralProgramLifecycleIsAudited() {
        UUID first =
                referrals.draftProgram(TENANT, BRAND, program(5_000, 5_000)).id();
        Fact drafted = AuditTrail.only(jdbc, "referral.program.drafted");
        assertThat(drafted.actorSubject()).isEqualTo(AUTHOR);
        assertThat(drafted.scopeType()).isEqualTo("BRAND");
        assertThat(drafted.targetType()).isEqualTo("referral.program");
        assertThat(drafted.targetId()).isEqualTo(first);
        assertThat(drafted.after("referrerRewardMinor").asLong()).isEqualTo(5_000L);
        assertThat(drafted.after("rewardCurrency").asText()).isEqualTo("UZS");

        referrals.activateProgram(TENANT, BRAND, first);
        UUID second =
                referrals.draftProgram(TENANT, BRAND, program(50_000, 10_000)).id();
        referrals.activateProgram(TENANT, BRAND, second);

        Fact replacement = AuditTrail.facts(jdbc, "referral.program.activated").get(1);
        assertThat(replacement.before("referrerRewardMinor").asLong()).isEqualTo(5_000L);
        assertThat(replacement.after("referrerRewardMinor").asLong()).isEqualTo(50_000L);
        assertThat(replacement.before("programId").asText()).isEqualTo(first.toString());
        assertThat(replacement.after("status").asText()).isEqualTo("ACTIVE");

        referrals.retireProgram(TENANT, BRAND, second);
        Fact retired = AuditTrail.only(jdbc, "referral.program.retired");
        assertThat(retired.before("status").asText()).isEqualTo("ACTIVE");
        assertThat(retired.after("status").asText()).isEqualTo("RETIRED");

        theEvidenceIsClean();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What would still be true if the audit were broken: a fact with a name-redacted value looks
     * like evidence and records nothing, so no stored change document may hold the redaction
     * marker; and no stored fact may hold an e-mail address, a phone number, or a string the test
     * knows is a secret.
     */
    private void theEvidenceIsClean(String... secrets) {
        List<String> documents = jdbc.sql("SELECT COALESCE(change_document::text, '') FROM audit.audit_events")
                .query(String.class)
                .list();
        assertThat(documents).isNotEmpty();
        for (String document : documents) {
            assertThat(document)
                    .as("a redacted value means a field name collided with ChangeDocuments' protected terms")
                    .doesNotContain("[redacted]");
            assertThat(EMAIL.matcher(document).find()).as(document).isFalse();
            assertThat(PHONE.matcher(document).find()).as(document).isFalse();
            for (String secret : secrets) {
                assertThat(document).doesNotContain(secret);
            }
        }
        List<String> reasons = jdbc.sql("SELECT COALESCE(reason, '') FROM audit.audit_events")
                .query(String.class)
                .list();
        assertThat(reasons).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    private int currentVersion(SalesChannel channel) {
        return channels.require(TENANT, channel.id()).version();
    }

    private void registerPaymentMethod(String code) {
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, :code, :code, 'OPERATOR', 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("code", code)
                .update();
    }

    private static SalesChannelService.CreateChannelCommand command(String code, String type, String name) {
        return new SalesChannelService.CreateChannelCommand(code, type, name, null, false, true, null);
    }

    private static PromoCodeDraft promoDraft(String code, long basisPoints) {
        return new PromoCodeDraft(
                "Summer " + code.substring(code.length() - 4),
                code,
                DiscountShape.PERCENTAGE_OFF_ORDER,
                basisPoints,
                null,
                "UZS",
                0,
                List.of(),
                List.of(),
                null,
                1,
                null,
                null);
    }

    private static AccrualRuleDraft accrual(int rateBasisPoints) {
        return new AccrualRuleDraft("BRAND", null, rateBasisPoints, 30_000L, 24, 180, 14, null, null);
    }

    private static RedemptionPolicyDraft redemption(int maxShareBasisPoints) {
        return new RedemptionPolicyDraft(maxShareBasisPoints, 50_000L, true, List.of(), null, null);
    }

    private static ProgramDraft program(long referrerRewardMinor, long refereeRewardMinor) {
        return new ProgramDraft("BOTH_SIDES", referrerRewardMinor, refereeRewardMinor, "UZS", null, 14, 90, null, null);
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'config-audit', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC1', 'loc-1', 'Location One', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
