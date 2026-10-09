package uz.horecaos.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.TokenUsage;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.conversations.api.ConversationParticipant.HandedOff;
import uz.horecaos.platform.conversations.api.ConversationParticipant.NotParticipating;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Outcome;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Replied;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.DeliveryBranchMatch;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort.OrderCard;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0069's grounded assistant, end to end through {@link AssistantTurnService}
 * over a real database: every answer traceable to a retrieval, refusal when
 * retrieval is empty, the spend ceiling, the handoff, and what must never leave.
 *
 * <p>Each test states what would still be true if the code under test were
 * broken -- the question CLAUDE.md asks of every assertion -- and the ones that
 * matter most are built around the dishonest model, the empty menu and the
 * exhausted budget, not around the happy path.
 */
class AssistantTurnServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AssistantFixture fx;
    private AssistantTurnService service;
    private UUID conversation;

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
        fx = new AssistantFixture(jdbc, NOW);
        fx.seedTenancy();
        service = fx.service;
        conversation = UUID.randomUUID();
        // The pilot brand: two branches that sell the same dish at the same price.
        fx.menu.offer(fx.chilonzor, AssistantFixture.dish("Плов", 45_000, UUID.randomUUID()));
    }

    private static String text(Outcome outcome) {
        return switch (outcome) {
            case Replied replied -> replied.text();
            case HandedOff handedOff -> handedOff.text();
            case NotParticipating ignored -> throw new AssertionError("expected the assistant to take the turn");
        };
    }

    private String lastOutcome() {
        List<Map<String, Object>> ledger = fx.ledger();
        return String.valueOf(ledger.getLast().get("outcome"));
    }

    private String lastRefusal() {
        return String.valueOf(fx.ledger().getLast().get("refusal_reason"));
    }

    // ============================================================ grounding

    @Test
    @DisplayName(
            "a price question is answered with exactly the number the menu path returned, and the turn records which fact it came from")
    void aPriceIsTheMenuPathsPrice() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(outcome).isInstanceOf(Replied.class);
        assertThat(text(outcome)).contains("45 000 сум").contains("Плов");
        assertThat(fx.model.calls()).isEqualTo(1);

        Map<String, Object> turn = fx.ledger().getLast();
        assertThat(turn.get("outcome")).isEqualTo("ANSWERED");
        assertThat((String) turn.get("facts")).contains("\"kind\": \"PRICE\"").contains("\"amountMinor\": 45000");
        assertThat((String) turn.get("cited")).isEqualTo("[\"f1\"]");

        assertThat(fx.model.lastRequest().facts()).singleElement().satisfies(fact -> {
            assertThat(fact.kind()).isEqualTo("PRICE");
            assertThat(fact.attributes()).containsEntry("price", "45 000 сум").containsEntry("item", "Плов");
        });
    }

    @Test
    @DisplayName(
            "what is said about a price is the price the platform returns: when it changes, the next answer changes with it")
    void thePriceIsReadLiveOnEveryTurn() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"))))
                .contains("45 000");

        fx.menu.offer(fx.chilonzor, AssistantFixture.dish("Плов", 52_000, UUID.randomUUID()));

        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"))))
                .contains("52 000")
                .doesNotContain("45 000");
    }

    @Test
    @DisplayName("a model that invents a price is discarded, paid for, and the customer is handed to a person")
    void aDishonestModelIsCaught() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.model.replyingWith("Плов стоит 38 000 сум", "f1");

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(outcome).isInstanceOf(HandedOff.class);
        assertThat(text(outcome)).doesNotContain("38 000").doesNotContain("45 000");
        assertThat(lastOutcome()).isEqualTo("REFUSED");
        assertThat(lastRefusal()).isEqualTo("UNGROUNDED_REPLY");
        assertThat(fx.ledger().getLast().get("cost_usd_micros"))
                .as("the call happened and cost money whatever was done with its answer")
                .isEqualTo(2_000L + 1_000L);
    }

    @Test
    @DisplayName("a model that cites nothing, or a fact it was not given, is refused the same way")
    void uncitedAndForeignCitationsAreRefused() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        fx.model.replyingWith("Плов стоит 45 000 сум");
        assertThat(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")))
                .isInstanceOf(HandedOff.class);

        fx.model.replyingWith("Плов стоит 45 000 сум", "f7");
        assertThat(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")))
                .isInstanceOf(HandedOff.class);

        assertThat(fx.ledger())
                .extracting(row -> row.get("refusal_reason"))
                .containsExactly("UNGROUNDED_REPLY", "UNGROUNDED_REPLY");
    }

    @Test
    @DisplayName("nothing retrieved is a refusal, offered a person, and the model is never asked")
    void emptyRetrievalIsARefusalWithoutAModelCall() {
        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит бешбармак?"));

        assertThat(outcome).isInstanceOf(HandedOff.class);
        assertThat(text(outcome)).contains("не буду гадать");
        assertThat(fx.model.calls())
                .as("an invented answer costs more than no answer")
                .isZero();
        assertThat(lastOutcome()).isEqualTo("REFUSED");
        assertThat(lastRefusal()).isEqualTo("NO_GROUNDING");
        assertThat(fx.ledger().getLast().get("cost_usd_micros")).isEqualTo(0L);
    }

    @Test
    @DisplayName(
            "a currency the platform has not decided how to read is never quoted, and a dish with no price is never called free")
    void anUnpricedDishIsNotFree() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.menu.currency = "USD";

        Outcome outcome = service.offer(fx.ask(conversation, "How much is the плов?"));

        assertThat(outcome).isInstanceOf(Replied.class);
        assertThat(text(outcome))
                .contains("no price is published")
                .doesNotContainPattern("\\b45\\b")
                .doesNotContain("free");
        assertThat(fx.ledger().getLast().get("facts")).asString().doesNotContain("amountMinor");
    }

    @Test
    @DisplayName("a sold-out dish is reported sold out beside its price, from the menu's own availability")
    void soldOutIsSaid() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.menu.offer(
                fx.chilonzor,
                new uz.horecaos.platform.catalog.api.MenuSearchPort.Dish(
                        UUID.randomUUID(),
                        "Плов",
                        null,
                        "ru",
                        List.of(new uz.horecaos.platform.catalog.api.MenuSearchPort.Form(
                                UUID.randomUUID(), null, true, false, true, 45_000L, null, null))));

        Outcome outcome = service.offer(fx.ask(conversation, "Плов есть?"));

        assertThat(text(outcome)).contains("sold out");
        assertThat(fx.model.lastRequest().facts().getFirst().kind()).isEqualTo("AVAILABILITY");
        assertThat(fx.model.lastRequest().facts().getFirst().attributes()).doesNotContainKey("price");
    }

    @Test
    @DisplayName(
            "with several branches and none named, a price that is the same at every one is a single fact, and one that differs is stated per branch")
    void pricesAcrossBranches() {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        fx.menu.offer(fx.chilonzor, AssistantFixture.dish(product, "Плов", 45_000, variant));
        fx.menu.offer(fx.yunusabad, AssistantFixture.dish(product, "Плов", 45_000, variant));

        service.offer(fx.ask(conversation, "Сколько стоит плов?"));
        assertThat(fx.model.lastRequest().facts())
                .singleElement()
                .satisfies(fact -> assertThat(fact.attributes()).containsEntry("branch", "every branch asked about"));

        fx.menu.offer(fx.yunusabad, AssistantFixture.dish(product, "Плов", 47_000, variant));
        service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));
        assertThat(fx.model.lastRequest().facts())
                .extracting(fact -> fact.attributes().get("branch"))
                .containsExactlyInAnyOrder("Chilonzor", "Yunusabad");
    }

    @Test
    @DisplayName("a branch the question names narrows the answer to that branch")
    void aNamedBranchNarrowsTheScope() {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        fx.menu.offer(fx.chilonzor, AssistantFixture.dish(product, "Плов", 45_000, variant));
        fx.menu.offer(fx.yunusabad, AssistantFixture.dish(product, "Плов", 47_000, variant));

        service.offer(fx.ask(conversation, "Сколько стоит плов в Yunusabad?"));

        assertThat(fx.model.lastRequest().facts()).singleElement().satisfies(fact -> {
            assertThat(fact.attributes()).containsEntry("price", "47 000 сум").containsEntry("branch", "Yunusabad");
        });
        assertThat(fx.menu.termsSearched)
                .as("the branch's name is not part of what the dish is called")
                .allSatisfy(terms -> assertThat(terms).doesNotContain("yunusabad"));
    }

    @Test
    @DisplayName(
            "words around the dish that are not the dish do not stop it being found, and the fact says the match was partial")
    void chatterAroundTheDishStillFindsIt() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов? мой секретный вопрос"));

        assertThat(outcome).isInstanceOf(Replied.class);
        assertThat(fx.model.lastRequest().facts())
                .singleElement()
                .satisfies(fact ->
                        assertThat(fact.attributes()).containsKey("matching").containsEntry("price", "45 000 сум"));
    }

    @Test
    @DisplayName("a dish whose every word matches is not marked partial")
    void anExactMatchIsNotPartial() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(fx.model.lastRequest().facts().getFirst().attributes()).doesNotContainKey("matching");
    }

    // ============================================================== branches

    @Test
    @DisplayName("where the branches are is answered from the tenant's own branch data")
    void branchesAreListed() {
        Outcome outcome = service.offer(fx.ask(conversation, "Where are you?"));

        assertThat(text(outcome))
                .contains("Chilonzor")
                .contains("Bunyodkor ko'chasi 12")
                .contains("Yunusabad");
        assertThat(fx.model.lastRequest().facts())
                .extracting(fact -> fact.kind())
                .containsOnly("BRANCH");
    }

    @Test
    @DisplayName(
            "hours come from the branch's schedule and whether it is open now from the one serviceability resolver")
    void hoursAreFromTheSchedule() {
        Outcome outcome = service.offer(fx.ask(conversation, "What time do you close?"));

        assertThat(text(outcome)).contains("Mon-Fri 09:00-23:00");
        assertThat(fx.model.lastRequest().facts().getFirst().attributes()).containsEntry("rightNow", "open for orders");
    }

    // ================================================================= coverage

    @Test
    @DisplayName(
            "delivery coverage is answered from the zone algorithm for a shared location, and asks for one when there is none")
    void coverage() {
        Outcome askedForOne = service.offer(fx.ask(conversation, "Do you deliver to my place?"));
        assertThat(text(askedForOne)).contains("share their location");
        assertThat(fx.coverage.asked.get())
                .as("no point, so the zone algorithm is not asked")
                .isZero();

        fx.coverage.matches =
                List.of(new DeliveryBranchMatch(fx.chilonzor, "Chilonzor", UUID.randomUUID(), 3, 10, 5_000));
        Outcome located = service.offer(fx.shareLocation(UUID.randomUUID(), null));

        assertThat(located).isInstanceOf(Replied.class);
        assertThat(text(located)).contains("Chilonzor").contains("delivers");
        assertThat(fx.coverage.asked.get()).isEqualTo(1);

        fx.coverage.matches = List.of();
        assertThat(text(service.offer(fx.shareLocation(UUID.randomUUID(), null))))
                .contains("none of this brand's branches");
    }

    // =================================================================== orders

    @Test
    @DisplayName(
            "an order is read only for the customer account the channel proved, and a chat with no proven account is told so")
    void ordersAreReadOnlyForTheProvenIdentity() {
        UUID account = UUID.randomUUID();
        fx.orders.card = new OrderCard(
                UUID.randomUUID(),
                "A-1043",
                "PREPARING",
                "UZS",
                98_000,
                NOW.plus(Duration.ofMinutes(30)),
                true,
                false,
                false);

        Outcome linked = service.offer(fx.ask(UUID.randomUUID(), "Where is my order?", account));

        assertThat(text(linked)).contains("A-1043").contains("being prepared in the kitchen");
        assertThat(fx.orders.askedFor).containsExactly(account);

        fx.orders.askedFor.clear();
        Outcome anonymous = service.offer(fx.ask(UUID.randomUUID(), "Where is my order?", null));

        assertThat(text(anonymous)).contains("not linked to a customer account");
        assertThat(fx.orders.askedFor).as("no proven identity, no order read").isEmpty();
    }

    @Test
    @DisplayName("a reply built from a customer's own order is never served to anyone else from the cache")
    void orderRepliesAreNotCached() {
        UUID account = UUID.randomUUID();
        fx.orders.card =
                new OrderCard(UUID.randomUUID(), "A-1043", "PREPARING", "UZS", 98_000, null, true, false, false);

        service.offer(fx.ask(UUID.randomUUID(), "Where is my order?", account));
        service.offer(fx.ask(UUID.randomUUID(), "Where is my order?", account));

        assertThat(fx.model.calls()).isEqualTo(2);
    }

    // ================================================================ escalation

    @Test
    @DisplayName("a complaint, a refund or a request for a person is handed over before anything is retrieved or asked")
    void personTopicsAreHandedOverUnasked() {
        for (String question :
                List.of("У меня жалоба, это ужасно", "I want a refund", "Позовите оператора, пожалуйста")) {
            Outcome outcome = service.offer(fx.ask(UUID.randomUUID(), question));

            assertThat(outcome).as(question).isInstanceOf(HandedOff.class);
            assertThat(lastOutcome()).isEqualTo("ESCALATED");
        }
        assertThat(fx.model.calls()).isZero();
        assertThat(fx.menu.searches.get()).as("nothing was retrieved either").isZero();
        assertThat(fx.ledger())
                .allSatisfy(row -> assertThat(row.get("cost_usd_micros")).isEqualTo(0L));
    }

    @Test
    @DisplayName("the handoff tells the truth about whether anyone is there: presence online, or nobody")
    void handoffTellsTheTruthAboutPresence() {
        fx.presence.someoneOnline = false;
        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора"))))
                .contains("никого из команды нет онлайн")
                .doesNotContain("ответит вам здесь");

        fx.presence.someoneOnline = true;
        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора"))))
                .contains("ответит вам здесь")
                .doesNotContain("никого");
    }

    // ================================================================== spend

    @Test
    @DisplayName(
            "the spend ceiling refuses with its own stable reason, before any retrieval or model call, and hands over")
    void theSpendCeilingRefuses() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        // The default ceiling is 2 500 cents = 25 000 000 micro-dollars; this month already spent exactly that.
        fx.spend(25_000_000L, NOW.minus(Duration.ofDays(2)));

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(outcome).isInstanceOf(HandedOff.class);
        assertThat(text(outcome)).contains("Сейчас я не могу ответить");
        assertThat(fx.model.calls()).isZero();
        assertThat(fx.menu.searches.get()).isZero();
        assertThat(lastRefusal()).isEqualTo("SPEND_CEILING");
    }

    @Test
    @DisplayName(
            "one micro-dollar under the ceiling still answers, last month's spend does not count, and nor does another tenant's")
    void theCeilingIsTheTenantsOwnCalendarMonth() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.spend(24_999_999L, NOW.minus(Duration.ofDays(2)));
        assertThat(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")))
                .isInstanceOf(Replied.class);

        // Month rollover: the same spend, a month later, is a fresh budget.
        fx.mutableClock.set(Instant.parse("2026-11-01T00:00:01Z"));
        fx.spend(100_000_000L, Instant.parse("2026-10-31T23:59:59Z"));
        assertThat(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")))
                .as("the previous month's overspend belongs to the previous month")
                .isInstanceOf(Replied.class);
    }

    @Test
    @DisplayName("a tenant with a ceiling of zero cents is paused: the assistant refuses everything and spends nothing")
    void aZeroCeilingPausesTheAssistant() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.configuration.put("assistant.monthly_spend_ceiling_usd_cents", 0L);
        AssistantTurnService paused = fx.rebuild();

        assertThat(paused.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(HandedOff.class);
        assertThat(fx.model.calls()).isZero();
        assertThat(lastRefusal()).isEqualTo("SPEND_CEILING");
    }

    @Test
    @DisplayName("each model call is costed from its reported tokens in integer micro-dollars and ledgered")
    void spendIsLedgered() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.model.using(new TokenUsage(1_500, 200));

        service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        Map<String, Object> turn = fx.ledger().getLast();
        assertThat(turn.get("input_tokens")).isEqualTo(1_500L);
        assertThat(turn.get("output_tokens")).isEqualTo(200L);
        assertThat(turn.get("cost_usd_micros")).isEqualTo(1_500L * 2 + 200L * 10);
        assertThat(turn.get("model_id")).isEqualTo("fake-model-1");
        assertThat(fx.usage.recorded).singleElement().satisfies(movement -> {
            assertThat(movement.key().code()).isEqualTo("assistant.turns_monthly_included");
            assertThat(movement.quantity()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a plan allowance that is exhausted and enforced refuses with its own reason")
    void anExhaustedAllowanceRefuses() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.entitlements.allowanceExhausted = true;

        assertThat(service.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(HandedOff.class);
        assertThat(fx.model.calls()).isZero();
        assertThat(lastRefusal()).isEqualTo("ENTITLEMENT_LIMIT");
    }

    @Test
    @DisplayName("the conversation turn cap hands over, and a day later the conversation may be answered again")
    void theTurnCap() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.configuration.put("assistant.conversation_turn_cap", 2);
        AssistantTurnService capped = fx.rebuild();

        assertThat(capped.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(Replied.class);
        fx.mutableClock.advance(Duration.ofMinutes(2));
        assertThat(capped.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(Replied.class);
        fx.mutableClock.advance(Duration.ofMinutes(2));

        Outcome third = capped.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(third).isInstanceOf(HandedOff.class);
        assertThat(lastRefusal()).isEqualTo("TURN_CAP");

        fx.mutableClock.advance(Duration.ofHours(25));
        assertThat(capped.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(Replied.class);
    }

    @Test
    @DisplayName("a burst of messages is dropped without a word, and nothing is sent to the provider for the excess")
    void aBurstIsRateLimited() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.configuration.put("assistant.conversation_turn_cap", 1_000);
        AssistantTurnService burst = fx.rebuild();

        int replies = 0;
        Outcome last = null;
        for (int i = 0; i < 9; i++) {
            last = burst.offer(fx.ask(conversation, "Сколько стоит плов?"));
            if (last instanceof Replied) {
                replies++;
            }
        }

        assertThat(replies).as("six a minute per conversation").isEqualTo(6);
        assertThat(last).isInstanceOf(NotParticipating.class);
        assertThat(fx.model.calls()).isLessThanOrEqualTo(6);
        assertThat(lastOutcome()).isEqualTo("DECLINED");
        assertThat(lastRefusal()).isEqualTo("RATE_LIMITED");
    }

    // ============================================================= provider

    @Test
    @DisplayName("a provider outage degrades into an honest handoff, with its own reason, and costs nothing")
    void aProviderOutageHandsOver() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.model.failing("PROVIDER_TIMEOUT", true);

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        assertThat(outcome).isInstanceOf(HandedOff.class);
        assertThat(lastRefusal()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(fx.ledger().getLast().get("cost_usd_micros")).isEqualTo(0L);
        assertThat(fx.meters.find("horecaos.assistant.model.failures").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("the model's own refusal is a refusal, handed over, with its own reason")
    void theModelsRefusalIsPassedOn() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.model.refusing();

        assertThat(service.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(HandedOff.class);
        assertThat(lastRefusal()).isEqualTo("MODEL_REFUSED");
    }

    // ================================================================= gating

    @Test
    @DisplayName(
            "without the entitlement, the switch or a configured provider the assistant is not there: no answer, no ledger row, no call")
    void notParticipatingUnlessEveryGateIsOpen() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        assertThat(service.willingToParticipate(fx.tenantId, fx.brandId)).isTrue();

        fx.entitlements.assistant = false;
        assertThat(service.willingToParticipate(fx.tenantId, fx.brandId)).isFalse();
        assertThat(service.offer(fx.ask(conversation, "Сколько стоит плов?"))).isInstanceOf(NotParticipating.class);
        fx.entitlements.assistant = true;

        fx.entitlements.conversations = false;
        assertThat(service.willingToParticipate(fx.tenantId, fx.brandId))
                .as("the assistant speaks inside ADR 0059's conversations and has nowhere to speak without them")
                .isFalse();
        fx.entitlements.conversations = true;

        fx.configuration.put("assistant.enabled", false);
        assertThat(fx.rebuild().offer(fx.ask(conversation, "Сколько стоит плов?")))
                .isInstanceOf(NotParticipating.class);
        fx.configuration.put("assistant.enabled", true);

        fx.model.unconfigured();
        assertThat(fx.rebuild().willingToParticipate(fx.tenantId, fx.brandId)).isFalse();
        assertThat(fx.withoutProvider().willingToParticipate(fx.tenantId, fx.brandId))
                .isFalse();

        assertThat(fx.ledger()).isEmpty();
        assertThat(fx.model.calls()).isZero();
    }

    @Test
    @DisplayName("the assistant ships off: with nothing configured it does not answer")
    void offByDefault() {
        AssistantFixture untouched = new AssistantFixture(jdbc, NOW);
        untouched.configuration.clear();
        untouched.entitlements.assistant = false;

        assertThat(untouched.rebuild().willingToParticipate(untouched.tenantId, untouched.brandId))
                .isFalse();
    }

    // ================================================================== cache

    @Test
    @DisplayName(
            "identical grounded questions are served from the cache; a changed fact is a different key and asks the model again")
    void theCacheIsKeyedByTheFacts() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        UUID variant = UUID.randomUUID();
        fx.menu.offer(fx.chilonzor, AssistantFixture.dish("Плов", 45_000, variant));

        Outcome first = service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));
        Outcome second = service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));

        assertThat(fx.model.calls())
                .as("the second asker is served from the cache")
                .isEqualTo(1);
        assertThat(text(second)).contains("45 000 сум");
        assertThat(((Replied) first).turnId()).isNotEqualTo(((Replied) second).turnId());
        List<Map<String, Object>> ledger = fx.ledger();
        assertThat(ledger.get(0).get("served_from_cache")).isEqualTo(false);
        assertThat(ledger.get(1).get("served_from_cache")).isEqualTo(true);
        assertThat(ledger.get(1).get("cost_usd_micros")).isEqualTo(0L);
        assertThat(fx.usage.recorded)
                .as("a cache hit costs nothing and is not metered")
                .hasSize(1);

        fx.menu.offer(fx.chilonzor, AssistantFixture.dish("Плов", 47_000, variant));
        Outcome afterPriceChange = service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));

        assertThat(fx.model.calls()).isEqualTo(2);
        assertThat(text(afterPriceChange)).contains("47 000").doesNotContain("45 000");
    }

    @Test
    @DisplayName(
            "the tenant is part of the cache key: another tenant asking the same question is never served this tenant's reply")
    void theCacheIsPerTenant() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));
        assertThat(fx.model.calls()).isEqualTo(1);

        AssistantFixture other = new AssistantFixture(jdbc, NOW);
        other.model.calls();
        // A different tenant, the same caches object: nothing of the first is visible to it.
        AssistantTurnService sharing = new AssistantTurnService(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory() {
                    {
                        registerSingleton("m", other.model);
                    }
                }.getBeanProvider(uz.horecaos.platform.assistant.api.AssistantModelPort.class),
                new AssistantSettings(
                        new uz.horecaos.platform.support.FakeConfigurationResolver(other.configuration),
                        other.entitlements),
                new RetrievalService(
                        other.branches,
                        other.menu,
                        other.new ChannelLookup(),
                        other.new OpenWhenAsked(),
                        other.coverage,
                        other.orders,
                        other.knowledge,
                        other.clock),
                new TurnRecorder(other.turns, other.audit),
                other.turns,
                new ProviderPricing(2, 10),
                new AssistantMetrics(other.meters),
                new uz.horecaos.platform.web.cache.InProcessRateLimiter(other.clock),
                fx.caches,
                other.entitlements,
                other.usage,
                other.protection,
                new AssistantFixture.Contacts(),
                uz.horecaos.platform.tenancy.api.BrandLocaleLookup.platformFallback(),
                other.branches,
                other.presence,
                other.clock);
        other.seedTenancy();
        other.branches.only(AssistantFixture.branch(other.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        other.menu.offer(other.chilonzor, AssistantFixture.dish("Плов", 45_000, UUID.randomUUID()));

        sharing.offer(other.ask(UUID.randomUUID(), "Сколько стоит плов?"));

        assertThat(other.model.calls())
                .as("same question, same price, different tenant: asked afresh")
                .isEqualTo(1);
    }

    // ================================================================= privacy

    @Test
    @DisplayName("no phone number, email, handle, address or account id ever reaches the provider")
    void noPersonalValueLeaves() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        UUID account = UUID.randomUUID();
        List<ConversationParticipant.HistoryEntry> history = List.of(
                new ConversationParticipant.HistoryEntry(
                        ConversationParticipant.Author.CUSTOMER,
                        "my number is +998 90 123 45 67",
                        NOW.minusSeconds(60)),
                new ConversationParticipant.HistoryEntry(
                        ConversationParticipant.Author.FLOW, "Thanks! We saved +998901234567", NOW.minusSeconds(50)),
                new ConversationParticipant.HistoryEntry(
                        ConversationParticipant.Author.OPERATOR, "Hello Aziz, this is Dilnoza", NOW.minusSeconds(40)),
                new ConversationParticipant.HistoryEntry(
                        ConversationParticipant.Author.ASSISTANT, "Plov: 45 000 сум", NOW.minusSeconds(30)));

        service.offer(fx.ask(
                conversation,
                "Сколько стоит плов? Пишите на aziz.k@example.uz, ул. Навои 12 кв 5, @aziz_k",
                account,
                history));

        String sent = fx.model.everythingSent();
        assertThat(sent)
                .doesNotContain("998")
                .doesNotContain("90 123")
                .doesNotContain("aziz.k@example.uz")
                .doesNotContain("Навои")
                .doesNotContain("@aziz_k")
                .doesNotContain(account.toString())
                .doesNotContain("Dilnoza")
                .doesNotContain("Thanks!");
        assertThat(sent)
                .contains("[phone]")
                .contains("[email]")
                .contains("[address]")
                .contains("[handle]");
        assertThat(fx.model.lastRequest().customerPseudonym())
                .as("the only identifier of the customer the provider gets is ADR 0029's keyed hash")
                .isNotNull()
                .doesNotContain(account.toString())
                .matches("[A-Za-z0-9_-]{43}");
        assertThat(fx.model.lastRequest().turns())
                .extracting(turn -> turn.role().name())
                .containsExactly("CUSTOMER", "ASSISTANT", "CUSTOMER");
    }

    @Test
    @DisplayName(
            "the ledger and the audit trail hold ids, amounts and codes -- never a word of the question or the reply")
    void noMessageTextIsStoredOutsideTheConversation() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        Outcome answered = service.offer(fx.ask(conversation, "Сколько стоит плов? мой секретный вопрос"));
        service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора, мой секретный вопрос"));

        String ledger = fx.ledger().toString();
        String audit = jdbc.sql(
                        "SELECT string_agg(COALESCE(change_document::text, '') || reason, ' ') FROM audit.audit_events")
                .query(String.class)
                .single();
        for (String forbidden : List.of("секретный", "Сколько стоит", "оператора", "Плов: 45", "стоит")) {
            assertThat(ledger).as("ledger must not contain " + forbidden).doesNotContain(forbidden);
            assertThat(audit).as("audit must not contain " + forbidden).doesNotContain(forbidden);
        }
        assertThat(text(answered)).contains("45 000 сум");
    }

    @Test
    @DisplayName("nothing the customer wrote or the assistant said reaches a log line, on any path, including failures")
    void nothingIsLogged() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        Collector collector = new Collector();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        collector.start();
        root.addAppender(collector);
        try {
            service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов? уникальноеслово1"));
            fx.model.failing("PROVIDER_TIMEOUT", true);
            service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов? уникальноеслово2"));
            fx.model.composingFromFacts();
            fx.model.replyingWith("Плов стоит 99 999 сум уникальныйответ", "f1");
            service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов? уникальноеслово3"));
            service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора уникальноеслово4"));
        } finally {
            root.detachAppender(collector);
        }

        assertThat(collector.messages)
                .noneMatch(message -> message.contains("уникальноеслово")
                        || message.contains("уникальныйответ")
                        || message.contains("99 999")
                        || message.contains("Сколько стоит"));
    }

    @Test
    @DisplayName("the metrics carry codes from closed sets and no tenant, conversation or customer")
    void metricsCarryNoIdentity() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"));
        service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора"));

        assertThat(fx.meters.getMeters()).isNotEmpty().allSatisfy(meter -> {
            assertThat(meter.getId().getName()).startsWith("horecaos.assistant.");
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getKey()).isIn("outcome", "reason", "cache", "direction", "code", "retryable");
                assertThat(tag.getValue()).doesNotContain(fx.tenantId.toString());
            });
        });
        assertThat(count("horecaos.assistant.turns", "outcome", "ANSWERED")).isEqualTo(1.0);
        assertThat(count("horecaos.assistant.turns", "outcome", "ESCALATED")).isEqualTo(1.0);
        assertThat(count("horecaos.assistant.model.tokens", "direction", "input"))
                .isEqualTo(1_000.0);
    }

    // ================================================================== audit

    @Test
    @DisplayName(
            "a turn that quoted a price leaves an ADR 0027 fact with the retrieved inputs, and its keys survive the redaction")
    void aPriceQuoteLeavesAnAuditFact() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        Outcome outcome = service.offer(fx.ask(conversation, "Сколько стоит плов?"));

        AuditTrail.Fact fact = AuditTrail.only(jdbc, "assistant.turn.answered");
        assertThat(fact.actorType()).isEqualTo("SERVICE");
        assertThat(fact.actorSubject()).isEqualTo("assistant");
        assertThat(fact.targetId()).isEqualTo(((Replied) outcome).turnId());
        assertThat(fact.scopeType()).isEqualTo("BRAND");
        assertThat(fact.after("outcome").asString()).isEqualTo("ANSWERED");
        assertThat(fact.after("factRefs").toString())
                .as("the retrieved inputs, not [redacted]")
                .contains("\"amountMinor\":45000")
                .contains("PRICE")
                .doesNotContain("[redacted]");
        assertThat(fact.after("citedFacts").toString()).contains("f1");
        assertThat(fact.after("modelId").asString()).isEqualTo("fake-model-1");
        assertThat(fact.change().toString()).doesNotContain("[redacted]");
    }

    @Test
    @DisplayName("the audit keys the assistant writes are none of the names ADR 0027's redaction would blank")
    void auditKeysAreNotProtectedNames() {
        for (String key : List.of(
                "outcome",
                "refusalReason",
                "questionKinds",
                "locale",
                "factRefs",
                "citedFacts",
                "knowledgeVersions",
                "servedFromCache",
                "modelId",
                "scope",
                "version",
                "status",
                "questionForm",
                "answerBody")) {
            assertThat(uz.horecaos.platform.audit.api.ChangeDocuments.isProtected(key))
                    .as(key + " would be written as [redacted]")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a handoff leaves its own fact, a plain answer that stated nothing binding leaves none")
    void handoffsAreAuditedAndPlainAnswersAreNot() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        service.offer(fx.ask(UUID.randomUUID(), "Where are you?"));
        assertThat(AuditTrail.facts(jdbc, "assistant.turn.answered"))
                .as("a branch listing binds nothing")
                .isEmpty();

        service.offer(fx.ask(UUID.randomUUID(), "Позовите оператора"));

        AuditTrail.Fact handoff = AuditTrail.only(jdbc, "assistant.turn.handed_off");
        assertThat(handoff.after("outcome").asString()).isEqualTo("ESCALATED");
    }

    // =============================================================== disclosure

    @Test
    @DisplayName(
            "the first answer of a conversation says it is automated and processed by an AI service; later ones do not repeat it")
    void theDisclosureRidesOnTheFirstAnswerOnly() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        String first = text(service.offer(fx.ask(conversation, "Сколько стоит плов?")));
        fx.mutableClock.advance(Duration.ofMinutes(2));
        String second = text(service.offer(fx.ask(conversation, "А плов сколько стоит?")));

        assertThat(first).contains("автоматический помощник").contains("ИИ-сервис");
        assertThat(second).doesNotContain("автоматический помощник");
    }

    @Test
    @DisplayName(
            "a tenant's own disclosure replaces the platform's wording for its language only, and the default stays for every other")
    void aTenantsOwnDisclosureReplacesTheDefaultForItsLanguageOnly() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.configuration.put(
                "assistant.disclosure_text_ru", "Вам отвечает робот нашей сети. Вопрос уходит во внешний ИИ.");
        AssistantTurnService custom = fx.rebuild();

        String russian = text(custom.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")));
        String english = text(custom.offer(fx.ask(UUID.randomUUID(), "How much is the plov?")));

        assertThat(russian).startsWith("Вам отвечает робот нашей сети. Вопрос уходит во внешний ИИ.\n\n");
        assertThat(russian).doesNotContain("автоматический помощник");
        assertThat(english).contains("automated assistant");
    }

    @Test
    @DisplayName(
            "a blank disclosure is the default and never silence: a tenant can replace the sentence and cannot remove it")
    void aBlankDisclosureKeepsTheDefault() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        fx.configuration.put("assistant.disclosure_text_ru", "   ");

        String first = text(fx.rebuild().offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?")));

        assertThat(first).contains("автоматический помощник").contains("ИИ-сервис");
    }

    @Test
    @DisplayName("the answer is in the customer's language: Russian, Uzbek and English each get their own wording")
    void theAnswerIsInTheCustomersLanguage() {
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));

        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Сколько стоит плов?"))))
                .contains("сум");
        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "Plov narxi qancha?"))))
                .contains("so'm");
        assertThat(text(service.offer(fx.ask(UUID.randomUUID(), "How much is the plov?"))))
                .contains("UZS");
        assertThat(fx.model.requests()).extracting(request -> request.locale()).containsExactly("ru", "uz", "en");
    }

    // ================================================================= helpers

    private double count(String meter, String tagKey, String tagValue) {
        return java.util.Objects.requireNonNull(
                        fx.meters.find(meter).tag(tagKey, tagValue).counter())
                .count();
    }

    private static final class Collector extends AppenderBase<ILoggingEvent> {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
                messages.add(event.getThrowableProxy().getMessage());
            }
        }
    }

    @AfterEach
    void noStrayAppenders() {
        // Detached in each test that attaches one; nothing to clean here, kept so a future test has the hook.
    }

    @SuppressWarnings("unused")
    private static AssistantModelResponse unused() {
        return AssistantModelResponse.refused(null, TokenUsage.NONE);
    }
}
