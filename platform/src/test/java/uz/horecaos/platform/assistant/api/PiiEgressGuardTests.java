package uz.horecaos.platform.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What may leave for the model provider (ADR 0069: "enforced by a test rather than
 * by care"). The request type refuses to be built with a personal value in the
 * text it carries, so the tests here are of the refusal, not of a promise.
 */
class PiiEgressGuardTests {

    private static final RetrievedFact FACT = new RetrievedFact("f1", "PRICE", Map.of("item", "Plov"));

    private static AssistantModelRequest requestWith(String customerText) {
        return new AssistantModelRequest(
                "posture",
                "en",
                List.of(FACT),
                List.of(new ModelTurn(ModelTurn.Role.CUSTOMER, customerText)),
                null,
                700);
    }

    @ParameterizedTest
    @DisplayName("a phone number, however it is typed, is removed")
    @ValueSource(
            strings = {
                "call me on +998 90 123 45 67 please",
                "my number is 998901234567",
                "90-123-45-67 and 998 (90) 1234567",
                "+998901234567",
                "phone 8 800 555 35 35 thanks"
            })
    void phoneNumbersAreRemoved(String text) {
        String redacted = PiiEgressGuard.redact(text);

        assertThat(redacted).contains("[phone]").doesNotContainPattern("\\d{5,}");
        assertThat(PiiEgressGuard.containsPersonalData(text)).isTrue();
    }

    @Test
    @DisplayName("an email address and a Telegram handle are removed")
    void emailAndHandleAreRemoved() {
        assertThat(PiiEgressGuard.redact("write to aziz.karimov@example.uz or @aziz_karimov"))
                .isEqualTo("write to [email] or [handle]");
    }

    @Test
    @DisplayName("a street address with a number is removed, in Russian, Uzbek and English")
    void addressesAreRemoved() {
        assertThat(PiiEgressGuard.redact("deliver to ул. Навои 12 кв 5, thanks"))
                .doesNotContain("Навои")
                .doesNotContain("12");
        assertThat(PiiEgressGuard.redact("Amir Temur street 7 please"))
                .contains("[address]")
                .doesNotContain("Temur");
        assertThat(PiiEgressGuard.redact("Chilonzor mahalla 3-uy")).contains("[address]");
    }

    @Test
    @DisplayName("ordinary questions, prices and times are left alone")
    void ordinaryTextIsUntouched() {
        for (String text : List.of(
                "How much is the plov?",
                "Сколько стоит плов за 45 000?",
                "Do you have street food?",
                "Open until 23:00? Table for 4",
                "Плов bormi, 2 ta kerak")) {
            assertThat(PiiEgressGuard.redact(text)).as(text).isEqualTo(text);
            assertThat(PiiEgressGuard.containsPersonalData(text)).as(text).isFalse();
        }
    }

    @Test
    @DisplayName("redaction is idempotent, which is what makes 'refuse what redaction would change' a sound check")
    void redactionIsIdempotent() {
        String once = PiiEgressGuard.redact("+998 90 123 45 67, aziz@example.uz, ул. Навои 12");

        assertThat(PiiEgressGuard.redact(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("a request cannot be built from text that still carries a personal value")
    void aRequestRefusesDirtyText() {
        assertThatThrownBy(() -> requestWith("my phone is +998 90 123 45 67"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("998");
        assertThatThrownBy(() -> requestWith("mail me aziz@example.uz")).isInstanceOf(IllegalArgumentException.class);

        assertThat(requestWith(PiiEgressGuard.redact("my phone is +998 90 123 45 67"))
                        .turns())
                .extracting(ModelTurn::text)
                .containsExactly("my phone is [phone]");
    }

    @Test
    @DisplayName("a request with no facts, or not ending with the customer, or with a name for a pseudonym, is refused")
    void aRequestRefusesWhatWouldLetTheModelAnswerFromMemoryOrIdentifyAPerson() {
        List<ModelTurn> customer = List.of(new ModelTurn(ModelTurn.Role.CUSTOMER, "How much is plov?"));

        assertThatThrownBy(() -> new AssistantModelRequest("p", "en", List.of(), customer, null, 700))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");
        assertThatThrownBy(() -> new AssistantModelRequest(
                        "p", "en", List.of(FACT), List.of(new ModelTurn(ModelTurn.Role.ASSISTANT, "Hello")), null, 700))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantModelRequest("p", "en", List.of(FACT), customer, "Aziz Karimov", 700))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantModelRequest("p", "en", List.of(FACT), customer, "+998901234567", 700))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new AssistantModelRequest("p", "en", List.of(FACT), customer, "k3J_2mN-q8Xz0Vb1Yt7Rw", 700)
                        .customerPseudonym())
                .isEqualTo("k3J_2mN-q8Xz0Vb1Yt7Rw");
    }
}
