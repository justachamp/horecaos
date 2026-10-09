package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import uz.horecaos.platform.commercial.domain.CardOnFile;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.commercial.infrastructure.PlatformCardGateway;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0095, the recurring token flow, against PostgreSQL and the fake provider behind the real gateway:
 * a tenant puts its own card on file through the provider's form, the card is charged for a statement's
 * remainder without the tenant present, and nothing is ever charged twice.
 *
 * <p>The assertions that matter are the ones a working-but-wrong flow would still pass a lazier test:
 * that the money the provider took is the money on the ledger (the fake keeps its own record of what it
 * charged, so a double charge shows there and nowhere else), that an answer lost after the money moved is
 * recorded rather than charged again, and that a card bound under one merchant account is never handed to
 * another.
 */
class CardRecurringTokenFlowTests extends WalletBillingFixture {

    // ------------------------------------------------------------ before any account exists

    @Test
    void noCardCanBeAddedWhileNoMerchantAccountIsConnected() {
        assertThatThrownBy(() -> cardOnFile.beginEnrolment(PILOT))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "CARDS_NOT_AVAILABLE"));

        assertThat(gateway.available()).isFalse();
        assertThat(wallet.cardOnFile(PILOT)).isEmpty();
    }

    @Test
    void aCardTenantIsCollectedLikeAnInvoiceOneWhileNoMerchantAccountIsConnected() {
        startOnPlan(START, MONTHLY);
        // Staff typed a reference before any account existed: the pre-existing path, unchanged.
        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:typed", MAKER, "asked", "c"));
        issue(PILOT, "2026-10", CLOSE);

        assertThat(wallet.settleCardRemainders(PILOT)).isZero();

        assertThat(statementPayment(PILOT, "2026-10").dueMinor())
                .as("nothing collected the remainder, exactly as for an invoice tenant")
                .isEqualTo(MONTHLY);
        assertThat(fake.successfulCharges()).isEmpty();
    }

    // ----------------------------------------------------------------- the enrolment flow

    @Test
    void aTenantPutsItsOwnCardOnFileAndHowItIsCollectedDoesNotChange() {
        UUID installationId = activateFake();

        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);

        CardOnFile card = wallet.cardOnFile(PILOT).orElseThrow();
        assertThat(card.last4()).isEqualTo("4242");
        assertThat(card.brand()).isEqualTo("HUMO");
        YearMonth expiry = YearMonth.from(START.atZone(ZoneOffset.UTC)).plusYears(3);
        assertThat(card.expiryMonth()).isEqualTo(expiry.getMonthValue());
        assertThat(card.expiryYear()).isEqualTo(expiry.getYear());
        assertThat(card.boundBy()).isEqualTo(TENANT_USER.subject());

        TenantBilling billing = wallet.billing(PILOT);
        assertThat(billing.paymentMethod())
                .as("a card to top up with is not consent to be charged for every statement")
                .isEqualTo(PaymentMethod.INVOICE);
        assertThat(billing.cardTokenReference())
                .as("minted under the account that is active, so a later change of account cannot misuse it")
                .startsWith(installationId + ":");

        assertThat(auditedActions("commercial.wallet.card_")).containsExactly("commercial.wallet.card_bound");
        assertThat(jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :code")
                        .param("code", "commercial.wallet.card_bound")
                        .query(String.class)
                        .single())
                .as("the audit fact says which card, and carries neither the reference nor the provider token")
                .contains("4242")
                .doesNotContain("fake_card_")
                .doesNotContain(installationId.toString());
    }

    @Test
    void aWrongBankCodeOrAnUnknownCardTokenPutsNothingOnFile() {
        activateFake();
        CardEnrolment.BeginOutcome.Begun begun = cardOnFile.beginEnrolment(PILOT);

        assertThatThrownBy(() -> cardOnFile.confirmEnrolment(
                        PILOT, begun.sessionReference(), FakeCardProvider.APPROVING_CARD, "999999", TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "WRONG_CODE"));
        assertThatThrownBy(() -> cardOnFile.confirmEnrolment(
                        PILOT,
                        begun.sessionReference(),
                        "tok_something_else",
                        FakeCardProvider.VERIFICATION_CODE,
                        TENANT_USER,
                        "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "UNKNOWN_CARD_TOKEN"));

        assertThat(wallet.cardOnFile(PILOT)).isEmpty();
        assertThat(wallet.billing(PILOT).cardTokenReference()).isNull();

        // The cardholder may try again on the same session, as with a real provider.
        cardOnFile.confirmEnrolment(
                PILOT,
                begun.sessionReference(),
                FakeCardProvider.APPROVING_CARD,
                FakeCardProvider.VERIFICATION_CODE,
                TENANT_USER,
                "c");
        assertThat(wallet.cardOnFile(PILOT)).isPresent();
    }

    @Test
    void aSessionOpenedForOneTenantCannotBeCompletedByAnotherNorAfterItExpires() {
        activateFake();
        CardEnrolment.BeginOutcome.Begun begun = cardOnFile.beginEnrolment(PILOT);

        assertThatThrownBy(() -> cardOnFile.confirmEnrolment(
                        RIVAL,
                        begun.sessionReference(),
                        FakeCardProvider.APPROVING_CARD,
                        FakeCardProvider.VERIFICATION_CODE,
                        TENANT_USER,
                        "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "SESSION_UNKNOWN"));
        assertThat(wallet.cardOnFile(RIVAL)).isEmpty();

        clock.advance(java.time.Duration.ofMinutes(16));
        assertThatThrownBy(() -> cardOnFile.confirmEnrolment(
                        PILOT,
                        begun.sessionReference(),
                        FakeCardProvider.APPROVING_CARD,
                        FakeCardProvider.VERIFICATION_CODE,
                        TENANT_USER,
                        "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "SESSION_EXPIRED"));
    }

    @Test
    void aTenantsCardIsNeverShownToAnotherTenant() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);

        assertThat(wallet.cardOnFile(PILOT)).isPresent();
        assertThat(wallet.cardOnFile(RIVAL)).isEmpty();
        assertThat(wallet.billing(RIVAL).cardTokenReference()).isNull();
    }

    // -------------------------------------------------------------- charging a statement

    @Test
    void aStatementsRemainderIsChargedToTheCardOnFileOnceAndTheLedgerHoldsWhatTheProviderTook() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);

        long charged = wallet.settleCardRemainders(PILOT);

        assertThat(charged).isEqualTo(MONTHLY);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(fake.successfulCharges()).singleElement().satisfies(charge -> {
            assertThat(charge.amountMinor()).isEqualTo(MONTHLY);
            assertThat(charge.tenantId()).isEqualTo(PILOT);
        });
        WalletEntry topUp = wallet.ledger(PILOT, null, 10).stream()
                .filter(entry -> WalletEntry.TOP_UP.equals(entry.entryType()))
                .findFirst()
                .orElseThrow();
        assertThat(topUp.externalReference())
                .as("the provider's reference is what proves the charge happened")
                .isEqualTo("FAKE-"
                        + fake.successfulCharges()
                                .getFirst()
                                .idempotencyKey()
                                .replace("-", "")
                                .toUpperCase());
        assertThat(topUp.amountMinor()).isEqualTo(MONTHLY);

        assertThat(wallet.settleCardRemainders(PILOT))
                .as("a second pass finds nothing owed and asks the provider for nothing")
                .isZero();
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(ledgerSum(PILOT, WalletEntry.PAID))
                .as("the statement was paid out of the money that came in, which nets to zero")
                .isZero();
    }

    @Test
    void aDeclinedCardLeavesTheStatementDueAndTheDeclineOnTheRecord() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.DECLINING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        assertThat(wallet.settleCardRemainders(PILOT)).isZero();

        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);
        assertThat(fake.successfulCharges()).isEmpty();
        assertThat(jdbc.sql("SELECT outcome || ':' || provider_detail FROM commercial.card_charge_attempts")
                        .query(String.class)
                        .list())
                .containsExactly("FAILED:INSUFFICIENT_FUNDS");
        assertThat(auditedActions("commercial.wallet.card_charge"))
                .containsExactly("commercial.wallet.card_charge_declined");
    }

    @Test
    void aChargeWhoseAnswerWasLostAfterTheMoneyMovedIsRecordedOnTheNextPassAndNeverTakenTwice() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        assertThat(wallet.settleCardRemainders(PILOT))
                .as("the provider took the money and the answer was lost: nothing can be recorded yet")
                .isZero();
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);
        assertThat(jdbc.sql("SELECT outcome FROM commercial.card_charge_attempts")
                        .query(String.class)
                        .list())
                .containsExactly("PENDING");

        assertThat(wallet.settleCardRemainders(PILOT)).isEqualTo(MONTHLY);

        assertThat(fake.successfulCharges())
                .as("asked what the provider believes before charging again, so the same attempt is "
                        + "recorded and not charged a second time")
                .hasSize(1);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM commercial.wallet_entries WHERE entry_type = 'TOP_UP'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ----------------------------------------------------- the card and the merchant account

    @Test
    void aCardBoundUnderAnotherMerchantAccountIsNeverSentToTheNewOne() {
        startOnPlan(START, MONTHLY);
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        inTx(() -> installations.suspend(first, 1, MAKER, "changing accounts", "c"));
        activateFake();

        assertThat(wallet.settleCardRemainders(PILOT)).isZero();

        assertThat(fake.successfulCharges()).isEmpty();
        assertThat(jdbc.sql("SELECT provider_detail FROM commercial.card_charge_attempts")
                        .query(String.class)
                        .list())
                .as("the real problem is named, rather than the new account declining a token it never minted")
                .containsExactly("CARD_BOUND_UNDER_ANOTHER_MERCHANT_ACCOUNT");
    }

    @Test
    void aTestDoubleIsNeitherActivatedNorUsedOutsideALocalOrTestRun() {
        UUID id = activateFake();
        PlatformCardGateway strict = new PlatformCardGateway(installationStore, List.of(fake), false);

        assertThat(strict.available())
                .as("an ACTIVE row naming a test double is ignored when this is not a local or test run")
                .isFalse();
        assertThat(strict.charge(
                        PILOT, "anything", 1_000, "UZS", UUID.randomUUID().toString()))
                .isInstanceOf(CardCharger.Outcome.NotConfigured.class);
        assertThat(strict.begin(PILOT)).isInstanceOf(CardEnrolment.BeginOutcome.NotConfigured.class);

        inTx(() -> installations.suspend(id, 1, MAKER, "x", "c"));
        PlatformCardInstallationService strictInstallations =
                new PlatformCardInstallationService(installationStore, strict, topUpStore, attemptStore, audit, clock);
        UUID second = inTx(() -> strictInstallations.create(
                FakeCardProvider.PROVIDER_TYPE, null, "Another", null, null, Map.of(), MAKER, "x", "c"));
        assertThatThrownBy(() -> inTx(() -> strictInstallations.activate(second, 0, MAKER, "x", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "TEST_DOUBLE_NOT_ALLOWED"));
    }

    @Test
    void onlyOneInstallationIsActiveAtATimeAndTheDatabaseAgrees() {
        UUID first = activateFake();
        UUID second = inTx(() -> installations.create(
                FakeCardProvider.PROVIDER_TYPE, null, "Second", null, null, Map.of(), MAKER, "x", "c"));

        assertThatThrownBy(() -> inTx(() -> installations.activate(second, 0, MAKER, "x", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("reason", "ANOTHER_INSTALLATION_ACTIVE"));

        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE commercial.platform_card_installations SET status = 'ACTIVE' WHERE id = :id")
                        .param("id", second)
                        .update())
                .as("the partial unique index holds even if a future code path forgets the check")
                .isInstanceOf(DuplicateKeyException.class);

        assertThatThrownBy(() -> inTx(() -> installations.suspend(first, 0, MAKER, "stale", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode().name()).isEqualTo("STALE_VERSION"));
    }

    @Test
    void anInstallationNeedsAnAdapterAnApprovedEnvironmentAndAPaymentSecretReference() {
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        "CLICK",
                        "click-prod",
                        "Click",
                        "horecaos:production:provider_payment:platform:click",
                        null,
                        Map.of(),
                        MAKER,
                        "x",
                        "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("reason", "NO_ADAPTER_FOR_PROVIDER_TYPE"));
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        FakeCardProvider.PROVIDER_TYPE, "click-prod", "Fake", null, null, Map.of(), MAKER, "x", "c")))
                .as("a fake makes no network call, so it names no environment")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        FakeCardProvider.PROVIDER_TYPE,
                        null,
                        "Fake",
                        "horecaos:local:provider_payment:platform:k",
                        null,
                        Map.of(),
                        MAKER,
                        "x",
                        "c")))
                .as("and holds no credential")
                .isInstanceOf(ApiException.class);

        // The same rules for a provider that does carry a credential, against a stand-in adapter.
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('w6-test-card', 'PAYMENT', 'W6_TEST_CARD', 'https://card.example.invalid', false, 'card.example.invalid')
                ON CONFLICT (code) DO NOTHING
                """).update();
        PlatformCardGateway withRealType =
                new PlatformCardGateway(installationStore, List.of(fake, new StandInAdapter()), true);
        PlatformCardInstallationService service = new PlatformCardInstallationService(
                installationStore, withRealType, topUpStore, attemptStore, audit, clock);
        String secret = "horecaos:production:provider_payment:platform:w6-test";

        assertThatThrownBy(() ->
                        inTx(() -> service.create("W6_TEST_CARD", null, "T", secret, null, Map.of(), MAKER, "x", "c")))
                .as("an environment must be named")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() ->
                        service.create("W6_TEST_CARD", "not-approved", "T", secret, null, Map.of(), MAKER, "x", "c")))
                .as("and must be in the approved catalogue, never a typed URL")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() ->
                        service.create("W6_TEST_CARD", "w6-test-card", "T", null, null, Map.of(), MAKER, "x", "c")))
                .as("a credential is a secret reference")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() -> service.create(
                        "W6_TEST_CARD", "w6-test-card", "T", "a-secret-value", null, Map.of(), MAKER, "x", "c")))
                .as("and never a value")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() -> service.create(
                        "W6_TEST_CARD",
                        "w6-test-card",
                        "T",
                        "horecaos:production:object_storage:platform:k",
                        null,
                        Map.of(),
                        MAKER,
                        "x",
                        "c")))
                .as("of the payment category")
                .isInstanceOf(ApiException.class);

        UUID created = inTx(
                () -> service.create("W6_TEST_CARD", "w6-test-card", "T", secret, null, Map.of(), MAKER, "x", "c"));
        assertThat(installationStore.find(created)).isPresent();
        assertThat(installationStore.find(created).orElseThrow().toString())
                .as("a log line carrying the installation does not carry where its credential lives")
                .doesNotContain("provider_payment");

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO commercial.platform_card_installations (
                                    id, provider_type, environment_code, display_name, created_by, created_at, updated_at)
                                VALUES (gen_random_uuid(), 'W6_TEST_CARD', 'w6-test-card', 'raw', 'x', now(), now())
                                """).update())
                .as("the schema refuses a credentialed provider with no secret reference even when the "
                        + "application is bypassed")
                .hasMessageContaining("ck_platform_card_installation_secret");
    }

    // ----------------------------------------------------------- replacing and removing

    @Test
    void replacingTheCardRevokesTheOldOneAtTheProvider() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        String oldReference = wallet.billing(PILOT).cardTokenReference();

        bindCard(PILOT, FakeCardProvider.DECLINING_CARD);

        assertThat(wallet.cardOnFile(PILOT).orElseThrow().last4()).isEqualTo("0002");
        assertThat(wallet.billing(PILOT).cardTokenReference()).isNotEqualTo(oldReference);
        assertThat(gateway.charge(
                        PILOT, oldReference, 1_000, "UZS", UUID.randomUUID().toString()))
                .as("the provider forgot the old reference, so nobody can charge it, including a bug of ours")
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
    }

    @Test
    void removingTheCardTakesACardTenantBackToInvoiceAndRevokesItAtTheProvider() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        String reference = wallet.billing(PILOT).cardTokenReference();

        CardOnFileService.RemovedCard removed = cardOnFile.removeCard(PILOT, TENANT_USER, "c");

        assertThat(removed.paymentMethod())
                .as("CARD with nothing to charge is a promise nothing can keep")
                .isEqualTo(PaymentMethod.INVOICE);
        assertThat(wallet.billing(PILOT).paymentMethod()).isEqualTo(PaymentMethod.INVOICE);
        assertThat(wallet.billing(PILOT).cardTokenReference()).isNull();
        assertThat(wallet.cardOnFile(PILOT)).isEmpty();
        assertThat(gateway.charge(
                        PILOT, reference, 1_000, "UZS", UUID.randomUUID().toString()))
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
        assertThat(auditedActions("commercial.wallet.card_")).contains("commercial.wallet.card_removed");
        assertThatThrownBy(() -> cardOnFile.removeCard(PILOT, TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "NO_CARD_ON_FILE"));
    }

    @Test
    void aStatementChargeStillWaitingForItsAnswerBlocksLeavingCardAndRemovingTheCard() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);
        wallet.settleCardRemainders(PILOT);
        assertThat(jdbc.sql("SELECT outcome FROM commercial.card_charge_attempts")
                        .query(String.class)
                        .list())
                .containsExactly("PENDING");

        assertThatThrownBy(() -> cardOnFile.chooseMethod(PILOT, PaymentMethod.WALLET, TENANT_USER, "c"))
                .as("a settlement only looks at CARD tenants, so a charge nobody is looking for any more is "
                        + "money taken and never recorded")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "CHARGE_IN_FLIGHT"));
        assertThatThrownBy(() -> cardOnFile.removeCard(PILOT, TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "CHARGE_IN_FLIGHT"));
        assertThat(wallet.billing(PILOT).paymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(wallet.cardOnFile(PILOT)).isPresent();

        wallet.settleCardRemainders(PILOT);
        cardOnFile.chooseMethod(PILOT, PaymentMethod.WALLET, TENANT_USER, "c");

        assertThat(wallet.billing(PILOT).paymentMethod()).isEqualTo(PaymentMethod.WALLET);
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
    }

    @Test
    void choosingCardNeedsACardOnFileAndChoosingAnotherMethodKeepsIt() {
        activateFake();
        assertThatThrownBy(() -> cardOnFile.chooseMethod(PILOT, PaymentMethod.CARD, TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "NO_CARD_ON_FILE"));

        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        cardOnFile.chooseMethod(PILOT, PaymentMethod.WALLET, TENANT_USER, "c");

        assertThat(wallet.billing(PILOT).paymentMethod()).isEqualTo(PaymentMethod.WALLET);
        assertThat(wallet.cardOnFile(PILOT))
                .as("the card stays on file: it is what a WALLET tenant tops up with")
                .isPresent();
        assertThat(auditedActions("commercial.wallet.payment_method_changed")).hasSize(2);
    }

    // ------------------------------------------- the staff path, unchanged in what it promises

    @Test
    void staffSwitchingMethodDoesNotTakeATenantsOwnCardOffFile() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        String reference = wallet.billing(PILOT).cardTokenReference();

        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.WALLET, null, MAKER, "asked", "c"));
        assertThat(wallet.billing(PILOT).cardTokenReference()).isEqualTo(reference);

        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, null, MAKER, "asked", "c"));
        assertThat(wallet.billing(PILOT).cardTokenReference())
                .as("choosing CARD without naming a reference keeps the card on file")
                .isEqualTo(reference);
        assertThat(wallet.cardOnFile(PILOT).orElseThrow().last4()).isEqualTo("4242");
    }

    @Test
    void aReferenceStaffTypeReplacesTheCardAndForgetsWhatWasShownAboutTheOldOne() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);

        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:typed", MAKER, "asked", "c"));

        CardOnFile card = wallet.cardOnFile(PILOT).orElseThrow();
        assertThat(card.last4())
                .as("what was shown described a card that is no longer on file")
                .isNull();
        assertThat(wallet.billing(PILOT).cardTokenReference()).isEqualTo("vault:typed");
        assertThatThrownBy(() -> inTxDo(() ->
                        wallet.setPaymentMethod(PILOT, PaymentMethod.INVOICE, "vault:typed", MAKER, "asked", "c")))
                .as("staff still cannot attach a reference to a method that does not charge")
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- a stand-in adapter

    /** A provider that carries a credential, for the installation rules the fake is exempt from. */
    private static final class StandInAdapter implements CardProviderAdapter {
        @Override
        public String providerType() {
            return "W6_TEST_CARD";
        }

        @Override
        public boolean usableInProduction() {
            return true;
        }

        @Override
        public boolean requiresSecret() {
            return true;
        }

        @Override
        public CardEnrolment.BeginOutcome begin(CardAccount account, UUID tenantId) {
            return new CardEnrolment.BeginOutcome.NotConfigured();
        }

        @Override
        public CardEnrolment.ConfirmOutcome confirm(
                CardAccount account, UUID tenantId, String sessionReference, String providerToken, String code) {
            return new CardEnrolment.ConfirmOutcome.NotConfigured();
        }

        @Override
        public CardEnrolment.RevokeOutcome revoke(CardAccount account, String providerToken) {
            return new CardEnrolment.RevokeOutcome.NotConfigured();
        }

        @Override
        public CardCharger.Outcome charge(
                CardAccount account,
                UUID tenantId,
                @Nullable String providerToken,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            return new CardCharger.Outcome.NotConfigured();
        }

        @Override
        public CardCharger.StatusOutcome status(CardAccount account, String idempotencyKey) {
            return new CardCharger.StatusOutcome.NotSucceeded();
        }
    }

    @SuppressWarnings("unused")
    private static Instant unusedForImportStability() {
        return START;
    }
}
