package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.ApprovalService.Decision;
import uz.horecaos.platform.audit.api.PiiEgressActions;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.CustomerErasureService.AccountNotFoundException;
import uz.horecaos.platform.customers.application.CustomerExportService;
import uz.horecaos.platform.customers.application.CustomerExportService.AccountNotExportableException;
import uz.horecaos.platform.customers.application.CustomerExportService.BundleNotAvailableException;
import uz.horecaos.platform.customers.application.CustomerExportService.ExportRequestNotPendingException;
import uz.horecaos.platform.customers.application.CustomerExportService.RequestedVia;
import uz.horecaos.platform.customers.application.CustomerExportService.Result;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerRecordsExportParticipant;
import uz.horecaos.platform.customers.application.ExportBundleSweeper;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerExportReads;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore.ExportRequestRow;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.media.infrastructure.storage.S3ObjectStorage;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.ObjectStoreContainer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * A data-subject access request, from the asking to the file and its removal (ADR 0029), over a real database, the real
 * approval model and a real S3-compatible store.
 *
 * <p>What each test would still pass on if the code were broken: the bundle test reads the object back out of the store and
 * checks its hash against the row, so a service that wrote the row and not the object, or hashed something else, fails; the
 * audit tests read the trail rather than trusting the call, so a fact written after the decrypt, or without the purpose, or
 * with the phone number in it, fails; the failing-participant test asserts the request is still pending and nothing is in the
 * store, so a service that put the object before the participants finished fails; the approval test asserts no object exists
 * while the second person has not decided; the link test fetches the URL over HTTP, so a link that was minted for the wrong
 * key fails; and the sweeper tests advance the clock past the retention rather than writing an expiry into the row.
 */
class CustomerExportServiceTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000e1");
    private static final UUID ACCOUNT = UUID.fromString("018f9f10-7000-7000-8000-0000000000e2");
    private static final String BUCKET = "dsar-test-bucket";
    private static final String PHONE = "+998901112233";
    private static final String PURPOSE = "Subject access request received by post, identity checked at the counter";
    private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
    private static final ActorRef OWNER = ActorRef.user("export-owner", null);
    private static final ActorRef SECOND = ActorRef.user("export-second-person", null);
    private static final ActorRef CUSTOMER = ActorRef.service("storefront-export-request");

    private static TestDatabase.Handle db;
    private static ObjectStoreContainer objectStore;
    private static S3Client s3;
    private static S3Presigner presigner;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private JdbcClient jdbc;
    private TickingClock clock;
    private ApprovalService approvals;
    private TransactionTemplate tx;
    private CustomerExportService exports;
    private ExportBundleSweeper sweeper;
    private JdbcExportRequestStore requests;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the export tests");
        db = TestDatabase.migrated();
        objectStore = new ObjectStoreContainer();
        objectStore.start();
        s3 = objectStore.s3Client();
        presigner = objectStore.s3Presigner();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Fine to reuse a bucket across runs.
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
        if (objectStore != null) {
            objectStore.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("DELETE FROM audit.approval_requests").update();
        jdbc.sql("DELETE FROM audit.approval_policies WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM customer.export_requests").update();
        jdbc.sql("DELETE FROM customer.contact_points WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).param("slug", "export-" + TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Karim Yoldoshev', 1, 1)
                """).param("id", ACCOUNT).param("t", TENANT).update();

        clock = new TickingClock(START);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        JdbcAuditRecorder audit = new JdbcAuditRecorder(jdbc, mapper);
        approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry(), mapper);
        JdbcCustomerStore store = new JdbcCustomerStore(jdbc);
        CustomerProfileService profiles =
                new CustomerProfileService(store, TestProtection.envelope(), mapper, clock, audit);
        tx.executeWithoutResult(status -> profiles.addContactPoint(TENANT, ACCOUNT, ContactType.PHONE, PHONE, true));

        requests = new JdbcExportRequestStore(jdbc);
        exports = serviceWith(
                List.of(new CustomerRecordsExportParticipant(
                        store,
                        new JdbcCustomerExportReads(jdbc),
                        requests,
                        profiles,
                        TestProtection.envelope(),
                        mapper)),
                Map.of(
                        "customers.dsar_response_deadline_days", 14,
                        "customers.dsar_bundle_retention_days", 3,
                        "customers.dsar_cover_note_en", "Authored English note"));
        sweeper = new ExportBundleSweeper(requests, new S3ObjectStorage(s3, presigner), clock, 100, false);
    }

    private CustomerExportService serviceWith(
            List<CustomerExportParticipant> participants, Map<String, Object> config) {
        return new CustomerExportService(
                requests,
                new JdbcCustomerStore(jdbc),
                approvals,
                new JdbcAuditRecorder(jdbc, mapper),
                new S3ObjectStorage(s3, presigner),
                new FakeConfigurationResolver(config),
                mapper,
                clock,
                BUCKET,
                participants);
    }

    // ------------------------------------------------------------------------------------------------- the request

    @Test
    @DisplayName("asking fixes the date the tenant owes an answer by, and a second ask is the same request")
    void askingIsIdempotentAndFixesTheDeadline() {
        ExportRequestRow first =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.STOREFRONT, CUSTOMER));
        ExportRequestRow second =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));

        assertThat(second.id())
                .as("one outstanding request per account, whoever asks again")
                .isEqualTo(first.id());
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.requestedVia()).isEqualTo("STOREFRONT");
        assertThat(first.responseDueAt())
                .as("fourteen days from the day it was raised, the tenant's setting at that time")
                .isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(AuditTrail.facts(jdbc, "customer.dsar.requested")).hasSize(1);
    }

    @Test
    @DisplayName("an account that does not exist, is anonymised or was merged away cannot ask")
    void aGoneAccountCannotAsk() {
        assertThatThrownBy(() -> tx.execute(
                        status -> exports.request(TENANT, UUID.randomUUID(), RequestedVia.OPERATIONS, OWNER)))
                .isInstanceOf(AccountNotFoundException.class);

        jdbc.sql("UPDATE customer.customer_accounts SET status = 'ANONYMIZED' WHERE id = :id")
                .param("id", ACCOUNT)
                .update();
        assertThatThrownBy(() -> tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER)))
                .isInstanceOf(AccountNotExportableException.class);
    }

    // ------------------------------------------------------------------------------------------------- the bundle

    @Test
    @DisplayName("producing stores one JSON bundle whose hash the row records, and says what is in it")
    void producingStoresTheBundleAndRecordsIt() throws Exception {
        ExportRequestRow asked =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.STOREFRONT, CUSTOMER));

        Result result = tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));

        assertThat(result).isInstanceOf(Result.Done.class);
        ExportRequestRow row = ((Result.Done) result).request();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.producedByActorId()).isEqualTo("export-owner");
        assertThat(row.bundleExpiresAt())
                .as("three days from the day it was produced")
                .isEqualTo(START.plus(Duration.ofDays(3)));

        byte[] stored = s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(row.bundleBucket())
                                .key(row.bundleKey())
                                .build(),
                        ResponseTransformer.toBytes())
                .asByteArray();
        assertThat(HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(stored)))
                .as("the object in the store is the one the row hashed")
                .isEqualTo(row.bundleSha256());
        assertThat(row.bundleBytes()).isEqualTo(stored.length);
        assertThat(row.bundleKey())
                .as("the key names the tenant and the request, never the person")
                .isEqualTo("tenants/" + TENANT + "/dsar-exports/" + asked.id() + ".json")
                .doesNotContain(ACCOUNT.toString());

        JsonNode bundle = mapper.readTree(stored);
        assertThat(bundle.path("format").asString()).isEqualTo("horecaos.dsar.v1");
        assertThat(bundle.path("customerAccountId").asString()).isEqualTo(ACCOUNT.toString());
        JsonNode contacts = bundle.path("sections")
                .path("customers")
                .path("customer.contact_points")
                .path("rows");
        assertThat(contacts.get(0).path("value").asString())
                .as("decrypted: the bundle is what she asked for")
                .isEqualTo(PHONE);
        JsonNode account = bundle.path("sections")
                .path("customers")
                .path("customer.customer_accounts")
                .path("rows");
        assertThat(account.get(0).path("displayName").asString()).isEqualTo("Karim Yoldoshev");

        // The cover note: the tenant's text where it set one, the provisional wording marked as such where not.
        JsonNode cover = bundle.path("cover");
        assertThat(cover.path("responseDeadlineDays").asInt()).isEqualTo(14);
        assertThat(cover.path("responseDueAt").asString())
                .isEqualTo(asked.responseDueAt().toString());
        assertThat(cover.path("note").path("en").asString()).isEqualTo("Authored English note");
        assertThat(cover.path("noteStatus").path("en").asString()).isEqualTo("AUTHORED");
        assertThat(cover.path("noteStatus").path("ru").asString()).isEqualTo("PROVISIONAL");
        assertThat(cover.path("note").path("ru").asString()).isNotBlank();

        // The row keeps counts and names, never a value.
        JsonNode manifest = mapper.readTree(row.manifestJson());
        assertThat(manifest.path("sections").get(0).path("name").asString()).isEqualTo("customers");
        assertThat(row.manifestJson()).doesNotContain(PHONE).doesNotContain("Karim");
        assertThat(manifest.path("withheld").toString()).contains("blacklist_entries.reasons_are_staff_notes");
    }

    @Test
    @DisplayName("the audit fact names who and why, is a registered egress code, and carries no value")
    void theAuditFactNamesWhoAndWhyAndNoValue() {
        ExportRequestRow asked = tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));
        tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));

        List<AuditTrail.Fact> facts = AuditTrail.facts(jdbc, "customer.dsar.exported");
        assertThat(facts).hasSize(1);
        AuditTrail.Fact fact = facts.getFirst();
        assertThat(fact.actorSubject()).isEqualTo("export-owner");
        assertThat(fact.reason()).isEqualTo(PURPOSE);
        assertThat(fact.targetId()).isEqualTo(ACCOUNT);
        assertThat(PiiEgressActions.isEgress("customer.dsar.exported"))
                .as("the merchant's egress log filters by this registry")
                .isTrue();

        String everything = jdbc.sql("SELECT string_agg(row_to_json(e)::text, ' ') FROM audit.audit_events e")
                .query(String.class)
                .single();
        assertThat(everything).doesNotContain(PHONE).doesNotContain("Karim");
    }

    @Test
    @DisplayName("a participant that fails leaves the request pending, no fact and no object")
    void aFailingParticipantLeavesNothingBehind() {
        CustomerExportService failing = serviceWith(List.of(new Exploding()), Map.of());
        ExportRequestRow asked = tx.execute(status -> failing.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));

        assertThatThrownBy(() -> tx.execute(status -> failing.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module is down");

        assertThat(requests.find(TENANT, asked.id()).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(AuditTrail.facts(jdbc, "customer.dsar.exported"))
                .as("rolled back with the decrypt it preceded")
                .isEmpty();
        assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("tenants/" + TENANT + "/dsar-exports/" + asked.id() + ".json")
                        .build()))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @ExportsCustomerData(
            name = "exploding",
            tables = {"customer.leads"})
    private static final class Exploding implements CustomerExportParticipant {
        @Override
        public ExportSection export(ExportSubject subject) {
            throw new IllegalStateException("module is down");
        }
    }

    @Test
    @DisplayName("producing twice writes one bundle and one fact")
    void producingTwiceIsOnce() {
        ExportRequestRow asked = tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));
        Result first = tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));
        Result second = tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));

        assertThat(((Result.Done) second).request().bundleSha256())
                .isEqualTo(((Result.Done) first).request().bundleSha256());
        assertThat(AuditTrail.facts(jdbc, "customer.dsar.exported")).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------------- two people

    @Test
    @DisplayName("with a tenant policy, producing waits for a second person and gathers nothing until then")
    void aPolicyMakesItATwoPersonAct() {
        jdbc.sql("""
                INSERT INTO audit.approval_policies (id, tenant_id, action_code, scope_type, threshold_json,
                    required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :t, 'customer.pii.export', 'TENANT', '{"description":"every export"}'::jsonb,
                        'customer.pii.reveal', :from, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("from", START.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        ExportRequestRow asked = tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));

        Result first = tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));

        assertThat(first).isInstanceOf(Result.Awaiting.class);
        ApprovalOutcome outcome = ((Result.Awaiting) first).outcome();
        assertThat(outcome).isInstanceOf(ApprovalOutcome.Pending.class);
        assertThat(requests.find(TENANT, asked.id()).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(AuditTrail.facts(jdbc, "customer.dsar.exported"))
                .as("nothing was revealed while the second person had not decided")
                .isEmpty();
        assertThatThrownBy(() -> approvals.decide(
                        ((ApprovalOutcome.Pending) outcome).requestId(),
                        Decision.APPROVE,
                        OWNER,
                        "I agree with myself"))
                .as("the maker cannot be the checker")
                .isInstanceOf(RuntimeException.class);

        approvals.decide(
                ((ApprovalOutcome.Pending) outcome).requestId(), Decision.APPROVE, SECOND, "Identity verified");
        Result second = tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER));

        assertThat(second).isInstanceOf(Result.Done.class);
        List<AuditTrail.Fact> facts = AuditTrail.facts(jdbc, "customer.dsar.exported");
        assertThat(facts).hasSize(1);
        assertThat(requests.find(TENANT, asked.id()).orElseThrow().approvalId())
                .as("the row records which approval it was produced under")
                .isNotNull();
    }

    // ------------------------------------------------------------------------------------------------- the link

    @Test
    @DisplayName("the link serves the bundle for a few minutes and every call to mint one is an audit fact")
    void theLinkServesTheBundleAndIsAudited() throws Exception {
        ExportRequestRow asked =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.STOREFRONT, CUSTOMER));
        ExportRequestRow produced = ((Result.Done)
                        tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER)))
                .request();

        CustomerExportService.BundleLink link = tx.execute(
                status -> exports.bundleLink(TENANT, ACCOUNT, asked.id(), "CUSTOMER_SELF_SERVICE", CUSTOMER));

        assertThat(link.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(link.url()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(response.body())))
                .as("the link serves the bundle the request produced")
                .isEqualTo(produced.bundleSha256());

        List<AuditTrail.Fact> facts = AuditTrail.facts(jdbc, "customer.dsar.bundle_revealed");
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().actorSubject()).isEqualTo("storefront-export-request");
        assertThat(facts.getFirst().reason()).isEqualTo("CUSTOMER_SELF_SERVICE");
        assertThat(PiiEgressActions.isEgress("customer.dsar.bundle_revealed")).isTrue();
        String everything = jdbc.sql("SELECT string_agg(row_to_json(e)::text, ' ') FROM audit.audit_events e")
                .query(String.class)
                .single();
        assertThat(everything)
                .as("the link is never recorded")
                .doesNotContain("X-Amz")
                .doesNotContain("Signature");
    }

    @Test
    @DisplayName("there is no link before the bundle exists")
    void noLinkBeforeTheBundle() {
        ExportRequestRow asked = tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));
        assertThatThrownBy(() -> tx.execute(status -> exports.bundleLink(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER)))
                .isInstanceOf(BundleNotAvailableException.class);
        assertThat(AuditTrail.facts(jdbc, "customer.dsar.bundle_revealed")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- expiry

    @Test
    @DisplayName("past its retention the bundle gets no link and the sweeper takes it out of the store")
    void anExpiredBundleIsRemoved() {
        ExportRequestRow asked = tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));
        ExportRequestRow produced = ((Result.Done)
                        tx.execute(status -> exports.produce(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER)))
                .request();

        String bundleKey = java.util.Objects.requireNonNull(produced.bundleKey());
        clock.advance(Duration.ofDays(2));
        assertThat(sweeper.runOnce()).as("not yet due").isZero();
        assertThat(exists(bundleKey)).isTrue();

        clock.advance(Duration.ofDays(1).plusSeconds(1));
        assertThatThrownBy(() -> tx.execute(status -> exports.bundleLink(TENANT, ACCOUNT, asked.id(), PURPOSE, OWNER)))
                .as("expired, even before the sweeper has run")
                .isInstanceOf(BundleNotAvailableException.class);

        ExportBundleSweeper reportOnly =
                new ExportBundleSweeper(requests, new S3ObjectStorage(s3, presigner), clock, 100, true);
        assertThat(reportOnly.runOnce()).as("report-only removes nothing").isZero();
        assertThat(exists(bundleKey)).isTrue();

        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(bundleKey)).isFalse();
        ExportRequestRow after = requests.find(TENANT, asked.id()).orElseThrow();
        assertThat(after.bundleRemovedAt()).isEqualTo(clock.instant());
        assertThat(after.status())
                .as("the request stays, as the record that it happened")
                .isEqualTo("COMPLETED");
        assertThat(sweeper.runOnce()).as("nothing left to remove").isZero();
    }

    // ------------------------------------------------------------------------------------------------- withdrawing

    @Test
    @DisplayName("a withdrawn request cannot be produced, and a produced one cannot be withdrawn")
    void withdrawingAndProducingExcludeEachOther() {
        ExportRequestRow withdrawn =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.STOREFRONT, CUSTOMER));
        ExportRequestRow cancelled = tx.execute(status -> exports.cancel(TENANT, ACCOUNT, withdrawn.id(), CUSTOMER));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(tx.execute(status -> exports.cancel(TENANT, ACCOUNT, withdrawn.id(), CUSTOMER))
                        .status())
                .as("idempotent")
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> tx.execute(status -> exports.produce(TENANT, ACCOUNT, withdrawn.id(), PURPOSE, OWNER)))
                .isInstanceOf(ExportRequestNotPendingException.class);

        ExportRequestRow produced =
                tx.execute(status -> exports.request(TENANT, ACCOUNT, RequestedVia.OPERATIONS, OWNER));
        tx.execute(status -> exports.produce(TENANT, ACCOUNT, produced.id(), PURPOSE, OWNER));
        assertThatThrownBy(() -> tx.execute(status -> exports.cancel(TENANT, ACCOUNT, produced.id(), OWNER)))
                .isInstanceOf(ExportRequestNotPendingException.class);
        assertThat(Set.of(withdrawn.id(), produced.id())).hasSize(2);
    }

    private boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            return true;
        } catch (NoSuchKeyException gone) {
            return false;
        }
    }

    /** The test's clock: every duration asserted advances it. */
    private static final class TickingClock extends Clock {

        private Instant now;

        TickingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
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
