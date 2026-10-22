package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.CustomerCorrectionService;
import uz.horecaos.platform.customers.application.CustomerCorrectionService.Outcome;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.application.CustomerProfileService.StaleRecordException;
import uz.horecaos.platform.customers.application.CustomerRecordsExportParticipant;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCorrectionStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerExportReads;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant.ExportSubject;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * A correction against evidence (ADR 0029): the same edit the operator could already make, with the value it replaced kept
 * under retention and the evidence it was made against recorded, in the same transaction.
 *
 * <p>What each test would still pass on if the code were broken: the prior value is read back through the data-subject export
 * (the only door to it) and compared with what was typed originally, so a correction that kept nothing, kept the new value, or
 * kept a value bound to the wrong row fails; the unchanged cases assert no history row exists, so a correction that recorded a
 * no-op fails; the stale case asserts the history is empty after the refusal, so a service that wrote history before the edit
 * could be refused fails; and the audit case greps the whole trail for both the old and the new value.
 */
class CustomerCorrectionServiceTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000f1");
    private static final UUID ACCOUNT = UUID.fromString("018f9f10-7000-7000-8000-0000000000f2");
    private static final ActorRef STAFF = ActorRef.user("support-1", null);
    private static final String EVIDENCE = "TICKET-4711";

    private static TestDatabase.Handle db;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final FieldProtection protection = TestProtection.envelope();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private CustomerProfileService profiles;
    private CustomerCorrectionService corrections;
    private CustomerRecordsExportParticipant exporter;
    private UUID contact;
    private UUID address;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the correction tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM customer.profile_corrections WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.addresses WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
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
                """).param("id", TENANT).param("slug", "correct-" + TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Karim Yoldosev', 1, 1)
                """).param("id", ACCOUNT).param("t", TENANT).update();

        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        JdbcAuditRecorder audit = new JdbcAuditRecorder(jdbc, mapper);
        JdbcCustomerStore store = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(store, protection, mapper, clock, audit);
        corrections = new CustomerCorrectionService(
                profiles, store, new JdbcCorrectionStore(jdbc), protection, mapper, audit, clock);
        exporter = new CustomerRecordsExportParticipant(
                store,
                new JdbcCustomerExportReads(jdbc),
                new JdbcExportRequestStore(jdbc),
                profiles,
                protection,
                mapper);

        contact = tx.execute(
                status -> profiles.addContactPoint(TENANT, ACCOUNT, ContactType.PHONE, "+998901112200", true));
        address = tx.execute(status -> profiles.addAddress(
                TENANT,
                ACCOUNT,
                "Home",
                fields("Chilonzor 9"),
                "ring twice",
                null,
                null,
                CoordinateSource.NOT_GEOCODED));
    }

    private static AddressFields fields(String line1) {
        return new AddressFields(line1, null, "Tashkent", "Chilonzor", null, null, null, null, null);
    }

    private int accountVersion() {
        return jdbc.sql("SELECT version FROM customer.customer_accounts WHERE id = :id")
                .param("id", ACCOUNT)
                .query(Integer.class)
                .single();
    }

    /** The only door to a prior value: the export's corrections table. */
    private List<JsonNode> priorValues() {
        ExportSection section =
                tx.execute(status -> exporter.export(new ExportSubject(TENANT, ACCOUNT, Set.of(), "correction test")));
        ExportTable table = section.tables().stream()
                .filter(candidate -> candidate.table().equals("customer.profile_corrections"))
                .findFirst()
                .orElseThrow();
        return table.rows().stream()
                .map(row -> mapper.<JsonNode>valueToTree(row))
                .toList();
    }

    private long historyRows() {
        return jdbc.sql("SELECT count(*) FROM customer.profile_corrections WHERE customer_account_id = :id")
                .param("id", ACCOUNT)
                .query(Long.class)
                .single();
    }

    // -------------------------------------------------------------------------------------------------- the name

    @Test
    @DisplayName("correcting the name changes it, keeps the old one under retention and records the evidence")
    void aNameIsCorrectedAndTheOldOneKept() {
        Outcome outcome = tx.execute(status ->
                corrections.correctDisplayName(TENANT, ACCOUNT, accountVersion(), "Karim Yoldoshev", EVIDENCE, STAFF));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        assertThat(jdbc.sql("SELECT display_name FROM customer.customer_accounts WHERE id = :id")
                        .param("id", ACCOUNT)
                        .query(String.class)
                        .single())
                .isEqualTo("Karim Yoldoshev");
        List<JsonNode> prior = priorValues();
        assertThat(prior).hasSize(1);
        assertThat(prior.getFirst().path("subject").asString()).isEqualTo("DISPLAY_NAME");
        assertThat(prior.getFirst().path("evidenceReference").asString()).isEqualTo(EVIDENCE);
        assertThat(prior.getFirst().path("priorValue").path("displayName").asString())
                .as("the value as it was before the correction")
                .isEqualTo("Karim Yoldosev");
    }

    @Test
    @DisplayName("a correction to what is already there records nothing")
    void anUnchangedValueIsNotACorrection() {
        Outcome outcome = tx.execute(status ->
                corrections.correctDisplayName(TENANT, ACCOUNT, accountVersion(), "Karim Yoldosev", EVIDENCE, STAFF));

        assertThat(outcome).isInstanceOf(Outcome.Unchanged.class);
        assertThat(historyRows()).isZero();
        assertThat(AuditTrail.facts(jdbc, "customer.correction.applied")).isEmpty();
    }

    @Test
    @DisplayName("a stale version is refused and leaves no history, because the edit failed")
    void aStaleVersionLeavesNoHistory() {
        int stale = accountVersion() - 1;
        assertThatThrownBy(() -> tx.execute(status ->
                        corrections.correctDisplayName(TENANT, ACCOUNT, stale, "Someone Else", EVIDENCE, STAFF)))
                .isInstanceOf(StaleRecordException.class);

        assertThat(historyRows()).isZero();
        assertThat(jdbc.sql("SELECT display_name FROM customer.customer_accounts WHERE id = :id")
                        .param("id", ACCOUNT)
                        .query(String.class)
                        .single())
                .isEqualTo("Karim Yoldosev");
    }

    // -------------------------------------------------------------------------------------------------- the rest

    @Test
    @DisplayName("correcting a date of birth keeps the wrong one, and refuses something that is not a date")
    void aDateOfBirthIsCorrected() {
        tx.executeWithoutResult(status -> profiles.updateDateOfBirth(TENANT, ACCOUNT, accountVersion(), "1990-05-17"));

        Outcome outcome = tx.execute(status ->
                corrections.correctDateOfBirth(TENANT, ACCOUNT, accountVersion(), "1991-05-17", EVIDENCE, STAFF));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        assertThat(profiles.revealDateOfBirth(TENANT, ACCOUNT, "test", STAFF)).contains("1991-05-17");
        assertThat(priorValues()
                        .getFirst()
                        .path("priorValue")
                        .path("dateOfBirth")
                        .asString())
                .isEqualTo("1990-05-17");
        assertThatThrownBy(() -> tx.execute(status -> corrections.correctDateOfBirth(
                        TENANT, ACCOUNT, accountVersion(), "17/05/1991", EVIDENCE, STAFF)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "correcting a contact point keeps the old number; the same number spelled differently is not a correction")
    void aContactPointIsCorrected() {
        Outcome same = tx.execute(status ->
                corrections.correctContactPoint(TENANT, ACCOUNT, contact, "+998 90 111 22 00", EVIDENCE, STAFF));
        assertThat(same).as("the same digits with other punctuation").isInstanceOf(Outcome.Unchanged.class);
        assertThat(historyRows()).isZero();

        Outcome changed = tx.execute(
                status -> corrections.correctContactPoint(TENANT, ACCOUNT, contact, "+998901112233", EVIDENCE, STAFF));

        assertThat(changed).isInstanceOf(Outcome.Applied.class);
        JsonNode prior = priorValues().getFirst();
        assertThat(prior.path("subject").asString()).isEqualTo("CONTACT_POINT");
        assertThat(prior.path("targetId").asString()).isEqualTo(contact.toString());
        assertThat(prior.path("priorValue").path("value").asString()).isEqualTo("+998901112200");
        assertThat(profiles.revealContactPoints(TENANT, ACCOUNT, "test", STAFF)
                        .getFirst()
                        .value())
                .isEqualTo("+998901112233");
    }

    @Test
    @DisplayName("correcting an address keeps the whole old address")
    void anAddressIsCorrected() {
        int version = jdbc.sql("SELECT version FROM customer.addresses WHERE id = :id")
                .param("id", address)
                .query(Integer.class)
                .single();

        Outcome outcome = tx.execute(status -> corrections.correctAddress(
                TENANT,
                ACCOUNT,
                address,
                version,
                "Home",
                fields("Chilonzor 19"),
                "ring twice",
                null,
                null,
                CoordinateSource.NOT_GEOCODED,
                EVIDENCE,
                STAFF));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        JsonNode prior = priorValues().getFirst().path("priorValue");
        assertThat(prior.path("fields").path("line1").asString()).isEqualTo("Chilonzor 9");
        assertThat(prior.path("deliveryInstructions").asString()).isEqualTo("ring twice");
        assertThat(profiles.revealAddress(TENANT, ACCOUNT, address, "test", STAFF)
                        .orElseThrow()
                        .fields()
                        .line1())
                .isEqualTo("Chilonzor 19");

        Outcome again = tx.execute(status -> corrections.correctAddress(
                TENANT,
                ACCOUNT,
                address,
                version + 1,
                "Home",
                fields("Chilonzor 19"),
                "ring twice",
                null,
                null,
                CoordinateSource.NOT_GEOCODED,
                EVIDENCE,
                STAFF));
        assertThat(again).isInstanceOf(Outcome.Unchanged.class);
    }

    // -------------------------------------------------------------------------------------------------- the evidence

    @Test
    @DisplayName("the evidence is a reference, never prose: a name or a sentence in the box is refused")
    void theEvidenceIsAReferenceNotProse() {
        for (String prose : List.of("customer showed passport", "Karim Yoldoshev", "ab", "", "x".repeat(81))) {
            assertThatThrownBy(() -> tx.execute(status -> corrections.correctDisplayName(
                            TENANT, ACCOUNT, accountVersion(), "Someone Else", prose, STAFF)))
                    .as("'%s' is not a reference", prose)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(historyRows()).isZero();

        // The database says the same, so a writer that is not this service cannot put prose there either.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO customer.profile_corrections (id, tenant_id, customer_account_id, subject,
                            evidence_reference, prior_value_protected, corrected_by_actor_type, corrected_by_actor_id)
                        VALUES (:id, :t, :a, 'DISPLAY_NAME', 'a name in a box', 'x', 'USER', 'someone')
                        """)
                        .param("id", UUID.randomUUID())
                        .param("t", TENANT)
                        .param("a", ACCOUNT)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the audit fact names the evidence and the subject, and neither the old value nor the new one")
    void theAuditFactCarriesNoValue() {
        tx.execute(status ->
                corrections.correctDisplayName(TENANT, ACCOUNT, accountVersion(), "Karim Yoldoshev", EVIDENCE, STAFF));

        List<AuditTrail.Fact> facts = AuditTrail.facts(jdbc, "customer.correction.applied");
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().actorSubject()).isEqualTo("support-1");
        assertThat(facts.getFirst().reason()).contains(EVIDENCE);
        assertThat(facts.getFirst().change().toString()).contains("DISPLAY_NAME");
        String everything = jdbc.sql("SELECT string_agg(row_to_json(e)::text, ' ') FROM audit.audit_events e")
                .query(String.class)
                .single();
        assertThat(everything).doesNotContain("Yoldosev").doesNotContain("Yoldoshev");
    }

    @Test
    @DisplayName("the history lists what was corrected, by whom and against what, and never the values")
    void theHistoryHasNoValues() {
        tx.execute(status ->
                corrections.correctDisplayName(TENANT, ACCOUNT, accountVersion(), "Karim Yoldoshev", EVIDENCE, STAFF));

        var history = corrections.history(TENANT, ACCOUNT);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().subject()).isEqualTo("DISPLAY_NAME");
        assertThat(history.getFirst().evidenceReference()).isEqualTo(EVIDENCE);
        assertThat(history.getFirst().correctedByActorId()).isEqualTo("support-1");
        assertThat(history.getFirst().toString()).doesNotContain("Yoldosev");
    }
}
