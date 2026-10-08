package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0165, new guard 1: the capability list cannot grow a stored-value verb
 * without somebody writing an ADR.
 *
 * <p>HorecaOS holds no customer funds. The structure already makes that true
 * (a closed set of ledger entry types, no deposit payment method), and what no
 * test watched was the one place a stored-value feature would announce itself
 * first: a capability that lets somebody do it. A "deposit.refund", a "wallet.topup" or
 * a "gift-card.issue" is the permission half of a feature, and it is written
 * before any table is, so this is where the build can say no early.
 *
 * <p>The codes are compared after removing {@code -} and {@code _} and lower-casing,
 * so {@code top-up}, {@code gift-card} and {@code stored_value} are caught as
 * well as their plain spellings.
 *
 * <p><strong>The exceptions are named, and asserted exactly.</strong> A test over
 * code strings cannot tell a courier payout from a points payout, nor a tenant
 * paying HorecaOS from a customer paying a tenant, so the four it permits are listed
 * by code and the list is compared for equality, not for containment: a new exception is
 * a visible diff in this file, and an ADR with it.
 *
 * <ul>
 *   <li>{@code commercial.wallet.read}, {@code commercial.wallet.manage} and
 *       {@code commercial.wallet.topup}: ADR 0095's tenant wallet, money a
 *       <em>tenant</em> paid <em>HorecaOS</em> in advance for HorecaOS's own subscription.
 *       HorecaOS is the seller; ADR 0165's table says "nobody should cite one as
 *       precedent for the other". ADR 0165 names the first two; the third was added
 *       by the wallet wave after it was written, for the tenant's own act of paying
 *       in, and belongs to the same ledger.
 *   <li>{@code courier.payout.authorise}: authorises the payout of a closed
 *       courier-settlement period (ADR 0042), pay owed to a courier, no customer funds.
 * </ul>
 */
class StoredValueCapabilityStemTests {

    /** Compared after {@code -} and {@code _} are removed and case is folded. */
    static final List<String> STEMS =
            List.of("deposit", "topup", "withdraw", "payout", "wallet", "storedvalue", "giftcard");

    /** The only capability codes allowed to contain a stem, compared for equality. */
    static final Set<String> NAMED_EXCEPTIONS = Set.of(
            "commercial.wallet.read",
            "commercial.wallet.manage",
            "commercial.wallet.topup",
            "courier.payout.authorise");

    static boolean containsStem(String code) {
        String folded = code.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return STEMS.stream().anyMatch(folded::contains);
    }

    static List<String> offenders(Collection<String> codes) {
        return codes.stream()
                .filter(StoredValueCapabilityStemTests::containsStem)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("no capability names a stored-value verb, apart from the four named ones")
    void noCapabilityNamesStoredValueApartFromTheNamedExceptions() {
        List<String> codes =
                Arrays.stream(Capability.values()).map(Capability::code).toList();

        assertThat(offenders(codes))
                .as("a capability whose code contains deposit, topup, withdraw, payout, wallet, "
                        + "storedvalue or giftcard is the permission half of a stored-value feature "
                        + "(ADR 0165). It needs an ADR that supersedes 0165 and an entry in "
                        + "NAMED_EXCEPTIONS in the same diff")
                .containsExactlyElementsOf(new TreeSet<>(NAMED_EXCEPTIONS));
    }

    @Test
    @DisplayName("every named exception is a capability that exists, so the list cannot rot into a free pass")
    void everyNamedExceptionIsARealCapability() {
        Set<String> codes =
                Arrays.stream(Capability.values()).map(Capability::code).collect(Collectors.toSet());

        assertThat(codes)
                .as("an exception for a capability that was renamed or deleted would silently "
                        + "permit the next one that reuses the spelling")
                .containsAll(NAMED_EXCEPTIONS);
    }

    @Test
    @DisplayName("the detector itself catches every spelling it claims to, so a green run above means something")
    void theDetectorFiresOnEachStemAndSpelling() {
        // The suite above would also pass if offenders() returned nothing at all and the
        // named list were empty. These are the inputs that make it fail: one per stem,
        // in the punctuation a real code would use.
        assertThat(offenders(List.of(
                        "customer.deposit.refund",
                        "customer.top-up.issue",
                        "customer.topup.issue",
                        "loyalty.withdraw",
                        "loyalty.pay_out.authorise",
                        "customer.payout.authorise",
                        "customer.wallet.read",
                        "customer.stored-value.manage",
                        "customer.stored_value.manage",
                        "marketing.gift-card.issue",
                        "marketing.giftcard.issue")))
                .hasSize(11);

        // And it does not fire on ordinary codes, including the neighbours that look closest.
        assertThat(offenders(List.of(
                        "loyalty.adjust",
                        "payment.read",
                        "refund.execute",
                        "courier.settlement.read",
                        "commercial.statement.issue")))
                .isEmpty();
    }
}
