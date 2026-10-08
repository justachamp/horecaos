package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.BonusGrantBalance;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The ledger's own invariants (ADR 0095, decision 1), held over a long, seeded, mixed history rather than
 * over the one scenario each feature's test happens to build.
 *
 * <p>The loyalty ledger died of a stored balance that drifted from its entries; this ledger has no stored
 * balance, so the quantities that CAN break are the ones the application, not the schema, is responsible
 * for — a grant drawn below zero, a statement paid for more than it was billed, money the provider took
 * that the ledger does not hold, an entry that changed after it was written. Those are checked after
 * EVERY operation, over transfers, card top-ups, bonus grants that lapse, approved corrections and
 * refunds, statements issued and voided and issued again, invoices paid by wire, and the settlement
 * sweeper, in an order a person never chose.
 *
 * <p>Each assertion is the adjacent-quantity kind CLAUDE.md warns about: the balance is compared with an
 * independent {@code SUM}, the decomposition by entry type is recomputed in Java, and the provider's own
 * record of what it charged is compared with the ledger rather than the ledger with itself.
 */
class WalletLedgerInvariantTests extends WalletBillingFixture {

    private static final long SEED = 20_261_007L;
    private static final int STEPS = 84;

    private final Map<UUID, String> seen = new HashMap<>();
    private int sequence;

    @Test
    void whateverIsDoneInWhateverOrderTheLedgerStaysTheSumOfItsOwnAppendOnlyEntries() {
        activateFake();
        configureBankDetails();
        for (UUID tenant : List.of(PILOT, RIVAL)) {
            startOnPlan(tenant, START, MONTHLY);
            bindCard(tenant, FakeCardProvider.APPROVING_CARD);
        }
        cardOnFile.chooseMethod(RIVAL, PaymentMethod.CARD, TENANT_USER, "c");
        Random random = new Random(SEED);
        WalletBonusExpirySweeper expiry = new WalletBonusExpirySweeper(wallet, clock, 200);
        Instant phaseStart = START;
        int operationsRun = 0;

        for (int step = 0; step < STEPS; step++) {
            sequence = step;
            if (step == 20) {
                closeMonth("2026-10", Instant.parse("2026-11-05T09:00:00Z"));
                expiry.runOnce();
                phaseStart = Instant.parse("2026-11-06T09:00:00Z");
            } else if (step == 40) {
                closeMonth("2026-11", Instant.parse("2026-12-05T09:00:00Z"));
                expiry.runOnce();
                phaseStart = Instant.parse("2026-12-06T09:00:00Z");
            } else if (step == 55) {
                voidAndIssueAgain(PILOT, "2026-10");
            } else if (step == 62) {
                closeMonth("2026-12", Instant.parse("2027-01-05T09:00:00Z"));
                expiry.runOnce();
                phaseStart = Instant.parse("2027-01-06T09:00:00Z");
            } else {
                clock.set(phaseStart.plus(Duration.ofMinutes(step)));
                operationsRun += randomOperation(random, expiry) ? 1 : 0;
            }
            assertInvariants();
            if (step % 9 == 0) {
                theLedgerRefusesAnEditAndADeleteFromEveryRole();
            }
        }

        assertThat(operationsRun)
                .as("the run did real work, not a series of refusals")
                .isGreaterThan(40);
        assertThat(ledgerSize(PILOT) + ledgerSize(RIVAL)).isGreaterThan(60);
        assertThat(fake.successfulCharges()).as("the card was really used").isNotEmpty();
        assertThat(jdbc.sql("SELECT count(DISTINCT entry_type) FROM commercial.wallet_entries")
                        .query(Long.class)
                        .single())
                .as("the history touched most of what a ledger can hold")
                .isGreaterThanOrEqualTo(7);
    }

    // ------------------------------------------------------------------ operations

    /** One random thing a person or the platform might do; false when it was legitimately refused. */
    private boolean randomOperation(Random random, WalletBonusExpirySweeper expiry) {
        UUID tenant = random.nextBoolean() ? PILOT : RIVAL;
        try {
            switch (random.nextInt(10)) {
                case 0, 1 ->
                    recordTransfer(tenant, 10_000 + random.nextInt(2_000_000), "MT-" + sequence + "-" + tenant);
                case 2, 3 -> topUps.requestTopUp(tenant, 10_000 + random.nextInt(1_500_000), TENANT_USER, "c");
                case 4 -> grantBonus(tenant, 10_000 + random.nextInt(300_000), 1 + random.nextInt(45));
                case 5 -> refundSome(tenant, random);
                case 6 -> adjust(tenant, random);
                case 7 -> payAnInvoice(tenant, random);
                case 8 -> {
                    wallet.settleCardRemainders(RIVAL);
                    sweeper.runOnce();
                }
                default -> expiry.runOnce();
            }
            return true;
        } catch (ApiException refused) {
            // A refusal is a legitimate answer (not enough paid money to refund, too many open invoices);
            // the invariants below must hold after it exactly as after a success.
            return false;
        }
    }

    private void closeMonth(String periodKey, Instant at) {
        for (UUID tenant : List.of(PILOT, RIVAL)) {
            issue(tenant, periodKey, at);
        }
        wallet.settleCardRemainders(RIVAL);
    }

    private void voidAndIssueAgain(UUID tenant, String periodKey) {
        clock.set(Instant.parse("2026-12-20T09:00:00Z"));
        Statement standing = statements.list(tenant).stream()
                .filter(s -> periodKey.equals(s.periodKey()) && Statement.ISSUED.equals(s.status()))
                .findFirst()
                .orElseThrow();
        UUID statementId = java.util.Objects.requireNonNull(standing.id());
        inTxDo(() -> statements.voidStatement(tenant, statementId, MAKER, "a wrong line, step " + sequence, "corr"));
        inTx(() -> statements.issue(tenant, periodKey, MAKER, "issued again, step " + sequence, "corr"));
    }

    private void grantBonus(UUID tenant, long amount, int days) {
        Instant expiresAt = clock.instant().plus(Duration.ofDays(days));
        applyChange(
                () -> wallet.proposeBonusGrant(tenant, amount, expiresAt, MAKER, "a credit, step " + sequence, "corr"));
    }

    private void refundSome(UUID tenant, Random random) {
        long paid = wallet.balances(tenant).paidMinor();
        if (paid <= 0) {
            return;
        }
        long amount = 1 + (long) (random.nextDouble() * paid);
        applyChange(() -> wallet.proposeRefund(
                tenant, amount, "PAYOUT-" + sequence + "-" + tenant, MAKER, "leaving, step " + sequence, "corr"));
    }

    private void adjust(UUID tenant, Random random) {
        long paid = wallet.balances(tenant).paidMinor();
        boolean down = paid > 0 && random.nextBoolean();
        long amount = down ? -(1 + (long) (random.nextDouble() * paid)) : 1 + random.nextInt(400_000);
        applyChange(() -> wallet.proposeAdjustment(
                tenant, WalletEntry.PAID, null, amount, MAKER, "correction, step " + sequence, "corr"));
    }

    private void payAnInvoice(UUID tenant, Random random) {
        PrepaymentInvoice invoice =
                inTx(() -> invoices.issue(tenant, 50_000 + random.nextInt(1_000_000), TENANT_USER, "c"));
        long wire = 1 + (long) (random.nextDouble() * invoice.amountMinor() * 1.2);
        inTx(() -> invoices.recordTransfer(
                tenant, invoice.number(), wire, "WIRE-" + sequence + "-" + tenant, MAKER, "wire", "c"));
    }

    // ------------------------------------------------------------------- invariants

    private void assertInvariants() {
        for (UUID tenant : List.of(PILOT, RIVAL)) {
            WalletBalances balances = wallet.balances(tenant);
            List<WalletEntry> entries = wallet.ledger(tenant, null, 1_000_000);

            // 1. A balance is the SUM of its own entries, and neither kind of money is ever negative.
            assertThat(balances.paidMinor())
                    .as("paid balance of %s at step %d", tenant, sequence)
                    .isEqualTo(ledgerSum(tenant, WalletEntry.PAID));
            assertThat(balances.bonusMinor())
                    .as("bonus balance of %s at step %d", tenant, sequence)
                    .isEqualTo(ledgerSum(tenant, WalletEntry.BONUS));
            assertThat(balances.paidMinor())
                    .as("paid money below zero at step %d", sequence)
                    .isGreaterThanOrEqualTo(0);
            assertThat(balances.bonusMinor())
                    .as("bonus money below zero at step %d", sequence)
                    .isGreaterThanOrEqualTo(0);

            // 2. The same figure rebuilt from the entries one by one, by type, in Java.
            long rebuiltPaid = 0;
            for (WalletEntry entry : entries) {
                if (WalletEntry.PAID.equals(entry.moneyKind())) {
                    rebuiltPaid += entry.amountMinor();
                }
            }
            assertThat(rebuiltPaid).isEqualTo(balances.paidMinor());

            // 3. No bonus grant is ever drawn below zero.
            for (WalletEntry grant : entries) {
                if (WalletEntry.BONUS_GRANT.equals(grant.entryType())) {
                    BonusGrantBalance remaining =
                            walletStore.findGrant(tenant, grant.id()).orElseThrow();
                    assertThat(remaining.remainingMinor())
                            .as("grant %s overdrawn at step %d", grant.id(), sequence)
                            .isGreaterThanOrEqualTo(0);
                }
            }

            // 4. A statement is never paid for more than it was billed, nor for less than nothing,
            //    and what the service says is paid is what the ledger says.
            for (StatementPayment payment : wallet.statementPayments(tenant)) {
                assertThat(payment.paidMinor())
                        .as("statement %s at step %d", payment.number(), sequence)
                        .isBetween(0L, payment.totalMinor());
                assertThat(payment.dueMinor()).isEqualTo(payment.totalMinor() - payment.paidMinor());
                long ledgerPaid = jdbc.sql("""
                                SELECT COALESCE(-SUM(amount_minor), 0) FROM commercial.wallet_entries
                                 WHERE tenant_id = :id AND statement_id = :statement
                                   AND entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL')
                                """)
                        .param("id", tenant)
                        .param("statement", payment.statementId())
                        .query(Long.class)
                        .single();
                assertThat(payment.paidMinor()).isEqualTo(ledgerPaid);
            }

            // 5. A card top-up that succeeded names the entry that holds exactly its money.
            for (CardTopUp topUp : topUps.recent(tenant, 1000)) {
                if (CardTopUp.SUCCEEDED.equals(topUp.outcome())) {
                    long credited = jdbc.sql(
                                    "SELECT amount_minor FROM commercial.wallet_entries WHERE tenant_id = :t AND id = :id")
                            .param("t", tenant)
                            .param("id", topUp.walletEntryId())
                            .query(Long.class)
                            .single();
                    assertThat(credited).isEqualTo(topUp.amountMinor());
                }
            }

            // 6. What an invoice has been paid is the ledger's own sum.
            for (PrepaymentInvoice invoice : invoices.list(tenant)) {
                long named = jdbc.sql("""
                                SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                                 WHERE tenant_id = :t AND prepayment_invoice_id = :invoice
                                """)
                        .param("t", tenant)
                        .param("invoice", invoice.id())
                        .query(Long.class)
                        .single();
                assertThat(invoice.paidMinor()).isEqualTo(named);
            }
        }

        // 7. The provider's own record of what it charged is the ledger's money in by card: nothing
        //    taken that the ledger lacks, nothing credited that the provider never took.
        long charged = fake.successfulCharges().stream()
                .mapToLong(c -> c.amountMinor())
                .sum();
        long credited = jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE entry_type = 'TOP_UP' AND external_reference LIKE 'FAKE-%'
                        """).query(Long.class).single();
        assertThat(credited)
                .as("card money on the ledger against card money the provider took, step %d", sequence)
                .isEqualTo(charged);

        // 8. Append-only, observed: every entry ever seen is still there, byte for byte, and none vanished.
        Map<UUID, String> now = new HashMap<>();
        jdbc.sql("SELECT id, row_to_json(w)::text AS body FROM commercial.wallet_entries w")
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), row.getString("body")))
                .list()
                .forEach(entry -> now.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<UUID, String> before : seen.entrySet()) {
            assertThat(now).as("an entry vanished at step %d", sequence).containsKey(before.getKey());
            assertThat(now.get(before.getKey()))
                    .as("entry %s changed at step %d", before.getKey(), sequence)
                    .isEqualTo(before.getValue());
        }
        assertThat(now.size()).isGreaterThanOrEqualTo(seen.size());
        seen.putAll(now);
    }

    private void theLedgerRefusesAnEditAndADeleteFromEveryRole() {
        UUID entry = jdbc.sql("SELECT id FROM commercial.wallet_entries ORDER BY created_at, id LIMIT 1")
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (entry == null) {
            return;
        }
        long before = jdbc.sql("SELECT count(*) FROM commercial.wallet_entries")
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> application
                        .sql("UPDATE commercial.wallet_entries SET amount_minor = amount_minor + 1 WHERE id = :id")
                        .param("id", entry)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM commercial.wallet_entries WHERE id = :id")
                        .param("id", entry)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE commercial.wallet_entries SET amount_minor = amount_minor + 1 WHERE id = :id")
                        .param("id", entry)
                        .update())
                .as("even the owner, which the grant does not bind, is stopped by the trigger")
                .hasMessageContaining("never changed or deleted");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.wallet_entries WHERE id = :id")
                        .param("id", entry)
                        .update())
                .hasMessageContaining("never changed or deleted");

        assertThat(jdbc.sql("SELECT count(*) FROM commercial.wallet_entries")
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }
}
