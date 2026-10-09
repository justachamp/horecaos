package uz.horecaos.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which platform read a question needs (ADR 0069), in the three languages
 * customers actually write in. The lexicon is code a pull request can extend, and
 * this is the suite that says what it must not break.
 */
class QuestionClassifierTests {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @DisplayName("each kind of question reaches the read that owns its answer")
    @CsvSource(delimiter = '|', textBlock = """
                    Сколько стоит плов? | PRICE
                    How much is the lagman? | PRICE
                    Plov narxi qancha? | PRICE
                    Есть ли у вас шашлык сегодня? | AVAILABILITY
                    Do you have samsa today? | AVAILABILITY
                    Samsa bormi? | AVAILABILITY
                    Где вы находитесь? | BRANCHES
                    Where are you? | BRANCHES
                    Manzilingiz qayerda? | BRANCHES
                    До скольки вы работаете? | HOURS
                    What time do you close? | HOURS
                    Soat nechagacha ishlaysiz? | HOURS
                    Вы доставляете на Чиланзар? | COVERAGE
                    Do you deliver to my place? | COVERAGE
                    Dostavka bormi? | COVERAGE
                    Где мой заказ? | ORDER_STATUS
                    Where is my order? | ORDER_STATUS
                    Buyurtmam qani? | ORDER_STATUS
                    """)
    void questionsReachTheirReads(String question, String expectedKind) {
        assertThat(QuestionClassifier.classify(question).kinds())
                .contains(RetrievalKind.valueOf(expectedKind), RetrievalKind.KNOWLEDGE);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @DisplayName("a topic only a person handles is decided before anything is retrieved")
    @CsvSource(delimiter = '|', textBlock = """
                    У меня жалоба, еда была холодной и невкусной, ужасно | COMPLAINT
                    This is terrible, I want to complain | COMPLAINT
                    Shikoyatim bor, juda yomon | COMPLAINT
                    Верните деньги за заказ | REFUND
                    I want a refund | REFUND
                    Pulni qaytarib bering | REFUND
                    Позовите оператора | HUMAN_REQUESTED
                    Can I talk to a real person? | HUMAN_REQUESTED
                    Menejer bilan gaplashmoqchiman, operator | HUMAN_REQUESTED
                    """)
    void personOnlyTopics(String question, String expectedTopic) {
        assertThat(QuestionClassifier.classify(question).escalation())
                .isEqualTo(EscalationTopic.valueOf(expectedTopic));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("ordinary questions that merely contain words a complaint also contains are not escalated")
    @CsvSource(delimiter = '|', textBlock = """
                    Сколько человек насытит семейный сет?
                    Есть холодный чай?
                    How many people does the family set feed?
                    Do you have cold brew?
                    Plov necha kishilik?
                    """)
    void ordinaryQuestionsAreNotEscalated(String question) {
        assertThat(QuestionClassifier.classify(question).escalation()).isNull();
    }

    @Test
    @DisplayName("what is left once the question is taken out is what the customer called the dish")
    void dishTerms() {
        assertThat(QuestionClassifier.classify("Сколько стоит плов?").dishTerms())
                .containsExactly("plov");
        assertThat(QuestionClassifier.classify("How much is the beef lagman today?")
                        .dishTerms())
                .containsExactly("beef", "lagman");
        assertThat(QuestionClassifier.classify("Plov bormi?").dishTerms()).containsExactly("plov");
        assertThat(QuestionClassifier.classify("Какие у вас часы работы?").dishTerms())
                .isEmpty();
    }

    @Test
    @DisplayName("a question about an order is not also a question about the menu or about delivery coverage")
    void anOrderQuestionIsOnlyAboutTheOrder() {
        var classification = QuestionClassifier.classify("When will my delivery arrive?");

        assertThat(classification.kinds()).contains(RetrievalKind.ORDER_STATUS);
        assertThat(classification.kinds())
                .doesNotContain(RetrievalKind.COVERAGE, RetrievalKind.PRICE, RetrievalKind.AVAILABILITY);
    }

    @Test
    @DisplayName("a question that matches nothing is still given to the tenant's knowledge entries")
    void unknownQuestionsStillTryKnowledge() {
        var classification = QuestionClassifier.classify("Is there parking at your place?");

        assertThat(classification.kinds()).containsExactly(RetrievalKind.KNOWLEDGE);
        assertThat(classification.dishTerms()).contains("parking");
    }
}
