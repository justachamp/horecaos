package uz.horecaos.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.assistant.api.TokenUsage;

/**
 * The proof that a reply says only what its facts say (ADR 0069: "an invented
 * price is worse than no answer, and this is the decision that keeps it out").
 *
 * <p>Each test builds the dishonest model the rule exists for and asserts the
 * reply is refused. A test that only showed an honest reply passing would be true
 * of a verifier that approved everything.
 */
class GroundingVerifierTests {

    private static final RetrievedFact PLOV = new RetrievedFact(
            "f1",
            "PRICE",
            Map.of("item", "Plov", "price", "45 000 so'm", "branch", "Chilonzor", "availability", "available"));
    private static final RetrievedFact LAGMAN =
            new RetrievedFact("f2", "PRICE", Map.of("item", "Lagman", "price", "38 000 so'm"));
    private static final RetrievedFact HOURS =
            new RetrievedFact("f3", "HOURS", Map.of("branch", "Chilonzor", "pickupHours", "Mon-Sun 09:00-23:00"));

    private static final List<RetrievedFact> FACTS = List.of(PLOV, LAGMAN, HOURS);

    private static AssistantModelResponse reply(String text, String... citations) {
        return new AssistantModelResponse(text, List.of(citations), false, null, TokenUsage.NONE);
    }

    private static GroundingVerdict verify(AssistantModelResponse response) {
        return GroundingVerifier.verify(response, FACTS, 700);
    }

    @Test
    @DisplayName("a reply that copies the fact's price and cites it is sendable")
    void anHonestReplyPasses() {
        GroundingVerdict verdict = verify(reply("Plov costs 45 000 so'm at Chilonzor.", "f1"));

        assertThat(verdict.grounded()).isTrue();
        assertThat(verdict.citedFactIds()).containsExactly("f1");
    }

    @Test
    @DisplayName("the same number written without the grouping space is the same number")
    void groupingIsNotADifference() {
        assertThat(verify(reply("Plov: 45000 so'm", "f1")).grounded()).isTrue();
        assertThat(verify(reply("Plov: 45 000 so'm", "f1")).grounded()).isTrue();
    }

    @Test
    @DisplayName("an invented price is refused")
    void anInventedPriceIsRefused() {
        GroundingVerdict verdict = verify(reply("Plov costs 50 000 so'm.", "f1"));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.reason()).isEqualTo(RefusalReason.UNGROUNDED_REPLY);
    }

    @Test
    @DisplayName("a rounded price is refused: 45 000 is not 45 500")
    void aRoundedPriceIsRefused() {
        assertThat(verify(reply("Plov is about 45 500 so'm.", "f1")).grounded()).isFalse();
        assertThat(verify(reply("Plov is 45 so'm.", "f1")).grounded()).isFalse();
    }

    @Test
    @DisplayName("a price from a fact the reply did not cite is refused, though another fact holds it")
    void aPriceOfAnUncitedFactIsRefused() {
        // 38 000 is Lagman's, retrieved this turn; the reply cites only Plov.
        GroundingVerdict verdict = verify(reply("Plov is 45 000 so'm and lagman is 38 000 so'm.", "f1"));

        assertThat(verdict.grounded()).isFalse();
    }

    @Test
    @DisplayName("an added-up total is refused: the assistant never computes money")
    void aTotalIsRefused() {
        assertThat(verify(reply("Both together are 83 000 so'm.", "f1", "f2")).grounded())
                .isFalse();
    }

    @Test
    @DisplayName("a reply that cites nothing is memory, not an answer")
    void anUncitedReplyIsRefused() {
        GroundingVerdict verdict = verify(reply("Plov is a rice dish."));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.reason()).isEqualTo(RefusalReason.UNGROUNDED_REPLY);
    }

    @Test
    @DisplayName("a citation of a fact that was never retrieved is refused")
    void aForeignCitationIsRefused() {
        assertThat(verify(reply("Plov costs 45 000 so'm.", "f1", "f99")).grounded())
                .isFalse();
    }

    @Test
    @DisplayName("the model's own refusal is a refusal with its own reason, not an error")
    void theModelsRefusalIsPassedOn() {
        GroundingVerdict verdict = verify(AssistantModelResponse.refused("NO_ANSWER", TokenUsage.NONE));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.reason()).isEqualTo(RefusalReason.MODEL_REFUSED);
    }

    @Test
    @DisplayName("an empty reply, and one longer than a chat message may be, are refused")
    void emptyAndOverlongRepliesAreRefused() {
        assertThat(verify(reply("   ", "f1")).grounded()).isFalse();
        assertThat(GroundingVerifier.verify(reply("x".repeat(701), "f1"), FACTS, 700)
                        .grounded())
                .isFalse();
    }

    @Test
    @DisplayName("times are checked too: the hours a fact states are the only hours a reply may state")
    void aTimeNotInTheFactIsRefused() {
        assertThat(verify(reply("We are open 09:00-23:00 every day.", "f3")).grounded())
                .isTrue();
        assertThat(verify(reply("We are open until 23:30.", "f3")).grounded()).isFalse();
        assertThat(verify(reply("We are open until 02:00.", "f3")).grounded()).isFalse();
    }

    @Test
    @DisplayName("single digits are prose, so 'one portion' needs no fact, but a currency beside one makes it money")
    void singleDigitsAreFreeUnlessTheyAreMoney() {
        assertThat(verify(reply("One portion of plov costs 45 000 so'm, 2 portions cost twice that.", "f1"))
                        .grounded())
                .as("a model may say 'twice that' as long as it states no figure of its own")
                .isTrue();
        assertThat(verify(reply("Plov costs 9 so'm.", "f1")).grounded())
                .as("nine so'm is a price, and no fact says it")
                .isFalse();
    }

    @Test
    @DisplayName("a four-digit year or phone number the facts do not contain is refused")
    void anyLongFigureMustBeInTheFacts() {
        assertThat(verify(reply("Call us on +998 90 123 45 67 about the plov.", "f1"))
                        .grounded())
                .isFalse();
        assertThat(verify(reply("Plov has been on our menu since 2019.", "f1")).grounded())
                .isFalse();
    }
}
