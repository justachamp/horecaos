package uz.horecaos.platform.integration.provider.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.assistant.api.AssistantModelPort;
import uz.horecaos.platform.assistant.api.AssistantModelRequest;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.AssistantModelUnavailableException;
import uz.horecaos.platform.assistant.api.ModelTurn;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.assistant.api.TokenUsage;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;

/**
 * The first {@link AssistantModelPort} adapter: Anthropic's Messages API (ADR
 * 0069, ADR 0026, ADR 0007).
 *
 * <p><strong>Everything provider-shaped stays in this class.</strong> The model id,
 * the {@code x-api-key} header, the {@code anthropic-version}, the structured-output
 * and thinking parameters, the response's {@code content} and {@code usage} blocks:
 * none reaches the assistant's core, which sees a normalized request and a
 * normalized response and nothing else. Swapping the provider is another class
 * behind the same port.
 *
 * <p><strong>The platform's installation, not a tenant's.</strong> The provider is a
 * processor the tenant did not choose, so the one account is the platform's: its
 * API key is an ADR 0028 reference named in configuration
 * ({@code horecaos.assistant.provider.secret-reference}), its endpoint is the
 * approved {@code anthropic-production} row of {@code integration.provider_environments}
 * (ADR 0026: never a URL a property or a tenant types), and until the owner
 * stores a key behind that reference {@link #configured()} is false and the
 * assistant simply does not take turns. Nothing here fails a deployment that
 * never configures it, the {@code TelegramGatewayClient} arrangement.
 *
 * <p><strong>The model is configuration.</strong> {@code
 * horecaos.assistant.provider.model-id} defaults to {@code claude-sonnet-5-5};
 * changing it is a deployment setting. Two request parameters travel with the model
 * because the API ties them to it, and are properties for the same reason: {@code
 * thinking} (Sonnet 5.5 refuses {@code disabled} and takes {@code between_tools} as
 * its lowest setting; other models take neither) and {@code effort}. Set them
 * together with the model id, blank to omit.
 *
 * <p><strong>Structured output, not forced tool use.</strong> The reply is a JSON
 * document constrained by {@code output_config.format}; forcing a tool call is
 * refused by this model family. The document is parsed and still not believed --
 * the core checks every citation and every figure against the facts.
 *
 * <p>Never logs a request or a response, and no exception it throws carries
 * either: a failure is a stable code and a retryability flag.
 */
@Component
public class AnthropicAssistantModelAdapter implements AssistantModelPort {

    public static final String PROVIDER_TYPE = "ANTHROPIC";
    static final String ADAPTER_VERSION = "assistant/anthropic-messages/v1";
    static final String API_VERSION = "2023-06-01";
    static final String MESSAGES_PATH = "/v1/messages";

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantModelAdapter.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** What the model is asked to return, as a JSON schema (every property required, none extra). */
    static final Map<String, Object> REPLY_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "reply", Map.of("type", "string"),
                    "citations", Map.of("type", "array", "items", Map.of("type", "string")),
                    "refusal", Map.of("type", "boolean"),
                    "refusal_code", Map.of("type", "string")),
            "required",
            List.of("reply", "citations", "refusal", "refusal_code"),
            "additionalProperties",
            false);

    private final ProviderHttpClient http;
    private final SecretResolver secrets;
    private final ObjectMapper objectMapper;
    private final Supplier<Optional<String>> baseUrl;
    private final @Nullable SecretReference keyReference;
    private final String modelId;
    private final String thinking;
    private final String effort;
    private final int maxOutputTokens;
    private final Duration timeout;

    @Autowired
    public AnthropicAssistantModelAdapter(
            ProviderHttpClient http,
            SecretResolver secrets,
            ObjectMapper objectMapper,
            JdbcProviderEnvironmentLookup environments,
            @Value("${horecaos.assistant.provider.environment-code:anthropic-production}") String environmentCode,
            @Value("${horecaos.assistant.provider.secret-reference:}") String secretReference,
            @Value("${horecaos.assistant.provider.model-id:claude-sonnet-5-5}") String modelId,
            @Value("${horecaos.assistant.provider.thinking:between_tools}") String thinking,
            @Value("${horecaos.assistant.provider.effort:low}") String effort,
            @Value("${horecaos.assistant.provider.max-output-tokens:700}") int maxOutputTokens,
            @Value("${horecaos.assistant.provider.timeout:PT20S}") Duration timeout) {
        this(
                http,
                secrets,
                objectMapper,
                () -> environments.baseUrlOf(environmentCode),
                secretReference,
                modelId,
                thinking,
                effort,
                maxOutputTokens,
                timeout);
    }

    /** For a test that points the adapter at a local fake provider. */
    public AnthropicAssistantModelAdapter(
            ProviderHttpClient http,
            SecretResolver secrets,
            ObjectMapper objectMapper,
            Supplier<Optional<String>> baseUrl,
            String secretReference,
            String modelId,
            String thinking,
            String effort,
            int maxOutputTokens,
            Duration timeout) {
        this.http = http;
        this.secrets = secrets;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.keyReference =
                secretReference == null || secretReference.isBlank() ? null : SecretReference.parse(secretReference);
        this.modelId = modelId;
        this.thinking = thinking;
        this.effort = effort;
        this.maxOutputTokens = maxOutputTokens;
        this.timeout = timeout;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(PROVIDER_TYPE, modelId, ADAPTER_VERSION);
    }

    @Override
    public boolean configured() {
        return keyReference != null;
    }

    @Override
    public AssistantModelResponse answer(AssistantModelRequest request) throws AssistantModelUnavailableException {
        SecretReference reference = keyReference;
        if (reference == null) {
            throw new AssistantModelUnavailableException("PROVIDER_NOT_CONFIGURED", false);
        }
        String url = baseUrl.get()
                .orElseThrow(() -> new AssistantModelUnavailableException("PROVIDER_ENDPOINT_UNKNOWN", false));
        Map<String, Object> body = bodyOf(request);

        ProviderOutcome outcome = dispatch(url, credential(reference, false), body);
        if (isWrongKey(outcome)) {
            // One read past the ADR 0028 cache, as every adapter does: either the cached
            // copy aged out or the key was rotated and only a fresh read tells the two
            // apart. Once, never in a loop.
            log.warn("The assistant's model provider rejected the cached credential; refreshing once");
            outcome = dispatch(url, credential(reference, true), body);
        }
        return responseOf(outcome);
    }

    // ----------------------------------------------------------------- request

    Map<String, Object> bodyOf(AssistantModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("max_tokens", maxOutputTokens);
        body.put("system", request.posture());
        if (!thinking.isBlank()) {
            body.put("thinking", Map.of("type", thinking));
        }
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        if (!effort.isBlank()) {
            outputConfig.put("effort", effort);
        }
        outputConfig.put("format", Map.of("type", "json_schema", "schema", REPLY_SCHEMA));
        body.put("output_config", outputConfig);
        body.put("messages", messagesOf(request));
        if (request.customerPseudonym() != null) {
            // The provider's own field for an opaque end-user id (abuse tracing on its
            // side). It is ADR 0029's keyed hash and nothing a person could be read from.
            body.put("metadata", Map.of("user_id", request.customerPseudonym()));
        }
        return body;
    }

    /**
     * The conversation as the API wants it: starting with the customer, strictly
     * alternating (adjacent turns by one speaker are joined), with the retrieved
     * facts and the reply language riding on the final customer turn.
     */
    private List<Map<String, Object>> messagesOf(AssistantModelRequest request) {
        List<ModelTurn> turns = new ArrayList<>(request.turns());
        while (!turns.isEmpty() && turns.getFirst().role() != ModelTurn.Role.CUSTOMER) {
            turns.removeFirst();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        String lastRole = null;
        StringBuilder current = null;
        for (int i = 0; i < turns.size() - 1; i++) {
            ModelTurn turn = turns.get(i);
            String role = turn.role() == ModelTurn.Role.CUSTOMER ? "user" : "assistant";
            if (role.equals(lastRole) && current != null) {
                current.append('\n').append(turn.text());
            } else {
                flush(messages, lastRole, current);
                lastRole = role;
                current = new StringBuilder(turn.text());
            }
        }
        flush(messages, lastRole, current);

        // A final customer turn after a customer turn must not leave two user messages in a row.
        ModelTurn last = turns.getLast();
        StringBuilder finalText = new StringBuilder();
        if (!messages.isEmpty() && "user".equals(messages.getLast().get("role"))) {
            Map<String, Object> previous = messages.removeLast();
            finalText.append(previous.get("content")).append('\n');
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", factsBlock(request)));
        content.add(Map.of("type", "text", "text", "CUSTOMER MESSAGE:\n" + finalText + last.text()));
        Map<String, Object> finalTurn = new LinkedHashMap<>();
        finalTurn.put("role", "user");
        finalTurn.put("content", content);
        messages.add(finalTurn);
        return messages;
    }

    private static void flush(List<Map<String, Object>> messages, @Nullable String role, @Nullable StringBuilder text) {
        if (role != null && text != null) {
            messages.add(Map.of("role", role, "content", text.toString()));
        }
    }

    private String factsBlock(AssistantModelRequest request) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (RetrievedFact fact : request.facts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", fact.id());
            entry.put("kind", fact.kind());
            entry.put("attributes", fact.attributes());
            facts.add(entry);
        }
        return "FACTS (reply language: " + request.locale() + ", at most " + request.maxReplyCharacters()
                + " characters):\n" + objectMapper.writeValueAsString(facts);
    }

    // ---------------------------------------------------------------- dispatch

    private String credential(SecretReference reference, boolean fresh) throws AssistantModelUnavailableException {
        try {
            return (fresh ? secrets.resolveFresh(reference) : secrets.resolve(reference)).reveal();
        } catch (SecretResolver.SecretNotFoundException missing) {
            throw new AssistantModelUnavailableException("CREDENTIAL_UNAVAILABLE", false);
        }
    }

    private ProviderOutcome dispatch(String baseUrl, String apiKey, Map<String, Object> body) {
        ProviderCall call = new ProviderCall(baseUrl, apiKey, null, timeout);
        return http.post(
                call,
                MESSAGES_PATH,
                Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION),
                body,
                this::classifySuccess);
    }

    // ---------------------------------------------------------------- response

    /** The 2xx branch: every other status was classified generically by the shared ADR 0007 classifier. */
    @SuppressWarnings("unchecked")
    private ProviderOutcome classifySuccess(Map<String, Object> parsed) {
        Object stopReason = parsed.get("stop_reason");
        TokenUsage usage = usageOf(parsed.get("usage"));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("inputTokens", usage.inputTokens());
        normalized.put("outputTokens", usage.outputTokens());

        if ("refusal".equals(stopReason)) {
            // The provider's own safety classifier declined: a refusal, which is an
            // outcome and not a failure.
            normalized.put("refusal", true);
            normalized.put("refusalCode", "PROVIDER_DECLINED");
            normalized.put("reply", "");
            normalized.put("citations", List.of());
            return ProviderOutcome.success(normalized, null);
        }
        if ("max_tokens".equals(stopReason)) {
            return ProviderOutcome.uncertain("RESPONSE_TRUNCATED", "The reply hit the output limit");
        }
        String text = firstText(parsed.get("content"));
        if (text == null) {
            return ProviderOutcome.uncertain("RESPONSE_UNREADABLE", "The response carried no text");
        }
        Map<String, Object> reply;
        try {
            reply = objectMapper.readValue(text, MAP);
        } catch (JacksonException notJson) {
            return ProviderOutcome.uncertain("RESPONSE_UNREADABLE", "The response was not the document asked for");
        }
        Object replyText = reply.get("reply");
        Object citations = reply.get("citations");
        if (!(replyText instanceof String) || !(citations instanceof List<?>)) {
            return ProviderOutcome.uncertain("RESPONSE_UNREADABLE", "The response lacked required fields");
        }
        List<String> cited = new ArrayList<>();
        for (Object citation : (List<Object>) citations) {
            if (citation instanceof String id) {
                cited.add(id);
            }
        }
        normalized.put("reply", replyText);
        normalized.put("citations", cited);
        normalized.put("refusal", Boolean.TRUE.equals(reply.get("refusal")));
        Object code = reply.get("refusal_code");
        normalized.put("refusalCode", code instanceof String string && !string.isBlank() ? string : null);
        return ProviderOutcome.success(normalized, null);
    }

    private static @Nullable String firstText(@Nullable Object content) {
        if (!(content instanceof List<?> blocks)) {
            return null;
        }
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map
                    && "text".equals(map.get("type"))
                    && map.get("text") instanceof String text) {
                return text;
            }
        }
        return null;
    }

    private static TokenUsage usageOf(@Nullable Object usage) {
        if (usage instanceof Map<?, ?> map) {
            return new TokenUsage(number(map.get("input_tokens")), number(map.get("output_tokens")));
        }
        return TokenUsage.NONE;
    }

    private static long number(@Nullable Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private AssistantModelResponse responseOf(ProviderOutcome outcome) throws AssistantModelUnavailableException {
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            // The code and whether a retry could help -- never the provider's detail,
            // which can quote the request.
            throw new AssistantModelUnavailableException(
                    outcome.errorCode() == null ? outcome.status().name() : outcome.errorCode(),
                    outcome.status() == ProviderOutcome.Status.RETRYABLE);
        }
        Map<String, Object> normalized = outcome.normalized();
        List<String> citations = new ArrayList<>();
        if (normalized.get("citations") instanceof List<?> listed) {
            for (Object citation : listed) {
                if (citation instanceof String id) {
                    citations.add(id);
                }
            }
        }
        Object reply = normalized.get("reply");
        Object refusalCode = normalized.get("refusalCode");
        return new AssistantModelResponse(
                reply instanceof String text ? text : "",
                citations,
                Boolean.TRUE.equals(normalized.get("refusal")),
                refusalCode instanceof String code ? code : null,
                new TokenUsage(number(normalized.get("inputTokens")), number(normalized.get("outputTokens"))));
    }

    /** {@code SmsGateway#isWrongKey}'s own test, restated: the provider says the credential is wrong. */
    private static boolean isWrongKey(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }
}
