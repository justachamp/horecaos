package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.PlanTerms;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.commercial.infrastructure.PlatformCardGateway;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardTopUpStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformBillingSettingsStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformCardInstallationStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPrepaymentInvoiceStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * The wiring every ADR 0095 card, top-up, invoice and settlement test stands on: the real services, the
 * real stores and the real schema, with the one thing HorecaOS does not have — a merchant account —
 * replaced by the fake provider behind the real gateway, activated the way an operator would.
 *
 * <p>Nothing here does in setup what production never does. A card reaches a tenant by the enrolment flow;
 * the fake is reached only through an ACTIVE installation row, which {@code activateFake} writes through
 * the installation service; an invoice is only issued once bank details have been approved by a second
 * person. A test that planted any of those by SQL would pass whatever the services said.
 */
abstract class WalletBillingFixture {

    static final UUID PILOT = UUID.fromString("018f6f4e-3300-7000-8000-0000000000d1");
    static final UUID RIVAL = UUID.fromString("018f6f4e-3300-7000-8000-0000000000d2");

    /** After V0504 seeds the bank-details policy (2026-10-07) and the wallet's own (2026-09-11). */
    static final Instant START = Instant.parse("2026-10-08T09:00:00Z");

    /** After October has ended, so its statement can be issued. */
    static final Instant CLOSE = Instant.parse("2026-11-05T09:00:00Z");

    static final ActorRef MAKER = ActorRef.user("finance-1", null);
    static final ActorRef CHECKER = ActorRef.user("finance-2", null);
    static final ActorRef TENANT_USER = ActorRef.user("tenant-finance-1", null);

    static final long MONTHLY = 1_200_000L;

    /** A login role holding only what the application role holds, for asserting what the application cannot do. */
    private static final String APP_PROBE = "w6_billing_probe_app";

    private static final String APP_PROBE_PASSWORD = "w6-billing-probe-app";

    static TestDatabase.Handle db;

    /** Connected as {@link #APP_PROBE}: neither superuser nor owner, so a REVOKE asserted through it means something. */
    static JdbcClient application;

    JdbcClient jdbc;
    TransactionTemplate transactions;
    TestClock clock;
    SimpleMeterRegistry meters;
    JdbcApprovalService approvals;
    JdbcAuditRecorder audit;
    PlanCatalogService plans;
    SubscriptionService subscriptions;
    StatementService statements;
    WalletService wallet;
    JdbcWalletStore walletStore;
    JdbcCardChargeAttemptStore attemptStore;
    JdbcCardTopUpStore topUpStore;
    JdbcPlatformCardInstallationStore installationStore;
    FakeCardProvider fake;
    PlatformCardGateway gateway;
    PlatformCardInstallationService installations;
    CardOnFileService cardOnFile;
    CardTopUpService topUps;
    PrepaymentInvoiceService invoices;
    PlatformBillingSettingsService billingSettings;
    WalletCardSettlementSweeper sweeper;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        JdbcClient owner = JdbcClient.create(db.dataSource());
        owner.sql("DROP ROLE IF EXISTS " + APP_PROBE).update();
        owner.sql("CREATE ROLE " + APP_PROBE + " LOGIN PASSWORD '" + APP_PROBE_PASSWORD + "'")
                .update();
        owner.sql("ALTER ROLE " + APP_PROBE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + APP_PROBE).update();
        application = JdbcClient.create(db.dataSourceAs(APP_PROBE, APP_PROBE_PASSWORD));
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
            try {
                // A role belongs to the cluster, not the database, and the cluster outlives this class.
                TestDatabase.onCluster("DROP ROLE IF EXISTS " + APP_PROBE);
            } catch (RuntimeException leftover) {
                System.err.println(APP_PROBE + " outlived the suite (" + leftover.getMessage() + ")");
            }
        }
    }

    @BeforeEach
    void wire() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("""
                TRUNCATE TABLE commercial.wallet_entries, commercial.tenant_billing,
                    commercial.card_charge_attempts, commercial.card_top_ups, commercial.prepayment_invoices,
                    commercial.platform_card_installations,
                    commercial.statement_lines, commercial.statements, commercial.subscriptions,
                    commercial.usage_events, commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.entitlement_overrides, commercial.tenant_modules, commercial.modules,
                    commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE
                """).update();
        jdbc.sql("""
                UPDATE commercial.platform_billing_settings
                   SET bank_beneficiary = '[beneficiary: set by HorecaOS finance]',
                       bank_name = '[bank: set by HorecaOS finance]',
                       bank_account = '[account: set by HorecaOS finance]',
                       bank_mfo = '[MFO]', bank_tax_id = '[tax id]', configured = false, version = 0,
                       updated_by = 'test reset', approved_by = NULL, approval_request_id = NULL
                """).update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:pilot, :rival)")
                .param("pilot", PILOT)
                .param("rival", RIVAL)
                .update();
        insertTenant(PILOT, "pilot-w6", "Non uyi");
        insertTenant(RIVAL, "rival-w6", "Choyxona");

        clock = new TestClock(START);
        meters = new SimpleMeterRegistry();
        JsonMapper json = JsonMapper.builder().build();
        audit = new JdbcAuditRecorder(jdbc, json);
        JdbcPlanStore planStore = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptionStore = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usageStore = new JdbcUsageStore(jdbc, json);
        JdbcModuleStore moduleStore = new JdbcModuleStore(jdbc);
        JdbcStatementStore statementStore = new JdbcStatementStore(jdbc);
        walletStore = new JdbcWalletStore(jdbc);
        attemptStore = new JdbcCardChargeAttemptStore(jdbc);
        topUpStore = new JdbcCardTopUpStore(jdbc);
        installationStore = new JdbcPlatformCardInstallationStore(jdbc, json);
        JdbcPlatformBillingSettingsStore settingsStore = new JdbcPlatformBillingSettingsStore(jdbc);
        JdbcPrepaymentInvoiceStore invoiceStore = new JdbcPrepaymentInvoiceStore(jdbc);

        approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry(), json);
        fake = new FakeCardProvider(clock);
        gateway = new PlatformCardGateway(installationStore, List.of(fake), true);
        wallet = new WalletService(
                walletStore, subscriptionStore, attemptStore, approvals, gateway, audit, meters, transactions, clock);
        EntitlementQueryService entitlements = new EntitlementQueryService(
                subscriptionStore,
                planStore,
                usageStore,
                moduleStore,
                new EnforcementCeiling(new JdbcConfigurationResolver(jdbc)),
                clock);
        plans = new PlanCatalogService(planStore, audit, clock);
        subscriptions = new SubscriptionService(subscriptionStore, planStore, entitlements, audit, clock);
        statements = new StatementService(
                subscriptionStore, planStore, moduleStore, statementStore, usageStore, wallet, audit, clock);
        installations =
                new PlatformCardInstallationService(installationStore, gateway, topUpStore, attemptStore, audit, clock);
        cardOnFile = new CardOnFileService(walletStore, topUpStore, attemptStore, gateway, audit, transactions, clock);
        topUps = new CardTopUpService(topUpStore, walletStore, wallet, gateway, audit, meters, transactions, clock);
        billingSettings = new PlatformBillingSettingsService(settingsStore, approvals, audit, clock);
        invoices = new PrepaymentInvoiceService(invoiceStore, settingsStore, walletStore, wallet, audit, clock, 14, 3);
        sweeper = new WalletCardSettlementSweeper(
                wallet,
                topUps,
                attemptStore,
                gateway,
                clock,
                100,
                java.time.Duration.ofHours(24),
                3,
                java.time.Duration.ofMinutes(2));
        fake.reset();
    }

    // ----------------------------------------------------------------- the card account

    /** Creates and activates the fake through the installation service, as an operator would. */
    UUID activateFake() {
        UUID id = inTx(() -> installations.create(
                FakeCardProvider.PROVIDER_TYPE, null, "Fake card account", null, null, Map.of(), MAKER, "local", "c"));
        inTx(() -> installations.activate(id, 0, MAKER, "local", "c"));
        return id;
    }

    /** Puts a card on file the way a tenant does: the provider's form, then the bank's code. */
    void bindCard(UUID tenantId, String providerToken) {
        CardEnrolment.BeginOutcome.Begun begun = cardOnFile.beginEnrolment(tenantId);
        cardOnFile.confirmEnrolment(
                tenantId,
                begun.sessionReference(),
                providerToken,
                FakeCardProvider.VERIFICATION_CODE,
                TENANT_USER,
                "c");
    }

    void chooseCard(UUID tenantId) {
        cardOnFile.chooseMethod(tenantId, uz.horecaos.platform.commercial.domain.PaymentMethod.CARD, TENANT_USER, "c");
    }

    // ------------------------------------------------------------- plans and statements

    void startOnPlan(Instant startAt, long monthlyMinor) {
        startOnPlan(PILOT, startAt, monthlyMinor);
    }

    void startOnPlan(UUID tenantId, Instant startAt, long monthlyMinor) {
        startOnPlan(tenantId, startAt, monthlyMinor, 0);
    }

    /** A plan that asks an activation deposit, which is owed beside the statements and not on one. */
    void startOnPlan(UUID tenantId, Instant startAt, long monthlyMinor, long activationDepositMinor) {
        clock.set(startAt);
        UUID versionId = activePlan(
                "PLAN_" + tenantId.toString().substring(30).toUpperCase().replace("-", "_"),
                monthlyMinor,
                activationDepositMinor);
        inTx(() -> subscriptions.start(tenantId, versionId, null, MAKER, "the pilot", "corr"));
    }

    UUID activePlan(String planCode, long monthlyMinor) {
        return activePlan(planCode, monthlyMinor, 0);
    }

    UUID activePlan(String planCode, long monthlyMinor, long activationDepositMinor) {
        UUID planId = inTx(() -> plans.createPlan(planCode, planCode, MAKER, "the price list", "corr"));
        UUID versionId = inTx(() -> plans.draftVersion(
                planId,
                "UZS",
                monthlyMinor,
                "MONTHLY",
                null,
                Map.of(),
                new PlanTerms(null, activationDepositMinor, Map.of()),
                MAKER,
                "the 2026 prices",
                "corr"));
        inTxDo(() -> plans.activate(versionId, CHECKER, "signed off", "corr"));
        return versionId;
    }

    /** Closes a month: moves the clock past it and issues its statement, which pays itself from the wallet. */
    UUID issue(UUID tenantId, String periodKey, Instant closeAt) {
        clock.set(closeAt);
        return inTx(() -> statements.issue(tenantId, periodKey, MAKER, periodKey + " close", "corr"))
                .statementId();
    }

    StatementPayment statementPayment(UUID tenantId, String periodKey) {
        return wallet.statementPayments(tenantId).stream()
                .filter(payment -> payment.periodKey().equals(periodKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No issued statement for " + periodKey));
    }

    // ----------------------------------------------------------------------- money

    UUID recordTransfer(UUID tenantId, long amountMinor, String bankReference) {
        return inTx(
                () -> wallet.recordTransfer(tenantId, amountMinor, bankReference, MAKER, "a bank transfer", "corr"));
    }

    /** Proposes a change, has {@code CHECKER} approve it, and proposes the identical change again. */
    WalletChangeOutcome applyChange(Supplier<WalletChangeOutcome> change) {
        WalletChangeOutcome first = inTx(change);
        assertThat(first.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        return inTx(change);
    }

    /** Real bank details, the way finance sets them: proposed, signed by somebody else, proposed again. */
    void configureBankDetails() {
        applyChange(() -> billingSettings.proposeBankDetails(realBankDetails(), MAKER, "launch details", "corr"));
    }

    static PlatformBillingSettingsService.BankDetails realBankDetails() {
        return new PlatformBillingSettingsService.BankDetails(
                "HorecaOS MCHJ", "Kapitalbank", "20208000900123456001", "01158", "309876543");
    }

    long ledgerSum(UUID tenantId, String moneyKind) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND money_kind = :kind
                        """)
                .param("id", tenantId)
                .param("kind", moneyKind)
                .query(Long.class)
                .single();
    }

    long ledgerSize(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM commercial.wallet_entries WHERE tenant_id = :id")
                .param("id", tenantId)
                .query(Long.class)
                .single();
    }

    /** Every audit fact with this action code prefix, oldest first. */
    List<String> auditedActions(String prefix) {
        return jdbc.sql("""
                        SELECT action_code FROM audit.audit_events
                         WHERE action_code LIKE :prefix ORDER BY occurred_at, action_code
                        """).param("prefix", prefix + "%").query(String.class).list();
    }

    // -------------------------------------------------------------------- plumbing

    <T> T inTx(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    void inTxDo(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    private void insertTenant(UUID id, String slug, String name) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("name", name).update();
    }

    /** A clock a test moves forward, so a retry holdback or an expiry is lived through rather than asserted at an instant. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(java.time.Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
