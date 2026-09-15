package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.customers.application.CustomerDirectoryExportAdapter;
import uz.horecaos.platform.customers.application.CustomerListQueryService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The export centre's own job: the row quota, the audit fact, and the PII omission (ADR
 * 0043/ADR 0029, wave P28).
 */
class ReportExportServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = "operator-export-test";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ReportExportService service;
    private RecordingObjectStorage storage;
    private JdbcReportExportStore exportStore;
    private CustomerProfileService profiles;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
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
        jdbc.sql("TRUNCATE TABLE reporting.report_exports").update();
        jdbc.sql("TRUNCATE TABLE customer.contact_points, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone,
                            status, version)
                        VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """).param("id", TENANT).param("slug", "export-centre-test").update();

        Clock clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
        var objectMapper = JsonMapper.builder().build();
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        java.util.Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")
                                ::get,
                        clock),
                "local"));
        var customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(
                customerStore, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        var lists = new CustomerListQueryService(
                customerStore,
                protection,
                new JdbcAuditRecorder(jdbc, objectMapper),
                clock,
                new uz.horecaos.platform.customers.api.CustomerOrderActivityPort() {},
                (tenantId, at) -> new uz.horecaos.platform.customers.api.BusinessDayWindows.Window(at, at));
        CustomerDirectoryExportPort customerDirectory = new CustomerDirectoryExportAdapter(lists);

        exportStore = new JdbcReportExportStore(jdbc, objectMapper);
        storage = new RecordingObjectStorage();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        service = new ReportExportService(
                exportStore,
                customerDirectory,
                protection,
                new JdbcAuditRecorder(jdbc, objectMapper),
                storage,
                transactions,
                objectMapper,
                clock,
                "test-exports-bucket");
    }

    @Test
    @DisplayName("a non-PII export beyond the row quota truncates rather than growing without bound")
    void rowQuotaTruncatesANonPiiExport() {
        bulkInsertCustomers(ReportExportService.DEFAULT_ROW_QUOTA + 1);

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.CUSTOMER_DIRECTORY,
                List.of("accountId", "status", "displayName"),
                null,
                null,
                "quota-test",
                SUBJECT,
                false);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.rowQuota()).isEqualTo(ReportExportService.DEFAULT_ROW_QUOTA);
        assertThat(view.truncated())
                .as("a filter matching one row more than the quota must say so rather than "
                        + "silently returning a full-looking page")
                .isTrue();
        assertThat(view.rowCount()).isEqualTo(ReportExportService.DEFAULT_ROW_QUOTA);
    }

    @Test
    @DisplayName("a completed export writes one audit fact naming who, what, how many rows and the PII group")
    void completionWritesOneAuditFact() {
        insertOneCustomer("Ada Lovelace", "+998900000001");

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.CUSTOMER_DIRECTORY,
                List.of("accountId", "status", "displayName"),
                "ACTIVE",
                null,
                "audit-shape-test",
                SUBJECT,
                false);

        assertThat(service.processNextQueued()).isTrue();

        assertThat(auditFactCount()).isEqualTo(1);
        var fact = jdbc.sql("""
                        SELECT change_document ->> 'reportKey', change_document ->> 'rowCount',
                               change_document ->> 'piiColumnGroup', change_document -> 'filters' ->> 'status',
                               correlation_id, reason
                        FROM audit.audit_events WHERE action_code = 'report.export.completed'
                        """)
                .query((row, number) -> new String[] {
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getString(6)
                })
                .single();
        assertThat(fact[0]).isEqualTo(ReportExportRegistry.CUSTOMER_DIRECTORY);
        assertThat(fact[1]).isEqualTo("1");
        assertThat(fact[2]).isEqualTo("EXCLUDED");
        assertThat(fact[3])
                .as("the audit fact's own filters carry the status filter, never the raw search text")
                .isEqualTo("ACTIVE");
        assertThat(fact[4]).isEqualTo(id.toString());
        assertThat(fact[5]).isEqualTo("audit-shape-test");
    }

    @Test
    @DisplayName("a principal without customer.pii.export gets the phone column omitted, never a refusal")
    void piiColumnOmittedRatherThanRefused() {
        insertOneCustomer("Grace Hopper", "+998900000002");

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.CUSTOMER_DIRECTORY,
                List.of("accountId", "status", "displayName", "phone"),
                null,
                null,
                "pii-omission-test",
                SUBJECT,
                false);

        // No exception above: the request is accepted even though "phone" was asked for.
        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.includesPiiColumns()).isFalse();
        assertThat(view.effectiveColumns()).containsExactly("accountId", "status", "displayName");
        assertThat(view.effectiveColumns()).doesNotContain("phone");

        assertThat(storage.puts).hasSize(1);
        String csv = new String(storage.puts.get(0).content(), StandardCharsets.UTF_8);
        assertThat(csv)
                .as("the artefact itself must not carry the phone column or value")
                .doesNotContainIgnoringCase("phone")
                .doesNotContain("+998900000002");
    }

    @Test
    @DisplayName("a principal holding customer.pii.export gets the phone column included")
    void piiColumnIncludedWhenGranted() {
        insertOneCustomer("Katherine Johnson", "+998900000003");

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.CUSTOMER_DIRECTORY,
                List.of("accountId", "status", "displayName", "phone"),
                null,
                null,
                "pii-inclusion-test",
                SUBJECT,
                true);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, true).orElseThrow();
        assertThat(view.includesPiiColumns()).isTrue();
        assertThat(view.effectiveColumns()).contains("phone");
        assertThat(view.downloadUrl()).isNotNull();
        String csv = new String(storage.puts.get(0).content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("+998900000003");
    }

    @Test
    @DisplayName("a principal without customer.pii.export polling or listing another principal's "
            + "PII export never sees the PII columns or the download URL")
    void statusAndHistoryRedactPiiFromAViewerWhoLacksTheCapability() {
        insertOneCustomer("Ada Lovelace", "+998900000004");

        // Requested by a principal who DOES hold customer.pii.export -- the row is stored
        // with the phone column included and a real artefact behind it.
        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.CUSTOMER_DIRECTORY,
                List.of("accountId", "status", "displayName", "phone"),
                null,
                null,
                "cross-viewer-pii-test",
                SUBJECT,
                true);
        assertThat(service.processNextQueued()).isTrue();

        // A second principal in the same tenant, REPORT_EXPORT-only (no customer.pii.export),
        // polls that same export's status.
        ReportExportService.ExportStatusView statusView =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(statusView.includesPiiColumns())
                .as("redacted for a viewer without customer.pii.export")
                .isFalse();
        assertThat(statusView.effectiveColumns())
                .doesNotContain("phone")
                .containsExactly("accountId", "status", "displayName");
        assertThat(statusView.downloadUrl())
                .as("no presigned URL for a PII export the viewer cannot see")
                .isNull();

        // Same redaction reading the job history list.
        List<ReportExportService.ExportStatusView> history = service.recentExports(TENANT, 50, false);
        assertThat(history).hasSize(1);
        ReportExportService.ExportStatusView historyView = history.get(0);
        assertThat(historyView.includesPiiColumns()).isFalse();
        assertThat(historyView.effectiveColumns()).doesNotContain("phone");
        assertThat(historyView.downloadUrl()).isNull();

        // The PII-holding principal themselves still sees the phone column and the URL.
        ReportExportService.ExportStatusView ownerView =
                service.status(TENANT, id, true).orElseThrow();
        assertThat(ownerView.includesPiiColumns()).isTrue();
        assertThat(ownerView.effectiveColumns()).contains("phone");
        assertThat(ownerView.downloadUrl()).isNotNull();
    }

    private long auditFactCount() {
        return jdbc.sql("SELECT COUNT(*) FROM audit.audit_events WHERE action_code = 'report.export.completed'")
                .query(Long.class)
                .single();
    }

    private void bulkInsertCustomers(int count) {
        jdbc.sql("""
                        INSERT INTO customer.customer_accounts (id, tenant_id, display_name, created_at)
                        SELECT gen_random_uuid(), :tenantId, 'Bulk customer ' || generate_series, now()
                        FROM generate_series(1, :count)
                        """).param("tenantId", TENANT).param("count", count).update();
    }

    /** The account row directly (this suite is not testing sign-in), the contact point via the real service. */
    private void insertOneCustomer(String displayName, String phone) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, created_at)
                        VALUES (:id, :tenantId, 'ACTIVE', :displayName, now())
                        """)
                .param("id", accountId)
                .param("tenantId", TENANT)
                .param("displayName", displayName)
                .update();

        profiles.addContactPoint(TENANT, accountId, ContactType.PHONE, phone, true);
    }

    private record RecordedPut(String bucket, String key, String contentType, byte[] content) {}

    private static final class RecordingObjectStorage implements ObjectStorage {
        private final List<RecordedPut> puts = new ArrayList<>();

        @Override
        public PresignedUpload presignUpload(
                String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
            throw new UnsupportedOperationException("Not exercised by the export centre");
        }

        @Override
        public URI presignDownload(String bucket, String key, Duration validFor) {
            return URI.create("https://example.invalid/" + bucket + "/" + key);
        }

        @Override
        public Optional<StoredObject> head(String bucket, String key) {
            throw new UnsupportedOperationException("Not exercised by the export centre");
        }

        @Override
        public void put(String bucket, String key, String contentType, byte[] content) {
            puts.add(new RecordedPut(bucket, key, contentType, content));
        }

        @Override
        public void delete(String bucket, String key) {
            throw new UnsupportedOperationException("Not exercised by the export centre");
        }
    }
}
