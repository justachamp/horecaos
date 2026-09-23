package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
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
    private JdbcReportingStore reportingStore;
    private CustomerProfileService profiles;
    private FieldProtection protection;
    private UUID brandId;
    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;

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
        jdbc.sql("TRUNCATE TABLE reporting.fact_order, reporting.agg_branch_day, reporting.business_day_policies")
                .update();
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
        seedOrderingFixture();

        Clock clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
        var objectMapper = JsonMapper.builder().build();
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
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

        var crmLogStore = new uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore(jdbc);
        var crmLogQueries =
                new uz.horecaos.platform.ordering.application.OrderCrmLogQueryService(crmLogStore, protection);
        uz.horecaos.platform.ordering.api.OrderCrmLogExportPort orderCrmLog =
                new uz.horecaos.platform.ordering.application.OrderCrmLogExportAdapter(crmLogQueries);

        reportingStore = new JdbcReportingStore(jdbc);
        ReportQueryService reportQueries =
                new ReportQueryService(reportingStore, new BusinessDayService(reportingStore), clock);

        exportStore = new JdbcReportExportStore(jdbc, objectMapper);
        storage = new RecordingObjectStorage();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        service = new ReportExportService(
                exportStore,
                customerDirectory,
                orderCrmLog,
                reportQueries,
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

    // ------------------------------------------------------- ORDER_CRM_LOG (7.2a)

    @Test
    @DisplayName("ORDER_CRM_LOG requires both from and to at queue time")
    void orderCrmLogRequiresARange() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                uz.horecaos.platform.web.api.ApiException.class,
                                () -> service.requestExport(
                                        TENANT,
                                        ReportExportRegistry.ORDER_CRM_LOG,
                                        List.of("orderId", "customerName"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "no-range-test",
                                        SUBJECT,
                                        true))
                        .errorCode())
                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("ORDER_CRM_LOG omits customer name and phone when the caller lacks customer.pii.export")
    void orderCrmLogOmitsPiiWithoutTheCapability() {
        seedCrmOrder("crm-1", "Alisher Karimov", "+998900000010");
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.ORDER_CRM_LOG,
                List.of("orderId", "customerName", "customerPhone", "operatorPrincipalId"),
                null,
                null,
                from,
                to,
                List.of(),
                "crm-log-no-pii-test",
                SUBJECT,
                false);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.includesPiiColumns()).isFalse();
        assertThat(view.effectiveColumns()).doesNotContain("customerName", "customerPhone");

        String csv = new String(storage.puts.getLast().content(), StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("Alisher Karimov").doesNotContain("+998900000010");
    }

    @Test
    @DisplayName("ORDER_CRM_LOG includes the decrypted name and phone when the caller holds customer.pii.export, "
            + "and the completed export's audit fact names ORDER_CRM_LOG")
    void orderCrmLogIncludesPiiWhenGranted() {
        seedCrmOrder("crm-2", "Nodira Yusupova", "+998900000011");
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.ORDER_CRM_LOG,
                List.of("orderId", "customerName", "customerPhone"),
                null,
                null,
                from,
                to,
                List.of(),
                "crm-log-pii-test",
                SUBJECT,
                true);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, true).orElseThrow();
        assertThat(view.includesPiiColumns()).isTrue();
        String csv = new String(storage.puts.getLast().content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Nodira Yusupova").contains("+998900000011");

        var fact = jdbc.sql("""
                        SELECT change_document ->> 'reportKey', change_document ->> 'piiColumnGroup'
                          FROM audit.audit_events
                         WHERE action_code = 'report.export.completed' AND correlation_id = :id
                        """)
                .param("id", id.toString())
                .query((row, number) -> new String[] {row.getString(1), row.getString(2)})
                .single();
        assertThat(fact[0]).isEqualTo(ReportExportRegistry.ORDER_CRM_LOG);
        assertThat(fact[1]).isEqualTo("INCLUDED");
    }

    private void seedOrderingFixture() {
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("t", TENANT).update();
        locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", TENANT)
                .param("b", brandId)
                .update();
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", brandId)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
    }

    /** One delivered order with a customer snapshot, for {@link #orderCrmLogOmitsPiiWithoutTheCapability}'s own suite. */
    private UUID seedCrmOrder(String seed, String customerName, String phone) {
        UUID orderId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        UUID cartId = UUID.nameUUIDFromBytes(("cart:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("quote:" + seed).getBytes(StandardCharsets.UTF_8));
        Instant createdAt = Instant.parse("2026-09-10T12:00:00Z");

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, 'hash', 45000, 0, 45000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("pub", publicationId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guestHash, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("guestHash", "guest-" + seed)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, created_at)
                VALUES (:id, :num, :t, :b, :loc, :ch, 'STOREFRONT', :guestHash, 'DELIVERY', 'AUTO_CONFIRM', 0,
                    'NONE', 'RECEIVED', 'UZS', 45000, 0, 45000, :quoteId, 'hash', :pub, :cartId, :idem, 1,
                    :createdAt)
                """)
                .param("id", orderId)
                .param("num", "F-" + seed)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("guestHash", "guest-" + seed)
                .param("quoteId", quoteId)
                .param("pub", publicationId)
                .param("cartId", cartId)
                .param("idem", "crm-export-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        String nameCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "display_name_encrypted", orderId),
                        customerName)
                .serialize();
        String phoneCipher = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", "contact_encrypted", orderId),
                        phone)
                .serialize();
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted,
                    contact_encrypted)
                VALUES (:orderId, :t, :name, :phone)
                """)
                .param("orderId", orderId)
                .param("t", TENANT)
                .param("name", nameCipher)
                .param("phone", phoneCipher)
                .update();
        return orderId;
    }

    // ------------------------------------------------------- ORDER_REPORT_LOG / ORDER_REPORT_SUMMARY (7.2e)

    /** A minimal {@code reporting.fact_order} row — this suite's own fixture, same shape {@code OrderGrainReportingTests} seeds with. */
    private UUID insertFactOrder(
            LocalDate businessDate, String channelCode, String fulfilmentType, long grossRevenueSom, long discountSom) {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime occurredAt = businessDate.atTime(10, 0).atOffset(ZoneOffset.UTC);
        long deliveryFee = 5_000L;
        long net = grossRevenueSom - discountSom;
        jdbc.sql("""
                        INSERT INTO reporting.fact_order (
                            tenant_id, order_id, business_date, boundary_version, occurred_at, closed_at,
                            brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                            gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                            line_count, item_count, metric_calculation_version, source_order_version)
                        VALUES (
                            :tenantId, :orderId, :businessDate, 1, :occurredAt, :occurredAt,
                            :brandId, :locationId, :channelCode, :fulfilmentType, 'COMPLETED',
                            :gross, :discount, :deliveryFee, 0, :net,
                            1, 3, 1, 1)
                        """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("businessDate", businessDate)
                .param("occurredAt", occurredAt)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelCode", channelCode)
                .param("fulfilmentType", fulfilmentType)
                .param("gross", grossRevenueSom)
                .param("discount", discountSom)
                .param("deliveryFee", deliveryFee)
                .param("net", net)
                .update();
        return orderId;
    }

    @Test
    @DisplayName("ORDER_REPORT_LOG requires both from and to at queue time")
    void orderReportLogRequiresARange() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                uz.horecaos.platform.web.api.ApiException.class,
                                () -> service.requestExport(
                                        TENANT,
                                        ReportExportRegistry.ORDER_REPORT_LOG,
                                        List.of("orderId", "grossRevenueSom"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "no-range-test",
                                        SUBJECT,
                                        true))
                        .errorCode())
                .isEqualTo(uz.horecaos.platform.web.api.ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("ORDER_REPORT_LOG produces the commercial order-grain rows straight off reporting.fact_order, "
            + "and no column is redacted for a viewer lacking customer.pii.export because none of them is PII")
    void orderReportLogProducesOrderGrainRowsWithNoPiiToRedact() {
        UUID orderId = insertFactOrder(LocalDate.of(2026, 9, 10), "TELEGRAM", "DELIVERY", 100_000L, 10_000L);

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.ORDER_REPORT_LOG,
                List.of("orderId", "businessDate", "channelCode", "fulfilmentType", "grossRevenueSom", "netRevenueSom"),
                null,
                null,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"),
                List.of(),
                "order-report-log-test",
                SUBJECT,
                // Deliberately the caller WITHOUT customer.pii.export — the export centre's own
                // decision for CUSTOMER_DIRECTORY/ORDER_CRM_LOG columns this report carries none of.
                false);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.includesPiiColumns())
                .as("reporting.fact_order carries no PERSONAL field (ADR 0029) — nothing to gate here")
                .isFalse();
        assertThat(view.effectiveColumns())
                .containsExactly(
                        "orderId", "businessDate", "channelCode", "fulfilmentType", "grossRevenueSom", "netRevenueSom");
        assertThat(view.rowCount()).isEqualTo(1);

        String csv = new String(storage.puts.getLast().content(), StandardCharsets.UTF_8);
        assertThat(csv)
                .contains(orderId.toString())
                .contains("TELEGRAM")
                .contains("DELIVERY")
                .contains("90000");

        var fact = jdbc.sql("""
                        SELECT change_document ->> 'reportKey', change_document ->> 'piiColumnGroup'
                          FROM audit.audit_events
                         WHERE action_code = 'report.export.completed' AND correlation_id = :id
                        """)
                .param("id", id.toString())
                .query((row, number) -> new String[] {row.getString(1), row.getString(2)})
                .single();
        assertThat(fact[0]).isEqualTo(ReportExportRegistry.ORDER_REPORT_LOG);
        assertThat(fact[1]).isEqualTo("EXCLUDED");
    }

    /**
     * «Сводка»'s own source: {@code ReportQueryService#run} reads {@code reporting.agg_branch_day}
     * (the pre-aggregated table the typed {@code /queries} pipeline groups), never {@code
     * fact_order} directly — unlike {@link #insertFactOrder}'s own table, which only backs {@code
     * /orders}.
     */
    private void insertAggBranchDay(
            LocalDate businessDate, String channelCode, String fulfilmentType, int orderCount, long grossSom) {
        jdbc.sql("""
                        INSERT INTO reporting.agg_branch_day (
                            tenant_id, business_date, location_id, channel_code, fulfilment_type,
                            boundary_version, metric_calculation_version, order_count, cancelled_count,
                            gross_som, discount_som, net_som, refunded_som, promised_count, late_count,
                            distinct_customers, new_customers)
                        VALUES (
                            :tenantId, :businessDate, :locationId, :channelCode, :fulfilmentType,
                            1, 1, :orderCount, 0,
                            :gross, 0, :gross, 0, 0, 0,
                            0, 0)
                        """)
                .param("tenantId", TENANT)
                .param("businessDate", businessDate)
                .param("locationId", locationId)
                .param("channelCode", channelCode)
                .param("fulfilmentType", fulfilmentType)
                .param("orderCount", orderCount)
                .param("gross", grossSom)
                .update();
    }

    @Test
    @DisplayName(
            "ORDER_REPORT_SUMMARY folds the day and legal-entity axes away into one row per branch/channel/fulfilment")
    void orderReportSummaryFoldsAcrossDatesIntoOneBucketPerSlice() {
        insertAggBranchDay(LocalDate.of(2026, 9, 10), "TELEGRAM", "DELIVERY", 1, 100_000L);
        insertAggBranchDay(LocalDate.of(2026, 9, 11), "TELEGRAM", "DELIVERY", 1, 50_000L);
        insertAggBranchDay(LocalDate.of(2026, 9, 10), "STOREFRONT", "PICKUP", 1, 20_000L);

        UUID id = service.requestExport(
                TENANT,
                ReportExportRegistry.ORDER_REPORT_SUMMARY,
                List.of("locationId", "channelCode", "fulfilmentType", "orderCount", "grossSom"),
                null,
                null,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"),
                List.of(),
                "order-report-summary-test",
                SUBJECT,
                false);

        assertThat(service.processNextQueued()).isTrue();

        ReportExportService.ExportStatusView view =
                service.status(TENANT, id, false).orElseThrow();
        assertThat(view.status()).isEqualTo("COMPLETE");
        assertThat(view.rowCount())
                .as("TELEGRAM/DELIVERY across two dates folds into one row; STOREFRONT/PICKUP is a second")
                .isEqualTo(2);

        String csv = new String(storage.puts.getLast().content(), StandardCharsets.UTF_8);
        assertThat(csv)
                .as("the two TELEGRAM/DELIVERY orders (100000 + 50000) fold into one summed row")
                .contains("150000");
        assertThat(csv).contains("20000");
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
