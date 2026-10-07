package uz.horecaos.platform.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.assistant.api.AssistantModelPort;
import uz.horecaos.platform.assistant.api.AssistantModelRequest;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.AssistantModelUnavailableException;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.assistant.api.TokenUsage;

/**
 * A deterministic stand-in for the model provider, in the ADR 0007 genre: no
 * network, no clock, and an answer that is a pure function of the facts it was
 * handed -- which is exactly what lets a test say "this reply came from that
 * retrieval" and mean it.
 *
 * <p>By default it composes the reply by copying the facts' own rendered values
 * and citing every fact it used, the behaviour the posture asks of a real model.
 * A test that needs a <em>dishonest</em> model -- one that invents a price, cites
 * nothing, cites a fact it was not given, or refuses -- scripts it with {@link
 * #respondWith}; the assistant's own checks are what such a test is about.
 *
 * <p>Records every request it receives, so a test can assert what did and did not
 * reach the provider (the PII guard) and how many times it was called at all (the
 * cache, the ceiling, the empty retrieval).
 */
public final class FakeAssistantModel implements AssistantModelPort {

    public static final TokenUsage DEFAULT_USAGE = new TokenUsage(1_000, 100);

    private final List<AssistantModelRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean configured = true;
    private volatile Function<AssistantModelRequest, AssistantModelResponse> behaviour = FakeAssistantModel::composeFromFacts;
    private volatile @Nullable AssistantModelUnavailableException failure;
    private volatile TokenUsage usage = DEFAULT_USAGE;

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("FAKE", "fake-model-1", "assistant/fake/v1");
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public AssistantModelResponse answer(AssistantModelRequest request) throws AssistantModelUnavailableException {
        calls.incrementAndGet();
        requests.add(request);
        AssistantModelUnavailableException toThrow = failure;
        if (toThrow != null) {
            throw toThrow;
        }
        AssistantModelResponse composed = behaviour.apply(request);
        return new AssistantModelResponse(
                composed.reply(), composed.citations(), composed.refusal(), composed.refusalCode(), usage);
    }

    // ----------------------------------------------------------------- scripting

    public FakeAssistantModel unconfigured() {
        this.configured = false;
        return this;
    }

    public FakeAssistantModel failing(String code, boolean retryable) {
        this.failure = new AssistantModelUnavailableException(code, retryable);
        return this;
    }

    public FakeAssistantModel respondWith(Function<AssistantModelRequest, AssistantModelResponse> behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    public FakeAssistantModel replyingWith(String reply, String... citations) {
        return respondWith(request -> new AssistantModelResponse(reply, List.of(citations), false, null, usage));
    }

    public FakeAssistantModel refusing() {
        return respondWith(request -> AssistantModelResponse.refused("NO_ANSWER", usage));
    }

    public FakeAssistantModel composingFromFacts() {
        this.behaviour = FakeAssistantModel::composeFromFacts;
        this.failure = null;
        return this;
    }

    public FakeAssistantModel using(TokenUsage usage) {
        this.usage = usage;
        return this;
    }

    // ---------------------------------------------------------------- inspection

    public int calls() {
        return calls.get();
    }

    public List<AssistantModelRequest> requests() {
        return List.copyOf(requests);
    }

    public AssistantModelRequest lastRequest() {
        return requests.getLast();
    }

    /** Everything the provider was ever sent, as one string -- what a "no personal data left" test searches. */
    public String everythingSent() {
        StringBuilder all = new StringBuilder();
        for (AssistantModelRequest request : requests) {
            all.append(request.posture()).append('\n');
            request.turns().forEach(turn -> all.append(turn.text()).append('\n'));
            request.facts().forEach(fact -> all.append(fact.attributes()).append('\n'));
            all.append(request.customerPseudonym()).append('\n');
        }
        return all.toString();
    }

    // ------------------------------------------------------------ the honest model

    /** One line per fact, copying its rendered values, citing every fact used. */
    static AssistantModelResponse composeFromFacts(AssistantModelRequest request) {
        List<String> lines = new ArrayList<>();
        List<String> cited = new ArrayList<>();
        for (RetrievedFact fact : request.facts()) {
            Map<String, String> a = fact.attributes();
            String line =
                    switch (fact.kind()) {
                        case "PRICE" -> a.get("item") + ": " + a.get("price")
                                + (a.get("availability") != null && !"available".equals(a.get("availability"))
                                        ? " (" + a.get("availability") + ")"
                                        : "");
                        case "AVAILABILITY" -> a.get("item") + ": " + a.get("availability");
                        case "BRANCH" -> a.get("branch") + ": " + a.getOrDefault("address", "");
                        case "HOURS" -> a.get("branch") + ": " + a.getOrDefault("pickupHours", a.getOrDefault("rightNow", ""));
                        case "COVERAGE" -> a.getOrDefault("branch", "") + " " + a.getOrDefault("result", a.getOrDefault("note", ""));
                        case "ORDER" -> a.containsKey("orderNumber")
                                ? "Order " + a.get("orderNumber") + ": " + a.get("statusMeaning")
                                : a.getOrDefault("note", "");
                        case "KNOWLEDGE" -> a.get("answer");
                        default -> "";
                    };
            if (!line.isBlank()) {
                lines.add(line.strip());
                cited.add(fact.id());
            }
        }
        if (lines.isEmpty()) {
            return AssistantModelResponse.refused("NO_ANSWER", DEFAULT_USAGE);
        }
        return new AssistantModelResponse(String.join("\n", lines), cited, false, null, DEFAULT_USAGE);
    }
}
