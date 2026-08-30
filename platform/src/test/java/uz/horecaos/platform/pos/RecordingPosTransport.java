package uz.horecaos.platform.pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import uz.horecaos.platform.integration.api.pos.PosApiCall;
import uz.horecaos.platform.integration.api.pos.PosApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * A transport that records what an adapter asked for and answers with a script.
 *
 * <p>Test sources only. It stands in for the ADR 0007 route so that an adapter's
 * mapping — the path it builds, the headers it sends, the effect it declares, and
 * what it makes of a reply — can be asserted without a network, and so a lost
 * response can be produced on demand. A real till cannot be asked to accept an
 * order and then drop the reply, and that is the case the whole export design
 * exists for.
 */
public final class RecordingPosTransport implements PosApiTransport {

    private final List<PosApiCall> calls = new ArrayList<>();
    private final List<Map<String, Object>> bodies = new ArrayList<>();
    private final Deque<ProviderOutcome> scripted = new ArrayDeque<>();
    private ProviderOutcome fallback = ProviderOutcome.success(Map.of("success", true), null);

    /** The credential the gateway would have supplied. Never a real one here. */
    private static final String CREDENTIAL = "test-secret";

    public RecordingPosTransport enqueue(ProviderOutcome outcome) {
        scripted.add(outcome);
        return this;
    }

    public RecordingPosTransport answerWith(ProviderOutcome outcome) {
        this.fallback = outcome;
        return this;
    }

    public List<PosApiCall> calls() {
        return List.copyOf(calls);
    }

    /** The body each call carried, resolved the way the gateway would resolve it. */
    public List<Map<String, Object>> bodies() {
        return List.copyOf(bodies);
    }

    public PosApiCall lastCall() {
        return calls.getLast();
    }

    public Map<String, String> lastHeaders() {
        return calls.getLast().authorization().apply(CREDENTIAL);
    }

    @Override
    public ProviderOutcome exchange(PosApiCall call) {
        calls.add(call);
        bodies.add(call.body() == null ? Map.of() : call.body().apply(CREDENTIAL));
        return scripted.isEmpty() ? fallback : scripted.poll();
    }

    /** A successful Clopos-shaped list response. */
    public static ProviderOutcome list(List<Map<String, Object>> data) {
        return ProviderOutcome.success(Map.of("success", true, "data", data), null);
    }

    /** A successful Clopos-shaped single-object response. */
    public static ProviderOutcome object(Map<String, Object> data) {
        return ProviderOutcome.success(Map.of("success", true, "data", data), null);
    }
}
