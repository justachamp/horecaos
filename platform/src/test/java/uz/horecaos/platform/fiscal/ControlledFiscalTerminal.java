package uz.horecaos.platform.fiscal;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;
import uz.horecaos.platform.fiscal.infrastructure.FakeFiscalTerminalAdapter;

/**
 * A fiscal terminal that fails on demand (ADR 0007), in test sources only.
 *
 * <p>The reason this exists rather than a sandbox: a real terminal cannot be asked to accept a request and lose the
 * reply, and that is the case that issues a second legal receipt if a caller gets it wrong. Scenario selection is a
 * queue the test fills, never a header or a flag the production fake reads; ADR 0007 forbids a scenario switch in
 * production provider code, so this one does not ship.
 *
 * <p>It wraps the production fake, so what it does when nothing is scripted is exactly what the fake does, and every
 * scripted failure that reaches the terminal (a lost reply, a timeout, a malformed body) really issues the receipt
 * first: that is what makes the failure uncertain rather than a refusal.
 */
public final class ControlledFiscalTerminal implements FiscalTerminalPort {

    private final FakeFiscalTerminalAdapter terminal;
    private final Deque<FiscalTerminalPortContract.Scenario> scripted = new ArrayDeque<>();
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final List<IssueRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean lookupsFail;

    public ControlledFiscalTerminal(Clock clock) {
        this.terminal = new FakeFiscalTerminalAdapter(clock);
    }

    /** The next {@link #issue} takes this scenario. */
    public void next(FiscalTerminalPortContract.Scenario scenario) {
        scripted.addLast(scenario);
    }

    /** Every {@link #lookup} answers that the terminal cannot be reached, until switched off. */
    public void lookupsFail(boolean fail) {
        this.lookupsFail = fail;
    }

    /** How many times issuing was asked, whatever the answer. */
    public int issueCalls() {
        return asked.get();
    }

    public int lookupCalls() {
        return lookups.get();
    }

    /** Every request issuing was asked, in order: what the caller put on the wire. */
    public List<IssueRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public IssueOutcome issue(IssueRequest request) {
        asked.incrementAndGet();
        requests.add(request);
        FiscalTerminalPortContract.Scenario scenario = scripted.pollFirst();
        if (scenario == null) {
            return terminal.issue(request);
        }
        return switch (scenario) {
            case OFFLINE -> new Unreachable("TERMINAL_OFFLINE");
            case REFUSE -> new Rejected("SHIFT_CLOSED");
            case ACCEPT_THEN_LOSE_THE_REPLY -> {
                terminal.issue(request);
                yield new Uncertain("REPLY_LOST");
            }
            case TIME_OUT_AFTER_SENDING -> {
                terminal.issue(request);
                yield new Uncertain("TIMEOUT");
            }
            case MALFORMED_BODY -> {
                terminal.issue(request);
                yield new Uncertain("MALFORMED_BODY");
            }
        };
    }

    @Override
    public IssueOutcome lookup(UUID tenantId, String terminalReference, String idempotencyKey) {
        lookups.incrementAndGet();
        if (lookupsFail) {
            return new Unreachable("TERMINAL_OFFLINE");
        }
        return terminal.lookup(tenantId, terminalReference, idempotencyKey);
    }
}
