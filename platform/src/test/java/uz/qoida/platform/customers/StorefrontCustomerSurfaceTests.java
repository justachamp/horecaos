package uz.qoida.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.customers.api.RecipientContactDirectory;
import uz.qoida.platform.payments.application.PaymentLegalEntityResolver;
import uz.qoida.platform.support.TestDatabase;

/**
 * The customer's own surface: their profile, their addresses, their orders, and
 * what they may pay with (ADR 0015, ADR 0029, ADR 0031, ADR 0049).
 *
 * <p>Three of these four things already existed and were reachable only by staff.
 * Addresses sat behind {@code CUSTOMER_MANAGE} and {@code CUSTOMER_PII_REVEAL},
 * order history had no list at all, and no endpoint would tell a customer their
 * own account id — so every assertion below that expects a 200 or a 201 would
 * have read 403 or 404 before this change, and the storefront's addresses screen
 * rendered an apology.
 *
 * <p>What the suite holds shut, in the directions these fail:
 *
 * <p><strong>The owner gets through, holding no capability.</strong> Enforcement
 * is left at its default of on; a suite that switched it off would pass just as
 * happily against the surface that refused everybody.
 *
 * <p><strong>A stranger does not, and cannot tell what they were refused.</strong>
 * Another customer's address, order and cart each answer exactly as one that never
 * existed — same status, same code, same words — because the identifiers are all
 * that stands between guessing an id and confirming it is real. The write paths
 * are checked separately from the read paths: an account predicate present in the
 * {@code SELECT} and absent from the {@code UPDATE} is a surface that refuses to
 * show you somebody's address and lets you overwrite it.
 *
 * <p><strong>The identity mode decides where a profile lives.</strong> Under
 * {@code TENANT_SHARED} one account spans a tenant's brands; under
 * {@code BRAND_ISOLATED} the same person is two accounts with two address books.
 * Both are asserted, because a surface that resolved the account the same way in
 * both modes would look correct against whichever one the fixture happened to use.
 *
 * <p><strong>No personal data leaves by a side door.</strong> The order list
 * carries no line note, the profile carries no contact value, and a refusal about
 * an address does not quote the address.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontCustomerSurfaceTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/qoida";

    /** A tenant whose one account spans both brands. */
    private static final UUID SHARED_TENANT = UUID.randomUUID();

    /** A tenant where the same person is a different account at each brand. */
    private static final UUID ISOLATED_TENANT = UUID.randomUUID();

    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SIBLING_BRAND = UUID.randomUUID();
    private static final UUID ISOLATED_BRAND_A = UUID.randomUUID();
    private static final UUID ISOLATED_BRAND_B = UUID.randomUUID();

    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();

    private static final UUID LEGAL_ENTITY = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();
    private static final UUID CLICK_BINDING = UUID.randomUUID();

    private static final String OWNER = "owner-subject";
    private static final String STRANGER = "stranger-subject";
    private static final String NO_ACCOUNT = "never-signed-up";
    private static final String TWO_BRAND_PERSON = "two-brand-subject";

    /**
     * A line the encryption is meant to keep out of everything else. Distinctive
     * enough that a substring assertion means something.
     */
    private static final String OWNERS_STREET = "Chinobod ko'chasi 12A, kvartira 47";

    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront customer surface test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("qoida.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // (issuer, subject) is the identity, so the seeded principal link has to
        // name the issuer the resolver will ask with.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        // A real key-encryption key, so the ADR 0029 envelope stack genuinely runs
        // over the addresses below. Stubbing FieldProtection would make the
        // tenant and row bindings agree by construction, and the assertion that an
        // address comes back to its own owner would then be about the stub.
        registry.add("qoida.secrets.data_encryption.platform.kek",
                () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RecipientContactDirectory recipients;

    private UUID ownerAccount;
    private UUID strangerAccount;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM ordering.orders WHERE tenant_id = :t").param("t", SHARED_TENANT)
                .update();
        jdbc.sql("DELETE FROM ordering.carts WHERE tenant_id = :t").param("t", SHARED_TENANT)
                .update();
        jdbc.sql("DELETE FROM pricing.quotes WHERE tenant_id = :t").param("t", SHARED_TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.addresses WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();
        jdbc.sql("DELETE FROM customer.contact_points WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();
        jdbc.sql("DELETE FROM customer.brand_profiles WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();
        jdbc.sql("DELETE FROM customer.principal_links WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();
        jdbc.sql("DELETE FROM platform.idempotency_records WHERE tenant_id IN (:a, :b)")
                .param("a", SHARED_TENANT).param("b", ISOLATED_TENANT).update();

        seedEstate();
        ownerAccount = account(SHARED_TENANT, null, OWNER);
        strangerAccount = account(SHARED_TENANT, null, STRANGER);
    }

    // -------------------------------------------------------------- the profile

    @Test
    @DisplayName("a customer reads their own account, which nothing else would tell them")
    void theOwnerReadsTheirOwnProfile() throws Exception {
        MvcResult result = mvc.perform(get(me(SHARED_TENANT, BRAND)).with(token(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.path("accountId").asText())
                .as("the account id is the one fact every account-keyed endpoint needs and "
                        + "no storefront endpoint published")
                .isEqualTo(ownerAccount.toString());
        assertThat(body.path("brandId").asText()).isEqualTo(BRAND.toString());
        assertThat(result.getResponse().getHeader("ETag"))
                .as("the version has to come back, or a client cannot send If-Match")
                .isEqualTo("W/\"1\"");
    }

    @Test
    @DisplayName("a signed-in caller with no account here is not found, not refused")
    void aGuestWithoutAnAccountIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get(me(SHARED_TENANT, BRAND)).with(token(NO_ACCOUNT)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("forbidden would tell somebody probing brand ids that this brand is real")
                .isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("no principal at all never reaches the handler")
    void anAnonymousCallerIsRefusedByTheFilterChain() throws Exception {
        // Ownership is checked inside the handler, so the handler must not be
        // reachable without a principal to own anything. These paths are
        // deliberately absent from SecurityConfiguration's permit list.
        assertThat(statusOf(get(me(SHARED_TENANT, BRAND)))).isEqualTo(401);
        assertThat(statusOf(get(me(SHARED_TENANT, BRAND) + "/addresses"))).isEqualTo(401);
        assertThat(statusOf(get(brand(SHARED_TENANT, BRAND) + "/orders"))).isEqualTo(401);
    }

    @Test
    @DisplayName("under TENANT_SHARED one profile answers at both of a tenant's brands")
    void aSharedProfileIsOneAccountAcrossBrands() throws Exception {
        JsonNode here = json(mvc.perform(get(me(SHARED_TENANT, BRAND)).with(token(OWNER)))
                .andReturn());
        JsonNode sibling = json(mvc.perform(get(me(SHARED_TENANT, SIBLING_BRAND))
                        .with(token(OWNER)))
                .andReturn());

        assertThat(sibling.path("accountId").asText()).isEqualTo(here.path("accountId").asText());
        assertThat(here.path("identityMode").asText()).isEqualTo("TENANT_SHARED");
        assertThat(here.path("profileScope").asText())
                .as("a change made here is visible at the sibling brand, and the response has to "
                        + "say so before somebody renames themselves at both")
                .isEqualTo("TENANT");
    }

    @Test
    @DisplayName("under BRAND_ISOLATED the same person is two accounts with two address books")
    void anIsolatedProfileIsOneAccountPerBrand() throws Exception {
        UUID atA = account(ISOLATED_TENANT, ISOLATED_BRAND_A, TWO_BRAND_PERSON);
        UUID atB = account(ISOLATED_TENANT, ISOLATED_BRAND_B, TWO_BRAND_PERSON);
        assertThat(atA).isNotEqualTo(atB);

        JsonNode a = json(mvc.perform(get(me(ISOLATED_TENANT, ISOLATED_BRAND_A))
                .with(token(TWO_BRAND_PERSON))).andReturn());
        JsonNode b = json(mvc.perform(get(me(ISOLATED_TENANT, ISOLATED_BRAND_B))
                .with(token(TWO_BRAND_PERSON))).andReturn());

        assertThat(a.path("accountId").asText()).isEqualTo(atA.toString());
        assertThat(b.path("accountId").asText())
                .as("resolving the same account at both brands is the cross-brand exposure "
                        + "BRAND_ISOLATED exists to prevent")
                .isEqualTo(atB.toString());
        assertThat(a.path("identityMode").asText()).isEqualTo("BRAND_ISOLATED");
        assertThat(a.path("profileScope").asText()).isEqualTo("BRAND");

        // And the address books are separate, which is the consequence that
        // matters: an address saved at one brand must not appear at the other.
        saveAddress(ISOLATED_TENANT, ISOLATED_BRAND_A, TWO_BRAND_PERSON, "Home", "Brand A street");
        assertThat(addressesOf(ISOLATED_TENANT, ISOLATED_BRAND_A, TWO_BRAND_PERSON)).hasSize(1);
        assertThat(addressesOf(ISOLATED_TENANT, ISOLATED_BRAND_B, TWO_BRAND_PERSON)).isEmpty();
    }

    @Test
    @DisplayName("a profile change requires an Idempotency-Key and an If-Match")
    void aProfileChangeCarriesBothPreconditions() throws Exception {
        MvcResult noKey = mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLocale\":\"uz\"}"))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(noKey)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        MvcResult noMatch = mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                        .header("Idempotency-Key", "profile-no-if-match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLocale\":\"uz\"}"))
                .andReturn();
        assertThat(noMatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(noMatch)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("a customer sets their own language, and the notification path reads it")
    void settingALanguageReachesTheColumnNotificationsRead() throws Exception {
        assertThat(recipients.preferredLocale(SHARED_TENANT, ownerAccount))
                .as("nothing in the platform wrote this column before this endpoint existed, "
                        + "so every message went out in the default language")
                .isEmpty();

        MvcResult result = mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                        .header("Idempotency-Key", "set-my-language")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ozod\",\"preferredLocale\":\"uz\","
                                + "\"preferredTimezone\":\"Asia/Tashkent\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result).path("version").asInt())
                .as("the version moves, or a second edit would be accepted against a stale read")
                .isEqualTo(2);
        assertThat(recipients.preferredLocale(SHARED_TENANT, ownerAccount)).contains("uz");
    }

    @Test
    @DisplayName("a stale If-Match on the profile is a conflict, not a silent overwrite")
    void aStaleProfileVersionIsRefused() throws Exception {
        mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                .header("Idempotency-Key", "first-edit").header("If-Match", "W/\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"First\"}")).andReturn();

        MvcResult second = mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                        .header("Idempotency-Key", "second-edit").header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Second\"}"))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(second)).isEqualTo("STALE_VERSION");
        assertThat(displayNameOf(ownerAccount))
                .as("the loser must not have written")
                .isEqualTo("First");
    }

    @Test
    @DisplayName("a profile write cannot reach a column that decides something")
    void aProfileWriteLeavesTheDecidingColumnsAlone() throws Exception {
        jdbc.sql("UPDATE customer.customer_accounts SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", ownerAccount).update();

        mvc.perform(patch(me(SHARED_TENANT, BRAND)).with(token(OWNER))
                .header("Idempotency-Key", "cannot-unsuspend").header("If-Match", "W/\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Ozod\",\"status\":\"ACTIVE\","
                        + "\"identityPolicyVersion\":99}")).andReturn();

        assertThat(statusOf(ownerAccount))
                .as("the update statement never names status, so no request field can reach it")
                .isEqualTo("SUSPENDED");
        assertThat(displayNameOf(ownerAccount))
                .as("and the fields that are the customer's own still wrote")
                .isEqualTo("Ozod");
    }

    // ------------------------------------------------------------- the addresses

    @Test
    @DisplayName("a customer saves and reads back their own address, holding no capability")
    void theOwnerSavesAndReadsAnAddress() throws Exception {
        MvcResult created = saveAddress(SHARED_TENANT, BRAND, OWNER, "Uy", OWNERS_STREET);

        assertThat(created.getResponse().getStatus())
                .as("CUSTOMER_MANAGE is an agent's capability; the person whose address this is "
                        + "held nothing and was refused")
                .isEqualTo(201);

        JsonNode listed = addressesOf(SHARED_TENANT, BRAND, OWNER).get(0);
        assertThat(listed.path("fields").path("line1").asText())
                .as("a reveal to the person the address belongs to is the one legitimate one, "
                        + "and it used to cost CUSTOMER_PII_REVEAL")
                .isEqualTo(OWNERS_STREET);
        assertThat(listed.path("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("another customer's address and one that never existed answer identically")
    void aStrangersAddressIsIndistinguishableFromAnAbsentOne() throws Exception {
        UUID strangers = addressId(saveAddress(SHARED_TENANT, BRAND, STRANGER, "Uy", "Their street"));

        MvcResult notMine = mvc.perform(get(me(SHARED_TENANT, BRAND) + "/addresses/" + strangers)
                .with(token(OWNER))).andReturn();
        MvcResult neverExisted = mvc.perform(
                get(me(SHARED_TENANT, BRAND) + "/addresses/" + UUID.randomUUID())
                        .with(token(OWNER))).andReturn();

        assertThat(notMine.getResponse().getStatus()).isEqualTo(404);
        assertThat(distinguishing(notMine))
                .as("a different status, code or wording would confirm the id is real. The two "
                        + "problem documents are compared whole, minus the correlation id and the "
                        + "instance path — one is per-request and the other necessarily echoes "
                        + "the id the caller already sent")
                .isEqualTo(distinguishing(neverExisted));
        assertThat(notMine.getResponse().getContentAsString())
                .as("and the refusal must not quote what it refused to show")
                .doesNotContain("Their street");
    }

    @Test
    @DisplayName("a stranger's address cannot be overwritten even with its real version")
    void theAccountPredicateIsInTheWriteAndNotOnlyInTheRead() throws Exception {
        UUID strangers = addressId(saveAddress(SHARED_TENANT, BRAND, STRANGER, "Uy", "Their street"));
        String before = ciphertextOf(strangers);

        MvcResult overwrite = mvc.perform(
                        put(me(SHARED_TENANT, BRAND) + "/addresses/" + strangers)
                                .with(token(OWNER))
                                .header("Idempotency-Key", "steal-an-address")
                                .header("If-Match", "W/\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addressBody("Uy", "My street", "LANDMARK_ONLY")))
                .andReturn();

        assertThat(overwrite.getResponse().getStatus()).isEqualTo(404);
        assertThat(ciphertextOf(strangers))
                .as("an account predicate present in the SELECT and missing from the UPDATE is a "
                        + "surface that will not show you an address and will let you rewrite it")
                .isEqualTo(before);

        MvcResult archive = mvc.perform(
                        delete(me(SHARED_TENANT, BRAND) + "/addresses/" + strangers)
                                .with(token(OWNER))
                                .header("Idempotency-Key", "archive-an-address")
                                .header("If-Match", "W/\"1\""))
                .andReturn();

        assertThat(archive.getResponse().getStatus()).isEqualTo(404);
        assertThat(rowStatusOf(strangers)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("removing an address archives the row and drops it from the list")
    void removingAnAddressArchivesIt() throws Exception {
        UUID mine = addressId(saveAddress(SHARED_TENANT, BRAND, OWNER, "Uy", OWNERS_STREET));

        int status = statusOf(delete(me(SHARED_TENANT, BRAND) + "/addresses/" + mine)
                .with(token(OWNER))
                .header("Idempotency-Key", "remove-my-address")
                .header("If-Match", "W/\"1\""));

        assertThat(status).isEqualTo(204);
        assertThat(rowStatusOf(mine))
                .as("the application role holds no DELETE on this table, and the row is what a "
                        + "dispute about where an order went is answered from")
                .isEqualTo("ARCHIVED");
        assertThat(addressesOf(SHARED_TENANT, BRAND, OWNER)).isEmpty();
        assertThat(statusOf(get(me(SHARED_TENANT, BRAND) + "/addresses/" + mine).with(token(OWNER))))
                .as("an archived address is gone to the customer, not merely hidden from a list")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a stale version is refused on an edit and on a removal")
    void aStaleAddressVersionIsRefused() throws Exception {
        UUID mine = addressId(saveAddress(SHARED_TENANT, BRAND, OWNER, "Uy", OWNERS_STREET));
        mvc.perform(put(me(SHARED_TENANT, BRAND) + "/addresses/" + mine).with(token(OWNER))
                .header("Idempotency-Key", "edit-one").header("If-Match", "W/\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressBody("Ish", "Amir Temur 1", "LANDMARK_ONLY"))).andReturn();

        MvcResult stale = mvc.perform(delete(me(SHARED_TENANT, BRAND) + "/addresses/" + mine)
                        .with(token(OWNER))
                        .header("Idempotency-Key", "remove-stale").header("If-Match", "W/\"1\""))
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(stale)).isEqualTo("STALE_VERSION");
        assertThat(stale.getResponse().getContentAsString())
                .as("a version conflict must not carry the address it is about")
                .doesNotContain("Amir Temur");
        assertThat(rowStatusOf(mine)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an edit replaces the whole document rather than merging into it")
    void anEditReplacesTheWholeDocument() throws Exception {
        UUID mine = addressId(saveAddress(SHARED_TENANT, BRAND, OWNER, "Uy", OWNERS_STREET));

        mvc.perform(put(me(SHARED_TENANT, BRAND) + "/addresses/" + mine).with(token(OWNER))
                .header("Idempotency-Key", "replace-the-lot").header("If-Match", "W/\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressBody("Ish", "Amir Temur 1", "LANDMARK_ONLY"))).andReturn();

        JsonNode after = addressesOf(SHARED_TENANT, BRAND, OWNER).get(0);
        assertThat(after.path("fields").path("line1").asText()).isEqualTo("Amir Temur 1");
        assertThat(after.path("label").asText()).isEqualTo("Ish");
        assertThat(after.path("version").asInt()).isEqualTo(2);
        assertThat(after.path("addressId").asText())
                .as("the row id must survive the edit: ADR 0029 binds the ciphertext to it, and a "
                        + "new row would leave every cart that copied the old one pointing at a "
                        + "different address")
                .isEqualTo(mine.toString());
    }

    @Test
    @DisplayName("a customer cannot claim a coordinate source that records who produced a point")
    void aCustomerCannotClaimAGeocodedSource() throws Exception {
        MvcResult result = mvc.perform(post(me(SHARED_TENANT, BRAND) + "/addresses")
                        .with(token(OWNER))
                        .header("Idempotency-Key", "claim-a-geocode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Uy","fields":{"line1":"Somewhere"},
                                 "latitude":41.3,"longitude":69.2,"coordinateSource":"GEOCODER"}
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("VALIDATION_FAILED");
        assertThat(addressesOf(SHARED_TENANT, BRAND, OWNER)).isEmpty();
    }

    @Test
    @DisplayName("the staff address endpoints still require a capability")
    void theOperatorAddressSurfaceIsUnchanged() throws Exception {
        // The same token that now sails through /me. An agent editing an address
        // on the telephone is exactly what a capability is for, and giving the
        // customer their own surface must not have weakened that one.
        MvcResult reveal = mvc.perform(get("/api/v1/tenants/" + SHARED_TENANT + "/customers/"
                        + ownerAccount + "/addresses?purpose=curiosity").with(token(OWNER)))
                .andReturn();

        assertThat(reveal.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(reveal)).isEqualTo("INSUFFICIENT_CAPABILITY");
    }

    // ---------------------------------------------------------------- the orders

    @Test
    @DisplayName("the list carries the caller's own orders and nobody else's")
    void theOrderListIsScopedToTheCallersAccount() throws Exception {
        UUID first = order(ownerAccount, "A-1", "2026-08-01T10:00:00Z");
        UUID second = order(ownerAccount, "A-2", "2026-08-02T10:00:00Z");
        order(strangerAccount, "A-3", "2026-08-03T10:00:00Z");

        JsonNode page = json(mvc.perform(get(brand(SHARED_TENANT, BRAND) + "/orders")
                .with(token(OWNER))).andReturn());

        assertThat(idsIn(page))
                .as("newest first, and a stranger's order is not an order of mine")
                .containsExactly(second.toString(), first.toString());
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("the page is a keyset and does not skip an order placed in the same microsecond")
    void thePageDoesNotSkipATiedInstant() throws Exception {
        // Two orders at one instant is ordinary at a busy branch, and a keyset on
        // created_at alone silently drops one of them on the page boundary.
        UUID tiedA = order(ownerAccount, "B-1", "2026-08-04T10:00:00Z");
        UUID tiedB = order(ownerAccount, "B-2", "2026-08-04T10:00:00Z");
        UUID older = order(ownerAccount, "B-3", "2026-08-03T10:00:00Z");

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            String query = brand(SHARED_TENANT, BRAND) + "/orders?limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = json(mvc.perform(get(query).with(token(OWNER))).andReturn());
            walked.addAll(idsIn(body));
            if (body.path("nextCursor").isNull()) {
                break;
            }
            cursor = body.path("nextCursor").asText();
        }

        assertThat(walked)
                .as("every order exactly once: a non-unique sort key loses one of a tied pair")
                .containsExactlyInAnyOrder(tiedA.toString(), tiedB.toString(), older.toString());
        assertThat(walked).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a cursor naming another customer's order is refused and returns nothing")
    void aCursorCannotWalkIntoAnotherCustomersHistory() throws Exception {
        order(ownerAccount, "C-1", "2026-08-01T10:00:00Z");
        UUID theirs = order(strangerAccount, "C-2", "2026-08-05T10:00:00Z");

        MvcResult result = mvc.perform(get(brand(SHARED_TENANT, BRAND) + "/orders?cursor=" + theirs)
                .with(token(OWNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("INVALID_REQUEST");
        assertThat(result.getResponse().getContentAsString())
                .as("and the refusal carries nothing of the order it would not continue from")
                .doesNotContain("C-2");
    }

    @Test
    @DisplayName("the list carries no line, no note, and no destination")
    void theListCarriesOnlyWhatAListNeeds() throws Exception {
        order(ownerAccount, "D-1", "2026-08-01T10:00:00Z");

        String body = mvc.perform(get(brand(SHARED_TENANT, BRAND) + "/orders").with(token(OWNER)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("D-1");
        assertThat(body)
                .as("a list endpoint that returns the whole aggregate publishes fields nobody "
                        + "meant to — a line note is ADR 0029 personal data with its own reveal")
                .doesNotContain("\"lines\"")
                .doesNotContain("hasCustomerNote")
                .doesNotContain("acceptancePolicyId")
                .doesNotContain("idempotencyKey");
    }

    // -------------------------------------------------------- the payment methods

    @Test
    @DisplayName("a cart is offered only methods that could actually take the money")
    void onlyOfferableMethodsAreListed() throws Exception {
        UUID cart = cart(ownerAccount);
        channelPaymentMethod("CASH", true);
        channelPaymentMethod("CLICK", true);
        channelPaymentMethod("PAYME", true);
        channelPaymentMethod("TELEGRAM", false);
        clickBinding("ACTIVE");

        JsonNode body = json(mvc.perform(
                get(brand(SHARED_TENANT, BRAND) + "/carts/" + cart + "/payment-methods")
                        .with(token(OWNER))).andReturn());

        assertThat(codes(body))
                .as("CASH needs no merchant account; CLICK has one; PAYME is configured and has "
                        + "none, and offering it would be a checkout that fails at its last step; "
                        + "TELEGRAM is switched off by the operator")
                .containsExactly("CASH", "CLICK");
        assertThat(body.path("fulfillmentMode").asText()).isEqualTo("DELIVERY");
    }

    @Test
    @DisplayName("a suspended provider binding stops the method being offered")
    void aSuspendedBindingRemovesTheMethod() throws Exception {
        UUID cart = cart(ownerAccount);
        channelPaymentMethod("CASH", true);
        channelPaymentMethod("CLICK", true);
        clickBinding("ACTIVE");

        assertThat(codes(json(mvc.perform(
                get(brand(SHARED_TENANT, BRAND) + "/carts/" + cart + "/payment-methods")
                        .with(token(OWNER))).andReturn())))
                .as("a fixture that never showed the method available would pass however the "
                        + "filter was written")
                .contains("CLICK");

        jdbc.sql("UPDATE payments.merchant_bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", CLICK_BINDING).update();

        assertThat(codes(json(mvc.perform(
                get(brand(SHARED_TENANT, BRAND) + "/carts/" + cart + "/payment-methods")
                        .with(token(OWNER))).andReturn())))
                .as("the merchant account is gone, so the method is gone — not listed as "
                        + "unavailable for a client to remember to filter")
                .containsExactly("CASH");
    }

    @Test
    @DisplayName("a channel with no matrix offers nothing rather than everything")
    void anUnconfiguredChannelOffersNothing() throws Exception {
        UUID cart = cart(ownerAccount);

        JsonNode body = json(mvc.perform(
                get(brand(SHARED_TENANT, BRAND) + "/carts/" + cart + "/payment-methods")
                        .with(token(OWNER))).andReturn());

        assertThat(codes(body))
                .as("V0020 makes an absent row mean unavailable; defaulting to every code would "
                        + "sell on a channel the operator never configured")
                .isEmpty();
    }

    @Test
    @DisplayName("another customer's cart has no payment methods and no confirmation it exists")
    void aStrangersCartIsNotFound() throws Exception {
        UUID theirs = cart(strangerAccount);
        channelPaymentMethod("CASH", true);

        MvcResult result = mvc.perform(
                get(brand(SHARED_TENANT, BRAND) + "/carts/" + theirs + "/payment-methods")
                        .with(token(OWNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------- fixture

    private void seedEstate() {
        tenant(SHARED_TENANT, "shared-tenant", "TENANT_SHARED");
        tenant(ISOLATED_TENANT, "isolated-tenant", "BRAND_ISOLATED");
        brandRow(SHARED_TENANT, BRAND, "MAIN");
        brandRow(SHARED_TENANT, SIBLING_BRAND, "SIBLING");
        brandRow(ISOLATED_TENANT, ISOLATED_BRAND_A, "AAA");
        brandRow(ISOLATED_TENANT, ISOLATED_BRAND_B, "BBB");

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", LOCATION).param("tenantId", SHARED_TENANT).param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE', false)
                ON CONFLICT (id) DO NOTHING
                """).param("id", CHANNEL).param("tenantId", SHARED_TENANT).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """).param("id", CATALOG).param("tenantId", SHARED_TENANT).param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", PUBLICATION).param("tenantId", SHARED_TENANT)
                .param("brandId", BRAND).param("catalogId", CATALOG).update();

        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, 'LE1', 'Seller MCHJ', '123456789', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """).param("id", LEGAL_ENTITY).param("tenantId", SHARED_TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('customer-surface-click', 'PAYMENT', 'CLICK',
                    'https://api.click.uz/v2/merchant', false, 'api.click.uz')
                ON CONFLICT (code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'customer-surface-click', 'Click',
                    'ACTIVE', 'qoida:test:provider_payment:tenant:click')
                ON CONFLICT (id) DO NOTHING
                """).param("id", INSTALLATION).param("tenantId", SHARED_TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """).param("id", INTEGRATION_BINDING).param("tenantId", SHARED_TENANT)
                .param("installationId", INSTALLATION).param("brandId", BRAND).update();

        jdbc.sql("DELETE FROM payments.merchant_bindings WHERE tenant_id = :t")
                .param("t", SHARED_TENANT).update();
        jdbc.sql("DELETE FROM tenant.channel_payment_methods WHERE tenant_id = :t")
                .param("t", SHARED_TENANT).update();
    }

    private void tenant(UUID id, String slug, String identityMode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", id).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :tenantId, 1, :mode, TIMESTAMPTZ '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """).param("id", UUID.nameUUIDFromBytes(id.toString().getBytes()))
                .param("tenantId", id).param("mode", identityMode).update();
    }

    private void brandRow(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", brandId).param("tenantId", tenantId).param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT)).update();
    }

    /**
     * An account and its principal link.
     *
     * <p>{@code partitionBrandId} is what makes a BRAND_ISOLATED tenant produce two
     * accounts for one subject: the partition is on the row and on the link, and
     * resolution matches them with {@code IS NOT DISTINCT FROM}.
     */
    private UUID account(UUID tenantId, UUID partitionBrandId, String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id,
                    identity_partition_brand_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, :partition, 'ACTIVE', :now, :now)
                """)
                .param("id", accountId).param("tenantId", tenantId)
                .param("partition", partitionBrandId).param("now", now).update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id,
                    identity_partition_brand_id, customer_account_id, issuer, subject, status,
                    linked_at)
                VALUES (:id, :tenantId, :partition, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("partition", partitionBrandId).param("accountId", accountId)
                .param("issuer", ISSUER).param("subject", subject).param("now", now).update();
        return accountId;
    }

    private UUID cart(UUID accountId) {
        UUID cartId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, version,
                    expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :accountId,
                    'DELIVERY', 'UZS', 'ACTIVE', 1, :expiresAt, :now, :now)
                """)
                .param("id", cartId).param("tenantId", SHARED_TENANT).param("brandId", BRAND)
                .param("locationId", LOCATION).param("channelId", CHANNEL)
                .param("accountId", accountId).param("expiresAt", now.plusHours(4))
                .param("now", now).update();
        return cartId;
    }

    /** One order, at a stated instant, for the keyset assertions. */
    private UUID order(UUID accountId, String number, String createdAt) {
        UUID orderId = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = cart(accountId);

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId,
                    1, 'hash', 12000, 0, 12000, now() + interval '1 day')
                """)
                .param("id", quote).param("tenantId", SHARED_TENANT).param("brandId", BRAND)
                .param("locationId", LOCATION).param("publicationId", PUBLICATION).update();

        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, customer_account_id, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    created_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :accountId, 'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'RECEIVED', 'UZS',
                    12000, 0, 12000, :quoteId, 'hash', :publicationId, :cartId, :key,
                    CAST(:createdAt AS timestamptz))
                """)
                .param("id", orderId).param("number", number).param("tenantId", SHARED_TENANT)
                .param("brandId", BRAND).param("locationId", LOCATION).param("channelId", CHANNEL)
                .param("accountId", accountId).param("quoteId", quote)
                .param("publicationId", PUBLICATION).param("cartId", cart)
                .param("key", UUID.randomUUID().toString()).param("createdAt", createdAt)
                .update();
        return orderId;
    }

    private void channelPaymentMethod(String code, boolean enabled) {
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (
                    tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, :code, :enabled)
                ON CONFLICT (channel_id, payment_method_code)
                DO UPDATE SET enabled = EXCLUDED.enabled
                """)
                .param("tenantId", SHARED_TENANT).param("channelId", CHANNEL)
                .param("code", code).param("enabled", enabled).update();
    }

    private void clickBinding(String status) {
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, secret_reference, callback_path_segment,
                    supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    '12345', '4444', 'qoida:test:provider_payment:tenant:click', :segment,
                    true, true, :status, :effectiveFrom)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """)
                .param("id", CLICK_BINDING).param("tenantId", SHARED_TENANT)
                .param("legalEntityId", LEGAL_ENTITY).param("installationId", INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .param("segment", "click-" + CLICK_BINDING.toString().substring(0, 8))
                .param("status", status).param("effectiveFrom", LocalDate.of(2020, 1, 1))
                .update();
    }

    // -------------------------------------------------------------------- helpers

    private MvcResult saveAddress(UUID tenantId, UUID brandId, String subject, String label,
            String line1) throws Exception {
        return mvc.perform(post(me(tenantId, brandId) + "/addresses").with(token(subject))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody(label, line1, "LANDMARK_ONLY")))
                .andReturn();
    }

    private static String addressBody(String label, String line1, String source) {
        return """
                {"label":"%s","fields":{"line1":"%s","city":"Toshkent"},
                 "deliveryInstructions":"Ring the top bell","coordinateSource":"%s"}
                """.formatted(label, line1, source);
    }

    private List<JsonNode> addressesOf(UUID tenantId, UUID brandId, String subject)
            throws Exception {
        JsonNode body = json(mvc.perform(get(me(tenantId, brandId) + "/addresses")
                .with(token(subject))).andReturn());
        List<JsonNode> items = new ArrayList<>();
        body.forEach(items::add);
        return items;
    }

    private static UUID addressId(MvcResult created) throws Exception {
        return UUID.fromString(json(created).path("addressId").asText());
    }

    private static List<String> idsIn(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("orderId").asText()));
        return ids;
    }

    private static List<String> codes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.path("methodCodes").forEach(code -> codes.add(code.asText()));
        return codes;
    }

    private String ciphertextOf(UUID addressId) {
        return jdbc.sql("SELECT encrypted_fields FROM customer.addresses WHERE id = :id")
                .param("id", addressId).query(String.class).single();
    }

    private String rowStatusOf(UUID addressId) {
        return jdbc.sql("SELECT status FROM customer.addresses WHERE id = :id")
                .param("id", addressId).query(String.class).single();
    }

    private String statusOf(UUID accountId) {
        return jdbc.sql("SELECT status FROM customer.customer_accounts WHERE id = :id")
                .param("id", accountId).query(String.class).single();
    }

    private String displayNameOf(UUID accountId) {
        return jdbc.sql("SELECT display_name FROM customer.customer_accounts WHERE id = :id")
                .param("id", accountId).query(String.class).single();
    }

    private static String brand(UUID tenantId, UUID brandId) {
        return "/api/v1/storefront/tenants/" + tenantId + "/brands/" + brandId;
    }

    private static String me(UUID tenantId, UUID brandId) {
        return brand(tenantId, brandId) + "/me";
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String codeOf(MvcResult result) throws Exception {
        return json(result).path("code").asText();
    }

    /**
     * A problem document reduced to everything that could tell two refusals apart.
     *
     * <p>{@code correlationId} is per-request and {@code instance} is the path the
     * caller themselves sent, so neither can carry a fact the caller did not
     * already have. Everything else — status, code, title, type, detail, and any
     * property an endpoint attaches — has to match, or the difference is a signal.
     */
    private static String distinguishing(MvcResult result) throws Exception {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json(result);
        document.remove("correlationId");
        document.remove("instance");
        return document.toString();
    }

    /**
     * A legal entity for the branch, and a stub only because ADR 0038 has not
     * shipped one.
     *
     * <p>{@code PaymentLegalEntityConfiguration} supplies a resolver that answers
     * "no seller is known", which makes every provider method unavailable
     * everywhere. That is the correct production answer today and it would make the
     * payment-method assertions vacuous: a list that never contains CLICK proves
     * nothing about a filter. This resolver names the seeded entity, so the
     * merchant binding below is genuinely resolved and genuinely lost when it is
     * suspended.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SeededSeller {

        @Bean
        @Primary
        PaymentLegalEntityResolver legalEntityResolver() {
            return (tenantId, locationId, businessDate) ->
                    SHARED_TENANT.equals(tenantId) && LOCATION.equals(locationId)
                            ? Optional.of(LEGAL_ENTITY)
                            : Optional.empty();
        }

        /** Avoids contacting a real issuer; this suite exercises the MVC chain. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none")
                    .claim("sub", "unused").build();
        }
    }
}
