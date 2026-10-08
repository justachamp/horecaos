package uz.horecaos.platform.integration.camel.einvoicing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * An {@link EInvoicingApiTransport} that records every call and answers each from a script
 * of the operator's documented answers, in order. A call with no scripted answer fails the
 * test loudly rather than answering something plausible.
 */
public final class ScriptedTransport implements EInvoicingApiTransport {

    private final List<EInvoicingApiCall> calls = new ArrayList<>();
    private final List<Function<EInvoicingApiCall, ProviderOutcome>> script = new ArrayList<>();

    public ScriptedTransport then(ProviderOutcome outcome) {
        script.add(call -> outcome);
        return this;
    }

    public ScriptedTransport then(Function<EInvoicingApiCall, ProviderOutcome> responder) {
        script.add(responder);
        return this;
    }

    @Override
    public ProviderOutcome exchange(EInvoicingApiCall call) {
        calls.add(call);
        if (calls.size() > script.size()) {
            throw new AssertionError("Unscripted call #" + calls.size() + ": " + call + " " + call.path());
        }
        return script.get(calls.size() - 1).apply(call);
    }

    public List<EInvoicingApiCall> calls() {
        return List.copyOf(calls);
    }

    public List<String> operations() {
        return calls.stream().map(EInvoicingApiCall::operation).toList();
    }

    public long count(String operation) {
        return calls.stream().filter(call -> call.operation().equals(operation)).count();
    }
}
