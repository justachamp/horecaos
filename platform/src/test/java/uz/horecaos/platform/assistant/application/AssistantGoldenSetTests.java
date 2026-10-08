package uz.horecaos.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.conversations.api.ConversationParticipant.HandedOff;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Outcome;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Replied;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The golden-set evaluation suite ADR 0069 makes a build gate: "a price question
 * yields the pricing engine's number, an unanswerable question yields a refusal,
 * a cross-tenant probe yields nothing."
 *
 * <p>A prompt, a retrieval change or a model swap changes what customers are told
 * and no compiler notices; this is what stands between a wording change and a
 * regression. It is deliberately <em>not</em> the repository's {@code evals/}
 * directory, which tests this repository's own agent configuration -- a different
 * concern that shares only the technique.
 *
 * <p>The set lives in {@code golden-set.psv} as data, one question per line, so
 * adding a case after an incident is one line and a review. The model here is the
 * deterministic fake, so what is graded is the part this platform owns:
 * classification, retrieval, scoping, grounding, refusal and the PII guard. A real
 * provider is run against the same file in a separate, non-gating job -- that is
 * stage two's pre-work, not this build's.
 *
 * <p>The pass floor is 100%: no case in this file is allowed to fail. A floor
 * below that would be a list of questions the assistant is permitted to get wrong,
 * and there is no such list.
 */
class AssistantGoldenSetTests {

    /** The share of cases that must pass. Raising it is a review; lowering it is not an option. */
    static final double PASS_FLOOR = 1.0;

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AssistantFixture world;
    private AssistantFixture otherTenant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql(
                        "TRUNCATE TABLE assistant.turns, assistant.knowledge_entry_versions, assistant.knowledge_entries CASCADE")
                .update();
        AuditTrail.clear(jdbc);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        world = new AssistantFixture(jdbc, NOW);
        otherTenant = new AssistantFixture(jdbc, NOW);
        world.seedTenancy();
        otherTenant.seedTenancy();

        UUID plovProduct = UUID.randomUUID();
        UUID plovVariant = UUID.randomUUID();
        UUID lagmanProduct = UUID.randomUUID();
        UUID lagmanVariant = UUID.randomUUID();
        for (UUID branch : List.of(world.chilonzor, world.yunusabad)) {
            world.menu.offer(
                    branch,
                    AssistantFixture.dish(plovProduct, "Плов", 45_000, plovVariant),
                    AssistantFixture.dish(lagmanProduct, "Лагман", 38_000, lagmanVariant));
        }

        world.knowledge.create(
                world.tenantId,
                world.brandId,
                null,
                "en",
                "Is there parking at your restaurant?",
                "Yes, free parking behind the building.",
                "author",
                "FAQ");
        world.knowledge.create(
                world.tenantId,
                world.brandId,
                null,
                "ru",
                "Есть ли у вас парковка?",
                "Да, парковка во дворе бесплатная.",
                "author",
                "FAQ");
        world.knowledge.create(
                world.tenantId,
                world.brandId,
                null,
                "en",
                "How many people does the family set feed?",
                "The family set feeds four to five people.",
                "author",
                "FAQ");

        // Another tenant's answers, in the same table, that nobody here may ever see.
        otherTenant.knowledge.create(
                otherTenant.tenantId,
                otherTenant.brandId,
                null,
                "en",
                "Do you have halal certification?",
                "Yes, here is the certificate of tenant B.",
                "b-author",
                "FAQ");
        otherTenant.knowledge.create(
                otherTenant.tenantId,
                otherTenant.brandId,
                null,
                "ru",
                "Есть у вас сертификат халяль?",
                "Да, это сертификат tenant B.",
                "b-author",
                "FAQ");
    }

    record Case(
            String id,
            String question,
            String outcome,
            List<String> mustContain,
            List<String> mustNotContain,
            List<String> mustNotReachProvider) {}

    static List<Case> cases() throws IOException {
        try (InputStream stream = AssistantGoldenSetTests.class.getResourceAsStream("/assistant/golden-set.psv")) {
            String text = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            List<Case> cases = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                assertThat(parts)
                        .as("a golden-set row has six columns: " + line)
                        .hasSize(6);
                cases.add(new Case(parts[0], parts[1], parts[2], split(parts[3]), split(parts[4]), split(parts[5])));
            }
            return cases;
        }
    }

    private static List<String> split(String column) {
        return column.isBlank() ? List.of() : List.of(column.split(";"));
    }

    /** Runs one case against a fresh conversation and returns what, if anything, went wrong. */
    private List<String> grade(Case golden) {
        int callsBefore = world.model.calls();
        Outcome outcome = world.service.offer(world.ask(UUID.randomUUID(), golden.question()));
        List<String> failures = new ArrayList<>();
        String said;
        String actual;
        if (outcome instanceof Replied replied) {
            actual = "ANSWERED";
            said = replied.text();
        } else if (outcome instanceof HandedOff handedOff) {
            actual = "HANDED_OFF";
            said = handedOff.text();
        } else {
            actual = "NOT_PARTICIPATING";
            said = "";
        }
        if (!golden.outcome().equals(actual)) {
            failures.add("expected " + golden.outcome() + " but the assistant " + actual);
        }
        for (String expected : golden.mustContain()) {
            if (!said.contains(expected)) {
                failures.add("the reply lacks \"" + expected + "\"");
            }
        }
        for (String forbidden : golden.mustNotContain()) {
            if (said.contains(forbidden)) {
                failures.add("the reply contains \"" + forbidden + "\"");
            }
        }
        if (!golden.mustNotReachProvider().isEmpty()) {
            String sent = world.model.requests().stream()
                    .skip(callsBefore)
                    .map(request -> request.turns().toString() + request.facts() + request.customerPseudonym())
                    .reduce("", String::concat);
            for (String forbidden : golden.mustNotReachProvider()) {
                if (sent.contains(forbidden)) {
                    failures.add("the provider was sent \"" + forbidden + "\"");
                }
            }
        }
        // Grounding is not a property of any one case: whatever was answered must be traceable.
        if ("ANSWERED".equals(actual)) {
            var turn = world.ledger().getLast();
            if (!"ANSWERED".equals(String.valueOf(turn.get("outcome")))
                    || String.valueOf(turn.get("cited")).equals("[]")) {
                failures.add("an answer with no cited fact in its ledger row");
            }
        }
        return failures;
    }

    @TestFactory
    @DisplayName("every golden case holds, one test each")
    Stream<DynamicTest> everyCase() throws IOException {
        return cases().stream()
                .map(golden -> DynamicTest.dynamicTest(golden.id() + ": " + golden.question(), () -> {
                    world.mutableClock.advance(java.time.Duration.ofMinutes(2));
                    assertThat(grade(golden)).as(golden.id()).isEmpty();
                }));
    }

    @Test
    @DisplayName("the pass rate over the whole set is at or above the floor, and the set is not empty or tiny")
    void theSetClearsItsFloor() throws IOException {
        List<Case> all = cases();
        assertThat(all)
                .as("a golden set that shrank has stopped guarding anything")
                .hasSizeGreaterThanOrEqualTo(25);

        List<String> failed = new ArrayList<>();
        for (Case golden : all) {
            world.mutableClock.advance(java.time.Duration.ofMinutes(2));
            List<String> failures = grade(golden);
            if (!failures.isEmpty()) {
                failed.add(golden.id() + " -> " + failures);
            }
        }
        double passRate = (all.size() - failed.size()) / (double) all.size();

        assertThat(passRate).as("pass rate; failing cases: " + failed).isGreaterThanOrEqualTo(PASS_FLOOR);
        assertThat(PASS_FLOOR).as("the floor may only rise").isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "the set covers every refusal reason a question can reach without provider help, and every retrieval kind")
    void theSetCoversWhatItMustCover() throws IOException {
        String everything =
                String.join("\n", cases().stream().map(Case::question).toList());

        assertThat(cases()).extracting(Case::outcome).contains("ANSWERED", "HANDED_OFF");
        assertThat(cases().stream().map(Case::id).toList())
                .anyMatch(id -> id.startsWith("price-"))
                .anyMatch(id -> id.startsWith("unknown-dish"))
                .anyMatch(id -> id.startsWith("cross-tenant"))
                .anyMatch(id -> id.startsWith("hours"))
                .anyMatch(id -> id.startsWith("branches"))
                .anyMatch(id -> id.startsWith("knowledge"))
                .anyMatch(id -> id.startsWith("order"))
                .anyMatch(id -> id.startsWith("coverage"))
                .anyMatch(id -> id.startsWith("complaint"))
                .anyMatch(id -> id.startsWith("refund"))
                .anyMatch(id -> id.startsWith("human"))
                .anyMatch(id -> id.contains("injection"));
        assertThat(everything).contains("Сколько").contains("narxi").contains("How much");
    }
}
