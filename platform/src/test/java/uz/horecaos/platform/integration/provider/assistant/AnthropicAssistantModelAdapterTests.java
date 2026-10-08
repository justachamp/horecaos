package uz.horecaos.platform.integration.provider.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.assistant.api.AssistantModelRequest;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.AssistantModelUnavailableException;
import uz.horecaos.platform.assistant.api.ModelTurn;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * The first model adapter against a local stand-in for its provider (ADR 0007
 * genre): what is on the wire, what comes back normalized, and -- the half that
 * matters most for an adapter that carries a customer's words -- what never
 * leaves it.
 */
class AnthropicAssistantModelAdapterTests {

    private static final String REFERENCE = "horecaos:local:provider_assistant:platform:anthropic";
    private static final String PSEUDONYM = "k3J_2mN-q8Xz0Vb1Yt7Rw4Pc9Ld6Hs5Fa2Gu1Ke8";

    private static final RetrievedFact PRICE =
            new RetrievedFact("f1", "PRICE", Map.of("item", "Plov", "price", "45 000 so'm", "branch", "Chilonzor"));

    private FakeAnthropicApi provider;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void start() throws IOException {
        provider = FakeAnthropicApi.start();
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    private AnthropicAssistantModelAdapter adapter(
            SecretResolver secrets, String modelId, String thinking, String effort) {
        return new AnthropicAssistantModelAdapter(
                new ProviderHttpClient(mapper, new ProviderExceptionClassifier()),
                secrets,
                mapper,
                () -> Optional.of(provider.baseUrl()),
                REFERENCE,
                modelId,
                thinking,
                effort,
                700,
                Duration.ofSeconds(5));
    }

    private AnthropicAssistantModelAdapter adapter() {
        return adapter(fixedKey("sk-ant-test-key"), "claude-sonnet-5-5", "between_tools", "low");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object value) {
        return (Map<String, Object>) java.util.Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(@Nullable Object value) {
        return (List<Map<String, Object>>) java.util.Objects.requireNonNull(value);
    }

    private static SecretResolver fixedKey(String key) {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(key);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of(key);
            }
        };
    }

    private static AssistantModelRequest request(String question) {
        return new AssistantModelRequest(
                "POSTURE TEXT",
                "en",
                List.of(PRICE),
                List.of(new ModelTurn(ModelTurn.Role.CUSTOMER, question)),
                PSEUDONYM,
                700);
    }

    @Test
    @DisplayName(
            "the request carries the configured model, the version header, the key, structured output and the facts")
    void theWireShape() throws Exception {
        provider.replyWith("Plov: 45 000 so'm", List.of("f1"), false, 812, 44);

        adapter().answer(request("How much is plov?"));

        FakeAnthropicApi.Received sent = provider.last();
        assertThat(sent.headers()).containsEntry("x-api-key", "sk-ant-test-key");
        assertThat(sent.headers()).containsEntry("anthropic-version", "2023-06-01");
        assertThat(sent.body()).containsEntry("model", "claude-sonnet-5-5");
        assertThat(sent.body()).containsEntry("system", "POSTURE TEXT");
        assertThat(sent.body().get("max_tokens")).isEqualTo(700);
        assertThat(sent.body().get("thinking")).isEqualTo(Map.of("type", "between_tools"));
        Map<String, Object> outputConfig = asMap(sent.body().get("output_config"));
        assertThat(outputConfig).containsEntry("effort", "low");
        Map<String, Object> format = asMap(outputConfig.get("format"));
        assertThat(format).containsEntry("type", "json_schema");
        assertThat(sent.body())
                .as("this model family refuses a forced tool call, so the adapter never sends one")
                .doesNotContainKey("tool_choice")
                .doesNotContainKey("tools");
        assertThat(sent.body().get("metadata")).isEqualTo(Map.of("user_id", PSEUDONYM));

        assertThat(sent.rawBody()).contains("FACTS").contains("45 000 so'm").contains("How much is plov?");
    }

    @Test
    @DisplayName("the response is normalized: reply, citations, refusal, and what the provider says it consumed")
    void theResponseIsNormalized() throws Exception {
        provider.replyWith("Plov: 45 000 so'm", List.of("f1"), false, 812, 44);

        AssistantModelResponse response = adapter().answer(request("How much is plov?"));

        assertThat(response.reply()).isEqualTo("Plov: 45 000 so'm");
        assertThat(response.citations()).containsExactly("f1");
        assertThat(response.refusal()).isFalse();
        assertThat(response.usage().inputTokens()).isEqualTo(812);
        assertThat(response.usage().outputTokens()).isEqualTo(44);
    }

    @Test
    @DisplayName("the model's own refusal is a refusal outcome, not an error")
    void aRefusalIsAnOutcome() throws Exception {
        provider.replyWith("", List.of(), true, 700, 12);

        AssistantModelResponse response = adapter().answer(request("What is the meaning of life?"));

        assertThat(response.refusal()).isTrue();
        assertThat(response.refusalCode()).isEqualTo("NO_ANSWER");
        assertThat(response.reply()).isEmpty();
    }

    @Test
    @DisplayName("the provider's own safety decline (stop_reason refusal) is a refusal outcome too")
    void aProviderSafetyDeclineIsARefusal() throws Exception {
        provider.stopReason("refusal");

        AssistantModelResponse response = adapter().answer(request("How much is plov?"));

        assertThat(response.refusal()).isTrue();
        assertThat(response.refusalCode()).isEqualTo("PROVIDER_DECLINED");
    }

    @Test
    @DisplayName(
            "the model id, thinking and effort are configuration: another model is another request, with nothing hard-coded")
    void theModelIsConfiguration() throws Exception {
        provider.replyWith("ok", List.of("f1"), false, 1, 1);

        adapter(fixedKey("k"), "claude-opus-5-5", "", "medium").answer(request("How much is plov?"));

        assertThat(provider.last().body())
                .containsEntry("model", "claude-opus-5-5")
                .doesNotContainKey("thinking");
        Map<String, Object> outputConfig = asMap(provider.last().body().get("output_config"));
        assertThat(outputConfig).containsEntry("effort", "medium");
        assertThat(adapter(fixedKey("k"), "claude-opus-5-5", "", "medium")
                        .descriptor()
                        .modelId())
                .isEqualTo("claude-opus-5-5");
    }

    @Test
    @DisplayName(
            "earlier turns become strictly alternating messages starting with the customer, facts riding on the last")
    void conversationShape() throws Exception {
        provider.replyWith("ok", List.of("f1"), false, 1, 1);
        AssistantModelRequest withHistory = new AssistantModelRequest(
                "P",
                "en",
                List.of(PRICE),
                List.of(
                        new ModelTurn(ModelTurn.Role.ASSISTANT, "stale leading assistant turn"),
                        new ModelTurn(ModelTurn.Role.CUSTOMER, "hello"),
                        new ModelTurn(ModelTurn.Role.CUSTOMER, "do you have plov"),
                        new ModelTurn(ModelTurn.Role.ASSISTANT, "yes"),
                        new ModelTurn(ModelTurn.Role.CUSTOMER, "how much")),
                null,
                700);

        adapter().answer(withHistory);

        List<Map<String, Object>> messages = asList(provider.last().body().get("messages"));
        assertThat(messages).extracting(m -> m.get("role")).containsExactly("user", "assistant", "user");
        assertThat(messages.getFirst().get("content")).isEqualTo("hello\ndo you have plov");
        assertThat(provider.last().body()).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("a rejected key is refreshed from the secret manager once, and a rotated key then works")
    void aRotatedKeyIsRefreshedOnce() throws Exception {
        provider.acceptOnlyKey("sk-new").replyWith("ok", List.of("f1"), false, 1, 1);
        SecretResolver rotated = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("sk-old");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of("sk-new");
            }
        };

        AssistantModelResponse response =
                adapter(rotated, "claude-sonnet-5-5", "between_tools", "low").answer(request("How much is plov?"));

        assertThat(response.reply()).isEqualTo("ok");
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(0).headers()).containsEntry("x-api-key", "sk-old");
        assertThat(provider.requests().get(1).headers()).containsEntry("x-api-key", "sk-new");
    }

    @Test
    @DisplayName("a key that is wrong after the refresh is an unavailable provider, tried twice and never in a loop")
    void aWrongKeyIsTriedTwice() {
        provider.acceptOnlyKey("some-other-key");

        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(AssistantModelUnavailableException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROVIDER_AUTHENTICATION");
                    assertThat(failure.retryable()).isFalse();
                });
        assertThat(provider.requests()).hasSize(2);
    }

    @Test
    @DisplayName("rate limiting and outages are unavailable, retryable; a bad request is not")
    void failuresAreClassified() {
        provider.respond(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.retryable()).isTrue());

        provider.respond(503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.retryable()).isTrue());

        provider.respond(
                400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.retryable()).isFalse());
    }

    @Test
    @DisplayName(
            "an error body that quotes the customer's question never reaches the exception, whose message is only its code")
    void aFailureNeverEchoesTheQuestion() {
        provider.respond(
                400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"could not process: my secret question about plov\"}}");

        assertThatThrownBy(() -> adapter().answer(request("my secret question about plov")))
                .isInstanceOfSatisfying(AssistantModelUnavailableException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo(failure.code());
                    assertThat(failure.getMessage()).doesNotContain("secret").doesNotContain("plov");
                    assertThat(failure.toString()).doesNotContain("secret");
                });
    }

    @Test
    @DisplayName("a reply that is not the document asked for, or was cut off, is unavailable rather than guessed at")
    void unreadableRepliesAreUnavailable() {
        provider.replyWithText("Sure! Plov is 45 000.");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESPONSE_UNREADABLE"));

        provider.replyWithText("{\"reply\": 5}");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESPONSE_UNREADABLE"));

        provider.stopReason("max_tokens");
        assertThatThrownBy(() -> adapter().answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESPONSE_TRUNCATED"));
    }

    @Test
    @DisplayName("with no credential reference configured the adapter is not configured and calls nothing")
    void unconfiguredCallsNothing() {
        AnthropicAssistantModelAdapter unconfigured = new AnthropicAssistantModelAdapter(
                new ProviderHttpClient(mapper, new ProviderExceptionClassifier()),
                fixedKey("k"),
                mapper,
                () -> Optional.of(provider.baseUrl()),
                "",
                "claude-sonnet-5-5",
                "between_tools",
                "low",
                700,
                Duration.ofSeconds(5));

        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROVIDER_NOT_CONFIGURED"));
        assertThat(provider.requests()).isEmpty();
        assertThat(adapter().configured()).isTrue();
    }

    @Test
    @DisplayName("an installation whose approved environment is unknown is unavailable, and no key is sent anywhere")
    void anUnknownEnvironmentSendsNothing() {
        AnthropicAssistantModelAdapter lost = new AnthropicAssistantModelAdapter(
                new ProviderHttpClient(mapper, new ProviderExceptionClassifier()),
                fixedKey("k"),
                mapper,
                Optional::empty,
                REFERENCE,
                "claude-sonnet-5-5",
                "between_tools",
                "low",
                700,
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> lost.answer(request("How much is plov?")))
                .isInstanceOfSatisfying(
                        AssistantModelUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROVIDER_ENDPOINT_UNKNOWN"));
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    @DisplayName("the adapter declares what it is, without a credential")
    void descriptor() {
        assertThat(adapter().descriptor().providerType()).isEqualTo("ANTHROPIC");
        assertThat(adapter().descriptor().adapterVersion()).isEqualTo("assistant/anthropic-messages/v1");
        assertThat(adapter().descriptor().toString()).doesNotContain("sk-ant");
    }
}
