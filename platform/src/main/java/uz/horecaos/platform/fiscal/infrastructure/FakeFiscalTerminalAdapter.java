package uz.horecaos.platform.fiscal.infrastructure;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;

/**
 * A fiscal terminal that always works, for the local stack and for the suites that need a terminal that answers
 * (ADR 0166 «Providers», ADR 0007).
 *
 * <p>It honours the port's contract and nothing more: it issues once per idempotency key, answers the same
 * receipt for a repeated key, reads back what it issued, and refuses an empty receipt or one whose lines do not add
 * up to what was paid. <strong>It has no way to fail on demand, deliberately.</strong> ADR 0007 forbids a scenario
 * switch in production provider code, and a fake that shipped one would be a switch somebody could throw; the
 * failure cases (an accepted-then-lost reply, a timeout, a malformed body, an offline terminal) belong to the
 * controlled double in test sources, which the same contract suite runs against.
 *
 * <p>The receipt it returns is visibly not a receipt: its sign starts {@code FAKE-}, its link is on a host no tax
 * authority owns, and nothing in this class is registered anywhere outside the local profile
 * ({@code FakeFiscalTerminalConfiguration}).
 */
public final class FakeFiscalTerminalAdapter implements FiscalTerminalPort {

    private final Clock clock;
    private final Map<String, Issued> issuedByKey = new ConcurrentHashMap<>();
    private final AtomicLong receiptNumbers = new AtomicLong(1000);

    public FakeFiscalTerminalAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public IssueOutcome issue(IssueRequest request) {
        if (request.lines().isEmpty()) {
            return new Rejected("NO_RECEIPT_LINES");
        }
        long total = 0;
        for (uz.horecaos.platform.fiscal.api.SentReceiptEvidence.Line line : request.lines()) {
            long gross = line.quantity()
                    .multiply(java.math.BigDecimal.valueOf(line.unitPriceMinor()))
                    .longValueExact();
            total += gross - (line.discountMinor() == null ? 0 : line.discountMinor());
        }
        if (total != request.paidMinor()) {
            return new Rejected("RECEIPT_DOES_NOT_MATCH_PAYMENT");
        }
        return issuedByKey.computeIfAbsent(key(request.tenantId(), request.idempotencyKey()), ignored -> {
            long number = receiptNumbers.incrementAndGet();
            String terminal = "FAKE-" + request.terminalReference();
            return new Issued(
                    "FAKE-SIGN-" + Long.toHexString(number).toUpperCase(java.util.Locale.ROOT),
                    "F-" + number,
                    terminal,
                    "https://fake-ofd.invalid/receipt/" + number,
                    clock.instant());
        });
    }

    @Override
    public IssueOutcome lookup(UUID tenantId, String terminalReference, String idempotencyKey) {
        Issued issued = issuedByKey.get(key(tenantId, idempotencyKey));
        return issued == null ? new NotFound() : issued;
    }

    private static String key(UUID tenantId, String idempotencyKey) {
        return tenantId + ":" + idempotencyKey;
    }
}
