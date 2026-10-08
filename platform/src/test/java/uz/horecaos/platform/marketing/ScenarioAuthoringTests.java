package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.PURPOSE;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.offerStep;
import static uz.horecaos.platform.marketing.ScenarioHarness.smsStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.application.OfferService.OfferDraft;
import uz.horecaos.platform.marketing.application.ScenarioService.ScenarioDraft;
import uz.horecaos.platform.marketing.application.ScenarioService.StepDraft;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a scenario and holding it to what ADR 0112 says a version is: steps editable
 * only while a draft, a revision a new draft with its own approval that halts what it
 * replaces on starting, and a scenario that cannot be launched into a step it could never send.
 */
class ScenarioAuthoringTests {

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for scenario tests");
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

    // ------------------------------------------------------------- versions

    @Test
    @DisplayName("a draft's steps are replaceable, and an approved or running scenario's are not")
    void stepsAreFixedOnceTheScenarioLeavesDraft() {
        UUID scenario = h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 60));

        h.scenarioService.replaceSteps(
                TENANT,
                BRAND,
                scenario,
                List.of(
                        smsStep("MARKETING_PROMOTION", 0),
                        smsStep("MARKETING_PROMOTION", 60),
                        smsStep("MARKETING_PROMOTION", 120)),
                h.author,
                "corr");
        assertThat(h.scenarioStore.steps(TENANT, scenario)).hasSize(3);
        assertThat(AuditTrail.facts(h.jdbc, "MARKETING_SCENARIO_STEPS_REPLACED"))
                .hasSize(1);

        h.approved(scenario);
        assertRefused(
                () -> h.scenarioService.replaceSteps(
                        TENANT, BRAND, scenario, List.of(smsStep("MARKETING_PROMOTION", 0)), h.author, "corr"),
                ErrorCode.UNPROCESSABLE_STATE,
                "revise");
        assertThat(h.scenarioStore.steps(TENANT, scenario)).hasSize(3);

        h.campaigns.start(TENANT, scenario);
        assertRefused(
                () -> h.scenarioService.replaceSteps(
                        TENANT, BRAND, scenario, List.of(smsStep("MARKETING_PROMOTION", 0)), h.author, "corr"),
                ErrorCode.UNPROCESSABLE_STATE,
                "SENDING");
        assertThat(h.scenarioStore.steps(TENANT, scenario)).hasSize(3);
    }

    @Test
    @DisplayName("steps of an approved scenario cannot be changed behind the service's back either")
    void theDatabaseRefusesToChangeAnApprovedScenariosSteps() {
        UUID scenario = h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));

        assertThatThrownBy(() -> h.jdbc.sql("DELETE FROM marketing.scenario_steps WHERE campaign_id = :id")
                        .param("id", scenario)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DRAFT");
        assertThatThrownBy(() -> h.jdbc.sql("""
                                INSERT INTO marketing.scenario_steps
                                    (id, tenant_id, brand_id, campaign_id, sequence, channel, template_key)
                                VALUES (gen_random_uuid(), :tenant, :brand, :id, 2, 'SMS', 'T')
                                """)
                        .param("tenant", TENANT)
                        .param("brand", BRAND)
                        .param("id", scenario)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DRAFT");
        assertThatThrownBy(() -> h.jdbc.sql(
                                "UPDATE marketing.scenario_steps SET wait_after_previous_seconds = 5 WHERE campaign_id = :id")
                        .param("id", scenario)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DRAFT");
        assertThat(h.scenarioStore.steps(TENANT, scenario)).hasSize(1);
        assertThat(h.scenarioStore.steps(TENANT, scenario).getFirst().waitAfterPreviousSeconds())
                .isZero();
    }

    @Test
    @DisplayName(
            "revising makes a new draft that needs its own approval, and starting it halts the version it replaces")
    void aRevisionNeedsItsOwnApprovalAndReplacesTheOriginalWhenItStarts() {
        h.reachableGuest("+998901100001");
        UUID original = h.launched(
                h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), smsStep("MARKETING_PROMOTION", 3600)));

        UUID revision = h.scenarioService.revise(
                TENANT, BRAND, original, UUID.fromString(h.author.subject()), h.author, "corr");

        CampaignRow row = h.campaignStore.find(TENANT, revision).orElseThrow();
        assertThat(row.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(row.isScenario()).isTrue();
        assertThat(row.supersedesCampaignId()).isEqualTo(original);
        assertThat(h.scenarioStore.steps(TENANT, revision))
                .extracting(step -> step.sequence() + ":" + step.channel() + ":" + step.waitAfterPreviousSeconds())
                .containsExactly("1:SMS:0", "2:SMS:3600");
        assertThat(h.scenarioStore.steps(TENANT, revision).stream().map(step -> step.id()))
                .doesNotContainAnyElementsOf(h.scenarioStore.steps(TENANT, original).stream()
                        .map(step -> step.id())
                        .toList());
        assertThat(AuditTrail.facts(h.jdbc, "MARKETING_SCENARIO_REVISED")).hasSize(1);

        // Nothing is inherited from the first approval: this version has none, so it can be
        // neither approved nor started yet, and the original keeps running meanwhile.
        assertThat(h.campaigns.approve(
                        TENANT,
                        revision,
                        UUID.fromString(h.approver.subject()),
                        UUID.randomUUID(),
                        h.approver,
                        "Looks fine",
                        "corr"))
                .isFalse();
        assertThat(h.campaigns.start(TENANT, revision)).isFalse();
        assertThat(h.campaignStore.find(TENANT, original).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);

        // The revision's steps can be edited as a draft, and only then approved and started.
        h.scenarioService.replaceSteps(
                TENANT, BRAND, revision, List.of(smsStep("MARKETING_PROMOTION", 0)), h.author, "corr");
        h.launched(revision);

        assertThat(h.campaignStore.find(TENANT, revision).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);
        CampaignRow replaced = h.campaignStore.find(TENANT, original).orElseThrow();
        assertThat(replaced.status()).isEqualTo(CampaignStatus.HALTED_OPERATOR);
        assertThat(replaced.haltedReason()).contains("Superseded");
        // The original's guests are no longer decided: it is not sending.
        assertThat(h.decide(original)).isZero();
        // And the first version's own steps are as they were.
        assertThat(h.scenarioStore.steps(TENANT, original)).hasSize(2);
    }

    @Test
    @DisplayName("only a scenario past draft is revised, and only a scenario of this brand")
    void revisionPreconditions() {
        UUID draft = h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0));
        assertRefused(
                () -> h.scenarioService.revise(
                        TENANT, BRAND, draft, UUID.fromString(h.author.subject()), h.author, "corr"),
                ErrorCode.UNPROCESSABLE_STATE,
                "draft is edited in place");

        UUID approved = h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        assertRefused(
                () -> h.scenarioService.revise(
                        TENANT, OTHER_BRAND, approved, UUID.fromString(h.author.subject()), h.author, "corr"),
                ErrorCode.RESOURCE_NOT_FOUND,
                "No scenario");
    }

    // ------------------------------------------------------------ validation

    @Test
    @DisplayName("a call-centre step is refused by name: the lead queue it hands off to does not exist yet")
    void aCallCentreStepIsRefused() {
        assertRefused(
                () -> create(List.of(
                        smsStep("MARKETING_PROMOTION", 0), new StepDraft("CALL_CENTRE", null, "CALL", 0, null, null))),
                ErrorCode.VALIDATION_FAILED,
                "CHANNEL_NOT_WIRED");
    }

    @Test
    @DisplayName(
            "a scenario has between one and ten steps, each with a channel, a wait and conditions from closed sets")
    void stepShapeIsValidated() {
        assertRefused(() -> create(List.of()), ErrorCode.VALIDATION_FAILED, "at least one step");
        List<StepDraft> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            eleven.add(smsStep("MARKETING_PROMOTION", 0));
        }
        assertRefused(() -> create(eleven), ErrorCode.VALIDATION_FAILED, "at most 10");
        assertRefused(
                () -> create(List.of(new StepDraft("CARRIER_PIGEON", null, "T", 0, null, null))),
                ErrorCode.VALIDATION_FAILED,
                "CARRIER_PIGEON");
        assertRefused(
                () -> create(List.of(smsStep("MARKETING_PROMOTION", 0, "WHENEVER", null))),
                ErrorCode.VALIDATION_FAILED,
                "WHENEVER");
        assertRefused(
                () -> create(List.of(smsStep("MARKETING_PROMOTION", 0, null, "WHEN_BORED"))),
                ErrorCode.VALIDATION_FAILED,
                "WHEN_BORED");
        assertRefused(
                () -> create(List.of(smsStep("MARKETING_PROMOTION", -1))),
                ErrorCode.VALIDATION_FAILED,
                "waits between");
        assertRefused(
                () -> create(List.of(
                        smsStep("MARKETING_PROMOTION", (int) Duration.ofDays(91).toSeconds()))),
                ErrorCode.VALIDATION_FAILED,
                "waits between");
        assertRefused(
                () -> create(List.of(new StepDraft("SMS", null, null, 0, null, null))),
                ErrorCode.VALIDATION_FAILED,
                "template key");
    }

    @Test
    @DisplayName("a control group is a percentage, an SMS scenario needs a ceiling, and the audience is this brand's")
    void scenarioShapeIsValidated() {
        UUID audience = h.everybody();
        List<StepDraft> steps = List.of(smsStep("MARKETING_PROMOTION", 0));
        assertRefused(() -> create(audience, 101, 500_000L, steps), ErrorCode.VALIDATION_FAILED, "between 0 and 100");
        assertRefused(() -> create(audience, -1, 500_000L, steps), ErrorCode.VALIDATION_FAILED, "between 0 and 100");
        assertRefused(() -> create(audience, null, null, steps), ErrorCode.VALIDATION_FAILED, "cost ceiling");
        assertRefused(
                () -> create(UUID.randomUUID(), null, 500_000L, steps), ErrorCode.VALIDATION_FAILED, "No audience");
        assertThat(create(audience, 0, 500_000L, steps)).isNotNull();
        assertThat(create(audience, 100, 500_000L, steps)).isNotNull();
        assertThat(create(audience, null, 500_000L, steps)).isNotNull();
    }

    @Test
    @DisplayName(
            "an in-app step needs an offer, and an offer must be published, this brand's, in window and allowed on the channel")
    void offerReferencesAreValidated() {
        assertRefused(
                () -> create(List.of(new StepDraft("IN_APP", null, "BANNER", 0, null, null))),
                ErrorCode.VALIDATION_FAILED,
                "names no offer");
        assertRefused(
                () -> create(List.of(offerStep("SMS", UUID.randomUUID(), 0))), ErrorCode.VALIDATION_FAILED, "No offer");

        UUID draftOffer = draftOffer(List.of("SMS"));
        assertRefused(
                () -> create(List.of(offerStep("SMS", draftOffer, 0))),
                ErrorCode.VALIDATION_FAILED,
                "only a published offer");

        UUID inAppOnly = h.offerService
                .publish(
                        TENANT,
                        BRAND,
                        draftOffer(List.of("IN_APP")),
                        1,
                        h.approver,
                        UUID.fromString(h.approver.subject()),
                        "corr")
                .id();
        assertRefused(
                () -> create(List.of(offerStep("SMS", inAppOnly, 0))), ErrorCode.VALIDATION_FAILED, "not allowed in");

        UUID good = h.publishedOffer("Autumn ten");
        assertThat(create(List.of(offerStep("SMS", good, 0), offerStep("IN_APP", good, 60))))
                .isNotNull();

        // An offer of the brand's sibling is not this brand's, even by its id.
        UUID siblingPromotion = h.seedPromotion(OTHER_BRAND, "ACTIVE");
        var sibling = h.offerService.create(
                TENANT,
                OTHER_BRAND,
                new OfferDraft(
                        "Sibling",
                        siblingPromotion,
                        null,
                        START.minus(Duration.ofDays(1)),
                        null,
                        null,
                        List.of("SMS"),
                        "MARKETING_PROMOTION",
                        null,
                        null),
                h.author,
                UUID.fromString(h.author.subject()),
                "corr");
        h.offerService.publish(
                TENANT,
                OTHER_BRAND,
                sibling.id(),
                sibling.rowVersion(),
                h.approver,
                UUID.fromString(h.approver.subject()),
                "corr");
        assertRefused(
                () -> create(List.of(offerStep("SMS", sibling.id(), 0))), ErrorCode.VALIDATION_FAILED, "No offer");
    }

    // ---------------------------------------------------------------- launch

    @Test
    @DisplayName("a scenario cannot be launched into a channel that cannot deliver, naming the step and the reason")
    void launchRefusesAnUnwiredChannel() {
        UUID scenario = h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.port.unwire();

        assertRefused(() -> h.campaigns.start(TENANT, scenario), ErrorCode.UNPROCESSABLE_STATE, "step 1");
        assertThat(h.campaignStore.find(TENANT, scenario).orElseThrow().status())
                .isEqualTo(CampaignStatus.APPROVED);
    }

    @Test
    @DisplayName(
            "a scenario is launched against the marketing purpose, so an account not cleared for it refuses with the stable code")
    void launchAsksTheMarketingPurpose() {
        UUID scenario = h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0)));
        h.port.refusingPurpose(CampaignMessagePort.PURPOSE_MARKETING);

        assertRefused(
                () -> h.campaigns.start(TENANT, scenario), ErrorCode.UNPROCESSABLE_STATE, "SMS_PURPOSE_NOT_PERMITTED");
        assertThat(h.port.wiringAsked()).contains("SMS/" + CampaignMessagePort.PURPOSE_MARKETING);
    }

    @Test
    @DisplayName(
            "a newer version of an offer published between approval and launch does not stop the launch: the scenario was approved with the old one")
    void launchKeepsTheVersionTheScenarioWasApprovedWith() {
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario =
                h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), offerStep("SMS", offer, 60)));
        h.publishedNewVersion(offer);
        assertThat(h.offerService.require(TENANT, BRAND, offer).status()).isEqualTo("SUPERSEDED");

        assertThat(h.campaigns.start(TENANT, scenario)).isTrue();

        assertThat(h.campaignStore.find(TENANT, scenario).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);
        // But the superseded version is not on offer to a new author: a step names what is in force.
        assertRefused(
                () -> create(List.of(offerStep("SMS", offer, 0))), ErrorCode.VALIDATION_FAILED, "only a published");
    }

    @Test
    @DisplayName("an offer retired between approval and launch stops the launch rather than the third step")
    void launchRefusesARetiredOffer() {
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario =
                h.approved(h.draftScenario(null, smsStep("MARKETING_PROMOTION", 0), offerStep("SMS", offer, 60)));
        h.offerService.retire(
                TENANT,
                BRAND,
                offer,
                h.offerService.require(TENANT, BRAND, offer).rowVersion(),
                h.author,
                "Ended",
                "corr");

        assertRefused(() -> h.campaigns.start(TENANT, scenario), ErrorCode.UNPROCESSABLE_STATE, "not in force");
    }

    // ---------------------------------------------------------------- helpers

    private UUID create(List<StepDraft> steps) {
        return create(h.everybody(), null, 500_000L, steps);
    }

    private UUID create(
            UUID audience, @Nullable Integer controlGroupPercent, @Nullable Long ceiling, List<StepDraft> steps) {
        return h.scenarioService.create(
                TENANT,
                BRAND,
                new ScenarioDraft(
                        "Win-back " + UUID.randomUUID(),
                        audience,
                        PURPOSE,
                        1_000,
                        ceiling,
                        "UZS",
                        controlGroupPercent,
                        null,
                        steps),
                UUID.fromString(h.author.subject()),
                h.author,
                "corr");
    }

    private UUID draftOffer(List<String> channels) {
        return h.offerService
                .create(
                        TENANT,
                        BRAND,
                        new OfferDraft(
                                "Offer " + UUID.randomUUID(),
                                h.seedPromotion("ACTIVE"),
                                null,
                                START.minus(Duration.ofDays(1)),
                                START.plus(Duration.ofDays(30)),
                                null,
                                channels,
                                "MARKETING_PROMOTION",
                                null,
                                null),
                        ActorRef.user(h.author.subject(), "Author"),
                        UUID.fromString(h.author.subject()),
                        "corr")
                .id();
    }

    private static void assertRefused(Runnable action, ErrorCode code, String mentions) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.getMessage()).contains(mentions);
        });
    }
}
