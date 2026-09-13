package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerCsvImportParser;
import uz.horecaos.platform.customers.application.CustomerCsvImportParser.CustomerCsvImportFormatException;
import uz.horecaos.platform.customers.application.CustomerCsvImportRejectReason;
import uz.horecaos.platform.customers.application.CustomerCsvImportRow;
import uz.horecaos.platform.customers.application.CustomerCsvImportRowOutcome;
import uz.horecaos.platform.customers.application.CustomerCsvImportRowService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerImportDirectoryService;
import uz.horecaos.platform.customers.application.CustomerImportService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.infrastructure.persistence.ConfiguredCustomerPolicyLookup;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerImportStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row {@code X.13}/{@code 5.1b}: the generic (non-Telegram) customer CSV
 * parser, the row-level resolution it feeds, and the async job surface a
 * progress bar polls against.
 */
class CustomerCsvImportTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
    private static final String IMPORTED_BY = "staff-import-tester";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private Clock clock;
    private FieldProtection protection;
    private JdbcCustomerStore store;
    private CustomerIdentityService identity;
    private CustomerProfileService profiles;
    private CustomerCsvImportRowService rowService;
    private JdbcCustomerImportStore importStore;
    private CustomerImportService imports;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for customer import tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE customer.customer_import_run_rows, customer.customer_import_runs, "
                        + "customer.consent_decisions, customer.addresses, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, customer.blacklist_entries, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.brands, tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();

        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone,
                            status, version)
                        VALUES (:id, 'tenant-csv-import', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """).param("id", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                        VALUES (:id, :tenantId, 'MAIN', 'main', 'Main brand', 'ACTIVE')
                        """).param("id", BRAND).param("tenantId", TENANT).update();

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new JdbcCustomerStore(jdbc);
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        java.util.Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")
                                ::get,
                        clock),
                "local"));
        var objectMapper = JsonMapper.builder().build();
        identity = new CustomerIdentityService(
                store,
                new ConfiguredCustomerPolicyLookup(jdbc),
                clock,
                new uz.horecaos.platform.customers.application.CustomerBlacklistService(
                        store, protection, clock, new JdbcAuditRecorder(jdbc, objectMapper)),
                new JdbcAuditRecorder(jdbc, objectMapper));
        profiles = new CustomerProfileService(
                store, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        var consent = new ConsentService(store, clock);
        var directory = new CustomerImportDirectoryService(identity, profiles, consent);
        rowService = new CustomerCsvImportRowService(directory);
        importStore = new JdbcCustomerImportStore(jdbc);
        imports = new CustomerImportService(new CustomerCsvImportParser(), rowService, importStore, protection, clock);
    }

    // ------------------------------------------------------------------ parser

    @Test
    @DisplayName("a row with no chat-id-shaped column at all still imports, keyed on phone alone")
    void parsesARowWithNoChatIdColumn() {
        String csv = "Full Name,Phone Number,City\nAyubkhon,+998901112233,Tashkent\n";

        List<CustomerCsvImportRow> rows = new CustomerCsvImportParser().parse(csv);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isRejected()).isFalse();
        assertThat(rows.get(0).rawPhone()).isEqualTo("+998901112233");
    }

    @Test
    @DisplayName("a row with no recognised phone column at all is MISSING_PHONE")
    void rejectsARowWithNoPhoneColumn() {
        String csv = "Full Name,City\nAyubkhon,Tashkent\n";

        List<CustomerCsvImportRow> rows = new CustomerCsvImportParser().parse(csv);

        assertThat(rows.get(0).rejectReason()).isEqualTo(CustomerCsvImportRejectReason.MISSING_PHONE);
    }

    @Test
    @DisplayName("a genuinely malformed CSV document throws before any row is produced")
    void refusesAnUnparseableDocument() {
        String malformed = "phone\n\"unterminated";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CustomerCsvImportParser().parse(malformed))
                .isInstanceOf(CustomerCsvImportFormatException.class);
    }

    // --------------------------------------------------------------- row service

    @Test
    @DisplayName("a fresh phone creates a new customer account")
    void createsANewCustomer() {
        CustomerCsvImportRowOutcome outcome = rowService.process(TENANT, BRAND, row(1, "+998901110001"), false);

        assertThat(outcome.type()).isEqualTo(CustomerCsvImportRowOutcome.Type.CREATED_CUSTOMER);
        assertThat(outcome.customerAccountId()).isNotNull();
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a dry run reports what it would do and writes nothing")
    void dryRunCreatesNothing() {
        CustomerCsvImportRowOutcome outcome = rowService.process(TENANT, BRAND, row(1, "+998901110002"), true);

        assertThat(outcome.type()).isEqualTo(CustomerCsvImportRowOutcome.Type.CREATED_CUSTOMER);
        assertThat(outcome.customerAccountId()).isNull();
        assertThat(accountCount()).isZero();
    }

    @Test
    @DisplayName("an existing phone matches instead of creating a duplicate")
    void matchesAnExistingAccount() {
        CustomerAccountRef existing = identity.createAccountWithoutPrincipal(TENANT, BRAND);
        profiles.addContactPoint(TENANT, existing.accountId(), ContactType.PHONE, "+998901110003", true);

        CustomerCsvImportRowOutcome outcome = rowService.process(TENANT, BRAND, row(1, "+998901110003"), false);

        assertThat(outcome.type()).isEqualTo(CustomerCsvImportRowOutcome.Type.MATCHED_CUSTOMER);
        assertThat(outcome.customerAccountId()).isEqualTo(existing.accountId());
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a phone shared by two accounts is ambiguous, never guessed")
    void ambiguousPhoneIsRejected() {
        CustomerAccountRef first = identity.createAccountWithoutPrincipal(TENANT, BRAND);
        CustomerAccountRef second = identity.createAccountWithoutPrincipal(TENANT, BRAND);
        profiles.addContactPoint(TENANT, first.accountId(), ContactType.PHONE, "+998901110004", true);
        profiles.addContactPoint(TENANT, second.accountId(), ContactType.PHONE, "+998901110004", true);

        CustomerCsvImportRowOutcome outcome = rowService.process(TENANT, BRAND, row(1, "+998901110004"), false);

        assertThat(outcome.type()).isEqualTo(CustomerCsvImportRowOutcome.Type.REJECTED);
        assertThat(outcome.rejectReason()).isEqualTo(CustomerCsvImportRejectReason.AMBIGUOUS_PHONE_MATCH);
    }

    @Test
    @DisplayName("placeholder text in the phone column is MALFORMED_PHONE, never a customer created from garbage")
    void placeholderTextIsMalformedPhone() {
        CustomerCsvImportRowOutcome outcome = rowService.process(TENANT, BRAND, row(1, "N/A"), false);

        assertThat(outcome.type()).isEqualTo(CustomerCsvImportRowOutcome.Type.REJECTED);
        assertThat(outcome.rejectReason()).isEqualTo(CustomerCsvImportRejectReason.MALFORMED_PHONE);
        assertThat(accountCount()).isZero();
    }

    // -------------------------------------------------------------- async job

    @Test
    @DisplayName("a queued run reports progress as the worker tick processes it, then completes")
    void asyncJobReportsProgressThenCompletes() {
        String csv = "phone\n+998901110010\n+998901110011\nnot-a-phone\n";

        UUID runId = imports.submit(TENANT, BRAND, false, "customers.csv", csv, IMPORTED_BY);

        JdbcCustomerImportStore.RunRow queued = importStore.run(TENANT, runId).orElseThrow();
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.rowsTotal()).isEqualTo(3);
        assertThat(queued.rowsProcessed()).isZero();

        boolean claimed = imports.processNextQueuedRun();
        assertThat(claimed)
                .as("exactly one run was queued for the worker to claim")
                .isTrue();

        JdbcCustomerImportStore.RunRow finished = importStore.run(TENANT, runId).orElseThrow();
        assertThat(finished.status()).isEqualTo("COMPLETE");
        assertThat(finished.rowsProcessed()).isEqualTo(3);
        assertThat(finished.rowsCreatedCustomer()).isEqualTo(2);
        assertThat(finished.rowsRejected()).isEqualTo(1);
        assertThat(accountCount()).isEqualTo(2);

        List<JdbcCustomerImportStore.ImportRowView> rows = importStore.rows(TENANT, runId, 100, 0);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).outcome()).isEqualTo("REJECTED");
        assertThat(rows.get(2).rejectReason()).isEqualTo("MALFORMED_PHONE");

        // A second tick finds nothing left queued.
        assertThat(imports.processNextQueuedRun()).isFalse();
    }

    @Test
    @DisplayName("a dry-run job's report is identical in shape and writes no customer")
    void asyncDryRunWritesNoCustomer() {
        String csv = "phone\n+998901110020\n";
        UUID runId = imports.submit(TENANT, BRAND, true, "customers.csv", csv, IMPORTED_BY);

        imports.processNextQueuedRun();

        JdbcCustomerImportStore.RunRow finished = importStore.run(TENANT, runId).orElseThrow();
        assertThat(finished.status()).isEqualTo("DRY_RUN_COMPLETE");
        assertThat(finished.rowsCreatedCustomer()).isEqualTo(1);
        assertThat(accountCount()).isZero();
    }

    @Test
    @DisplayName("the source file is cleared once a run finishes")
    void completedRunClearsTheSourceContent() {
        String csv = "phone\n+998901110030\n";
        UUID runId = imports.submit(TENANT, BRAND, true, "customers.csv", csv, IMPORTED_BY);
        imports.processNextQueuedRun();

        boolean contentCleared = jdbc.sql(
                        "SELECT (encrypted_content IS NULL) FROM customer.customer_import_runs WHERE id = :id")
                .param("id", runId)
                .query(Boolean.class)
                .single();
        assertThat(contentCleared)
                .as("the source file is cleared once the run finishes")
                .isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static CustomerCsvImportRow row(int rowNumber, String rawPhone) {
        return new CustomerCsvImportRow(rowNumber, rawPhone, null);
    }

    private long accountCount() {
        return jdbc.sql("SELECT count(*) FROM customer.customer_accounts")
                .query(Long.class)
                .single();
    }
}
