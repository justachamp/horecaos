package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.CustomerErasureService;
import uz.horecaos.platform.customers.application.CustomerErasureService.RequestedVia;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ErasureRequestRow;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The erasure-request mechanism ADR 0029's own Implementation status said did
 * not exist anywhere in this codebase, and ADR 0044 hit from the other side the
 * same day: the module-level erasure operations {@code
 * CustomerMetricProjectionService.erase} and {@code JdbcAudienceStore.eraseMembership}
 * "exist and are tested", but "nothing in the platform ever produces the fact a
 * sweep would consume".
 *
 * <p>The properties under test are the ones the wave brief names directly: a
 * request is recorded once no matter how many times it is asked for; execution
 * actually overwrites what it claims to and leaves a second subject's data
 * alone; every transition leaves an ADR 0027 fact naming who did it; another
 * tenant's subject cannot be reached at all; and what the platform must keep —
 * the account row order and payment history point at, the commercial facts on
 * a brand profile, the append-only consent trail — survives the same erasure
 * that removes everything else.
 */
class CustomerErasureTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String ISSUER = "https://auth.horecaos.uz/realms/horecaos";
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private static final ActorRef STAFF_ACTOR = ActorRef.user("owner-1", null);
    private static final ActorRef SELF_SERVICE_ACTOR = ActorRef.service("storefront-erasure-request");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCustomerStore store;
    private FieldProtection protection;
    private Clock clock;
    private CustomerErasureService erasure;
    private List<UUID> calledParticipants;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for customer erasure tests");
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
        jdbc.sql("TRUNCATE TABLE customer.erasure_requests, customer.consent_decisions, "
                        + "customer.addresses, customer.contact_points, customer.brand_profiles, "
                        + "customer.principal_links, customer.blacklist_entries, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        insertTenantRow(TENANT, "tenant-a");
        insertTenantRow(OTHER_TENANT, "tenant-b");

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new JdbcCustomerStore(jdbc);
        // A real envelope-encryption stack, matching CustomerIdentityTests: the
        // tenant and row binding this suite depends on (a ciphertext that
        // survives erasure unchanged, and one that does not) has to be genuinely
        // exercised rather than stubbed into agreeing.
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        java.util.Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")
                                ::get,
                        clock),
                "local"));
        ObjectMapper objectMapper = JsonMapper.builder().build();
        calledParticipants = new java.util.ArrayList<>();
        CustomerErasureParticipant recordingParticipant = (tenantId, accountId) -> calledParticipants.add(accountId);
        erasure = new CustomerErasureService(
                store, protection, clock, new JdbcAuditRecorder(jdbc, objectMapper), List.of(recordingParticipant));
    }

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("raising a request twice returns the same request; retrying after execution returns the completed one")
    void requestIsIdempotentUnderRetry() {
        UUID accountId = newAccount(TENANT, "Aziz Rahimov");

        ErasureRequestRow first = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);
        ErasureRequestRow second = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(requestCount(accountId)).isEqualTo(1);

        erasure.execute(TENANT, accountId, first.id(), STAFF_ACTOR);
        ErasureRequestRow third = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);

        assertThat(third.id()).isEqualTo(first.id());
        assertThat(third.status()).isEqualTo("COMPLETED");
        assertThat(requestCount(accountId))
                .as("an already-erased account gets its existing completed request back, not a fresh PENDING one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("executing an already-completed request is a no-op, not a second erasure")
    void executeIsIdempotentUnderRetry() {
        UUID accountId = newAccountWithContactAndAddress(TENANT, "Nodira Yusupova");
        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);

        ErasureRequestRow firstExecution = erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);
        String ciphertextAfterFirst = rawContactCiphertext(accountId);
        ErasureRequestRow secondExecution = erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);

        assertThat(secondExecution.completedAt()).isEqualTo(firstExecution.completedAt());
        assertThat(rawContactCiphertext(accountId))
                .as("a second execution must not re-encrypt what the first already tombstoned")
                .isEqualTo(ciphertextAfterFirst);
        assertThat(completedAuditFactCount(accountId))
                .as("one execution, one fact — a retried call is not a second recorded erasure")
                .isEqualTo(1);
        assertThat(calledParticipants)
                .as("a no-op replay must not call every registered erasure participant a second time")
                .containsExactly(accountId);
    }

    // ---------------------------------------------------------- erasure itself

    @Test
    @DisplayName("execution anonymises the account and overwrites every contact point and address, active or archived")
    void executionErasesWhatItClaimsTo() {
        UUID accountId = newAccount(TENANT, "Karim Yoldoshev");
        String originalPhoneCiphertext = insertContact(accountId, "PHONE", "+998901112233", true);
        UUID activeAddressId = insertAddress(accountId, "Home", "line 1, Tashkent", true);
        UUID archivedAddressId = insertAddress(accountId, "Old flat", "line 1, old address", false);
        String originalArchivedCiphertext = rawAddressCiphertext(archivedAddressId);
        UUID linkId = insertPrincipalLink(accountId, "subject-erase-me");
        // newAccount already moved the row from version 1 (insertAccount's own
        // default) to version 2 via its own updateAccountProfile call.
        int written = store.updateDateOfBirth(
                TENANT,
                accountId,
                2,
                protect("customer.customer_accounts", accountId, "date_of_birth_encrypted", "1990-01-01"),
                NOW);
        assertThat(written)
                .as("fixture setup: the date of birth must actually have been written")
                .isEqualTo(1);

        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.OPERATIONS, STAFF_ACTOR);
        erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);

        var account = store.account(TENANT, accountId).orElseThrow();
        assertThat(account.status()).isEqualTo("ANONYMIZED");
        assertThat(account.displayName()).isNull();
        assertThat(account.dateOfBirthEncrypted()).isNull();
        assertThat(anonymizedAtOf(accountId)).isNotNull();

        assertThat(rawContactCiphertext(accountId)).isNotEqualTo(originalPhoneCiphertext);
        assertThat(rawContactHash(accountId))
                .as("the lookup hash must change too, or a phone search still finds the erased row")
                .isNotEqualTo(protection.lookupHash(TENANT, "customer.contact.phone", "+998901112233"));

        assertThat(rawAddressCiphertext(activeAddressId)).isNotNull();
        assertThat(rawAddressCiphertext(archivedAddressId))
                .as(
                        "an archived address is not exempt — archiveAddress's own doc leaves the ciphertext for exactly this act")
                .isNotEqualTo(originalArchivedCiphertext);

        assertThat(principalLinkStatus(linkId))
                .as("a severed link stops a later sign-in from reattaching to the account it just anonymised")
                .isEqualTo("UNLINKED");

        assertThat(calledParticipants).containsExactly(accountId);
    }

    @Test
    @DisplayName("erasing one subject leaves a second subject's contact and address data untouched")
    void erasureLeavesAnotherSubjectUntouched() {
        UUID erased = newAccount(TENANT, "Erased Person");
        String erasedCiphertext = insertContact(erased, "PHONE", "+998901110001", true);
        insertAddress(erased, "Home", "erased street", true);

        UUID other = newAccount(TENANT, "Other Person");
        String otherCiphertext = insertContact(other, "PHONE", "+998901110002", true);
        UUID otherAddressId = insertAddress(other, "Home", "untouched street", true);

        ErasureRequestRow request = erasure.request(TENANT, erased, RequestedVia.OPERATIONS, STAFF_ACTOR);
        erasure.execute(TENANT, erased, request.id(), STAFF_ACTOR);

        assertThat(rawContactCiphertext(erased)).isNotEqualTo(erasedCiphertext);
        assertThat(rawContactCiphertext(other))
                .as("another customer's phone must survive somebody else's erasure byte for byte")
                .isEqualTo(otherCiphertext);
        assertThat(rawAddressCiphertext(otherAddressId)).isNotNull();
        assertThat(store.account(TENANT, other).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    // --------------------------------------------------------------------- audit

    @Test
    @DisplayName("every transition leaves an ADR 0027 fact naming the actor who caused it")
    void auditFactNamesTheActor() {
        UUID accountId = newAccount(TENANT, "Audited Person");

        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);
        assertThat(auditActorSubject("customer.erasure.requested", accountId)).isEqualTo(SELF_SERVICE_ACTOR.subject());

        erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);
        assertThat(auditActorSubject("customer.erasure.completed", accountId)).isEqualTo(STAFF_ACTOR.subject());
    }

    @Test
    @DisplayName("cancelling a request leaves its own fact naming the actor, and the account stays untouched")
    void cancelLeavesAnAuditFactAndDoesNotErase() {
        UUID accountId = newAccount(TENANT, "Changed Their Mind");
        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);

        ErasureRequestRow cancelled = erasure.cancel(TENANT, accountId, request.id(), SELF_SERVICE_ACTOR);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(auditActorSubject("customer.erasure.cancelled", accountId)).isEqualTo(SELF_SERVICE_ACTOR.subject());
        assertThat(store.account(TENANT, accountId).orElseThrow().status()).isEqualTo("ACTIVE");

        // Idempotent: cancelling again is a no-op, not an error.
        assertThat(erasure.cancel(TENANT, accountId, request.id(), SELF_SERVICE_ACTOR)
                        .status())
                .isEqualTo("CANCELLED");

        assertThatThrownBy(() -> erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR))
                .isInstanceOf(CustomerErasureService.ErasureRequestCancelledException.class);
    }

    @Test
    @DisplayName("a completed request cannot be cancelled")
    void cannotCancelACompletedRequest() {
        UUID accountId = newAccount(TENANT, "Already Erased");
        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.OPERATIONS, STAFF_ACTOR);
        erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);

        assertThatThrownBy(() -> erasure.cancel(TENANT, accountId, request.id(), STAFF_ACTOR))
                .isInstanceOf(CustomerErasureService.ErasureRequestCompletedException.class);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("a request for another tenant's subject is refused")
    void crossTenantRequestIsRefused() {
        UUID accountId = newAccount(TENANT, "Tenant A's Customer");

        assertThatThrownBy(() -> erasure.request(OTHER_TENANT, accountId, RequestedVia.OPERATIONS, STAFF_ACTOR))
                .isInstanceOf(CustomerErasureService.AccountNotFoundException.class);
    }

    @Test
    @DisplayName("executing a request through another tenant's id is refused")
    void crossTenantExecuteIsRefused() {
        UUID accountId = newAccount(TENANT, "Tenant A's Customer");
        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.OPERATIONS, STAFF_ACTOR);

        assertThatThrownBy(() -> erasure.execute(OTHER_TENANT, accountId, request.id(), STAFF_ACTOR))
                .isInstanceOf(CustomerErasureService.NoSuchErasureRequestException.class);

        // The account is provably untouched: the refusal happened before anything
        // in TENANT's own data was read, let alone written.
        assertThat(store.account(TENANT, accountId).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    // --------------------------------------------------------------- retention

    @Test
    @DisplayName("what the platform must keep survives the same erasure that removes everything else")
    void retentionSurvivesErasure() {
        UUID accountId = newAccount(TENANT, "Retained Somewhere");
        UUID brandProfileId = UUID.randomUUID();
        store.upsertBrandProfile(brandProfileId, TENANT, BRAND, accountId, NOW);
        jdbc.sql("""
                UPDATE customer.brand_profiles
                SET loyalty_reference = 'LOY-42', first_order_at = :now, last_order_at = :now
                WHERE id = :id
                """)
                .param("id", brandProfileId)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
        store.insertConsentDecision(
                UUID.randomUUID(),
                TENANT,
                accountId,
                BRAND,
                "MARKETING",
                "SMS",
                "GRANTED",
                "v1",
                "STOREFRONT",
                null,
                NOW);

        ErasureRequestRow request = erasure.request(TENANT, accountId, RequestedVia.OPERATIONS, STAFF_ACTOR);
        erasure.execute(TENANT, accountId, request.id(), STAFF_ACTOR);

        // The account row itself: orders, payments, and loyalty all carry a
        // foreign key to (id, tenant_id) here, so this row surviving with the
        // same id is what keeps that history reconcilable at all.
        assertThat(store.accountExists(TENANT, accountId)).isTrue();

        // Commercial facts: loyalty reference and order timestamps are what ADR
        // 0029 means by "order totals and settlement facts stay reconcilable".
        var brandProfile = jdbc.sql(
                        "SELECT loyalty_reference, first_order_at FROM customer.brand_profiles WHERE id = :id")
                .param("id", brandProfileId)
                .query((row, n) -> new Object[] {row.getString("loyalty_reference"), row.getObject("first_order_at")})
                .single();
        assertThat(brandProfile[0]).isEqualTo("LOY-42");
        assertThat(brandProfile[1]).isNotNull();

        // Append-only consent evidence: the record of what was agreed to and when
        // is not itself personal data, and erasing it would destroy the proof
        // that a marketing message was ever lawfully sent.
        assertThat(consentDecisionCount(accountId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a merged account is not erased directly; the survivor is named instead")
    void mergedAccountRefusesDirectErasure() {
        UUID survivor = newAccount(TENANT, "Survivor");
        UUID merged = newAccount(TENANT, "Merged Away");
        jdbc.sql("""
                UPDATE customer.customer_accounts SET status = 'MERGED', merged_into_account_id = :survivor
                WHERE id = :merged
                """).param("survivor", survivor).param("merged", merged).update();

        ErasureRequestRow request = erasure.request(TENANT, merged, RequestedVia.OPERATIONS, STAFF_ACTOR);

        assertThatThrownBy(() -> erasure.execute(TENANT, merged, request.id(), STAFF_ACTOR))
                .isInstanceOf(CustomerErasureService.MergedAccountException.class)
                .satisfies(thrown -> assertThat(
                                ((CustomerErasureService.MergedAccountException) thrown).survivingAccountId())
                        .isEqualTo(survivor));
    }

    // -------------------------------------------------- tenant-wide worklist (ADR 0109)

    @Test
    @DisplayName("the tenant-wide worklist lists every account's requests, not just one account's own history")
    void worklistListsEveryAccountsRequests() {
        UUID first = newAccount(TENANT, "First");
        ErasureRequestRow firstRequest = erasure.request(TENANT, first, RequestedVia.OPERATIONS, STAFF_ACTOR);
        UUID second = newAccount(TENANT, "Second");
        ErasureRequestRow secondRequest = erasure.request(TENANT, second, RequestedVia.STOREFRONT, SELF_SERVICE_ACTOR);

        List<ErasureRequestRow> worklist = erasure.worklist(TENANT, null, 200);

        assertThat(worklist)
                .extracting(ErasureRequestRow::id)
                .containsExactlyInAnyOrder(secondRequest.id(), firstRequest.id());
    }

    @Test
    @DisplayName("the worklist filters by status")
    void worklistFiltersByStatus() {
        UUID pendingAccount = newAccount(TENANT, "Still pending");
        ErasureRequestRow pending = erasure.request(TENANT, pendingAccount, RequestedVia.OPERATIONS, STAFF_ACTOR);
        UUID completedAccount = newAccountWithContactAndAddress(TENANT, "Completed");
        ErasureRequestRow completed = erasure.request(TENANT, completedAccount, RequestedVia.OPERATIONS, STAFF_ACTOR);
        erasure.execute(TENANT, completedAccount, completed.id(), STAFF_ACTOR);

        assertThat(erasure.worklist(TENANT, "PENDING", 200))
                .extracting(ErasureRequestRow::id)
                .containsExactly(pending.id());
        assertThat(erasure.worklist(TENANT, "COMPLETED", 200))
                .extracting(ErasureRequestRow::id)
                .containsExactly(completed.id());
    }

    @Test
    @DisplayName("the worklist never crosses a tenant boundary")
    void worklistNeverCrossesATenantBoundary() {
        UUID mine = newAccount(TENANT, "Mine");
        ErasureRequestRow myRequest = erasure.request(TENANT, mine, RequestedVia.OPERATIONS, STAFF_ACTOR);
        UUID theirs = newAccount(OTHER_TENANT, "Theirs");
        erasure.request(OTHER_TENANT, theirs, RequestedVia.OPERATIONS, STAFF_ACTOR);

        assertThat(erasure.worklist(TENANT, null, 200))
                .extracting(ErasureRequestRow::id)
                .containsExactly(myRequest.id());
    }

    // ------------------------------------------------------------------- fixture

    private void insertTenantRow(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private UUID newAccount(UUID tenantId, String displayName) {
        UUID accountId = UUID.randomUUID();
        store.insertAccount(accountId, tenantId, null, 1, NOW);
        store.updateAccountProfile(tenantId, accountId, 1, displayName, "ru", "Asia/Tashkent", NOW);
        return accountId;
    }

    private UUID newAccountWithContactAndAddress(UUID tenantId, String displayName) {
        UUID accountId = newAccount(tenantId, displayName);
        insertContact(accountId, "PHONE", "+998901119999", true);
        insertAddress(accountId, "Home", "line 1", true);
        return accountId;
    }

    /** @return the ciphertext this contact point was written with, for a before/after comparison */
    private String insertContact(UUID accountId, String type, String rawValue, boolean primary) {
        UUID contactId = UUID.randomUUID();
        String encrypted = protect("customer.contact_points", contactId, "encrypted_value", rawValue);
        store.insertContactPoint(
                contactId,
                TENANT,
                accountId,
                type,
                protection.lookupHash(TENANT, "customer.contact.phone", rawValue),
                encrypted,
                primary,
                NOW);
        return encrypted;
    }

    private UUID insertAddress(UUID accountId, String label, String line, boolean active) {
        UUID addressId = UUID.randomUUID();
        String encryptedFields = protect("customer.addresses", addressId, "encrypted_fields", line);
        store.insertAddress(
                addressId, TENANT, accountId, label, encryptedFields, null, null, null, "LANDMARK_ONLY", NOW);
        if (!active) {
            store.archiveAddress(TENANT, accountId, addressId, 1, NOW);
        }
        return addressId;
    }

    private UUID insertPrincipalLink(UUID accountId, String subject) {
        UUID linkId = UUID.randomUUID();
        store.insertPrincipalLink(linkId, TENANT, null, accountId, ISSUER, subject, NOW);
        return linkId;
    }

    private String protect(String table, UUID recordId, String column, String plaintext) {
        return protection
                .protect(TENANT, DataClass.PERSONAL, new RecordRef(table, column, recordId), plaintext)
                .serialize();
    }

    // ------------------------------------------------------------------- readers

    private long requestCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM customer.erasure_requests WHERE customer_account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private String rawContactCiphertext(UUID accountId) {
        return jdbc.sql(
                        "SELECT encrypted_value FROM customer.contact_points WHERE customer_account_id = :id ORDER BY created_at LIMIT 1")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private String rawContactHash(UUID accountId) {
        return jdbc.sql(
                        "SELECT normalized_hash FROM customer.contact_points WHERE customer_account_id = :id ORDER BY created_at LIMIT 1")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private String rawAddressCiphertext(UUID addressId) {
        return jdbc.sql("SELECT encrypted_fields FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();
    }

    private String principalLinkStatus(UUID linkId) {
        return jdbc.sql("SELECT status FROM customer.principal_links WHERE id = :id")
                .param("id", linkId)
                .query(String.class)
                .single();
    }

    private @org.jspecify.annotations.Nullable Instant anonymizedAtOf(UUID accountId) {
        OffsetDateTime value = jdbc.sql("SELECT anonymized_at FROM customer.customer_accounts WHERE id = :id")
                .param("id", accountId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        return value == null ? null : value.toInstant();
    }

    private long consentDecisionCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM customer.consent_decisions WHERE customer_account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private String auditActorSubject(String actionCode, UUID accountId) {
        return jdbc.sql("""
                SELECT actor_subject FROM audit.audit_events
                WHERE action_code = :actionCode AND target_id = :accountId
                ORDER BY recorded_at DESC LIMIT 1
                """)
                .param("actionCode", actionCode)
                .param("accountId", accountId)
                .query(String.class)
                .single();
    }

    private long completedAuditFactCount(UUID accountId) {
        return jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = 'customer.erasure.completed' AND target_id = :accountId
                """).param("accountId", accountId).query(Long.class).single();
    }
}
