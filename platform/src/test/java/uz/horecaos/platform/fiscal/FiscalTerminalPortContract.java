package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort.IssueRequest;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;

/**
 * What every {@link FiscalTerminalPort} must do, whoever implements it (ADR 0007, ADR 0166 «Providers»).
 *
 * <p>The adapter for the first real vendor extends this class and supplies its own port and its own way of making
 * that vendor misbehave (the other adapters do it with {@code ControlledFakeProvider}, an HTTP server that accepts a
 * request and loses the reply). The cases are the four ADR 0166 names, because a fiscal receipt is the one outbound
 * call in this platform that has a legal effect and cannot be taken back:
 *
 * <ul>
 *   <li>a <strong>duplicate submission</strong> issues once;</li>
 *   <li>an <strong>accepted-then-lost reply</strong> is uncertain and the receipt is found by looking, never by
 *       asking again;</li>
 *   <li>a <strong>timeout</strong> after the request left is the same;</li>
 *   <li>a <strong>malformed body</strong> after the request left is the same, not a refusal: the terminal may have
 *       issued, and calling it rejected would invite a second receipt.</li>
 * </ul>
 *
 * <p>An implementation that cannot fail on demand (the production fake, which ADR 0007 forbids from carrying a
 * scenario switch) runs the first group only; the second group is skipped and says why.
 */
public abstract class FiscalTerminalPortContract {

    protected static final UUID TENANT = UUID.fromString("018f7b20-3000-7000-8000-0000000000a1");
    protected static final UUID ENTITY = UUID.fromString("018f7b20-3000-7000-8000-0000000000a2");
    protected static final String TERMINAL = "Kassa 2";

    /** How a double is made to misbehave. */
    public enum Scenario {
        /** The request reaches the terminal, which issues the receipt, and the answer never comes back. */
        ACCEPT_THEN_LOSE_THE_REPLY,
        /** The request reaches the terminal and the read times out. */
        TIME_OUT_AFTER_SENDING,
        /** The terminal answers with a body nobody can read. */
        MALFORMED_BODY,
        /** The terminal is offline and the request never leaves. */
        OFFLINE,
        /** The terminal says no. */
        REFUSE
    }

    protected abstract FiscalTerminalPort port();

    /** Whether this implementation can be made to fail on demand. */
    protected boolean supportsScenarios() {
        return false;
    }

    /** Makes the next {@code issue} take this scenario. Only called when {@link #supportsScenarios()}. */
    protected void next(Scenario scenario) {
        throw new UnsupportedOperationException("this implementation cannot fail on demand");
    }

    protected static SentReceiptEvidence.Line plov(long unitPriceMinor, int quantity) {
        return new SentReceiptEvidence.Line(
                SentReceiptEvidence.KIND_ITEM,
                "Plov",
                "10901001001000000",
                "1500123",
                796L,
                BigDecimal.valueOf(quantity),
                unitPriceMinor,
                unitPriceMinor * quantity * 12 / 112,
                12,
                null,
                null,
                0,
                null);
    }

    protected static IssueRequest request(String key, long paidMinor, SentReceiptEvidence.Line... lines) {
        return new IssueRequest(
                TENANT, UUID.randomUUID(), UUID.randomUUID(), ENTITY, TERMINAL, key, "UZS", List.of(lines), paidMinor);
    }

    // ------------------------------------------------------------------------------------ every implementation

    @Test
    @DisplayName("a valid receipt is issued, and the answer carries what the terminal printed")
    void issuesAReceipt() {
        var outcome = port().issue(request("k-issue", 50_000, plov(25_000, 2)));

        assertThat(outcome).isInstanceOfSatisfying(FiscalTerminalPort.Issued.class, issued -> {
            assertThat(issued.fiscalSign()).isNotBlank();
            assertThat(issued.receiptNumber()).isNotBlank();
            assertThat(issued.terminalId()).isNotBlank();
            assertThat(issued.registeredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a duplicate submission issues once: the second answer is the first receipt")
    void aRepeatedKeyIsOneReceipt() {
        var first = port().issue(request("k-dup", 25_000, plov(25_000, 1)));
        var second = port().issue(request("k-dup", 25_000, plov(25_000, 1)));

        assertThat(first).isInstanceOf(FiscalTerminalPort.Issued.class);
        assertThat(second).as("the same receipt, not a second one").isEqualTo(first);
    }

    @Test
    @DisplayName("a different key is a different sale and a different receipt")
    void aDifferentKeyIsADifferentReceipt() {
        var first = (FiscalTerminalPort.Issued) port().issue(request("k-a", 25_000, plov(25_000, 1)));
        var second = (FiscalTerminalPort.Issued) port().issue(request("k-b", 25_000, plov(25_000, 1)));

        assertThat(second.fiscalSign()).isNotEqualTo(first.fiscalSign());
        assertThat(second.receiptNumber()).isNotEqualTo(first.receiptNumber());
    }

    @Test
    @DisplayName("looking up a key reads back the receipt that was issued for it, and issues nothing")
    void lookupReadsBack() {
        var issued = port().issue(request("k-look", 25_000, plov(25_000, 1)));

        assertThat(port().lookup(TENANT, TERMINAL, "k-look")).isEqualTo(issued);
    }

    @Test
    @DisplayName("looking up a key the terminal never saw is not found, and creates no receipt")
    void lookupOfAnUnknownKey() {
        assertThat(port().lookup(TENANT, TERMINAL, "k-never")).isInstanceOf(FiscalTerminalPort.NotFound.class);
        assertThat(port().lookup(TENANT, TERMINAL, "k-never"))
                .as("asking twice is still asking: a lookup issues nothing")
                .isInstanceOf(FiscalTerminalPort.NotFound.class);
    }

    @Test
    @DisplayName("a receipt with no lines, or lines that do not add up to what was paid, is refused")
    void aReceiptThatCannotBeRightIsRefused() {
        assertThat(port().issue(request("k-empty", 0))).isInstanceOf(FiscalTerminalPort.Rejected.class);
        assertThat(port().issue(request("k-short", 24_000, plov(25_000, 1))))
                .isInstanceOf(FiscalTerminalPort.Rejected.class);
        assertThat(port().lookup(TENANT, TERMINAL, "k-short")).isInstanceOf(FiscalTerminalPort.NotFound.class);
    }

    // -------------------------------------------------------------------- implementations that can fail on demand

    @Test
    @DisplayName("an accepted-then-lost reply is uncertain, and the receipt that was issued is found by looking")
    void anAcceptedThenLostReplyIsUncertain() {
        Assumptions.assumeTrue(supportsScenarios(), "this implementation cannot be made to lose a reply on demand");
        next(Scenario.ACCEPT_THEN_LOSE_THE_REPLY);

        var outcome = port().issue(request("k-lost", 25_000, plov(25_000, 1)));

        assertThat(outcome).isInstanceOf(FiscalTerminalPort.Uncertain.class);
        assertThat(port().lookup(TENANT, TERMINAL, "k-lost"))
                .as("the terminal did issue it: the way to know is to look, never to ask again")
                .isInstanceOf(FiscalTerminalPort.Issued.class);
        assertThat(port().issue(request("k-lost", 25_000, plov(25_000, 1))))
                .as("and asking again, which the caller never does, still does not issue a second")
                .isEqualTo(port().lookup(TENANT, TERMINAL, "k-lost"));
    }

    @Test
    @DisplayName("a timeout after the request left is uncertain, not unreachable and not a refusal")
    void aTimeoutAfterSendingIsUncertain() {
        Assumptions.assumeTrue(supportsScenarios(), "this implementation cannot be made to time out on demand");
        next(Scenario.TIME_OUT_AFTER_SENDING);

        assertThat(port().issue(request("k-timeout", 25_000, plov(25_000, 1))))
                .isInstanceOf(FiscalTerminalPort.Uncertain.class);
    }

    @Test
    @DisplayName("a malformed body after the request left is uncertain, because the terminal may have issued")
    void aMalformedBodyIsUncertain() {
        Assumptions.assumeTrue(supportsScenarios(), "this implementation cannot be made to answer garbage on demand");
        next(Scenario.MALFORMED_BODY);

        assertThat(port().issue(request("k-garbage", 25_000, plov(25_000, 1))))
                .as("not Rejected: calling it refused would invite a second receipt")
                .isInstanceOf(FiscalTerminalPort.Uncertain.class);
    }

    @Test
    @DisplayName("an offline terminal is unreachable, safe to ask again, and nothing was issued")
    void anOfflineTerminalIsUnreachable() {
        Assumptions.assumeTrue(supportsScenarios(), "this implementation cannot be made to go offline on demand");
        next(Scenario.OFFLINE);

        assertThat(port().issue(request("k-offline", 25_000, plov(25_000, 1))))
                .isInstanceOf(FiscalTerminalPort.Unreachable.class);
        assertThat(port().lookup(TENANT, TERMINAL, "k-offline")).isInstanceOf(FiscalTerminalPort.NotFound.class);
    }

    @Test
    @DisplayName("a refusal is rejected with a stable code, and nothing was issued")
    void aRefusalIsRejected() {
        Assumptions.assumeTrue(supportsScenarios(), "this implementation cannot be made to refuse on demand");
        next(Scenario.REFUSE);

        assertThat(port().issue(request("k-refused", 25_000, plov(25_000, 1))))
                .isInstanceOfSatisfying(
                        FiscalTerminalPort.Rejected.class,
                        rejected -> assertThat(rejected.code()).matches("[A-Z][A-Z0-9_]+"));
        assertThat(port().lookup(TENANT, TERMINAL, "k-refused")).isInstanceOf(FiscalTerminalPort.NotFound.class);
    }
}
