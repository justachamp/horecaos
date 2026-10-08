package uz.horecaos.platform.customers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.api.LeadIntake;
import uz.horecaos.platform.customers.api.LeadSource;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;

/**
 * How another module hands the call centre a guest (ADR 0111 §4): the port the storefront, the
 * Telegram bot and a campaign scenario's call step go through.
 *
 * <p>The scenario case is the one that matters: ADR 0111 has {@code EnqueueCallTaskCommand} arrive
 * under ADR 0005's at-least-once inbox and produce <em>exactly one</em> lead, so the same campaign,
 * step and number is one lead however many times it is delivered -- and a different step, or a
 * different number, is a different one. Asserted against the real unique index, not a mock.
 */
@SpringBootTest
class LeadIntakeTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c1");
    private static final UUID CAMPAIGN = UUID.fromString("018f9f10-9000-7000-8000-0000000000d1");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the lead intake test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private LeadIntake intake;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        fixtures.tenant(TENANT, "lead-intake", BRAND, LOCATION);
    }

    @Test
    @DisplayName("a scenario step delivered twice is one lead; another step or another number is another")
    void aScenarioStepIsOneLeadHoweverOftenItIsDelivered() {
        UUID first = intake.registerLead(scenario("+998 90 123 45 67", 2));
        UUID redelivered = intake.registerLead(scenario("+998901234567", 2));
        UUID nextStep = intake.registerLead(scenario("+998 90 123 45 67", 3));
        UUID otherGuest = intake.registerLead(scenario("+998 91 555 00 11", 2));

        assertThat(redelivered)
                .as("the same campaign, step and number -- in a different spelling -- collapses")
                .isEqualTo(first);
        assertThat(nextStep).isNotEqualTo(first);
        assertThat(otherGuest).isNotEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.leads")
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
        assertThat(jdbc.sql("SELECT source || ':' || origin_campaign_id || ':' || origin_step_sequence "
                                + "FROM customer.leads WHERE id = :id")
                        .param("id", first)
                        .query(String.class)
                        .single())
                .isEqualTo("CAMPAIGN_SCENARIO:" + CAMPAIGN + ":2");
        assertThat(jdbc.sql("SELECT count(*) FROM integration.outbox_events WHERE event_type = 'LeadRegistered'")
                        .query(Long.class)
                        .single())
                .as("a redelivery publishes nothing the first delivery had not")
                .isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.lead.registered'")
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("only a scenario carries a campaign and a step, and it always does")
    void theCampaignPairBelongsToTheScenarioAlone() {
        assertThatThrownBy(() -> intake.registerLead(new LeadIntake.Registration(
                        TENANT,
                        BRAND,
                        LeadSource.CAMPAIGN_SCENARIO,
                        "+998901234567",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "campaign-scenario")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intake.registerLead(new LeadIntake.Registration(
                        TENANT,
                        BRAND,
                        LeadSource.CALLBACK_REQUEST,
                        "+998901234567",
                        null,
                        null,
                        null,
                        CAMPAIGN,
                        1,
                        "telegram-bot")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.leads")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("an ordinary intake is not idempotent: two asks are two leads, and an unusable number is refused")
    void anOrdinaryIntakeIsNotDeduplicated() {
        LeadIntake.Registration request = new LeadIntake.Registration(
                TENANT, BRAND, LeadSource.TELEGRAM_BOT, "+998901234567", null, null, null, null, null, "telegram-bot");

        UUID one = intake.registerLead(request);
        UUID two = intake.registerLead(request);

        assertThat(one)
                .as("deduplicating two messages from one chat is a hint for an operator, never an automatic merge")
                .isNotEqualTo(two);
        assertThatThrownBy(() -> intake.registerLead(new LeadIntake.Registration(
                        TENANT, BRAND, LeadSource.TELEGRAM_BOT, "nope", null, null, null, null, null, "telegram-bot")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("nope");
    }

    private static LeadIntake.Registration scenario(String phone, int step) {
        return new LeadIntake.Registration(
                TENANT,
                BRAND,
                LeadSource.CAMPAIGN_SCENARIO,
                phone,
                null,
                null,
                null,
                CAMPAIGN,
                step,
                "campaign-scenario");
    }
}
