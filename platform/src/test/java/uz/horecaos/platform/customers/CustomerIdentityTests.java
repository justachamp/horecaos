package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.api.CustomerIdentityPolicy;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.infrastructure.persistence.ConfiguredCustomerPolicyLookup;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Customer identity, personal data, and consent (ADR 0015, ADR 0029).
 *
 * <p>The properties under test are the ones that decide whether two people become
 * one account by mistake, whether personal data survives being copied between
 * rows, and whether a consent record can be quietly rewritten.
 */
class CustomerIdentityTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND_A = UUID.randomUUID();
    private static final UUID BRAND_B = UUID.randomUUID();
    private static final String ISSUER = "https://auth.horecaos.uz/realms/horecaos";
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final Instant POLICY_EFFECTIVE_FROM = Instant.parse("2026-08-20T00:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcCustomerStore store;
    private CustomerIdentityService identity;
    private CustomerProfileService profiles;
    private ConsentService consent;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for customer identity tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.addresses, "
                        + "customer.contact_points, customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant(TENANT, "tenant-shared", CustomerIdentityPolicy.TENANT_SHARED);
        insertTenant(OTHER_TENANT, "tenant-isolated", CustomerIdentityPolicy.BRAND_ISOLATED);

        java.time.Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new JdbcCustomerStore(jdbc);
        identity = new CustomerIdentityService(store, new ConfiguredCustomerPolicyLookup(jdbc), clock);

        // A real envelope-encryption stack over a throwaway key-encryption key,
        // so the tenant binding and row binding below are genuinely exercised
        // rather than stubbed into agreeing.
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        java.util.Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")
                                ::get,
                        clock),
                "local"));
        profiles = new CustomerProfileService(
                store, protection, JsonMapper.builder().build(), clock);
        consent = new ConsentService(store, clock);
    }

    @Test
    @DisplayName("a first sign-in creates an account; the second returns the same one")
    void signingInTwiceReturnsOneAccount() {
        var first = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-1");
        var second = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-1");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.account().accountId()).isEqualTo(first.account().accountId());
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("under TENANT_SHARED, two brands share one account with a profile each")
    void tenantSharedGivesOneAccountAcrossBrands() {
        var atBrandA = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-2");
        var atBrandB = identity.resolve(TENANT, BRAND_B, ISSUER, "subject-2");

        assertThat(atBrandB.account().accountId()).isEqualTo(atBrandA.account().accountId());
        assertThat(atBrandB.policy()).isEqualTo(CustomerIdentityPolicy.TENANT_SHARED);
        // One identity, two brand profiles: the customer sees their history at
        // both brands, and each brand keeps its own preferences.
        assertThat(brandProfileCount(atBrandA.account().accountId())).isEqualTo(2);
    }

    @Test
    @DisplayName("under BRAND_ISOLATED, the same person gets a separate account per brand")
    void brandIsolatedSeparatesAccounts() {
        var atBrandA = identity.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-3");
        var atBrandB = identity.resolve(OTHER_TENANT, BRAND_B, ISSUER, "subject-3");

        assertThat(atBrandB.account().accountId())
                .isNotEqualTo(atBrandA.account().accountId());
        assertThat(atBrandB.created()).isTrue();
        assertThat(atBrandB.policy()).isEqualTo(CustomerIdentityPolicy.BRAND_ISOLATED);
        // The partition is on the row, so the boundary survives a later read that
        // has no policy in hand.
        assertThat(partitionOf(atBrandA.account().accountId())).isEqualTo(BRAND_A.toString());
        assertThat(partitionOf(atBrandB.account().accountId())).isEqualTo(BRAND_B.toString());
    }

    @Test
    @DisplayName("a tenant that never configured anything is still TENANT_SHARED")
    void anUnconfiguredTenantKeepsTheSharedDefault() {
        UUID unconfigured = UUID.randomUUID();
        insertTenantRow(unconfigured, "tenant-unconfigured");

        var atBrandA = identity.resolve(unconfigured, BRAND_A, ISSUER, "subject-default");
        var atBrandB = identity.resolve(unconfigured, BRAND_B, ISSUER, "subject-default");

        assertThat(atBrandA.policy()).isEqualTo(CustomerIdentityPolicy.TENANT_SHARED);
        assertThat(atBrandB.account().accountId()).isEqualTo(atBrandA.account().accountId());
        assertThat(partitionOf(atBrandA.account().accountId())).isEqualTo("SHARED");
    }

    @Test
    @DisplayName("there is no second, stored copy of a tenant's identity mode")
    void theTenantRowCarriesNoStoredCopyOfTheIdentityMode() {
        // V0017 put the mode on the tenant row; V0060 made a trigger mirror the
        // versioned table into it; V0072 removed both, because no trigger can
        // maintain it — see the future-cutover test below for why.
        //
        // This assertion is the guard against the column coming back. A stored
        // mode looks harmless and reads fast, and it has now been wrong in two
        // different ways: never written at all (V0017 to V0060), then written
        // from the newest row instead of the current one (V0060 to V0072). The
        // failure of this test is the invitation to read that history first.
        assertThat(tenantColumns()).doesNotContain("customer_identity_policy");
        assertThat(triggerNames("tenant", "customer_identity_policies"))
                .doesNotContain("trg_customer_identity_policy_mirror");
    }

    @Test
    @DisplayName("an account records the version of the policy that governed it, not the mode")
    void anAccountRecordsTheGoverningPolicyVersion() {
        // The version and the mode are two different facts that were briefly the
        // same small integer. The writer bound the identity-mode enum's ordinal
        // plus one, so a TENANT_SHARED tenant stamped 1 — which happened to be
        // the version every tenant sits on — and a BRAND_ISOLATED tenant stamped
        // 2, a version that does not exist. Nothing rejected it: the column has
        // no foreign key. It was invisible only while the V0060 bug made every
        // tenant resolve TENANT_SHARED.
        var shared = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-version-shared");
        var isolated = identity.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-version-isolated");

        assertThat(isolated.policy()).isEqualTo(CustomerIdentityPolicy.BRAND_ISOLATED);
        // Both tenants are on version 1. The mode differs; the version does not.
        assertThat(policyVersionOf(shared.account().accountId())).isEqualTo("1");
        assertThat(policyVersionOf(isolated.account().accountId())).isEqualTo("1");
    }

    @Test
    @DisplayName("a tenant on its third policy version stamps three")
    void anAccountRecordsALaterPolicyVersion() {
        // The point of the column: telling an account created before a governed
        // migration from one created after. A version derived from the mode can
        // only ever be 1 or 2, so it says the same thing about an account created
        // under the tenant's first decision and its third, and the migration that
        // needs a starting point has none.
        supersedeIdentityPolicy(
                TENANT, 1, CustomerIdentityPolicy.BRAND_ISOLATED, Instant.parse("2026-08-20T06:00:00Z"));
        supersedeIdentityPolicy(TENANT, 2, CustomerIdentityPolicy.TENANT_SHARED, Instant.parse("2026-08-20T12:00:00Z"));

        var resolved = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-version-three");

        assertThat(resolved.policy()).isEqualTo(CustomerIdentityPolicy.TENANT_SHARED);
        assertThat(policyVersionOf(resolved.account().accountId())).isEqualTo("3");
    }

    @Test
    @DisplayName("a tenant that configured nothing records no policy version")
    void anUnconfiguredTenantRecordsNoPolicyVersion() {
        UUID unconfigured = UUID.randomUUID();
        insertTenantRow(unconfigured, "tenant-unversioned");

        var resolved = identity.resolve(unconfigured, BRAND_A, ISSUER, "subject-unversioned");

        // Null, not 1. No decision was made, and naming a version would tell a
        // later migration the tenant had governed itself into TENANT_SHARED when
        // nobody chose anything — the same invention the ordinal was.
        assertThat(resolved.policy()).isEqualTo(CustomerIdentityPolicy.TENANT_SHARED);
        assertThat(policyVersionOf(resolved.account().accountId())).isEqualTo("UNCONFIGURED");
    }

    @Test
    @DisplayName("a policy dated for a future cutover does not govern yet")
    void aFutureDatedPolicyDoesNotGovernUntilItTakesEffect() {
        // "Current" used to mean superseded_at IS NULL and nothing else, so the
        // moment an operator recorded a mode change scheduled for next month, it
        // governed. Here that would merge two brands' customers into one account
        // ten days early — the silent re-partitioning V0060 refused to let a
        // deployment do, arriving through a scheduled row instead.
        supersedeIdentityPolicy(
                OTHER_TENANT, 1, CustomerIdentityPolicy.TENANT_SHARED, Instant.parse("2026-09-01T00:00:00Z"));

        var atBrandA = identity.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-future-cutover");
        var atBrandB = identity.resolve(OTHER_TENANT, BRAND_B, ISSUER, "subject-future-cutover");

        assertThat(atBrandA.policy()).isEqualTo(CustomerIdentityPolicy.BRAND_ISOLATED);
        assertThat(atBrandB.account().accountId())
                .isNotEqualTo(atBrandA.account().accountId());
        // Stamped with the version that actually governed them, not the one
        // waiting to.
        assertThat(policyVersionOf(atBrandA.account().accountId())).isEqualTo("1");
    }

    @Test
    @DisplayName("the governing policy changes at the cutover with nothing writing to the table")
    void theCurrentPolicyChangesWithTheClockAndNotWithAWrite() {
        // Why the mode cannot be mirrored into a column, demonstrated rather than
        // asserted about: this is the same divergent state as the test above, and
        // between the two reads below nothing writes to any table.
        Instant cutover = Instant.parse("2026-09-01T00:00:00Z");
        supersedeIdentityPolicy(OTHER_TENANT, 1, CustomerIdentityPolicy.TENANT_SHARED, cutover);

        // During the scheduling window the newest row is version 2 and the
        // governing row is still version 1. A trigger fired on the insert of
        // version 2 could only have stored one of these, and version 2 is the one
        // it would have stored — the answer that is wrong for eleven days.
        assertThat(currentPolicy(OTHER_TENANT, NOW)).isEqualTo("1 BRAND_ISOLATED");

        // The passage of time alone flips it. There is no write to fire a trigger
        // on, so a stored mirror would now be stale in the other direction —
        // whichever predicate had been written into the trigger.
        assertThat(currentPolicy(OTHER_TENANT, cutover)).isEqualTo("2 TENANT_SHARED");

        // And resolution follows the clock it was given, because the instant is a
        // parameter of the function rather than now() inside it: the same tenant,
        // the same two brands, one account after the cutover instead of two.
        var beforeAtA = identity.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-window");
        var beforeAtB = identity.resolve(OTHER_TENANT, BRAND_B, ISSUER, "subject-window");
        assertThat(beforeAtB.account().accountId())
                .isNotEqualTo(beforeAtA.account().accountId());

        CustomerIdentityService afterCutover = new CustomerIdentityService(
                store, new ConfiguredCustomerPolicyLookup(jdbc), Clock.fixed(cutover, ZoneOffset.UTC));
        var afterAtA = afterCutover.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-after");
        var afterAtB = afterCutover.resolve(OTHER_TENANT, BRAND_B, ISSUER, "subject-after");
        assertThat(afterAtB.account().accountId()).isEqualTo(afterAtA.account().accountId());
        assertThat(policyVersionOf(afterAtA.account().accountId())).isEqualTo("2");
    }

    @Test
    @DisplayName("the same subject in two tenants is two unrelated customers")
    void tenantsNeverShareAnAccount() {
        var inTenant = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-4");
        var inOther = identity.resolve(OTHER_TENANT, BRAND_A, ISSUER, "subject-4");

        assertThat(inOther.account().accountId())
                .isNotEqualTo(inTenant.account().accountId());
        assertThat(identity.find(TENANT, BRAND_A, ISSUER, "subject-4")).contains(inTenant.account());
    }

    @Test
    @DisplayName("a second active link for one subject in one partition is impossible")
    void oneSubjectCannotHaveTwoActiveAccounts() {
        var resolved = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-5");
        UUID strayAccount = UUID.randomUUID();
        store.insertAccount(strayAccount, TENANT, null, 1, Instant.parse("2026-08-21T12:00:00Z"));

        // The partial unique index is what stops one person quietly owning two
        // accounts; without it the null partition would compare unequal to
        // itself and every TENANT_SHARED subject could be linked repeatedly.
        assertThat(catchThrowable(() -> store.insertPrincipalLink(
                        UUID.randomUUID(),
                        TENANT,
                        null,
                        strayAccount,
                        ISSUER,
                        "subject-5",
                        Instant.parse("2026-08-21T12:00:00Z"))))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(resolved.account().accountId()).isNotNull();
    }

    @Test
    @DisplayName("a merged account redirects to its surviving target")
    void mergedAccountsRedirect() {
        var source = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-6");
        UUID target = UUID.randomUUID();
        store.insertAccount(target, TENANT, null, 1, Instant.parse("2026-08-21T12:00:00Z"));
        jdbc.sql("UPDATE customer.customer_accounts SET status = 'MERGED', "
                        + "merged_into_account_id = :target WHERE id = :source")
                .param("target", target)
                .param("source", source.account().accountId())
                .update();

        // The source row stays because immutable order snapshots point at it;
        // sign-in follows the redirect rather than resurrecting the tombstone.
        assertThat(identity.resolve(TENANT, BRAND_A, ISSUER, "subject-6")
                        .account()
                        .accountId())
                .isEqualTo(target);
    }

    @Test
    @DisplayName("a cyclic merge redirect is refused rather than looping forever")
    void cyclicMergeRedirectIsRefused() {
        var first = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-7");
        UUID second = UUID.randomUUID();
        store.insertAccount(second, TENANT, null, 1, Instant.parse("2026-08-21T12:00:00Z"));

        jdbc.sql("UPDATE customer.customer_accounts SET merged_into_account_id = :b WHERE id = :a")
                .param("b", second)
                .param("a", first.account().accountId())
                .update();
        jdbc.sql("UPDATE customer.customer_accounts SET merged_into_account_id = :a WHERE id = :b")
                .param("a", first.account().accountId())
                .param("b", second)
                .update();

        // Bad merge data must surface, not hang a customer's sign-in.
        assertThat(catchThrowable(() -> identity.resolve(TENANT, BRAND_A, ISSUER, "subject-7")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cyclic");
    }

    @Test
    @DisplayName("two people sharing a phone number stay two accounts")
    void aSharedPhoneNumberDoesNotMergeAccounts() {
        var husband = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-8");
        var wife = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-9");

        profiles.addContactPoint(TENANT, husband.account().accountId(), ContactType.PHONE, "+998 90 111-22-33", true);
        profiles.addContactPoint(TENANT, wife.account().accountId(), ContactType.PHONE, "+998901112233", true);

        // A household shares a phone and a recycled number changes owner, so a
        // unique constraint here would silently fuse two customers. The lookup
        // returns both and lets a human decide.
        assertThat(profiles.findAccountsByContact(TENANT, ContactType.PHONE, "+998901112233"))
                .containsExactlyInAnyOrder(
                        husband.account().accountId(), wife.account().accountId());
    }

    @Test
    @DisplayName("differently formatted phone numbers find the same customer")
    void phoneNumbersAreNormalisedBeforeHashing() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-10");
        profiles.addContactPoint(TENANT, account.account().accountId(), ContactType.PHONE, "+998 (90) 111-22-33", true);

        // Uzbek numbers get written with spaces, dashes, and brackets in about
        // equal measure; without normalisation a support agent finds nobody.
        assertThat(profiles.findAccountsByContact(TENANT, ContactType.PHONE, "+998901112233"))
                .containsExactly(account.account().accountId());
    }

    @Test
    @DisplayName("a phone number is never stored in clear and is not readable cross-tenant")
    void contactValuesAreEncryptedAndTenantBound() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-11");
        profiles.addContactPoint(TENANT, account.account().accountId(), ContactType.PHONE, "+998901112233", true);

        String stored = jdbc.sql("SELECT encrypted_value FROM customer.contact_points")
                .query(String.class)
                .single();
        assertThat(stored).doesNotContain("998901112233");

        assertThat(profiles.revealContactPoints(TENANT, account.account().accountId(), "support-view"))
                .singleElement()
                .satisfies(contact -> assertThat(contact.value()).isEqualTo("+998901112233"));

        // Another tenant cannot even load the row: the tenant predicate is in the
        // query, so the read returns nothing before decryption is attempted.
        // (The key binding itself is exercised by ciphertextIsBoundToItsRow.)
        assertThat(profiles.revealContactPoints(OTHER_TENANT, account.account().accountId(), "probe"))
                .isEmpty();
    }

    @Test
    @DisplayName("a ciphertext moved to another row refuses to decrypt")
    void ciphertextIsBoundToItsRow() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-12");
        UUID first = profiles.addContactPoint(
                TENANT, account.account().accountId(), ContactType.PHONE, "+998901112233", true);
        UUID second = profiles.addContactPoint(
                TENANT, account.account().accountId(), ContactType.EMAIL, "someone@example.uz", false);

        // Simulate an attacker with write access copying one row's ciphertext
        // onto another. The row id is bound into the AEAD associated data, so
        // this fails loudly instead of revealing the wrong person's data.
        jdbc.sql("UPDATE customer.contact_points SET encrypted_value = "
                        + "(SELECT encrypted_value FROM customer.contact_points WHERE id = :first) "
                        + "WHERE id = :second")
                .param("first", first)
                .param("second", second)
                .update();

        assertThat(catchThrowable(() ->
                        profiles.revealContactPoints(TENANT, account.account().accountId(), "probe")))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    @DisplayName("an address is encrypted but its coordinates stay routable")
    void addressesEncryptTextButKeepCoordinates() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-13");
        UUID addressId = profiles.addAddress(
                TENANT,
                account.account().accountId(),
                "Home",
                new AddressFields(
                        "Amir Temur ko'chasi 12",
                        null,
                        "Toshkent",
                        "Yunusobod",
                        "100084",
                        "2",
                        "4",
                        "12",
                        "Do'kon ro'parasida"),
                "Call on arrival",
                41.3111,
                69.2797,
                CoordinateSource.CUSTOMER_PIN);

        String stored = jdbc.sql("SELECT encrypted_fields FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();
        assertThat(stored).doesNotContain("Amir Temur");

        assertThat(profiles.revealAddresses(TENANT, account.account().accountId(), "dispatch"))
                .singleElement()
                .satisfies(address -> {
                    assertThat(address.fields().line1()).isEqualTo("Amir Temur ko'chasi 12");
                    assertThat(address.deliveryInstructions()).isEqualTo("Call on arrival");
                    // A courier cannot be routed to a ciphertext, and a
                    // coordinate identifies a building rather than a person.
                    assertThat(address.latitude()).isEqualTo(41.3111);
                });
    }

    @Test
    @DisplayName("подъезд, этаж and ориентир survive the round trip and stay encrypted")
    void structuredAddressPartsAreStoredInsideTheEncryptedDocument() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-16");
        profiles.addAddress(
                TENANT,
                account.account().accountId(),
                "Home",
                new AddressFields(
                        "Chilonzor 9-kvartal, 4-uy",
                        null,
                        "Toshkent",
                        "Chilonzor",
                        null,
                        "3",
                        "7",
                        "45",
                        "Ko'k darvoza, dorixona ro'parasida"),
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY);

        String stored = jdbc.sql("SELECT encrypted_fields FROM customer.addresses " + "WHERE customer_account_id = :id")
                .param("id", account.account().accountId())
                .query(String.class)
                .single();

        // The premise: these are inside the ciphertext, not beside it. A landmark
        // and a floor say where one identified person lives, so a clear column
        // would put them in every backup, replica and export.
        assertThat(stored).doesNotContain("Ko'k darvoza");
        assertThat(stored).doesNotContain("Chilonzor");

        assertThat(profiles.revealAddresses(TENANT, account.account().accountId(), "dispatch"))
                .singleElement()
                .satisfies(address -> {
                    // Separately addressable rather than flattened into a line:
                    // a courier needs the entrance and floor as their own facts,
                    // and a partner adapter has its own fields for them.
                    assertThat(address.fields().entrance()).isEqualTo("3");
                    assertThat(address.fields().floor()).isEqualTo("7");
                    assertThat(address.fields().apartment()).isEqualTo("45");
                    assertThat(address.fields().landmark()).isEqualTo("Ko'k darvoza, dorixona ro'parasida");
                });
    }

    @Test
    @DisplayName("an address with no point says why, and only the retryable one is queued")
    void aMissingCoordinateStatesWhetherItIsWorthRetrying() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-17");
        UUID accountId = account.account().accountId();

        // Taken over the phone; nobody has geocoded it yet.
        UUID pending = profiles.addAddress(
                TENANT,
                accountId,
                "Office",
                new AddressFields("Amir Temur 108", null, "Toshkent", "Yunusobod", null, null, null, null, null),
                null,
                null,
                null,
                CoordinateSource.NOT_GEOCODED);

        // A mahalla house described by its landmark. This is a complete address
        // in this market, not a failed one.
        UUID landmarkOnly = profiles.addAddress(
                TENANT,
                accountId,
                "Uy",
                new AddressFields(
                        "Yangiobod mahallasi",
                        null,
                        "Toshkent",
                        "Sergeli",
                        null,
                        null,
                        null,
                        null,
                        "Katta chinor yonida"),
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY);

        // Both have a null coordinate pair. Without the source, a backfill
        // selecting on "latitude IS NULL" would re-query the landmark address on
        // every run for the life of the account and never resolve it.
        assertThat(profiles.revealAddresses(TENANT, accountId, "dispatch"))
                .allSatisfy(address -> assertThat(address.latitude()).isNull());

        assertThat(store.addressesAwaitingGeocoding(TENANT, 100))
                .containsExactly(pending)
                .doesNotContain(landmarkOnly);
    }

    @Test
    @DisplayName("a coordinate source that disagrees with the coordinates is refused")
    void coordinateSourceAndCoordinatesMustAgree() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-18");
        UUID accountId = account.account().accountId();
        AddressFields fields =
                new AddressFields("Navoiy 1", null, "Toshkent", "Shayxontohur", null, null, null, null, null);

        // Claiming a geocoder result with no point would leave dispatch believing
        // it has a routable address when it does not.
        assertThat(catchThrowable(() -> profiles.addAddress(
                        TENANT, accountId, "A", fields, null, null, null, CoordinateSource.GEOCODER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claims a point");

        // And the reverse: a landmark-only address carrying a point would be
        // treated as unresolved by a backfill that trusts the source.
        assertThat(catchThrowable(() -> profiles.addAddress(
                        TENANT, accountId, "B", fields, null, 41.3, 69.2, CoordinateSource.LANDMARK_ONLY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no point");

        // Half a coordinate points at the prime meridian.
        assertThat(catchThrowable(() -> profiles.addAddress(
                        TENANT, accountId, "C", fields, null, 41.3, null, CoordinateSource.CUSTOMER_PIN)))
                .isInstanceOf(IllegalArgumentException.class);

        // A new row may not claim the migration-only origin, or the column would
        // stop meaning anything.
        assertThat(catchThrowable(() -> profiles.addAddress(
                        TENANT, accountId, "D", fields, null, 41.3, 69.2, CoordinateSource.LEGACY_UNSOURCED)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(profiles.revealAddresses(TENANT, accountId, "dispatch")).isEmpty();
    }

    @Test
    @DisplayName("the database refuses a disagreeing coordinate source even without the service")
    void theDatabaseEnforcesTheCoordinateSourceRuleToo() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-19");
        UUID addressId = profiles.addAddress(
                TENANT,
                account.account().accountId(),
                "Home",
                new AddressFields("Bunyodkor 12", null, "Toshkent", "Chilonzor", null, null, null, null, null),
                null,
                41.2856,
                69.2034,
                CoordinateSource.OPERATOR_PIN);

        // The service check exists for the message. This is the protection: a
        // migration or a stray UPDATE cannot strip the point while leaving the
        // row claiming an operator placed one.
        assertThat(catchThrowable(
                        () -> jdbc.sql("UPDATE customer.addresses SET latitude = NULL, longitude = NULL WHERE id = :id")
                                .param("id", addressId)
                                .update()))
                .hasMessageContaining("ck_address_coordinate_source_agrees");
    }

    @Test
    @DisplayName("half a coordinate is refused by the database")
    void aLatitudeWithoutALongitudeIsRefused() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-20");
        UUID addressId = profiles.addAddress(
                TENANT,
                account.account().accountId(),
                "Home",
                new AddressFields("Bunyodkor 12", null, "Toshkent", "Chilonzor", null, null, null, null, null),
                null,
                41.2856,
                69.2034,
                CoordinateSource.OPERATOR_PIN);

        // The original range check passed on a null longitude, because the AND
        // evaluated to NULL and a CHECK accepts NULL. A latitude alone routes a
        // courier to the prime meridian.
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE customer.addresses SET longitude = NULL WHERE id = :id")
                        .param("id", addressId)
                        .update()))
                .hasMessageContaining("ck_address_coordinates");
    }

    // ------------------------------------- a customer editing their own address

    @Test
    @DisplayName("the account predicate is inside the address UPDATE, not only in the read")
    void anAddressUpdateNamesTheAccountItBelongsTo() {
        UUID mine = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-owner")
                .account()
                .accountId();
        UUID theirs = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-other")
                .account()
                .accountId();
        UUID addressId = profiles.addAddress(
                TENANT,
                theirs,
                "Uy",
                new AddressFields(
                        "Yangiobod 4", null, "Toshkent", "Sergeli", null, null, null, null, "qarshisida dorixona"),
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY);
        String before = ciphertextOf(addressId);

        // The store is called directly, with the right address id, the right
        // version and the wrong account. A web-layer test cannot reach this: the
        // handler reads the row first and refuses before the UPDATE runs, so an
        // account predicate that existed only in the SELECT would pass every
        // endpoint assertion while leaving a statement that overwrites a
        // stranger's home for anyone who ever reaches it.
        int written = store.updateAddress(
                TENANT,
                mine,
                addressId,
                1,
                "Ish",
                "not-even-real-ciphertext",
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY.name(),
                NOW);

        assertThat(written).isZero();
        assertThat(ciphertextOf(addressId)).isEqualTo(before);
        assertThat(store.archiveAddress(TENANT, mine, addressId, 1, NOW)).isZero();
        assertThat(statusOf(addressId)).isEqualTo("ACTIVE");

        // The same statements, with the account they belong to, do write — or the
        // assertions above would hold for a method that writes nothing at all.
        assertThat(store.updateAddress(
                        TENANT,
                        theirs,
                        addressId,
                        1,
                        "Ish",
                        before,
                        null,
                        null,
                        null,
                        CoordinateSource.LANDMARK_ONLY.name(),
                        NOW))
                .isOne();
        assertThat(store.archiveAddress(TENANT, theirs, addressId, 2, NOW)).isOne();
        assertThat(statusOf(addressId)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("an archived address is gone from every read a customer has")
    void archivingRemovesAnAddressFromTheReads() {
        UUID accountId = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-archiver")
                .account()
                .accountId();
        UUID addressId = profiles.addAddress(
                TENANT,
                accountId,
                "Uy",
                new AddressFields("Yangiobod 4", null, "Toshkent", "Sergeli", null, null, null, null, null),
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY);

        profiles.archiveAddress(TENANT, accountId, addressId, 1);

        assertThat(profiles.revealAddresses(TENANT, accountId, "self-service")).isEmpty();
        assertThat(profiles.revealAddress(TENANT, accountId, addressId, "self-service"))
                .isEmpty();
        assertThat(store.addressesAwaitingGeocoding(TENANT, 100)).doesNotContain(addressId);
        // And the row is still there, which is the whole difference between an
        // archive and a delete: a dispute about where an order went is answered
        // from it, and the application role holds no DELETE on this table.
        assertThat(statusOf(addressId)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("a profile write is scoped to the tenant and cannot reach a deciding column")
    void aProfileWriteIsScopedAndNarrow() {
        UUID accountId = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-profile")
                .account()
                .accountId();
        jdbc.sql("UPDATE customer.customer_accounts SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", accountId)
                .update();

        assertThat(store.updateAccountProfile(OTHER_TENANT, accountId, 1, "Nobody", "ru", null, NOW))
                .as("an account id alone is not evidence of anything; the tenant is a predicate")
                .isZero();

        assertThat(store.updateAccountProfile(TENANT, accountId, 1, "Ozod", "uz", "Asia/Tashkent", NOW))
                .isOne();
        assertThat(store.account(TENANT, accountId)).hasValueSatisfying(account -> {
            assertThat(account.displayName()).isEqualTo("Ozod");
            assertThat(account.preferredLocale()).isEqualTo("uz");
            assertThat(account.version()).isEqualTo(2);
            assertThat(account.status())
                    .as("the statement never names status, so no caller can un-suspend an "
                            + "account by editing their own display name")
                    .isEqualTo("SUSPENDED");
        });

        assertThat(store.updateAccountProfile(TENANT, accountId, 1, "Again", null, null, NOW))
                .as("the version has moved, so the second writer loses")
                .isZero();
    }

    @Test
    @DisplayName("reading one address is scoped to its own account")
    void readingOneAddressIsScopedToItsAccount() {
        UUID mine = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-reader")
                .account()
                .accountId();
        UUID theirs = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-read-other")
                .account()
                .accountId();
        UUID addressId = profiles.addAddress(
                TENANT,
                theirs,
                "Uy",
                new AddressFields("Yangiobod 4", null, "Toshkent", "Sergeli", null, null, null, null, null),
                null,
                null,
                null,
                CoordinateSource.LANDMARK_ONLY);

        assertThat(profiles.revealAddress(TENANT, mine, addressId, "self-service"))
                .as("empty before anything is decrypted, so no purpose is recorded against a row "
                        + "the caller had no business reading")
                .isEmpty();
        assertThat(profiles.revealAddress(TENANT, theirs, addressId, "self-service"))
                .isPresent();
    }

    private String ciphertextOf(UUID addressId) {
        return jdbc.sql("SELECT encrypted_fields FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();
    }

    private String statusOf(UUID addressId) {
        return jdbc.sql("SELECT status FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("no consent decision means no consent")
    void absenceOfADecisionIsNotConsent() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-14");

        // "We never asked" and "they said yes" are different, and a default-true
        // would merge them into a marketing message nobody agreed to.
        assertThat(consent.hasConsent(TENANT, account.account().accountId(), BRAND_A, "MARKETING", "SMS"))
                .isFalse();
    }

    @Test
    @DisplayName("withdrawing consent supersedes the grant without erasing it")
    void withdrawalSupersedesButPreservesHistory() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-15");
        UUID accountId = account.account().accountId();

        consent.record(
                TENANT,
                accountId,
                BRAND_A,
                "MARKETING",
                "SMS",
                ConsentService.Decision.GRANTED,
                "2026-01",
                ConsentService.Source.STOREFRONT,
                "signup-form",
                Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(consent.hasConsent(TENANT, accountId, BRAND_A, "MARKETING", "SMS"))
                .isTrue();

        consent.record(
                TENANT,
                accountId,
                BRAND_A,
                "MARKETING",
                "SMS",
                ConsentService.Decision.WITHDRAWN,
                "2026-01",
                ConsentService.Source.STOREFRONT,
                "preferences-page",
                Instant.parse("2026-06-01T10:00:00Z"));

        assertThat(consent.hasConsent(TENANT, accountId, BRAND_A, "MARKETING", "SMS"))
                .isFalse();
        // Both decisions survive: proving what someone agreed to and when is the
        // whole obligation, and an update would have destroyed the earlier one.
        assertThat(consent.history(TENANT, accountId)).hasSize(2);
    }

    @Test
    @DisplayName("consent decisions cannot be updated or deleted by the application role")
    void consentIsAppendOnlyAtTheGrantLevel() {
        List<String> privileges = jdbc.sql("""
                SELECT privilege_type FROM information_schema.role_table_grants
                WHERE table_schema = 'customer' AND table_name = 'consent_decisions'
                  AND grantee = 'horecaos_application'
                """).query(String.class).list();

        // The grant, not a code convention. A future bug must not be able to
        // rewrite the evidence either.
        assertThat(privileges).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    @DisplayName("consent is scoped, so a grant at one brand is not a grant at another")
    void consentIsScopedPerBrand() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-16");
        UUID accountId = account.account().accountId();

        consent.record(
                TENANT,
                accountId,
                BRAND_A,
                "MARKETING",
                "SMS",
                ConsentService.Decision.GRANTED,
                "2026-01",
                ConsentService.Source.STOREFRONT,
                null,
                Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(consent.hasConsent(TENANT, accountId, BRAND_A, "MARKETING", "SMS"))
                .isTrue();
        assertThat(consent.hasConsent(TENANT, accountId, BRAND_B, "MARKETING", "SMS"))
                .isFalse();
        assertThat(consent.hasConsent(TENANT, accountId, BRAND_A, "MARKETING", "EMAIL"))
                .isFalse();
    }

    @Test
    @DisplayName("adding a contact to another tenant's account is refused")
    void contactsCannotCrossTenants() {
        var account = identity.resolve(TENANT, BRAND_A, ISSUER, "subject-17");

        assertThat(catchThrowable(() -> profiles.addContactPoint(
                        OTHER_TENANT, account.account().accountId(), ContactType.PHONE, "+998901112233", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the migration gives an existing address a source it can honestly claim")
    void theMigrationBackfillsAddressesThatAlreadyCarriedAPoint() {
        // A separate database migrated only as far as the migration before this
        // one, so this exercises
        // the path a real deployment takes: rows already exist, and the new
        // NOT NULL column plus its equivalence check must not reject them.
        //
        // It comes from the shared holder rather than a CREATE DATABASE issued
        // through this suite's own connection. That spelling named a fixed
        // database and never dropped it, which was survivable only while the
        // container died with the class; and it derived the URL by substituting
        // this connection's database name, which stopped being the container's
        // default the moment a database became a per-class clone — the replace
        // would have matched nothing, the "legacy" database would have been this
        // one, already at the latest version, and the assertions below would have
        // read the wrong tables without failing.
        try (TestDatabase.Handle legacyDb = TestDatabase.empty()) {
            DataSource legacy = legacyDb.dataSource();
            Flyway.configure().dataSource(legacy).target("0019").load().migrate();
            JdbcClient legacyJdbc = JdbcClient.create(legacy);

            UUID tenantId = UUID.randomUUID();
            legacyJdbc.sql("""
                    INSERT INTO tenant.tenants (
                        id, slug, legal_name, display_name, default_currency, default_timezone,
                        status, version)
                    VALUES (:id, 'legacy', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenantId).update();

            UUID accountId = UUID.randomUUID();
            legacyJdbc
                    .sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status)
                    VALUES (:id, :tenantId, 'ACTIVE')
                    """)
                    .param("id", accountId)
                    .param("tenantId", tenantId)
                    .update();

            UUID withPoint = UUID.randomUUID();
            UUID withoutPoint = UUID.randomUUID();
            UUID halfPoint = UUID.randomUUID();
            insertLegacyAddress(legacyJdbc, withPoint, tenantId, accountId, 41.3111, 69.2797);
            insertLegacyAddress(legacyJdbc, withoutPoint, tenantId, accountId, null, null);
            // The old range check accepted this: with longitude NULL the AND
            // evaluated to NULL and the constraint passed.
            insertLegacyAddress(legacyJdbc, halfPoint, tenantId, accountId, 41.5, null);

            Flyway.configure().dataSource(legacy).load().migrate();

            assertThat(coordinateSource(legacyJdbc, withPoint)).isEqualTo("LEGACY_UNSOURCED");
            // Not GEOCODER: nobody recorded where that point came from, and claiming
            // a provider resolved it would make a later provenance audit impossible.
            assertThat(coordinateSource(legacyJdbc, withoutPoint)).isEqualTo("NOT_GEOCODED");

            // The half coordinate is discarded rather than carried forward, so it
            // cannot be mistaken for a location on the prime meridian.
            assertThat(coordinateSource(legacyJdbc, halfPoint)).isEqualTo("NOT_GEOCODED");
            assertThat(legacyJdbc
                            .sql("SELECT latitude FROM customer.addresses WHERE id = :id")
                            .param("id", halfPoint)
                            .query(Double.class)
                            .optional())
                    .isEmpty();
        }
    }

    private static void insertLegacyAddress(
            JdbcClient client, UUID id, UUID tenantId, UUID accountId, Double latitude, Double longitude) {
        client.sql("""
                INSERT INTO customer.addresses (
                    id, tenant_id, customer_account_id, label, encrypted_fields,
                    latitude, longitude)
                VALUES (:id, :tenantId, :accountId, 'Home', 'ciphertext', :latitude, :longitude)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .update();
    }

    private static String coordinateSource(JdbcClient client, UUID addressId) {
        return client.sql("SELECT coordinate_source FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();
    }

    /**
     * A tenant the way the control plane creates one: the tenant row, and the
     * versioned identity policy that records what the operator chose.
     *
     * <p>There is nowhere else to write the mode. The tenant row used to carry a
     * denormalised copy of it; V0072 dropped that column because no trigger can
     * keep a stored copy correct across a scheduled cutover, so the policy row is
     * the only thing a fixture can set and the only thing anything reads.
     */
    private void insertTenant(UUID id, String slug, CustomerIdentityPolicy policy) {
        insertTenantRow(id, slug);
        configureIdentityPolicy(id, policy);
    }

    /** A tenant with no identity policy configured at all. */
    private void insertTenantRow(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    /** What {@code TenantControlPlaneService.createTenant} writes. */
    private void configureIdentityPolicy(UUID tenantId, CustomerIdentityPolicy policy) {
        insertIdentityPolicy(tenantId, 1, policy, POLICY_EFFECTIVE_FROM, null);
    }

    /**
     * A governed mode change: the current row is closed and the next version
     * opens at the same instant.
     *
     * <p>What {@code CustomerIdentityPolicy.supersede} produces, written directly
     * because that path lives in the tenancy module and what is under test here is
     * what customer identity resolution does with the result.
     */
    private void supersedeIdentityPolicy(
            UUID tenantId, int fromVersion, CustomerIdentityPolicy nextMode, Instant changedAt) {
        jdbc.sql("""
                UPDATE tenant.customer_identity_policies
                SET superseded_at = :changedAt
                WHERE tenant_id = :tenantId AND version = :version
                """)
                .param("changedAt", java.time.OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("version", fromVersion)
                .update();
        insertIdentityPolicy(tenantId, fromVersion + 1, nextMode, changedAt, null);
    }

    private void insertIdentityPolicy(
            UUID tenantId, int version, CustomerIdentityPolicy mode, Instant effectiveFrom, Instant supersededAt) {
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from, superseded_at)
                VALUES (:id, :tenantId, :version, :mode, :effectiveFrom, :supersededAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("version", version)
                .param("mode", mode.name())
                // The fixture's clock is the test's clock: a policy that took
                // effect before the fixed instant the service reads.
                .param("effectiveFrom", java.time.OffsetDateTime.ofInstant(effectiveFrom, ZoneOffset.UTC))
                .param(
                        "supersededAt",
                        supersededAt == null ? null : java.time.OffsetDateTime.ofInstant(supersededAt, ZoneOffset.UTC),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * The policy version stamped on an account, as text so that "no row", "null
     * version" and a real version cannot read the same in an assertion.
     */
    private String policyVersionOf(UUID accountId) {
        return jdbc.sql("""
                SELECT coalesce(identity_policy_version::text, 'UNCONFIGURED')
                FROM customer.customer_accounts WHERE id = :id
                """).param("id", accountId).query(String.class).single();
    }

    /**
     * The account's stored partition, as text so that "no row" and "null
     * partition" cannot read the same in an assertion.
     */
    private String partitionOf(UUID accountId) {
        return jdbc.sql("""
                SELECT coalesce(identity_partition_brand_id::text, 'SHARED')
                FROM customer.customer_accounts WHERE id = :id
                """).param("id", accountId).query(String.class).single();
    }

    /**
     * The governing policy at an instant, as one string, so that the version and
     * the mode are asserted together — they are two facts from one row and the
     * bug this closes was reading one of them from somewhere else.
     */
    private String currentPolicy(UUID tenantId, Instant at) {
        return jdbc.sql("""
                SELECT policy_version || ' ' || identity_mode
                FROM tenant.current_customer_identity_policy(:tenantId, :at)
                """)
                .param("tenantId", tenantId)
                .param("at", java.time.OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(String.class)
                .single();
    }

    private List<String> tenantColumns() {
        return jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'tenant' AND table_name = 'tenants'
                """).query(String.class).list();
    }

    private List<String> triggerNames(String schema, String table) {
        return jdbc.sql("""
                SELECT t.tgname
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = :schema AND c.relname = :table AND NOT t.tgisinternal
                """)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .list();
    }

    private long accountCount() {
        return jdbc.sql("SELECT count(*) FROM customer.customer_accounts")
                .query(Long.class)
                .single();
    }

    private long brandProfileCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM customer.brand_profiles WHERE customer_account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }
}
