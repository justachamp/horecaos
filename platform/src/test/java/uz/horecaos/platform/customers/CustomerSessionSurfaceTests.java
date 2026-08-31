package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.domain.CustomerSessionToken;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Signing in, and staying signed in (ADR 0051, ADR 0015, ADR 0031).
 *
 * <p>This is the suite that would have failed before the change and did not exist
 * to. Every assertion below is about a request a customer's own browser makes with
 * the credential the platform gave it — no {@code jwt()} post-processor, no
 * pre-seeded security context, and no principal that a storefront could not
 * actually obtain. Two of them fail on the old code before any of the new code is
 * reached at all: the request for a one-time code was answered 401 by the filter
 * chain, and there was no endpoint that turned a grant into anything.
 *
 * <p>What the suite holds shut, in the directions these fail:
 *
 * <p><strong>The token is a key, not a claim.</strong> The row it finds carries
 * the tenant, the account and the partition; the token carries nothing. So the
 * assertions about reaching a sibling brand are assertions about a database
 * comparison and not about a client's honesty.
 *
 * <p><strong>Ending a session is asserted by advancing a clock, not by asking
 * nicely.</strong> An expiry checked without moving time is an assertion about an
 * instant. Here the row's {@code expires_at} is moved into the past and the same
 * token is presented again — which is what the passage of time does to it.
 *
 * <p><strong>An ended session and an invented one answer differently.</strong> A
 * customer whose token expired mid-basket must not be shown what a stranger sees.
 * Both are 401, so a suite that asserted only the status would pass against the
 * behaviour this distinction exists to prevent.
 *
 * <p><strong>The preset code cannot hide a broken SMS path.</strong> Any other
 * number on this profile still goes down the real route to the real adapter and
 * still comes back {@code NO_PROVIDER_BINDING}, because no gateway is installed
 * for this tenant. If the preset ever widened past its one configured number,
 * that is the test that goes red.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerSessionSurfaceTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    /**
     * The preset number, and it addresses nobody.
     *
     * <p>Uzbek mobile operator codes are two digits in the 33 and 88–99 ranges, so
     * {@code 00} is allocated to no operator. It satisfies {@code PhoneNumber}'s
     * {@code +998}-and-nine-digits rule and reaches no subscriber, which is the
     * only combination that is safe to write into a file.
     */
    private static final String PRESET_PHONE = "+998000000000";

    private static final String PRESET_CODE = "000000";

    /** An ordinary number. It has no preset, so it needs a gateway, and there is none here. */
    private static final String ORDINARY_PHONE = "+998901112233";

    private static final UUID SHARED_TENANT = UUID.randomUUID();
    private static final UUID ISOLATED_TENANT = UUID.randomUUID();

    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SIBLING_BRAND = UUID.randomUUID();
    private static final UUID ISOLATED_BRAND_A = UUID.randomUUID();
    private static final UUID ISOLATED_BRAND_B = UUID.randomUUID();

    private static final String STAFF_SUBJECT = "staff-subject";

    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the customer session test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        // A real key-encryption key, so the ADR 0029 envelope and the keyed code
        // MAC genuinely run. Stubbing them would make the number's lookup hash —
        // which is the subject a returning customer resolves through — agree with
        // itself by construction.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        // The preset, set the way a local profile sets it. The test profile is in
        // the local set, which is exactly the binding under test: this must work
        // here and must refuse to start anywhere else, and
        // PresetVerificationCodeTests asserts the second half.
        registry.add("horecaos.customers.verification.preset.phone", () -> PRESET_PHONE);
        registry.add("horecaos.customers.verification.preset.code", () -> PRESET_CODE);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        for (UUID tenant : new UUID[] {SHARED_TENANT, ISOLATED_TENANT}) {
            jdbc.sql("DELETE FROM customer.customer_sessions WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM customer.verification_challenges WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM customer.contact_points WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM customer.brand_profiles WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM customer.principal_links WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
            jdbc.sql("DELETE FROM platform.idempotency_records WHERE tenant_id = :t")
                    .param("t", tenant)
                    .update();
        }

        tenant(SHARED_TENANT, "session-shared", "TENANT_SHARED");
        tenant(ISOLATED_TENANT, "session-isolated", "BRAND_ISOLATED");
        brandRow(SHARED_TENANT, BRAND, "SMAIN");
        brandRow(SHARED_TENANT, SIBLING_BRAND, "SSIB");
        brandRow(ISOLATED_TENANT, ISOLATED_BRAND_A, "IAAA");
        brandRow(ISOLATED_TENANT, ISOLATED_BRAND_B, "IBBB");
    }

    // ------------------------------------------------------- the whole journey

    @Test
    @DisplayName("the preset number signs in, sends no SMS, and reaches its own account")
    void theOwnerSignsInAndStaysSignedIn() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        assertThat(signedIn.created())
                .as("the first sign-in brings the account into existence")
                .isTrue();
        assertThat(signedIn.token())
                .as("the storefront needs something it can put in an Authorization header")
                .startsWith(CustomerSessionToken.PREFIX);

        MvcResult me = mvc.perform(get(me(SHARED_TENANT, BRAND)).with(session(signedIn.token())))
                .andReturn();

        assertThat(me.getResponse().getStatus())
                .as("this is the request the owner cannot make today: a customer's own "
                        + "credential reaching a customer's own surface")
                .isEqualTo(200);
        assertThat(json(me).path("accountId").asText()).isEqualTo(signedIn.accountId());
    }

    @Test
    @DisplayName("asking for a code needs no token, which the filter chain never allowed")
    void theCodeRequestIsReachableWithoutAPrincipal() throws Exception {
        // Before this change the three pre-account identity paths were missing
        // from SecurityConfiguration's permit list entirely, so this answered 401
        // long before the handler written for an anonymous caller could run. The
        // controller's javadoc said they were unauthenticated; nothing agreed.
        int status = mvc.perform(post(identity(SHARED_TENANT, BRAND) + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PRESET_PHONE + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(202);
    }

    @Test
    @DisplayName("signing in twice with the same number reaches the same account")
    void aReturningCustomerIsTheSamePerson() throws Exception {
        SignedIn first = signIn(SHARED_TENANT, BRAND);
        SignedIn second = signIn(SHARED_TENANT, BRAND);

        assertThat(second.accountId())
                .as("a second sign-in that created a second account would give a returning "
                        + "customer an empty order history and a stranger's basket")
                .isEqualTo(first.accountId());
        assertThat(second.created()).isFalse();
        assertThat(second.token())
                .as("each sign-in is its own session, so signing out of one leaves the other")
                .isNotEqualTo(first.token());
    }

    @Test
    @DisplayName("the account resolves through the proven-number issuer, never through the realm")
    void theLinkNamesThePlatformIssuer() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        String issuer = jdbc.sql("""
                SELECT issuer FROM customer.principal_links
                WHERE tenant_id = :t AND customer_account_id = :a AND status = 'ACTIVE'
                """)
                .param("t", SHARED_TENANT)
                .param("a", UUID.fromString(signedIn.accountId()))
                .query(String.class)
                .single();

        assertThat(issuer)
                .as("two issuers, two subject namespaces. A phone-derived subject stored "
                        + "under the realm's issuer could collide with a Keycloak subject")
                .isEqualTo(CustomerIdentityService.PROVEN_NUMBER_ISSUER)
                .isNotEqualTo(ISSUER);
    }

    // ---------------------------------------------------------- what is stored

    @Test
    @DisplayName("the session token is not recoverable from the row that authenticates it")
    void theTokenIsNotStored() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        String storedHash = jdbc.sql("SELECT token_hash FROM customer.customer_sessions WHERE tenant_id = :t")
                .param("t", SHARED_TENANT)
                .query(String.class)
                .single();

        assertThat(storedHash)
                .isNotEqualTo(signedIn.token())
                .doesNotContain(signedIn.token())
                .matches("[0-9a-f]{64}");
        assertThat(CustomerSessionToken.hash(signedIn.token()))
                .as("and the digest is the one the resolver will compute")
                .isEqualTo(storedHash);
    }

    @Test
    @DisplayName("the session row carries no phone number and nothing derived from one")
    void theSessionRowHoldsNoPersonalData() throws Exception {
        signIn(SHARED_TENANT, BRAND);

        String columns = jdbc.sql("""
                SELECT string_agg(column_name, ',') FROM information_schema.columns
                WHERE table_schema = 'customer' AND table_name = 'customer_sessions'
                """).query(String.class).single();

        // ADR 0029. The number that proved this session lives on an encrypted
        // contact point and on a challenge row that is purged; a copy here — even
        // a hash — would be a per-customer correlation key in a table with no
        // question to answer with it.
        assertThat(columns)
                .doesNotContain("phone")
                .doesNotContain("destination")
                .doesNotContain("contact");
    }

    // ------------------------------------------------------------ ending a session

    @Test
    @DisplayName("an expired token says the session ended, not that the caller is a stranger")
    void anExpiredSessionIsDistinguishable() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        // The clock is moved by moving the row, which is what the passage of time
        // does to it. Asserting an expiry without advancing anything asserts an
        // instant.
        jdbc.sql("""
                UPDATE customer.customer_sessions
                SET issued_at = now() - interval '40 days', expires_at = now() - interval '10 days'
                WHERE token_hash = :hash
                """).param("hash", CustomerSessionToken.hash(signedIn.token())).update();

        MvcResult refused = mvc.perform(get(me(SHARED_TENANT, BRAND)).with(session(signedIn.token())))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(codeOf(refused))
                .as("a customer whose session ended mid-basket must not be shown what "
                        + "somebody who never signed in is shown")
                .isEqualTo("SESSION_EXPIRED");
    }

    @Test
    @DisplayName("a token this platform never issued is not an expired session")
    void anInventedTokenIsUnauthenticated() throws Exception {
        MvcResult refused = mvc.perform(get(me(SHARED_TENANT, BRAND))
                        .with(session(CustomerSessionToken.PREFIX + "notatokenwewouldevermint")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(codeOf(refused))
                .as("both are 401, so a test that checked only the status would pass "
                        + "against the behaviour the distinction exists to prevent")
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("signing out ends that session and only that session")
    void signingOutEndsTheSession() throws Exception {
        SignedIn first = signIn(SHARED_TENANT, BRAND);
        SignedIn second = signIn(SHARED_TENANT, BRAND);

        int signedOut = mvc.perform(delete(identity(SHARED_TENANT, BRAND) + "/sessions/current")
                        .with(session(first.token()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(signedOut).isEqualTo(204);

        MvcResult afterwards = mvc.perform(get(me(SHARED_TENANT, BRAND)).with(session(first.token())))
                .andReturn();
        assertThat(afterwards.getResponse().getStatus()).isEqualTo(401);
        assertThat(codeOf(afterwards)).isEqualTo("SESSION_EXPIRED");

        assertThat(mvc.perform(get(me(SHARED_TENANT, BRAND)).with(session(second.token())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("signing out on one handset must not sign the customer out everywhere")
                .isEqualTo(200);
    }

    // --------------------------------------------------------- the identity mode

    @Test
    @DisplayName("under TENANT_SHARED one session reaches both of a tenant's brands")
    void aSharedSessionSpansBrands() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        MvcResult sibling = mvc.perform(get(me(SHARED_TENANT, SIBLING_BRAND)).with(session(signedIn.token())))
                .andReturn();

        assertThat(sibling.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(sibling).path("accountId").asText())
                .as("a shared account is one person across the tenant, which is what the " + "mode means")
                .isEqualTo(signedIn.accountId());
    }

    @Test
    @DisplayName("under BRAND_ISOLATED a session reaches only the brand it was minted at")
    void anIsolatedSessionIsOneBrand() throws Exception {
        SignedIn atA = signIn(ISOLATED_TENANT, ISOLATED_BRAND_A);

        MvcResult atB = mvc.perform(get(me(ISOLATED_TENANT, ISOLATED_BRAND_B)).with(session(atA.token())))
                .andReturn();

        assertThat(atB.getResponse().getStatus())
                .as("these are separate businesses holding separate accounts for the same "
                        + "person; a session that spanned them is the cross-brand exposure "
                        + "the mode exists to prevent")
                .isEqualTo(404);

        SignedIn atBOwn = signIn(ISOLATED_TENANT, ISOLATED_BRAND_B);
        assertThat(atBOwn.accountId())
                .as("and proving the same number at the sibling brand is a different account")
                .isNotEqualTo(atA.accountId());
        assertThat(atBOwn.created()).isTrue();
    }

    // ------------------------------------------------- what the preset does not do

    @Test
    @DisplayName("any other number still needs a gateway, so the preset hides nothing")
    void anOrdinaryNumberStillReachesTheTransport() throws Exception {
        // An ordinary number takes the real path: the ADR 0007 route, the VAS
        // adapter, and the ADR 0026 lookup for this tenant's SMS binding. There is
        // no installation here, so it comes back NO_PROVIDER_BINDING — which is
        // the point twice over. It proves the preset did not widen to cover
        // everybody, and it proves an unconfigured deployment says exactly what is
        // missing rather than silently swallowing the code. If the preset ever
        // matched more than its one configured number, this is the test that goes
        // red.
        MvcResult result = mvc.perform(post(identity(SHARED_TENANT, BRAND) + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + ORDINARY_PHONE + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(json(result).path("reason").asText())
                .as("the reason names what an operator has to fix — an ADR 0026 "
                        + "installation and binding for this tenant — and never the "
                        + "number or the code")
                .isEqualTo("NO_PROVIDER_BINDING");
        assertThat(result.getResponse().getContentAsString())
                .as("ADR 0029: a refusal never quotes the number it was about")
                .doesNotContain(ORDINARY_PHONE)
                .doesNotContain("901112233");

        assertThat(jdbc.sql("SELECT count(*) FROM customer.verification_challenges " + "WHERE tenant_id = :t")
                        .param("t", SHARED_TENANT)
                        .query(Integer.class)
                        .single())
                .as("a challenge whose code never left is withdrawn, so it charges the "
                        + "customer's budget for our outage")
                .isZero();
    }

    // ------------------------------------------------------- the other principal

    @Test
    @DisplayName("a staff token behaves exactly as it did")
    void theRealmTokenPathIsUntouched() throws Exception {
        UUID staffAccount = seedRealmAccount(STAFF_SUBJECT);

        MvcResult me = mvc.perform(get(me(SHARED_TENANT, BRAND))
                        .with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(STAFF_SUBJECT))))
                .andReturn();

        assertThat(me.getResponse().getStatus())
                .as("the customer filter must be invisible to a request that carries no " + "customer token")
                .isEqualTo(200);
        assertThat(json(me).path("accountId").asText()).isEqualTo(staffAccount.toString());
    }

    @Test
    @DisplayName("a customer session confers no staff authority")
    void aSessionIsNotAStaffToken() throws Exception {
        SignedIn signedIn = signIn(SHARED_TENANT, BRAND);

        // ADR 0049: a customer is authorized by owning a row, never by holding a
        // capability. The authentication carries no authorities at all, so an
        // ADR 0025 endpoint refuses it — and refuses it at the capability check
        // rather than by failing to find an actor, which is what would happen if
        // JwtCurrentActor had been left unable to describe a non-staff caller.
        int status = mvc.perform(get("/api/v1/tenants/" + SHARED_TENANT + "/customers/" + signedIn.accountId()
                                + "/contact-points")
                        .with(session(signedIn.token())))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("reading a customer's contact points is CUSTOMER_PII_REVEAL, and the "
                        + "customer themselves does not hold it — their own surface is /me")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("no credential at all is still refused")
    void anAnonymousCallerIsStillRefused() throws Exception {
        assertThat(mvc.perform(get(me(SHARED_TENANT, BRAND)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    /** The whole journey: ask for a code, type it, exchange the grant. */
    private SignedIn signIn(UUID tenantId, UUID brandId) throws Exception {
        MvcResult challenge = mvc.perform(post(identity(tenantId, brandId) + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PRESET_PHONE + "\"}"))
                .andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(202);
        String challengeId = json(challenge).path("challengeId").asText();

        MvcResult attempt = mvc.perform(
                        post(identity(tenantId, brandId) + "/verification-challenges/" + challengeId + "/attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"" + PRESET_CODE + "\"}"))
                .andReturn();
        assertThat(attempt.getResponse().getStatus())
                .as("the preset code is the code the challenge was written with")
                .isEqualTo(200);
        String grant = json(attempt).path("grant").asText();

        MvcResult session = mvc.perform(post(identity(tenantId, brandId) + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"" + grant + "\"}"))
                .andReturn();
        assertThat(session.getResponse().getStatus()).isIn(200, 201);

        JsonNode body = json(session);
        return new SignedIn(
                body.path("token").asText(),
                body.path("accountId").asText(),
                body.path("created").asBoolean());
    }

    private record SignedIn(String token, String accountId, boolean created) {}

    /**
     * The customer's own credential, in the header a browser would send it in.
     *
     * <p>Deliberately not a {@code jwt()} post-processor. That sets a security
     * context directly and would step over the filter, the bearer-token resolver
     * and the row lookup — every part of what is being asserted.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor session(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private UUID seedRealmAccount(String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id,
                    identity_partition_brand_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, NULL, 'ACTIVE', :now, :now)
                """)
                .param("id", accountId)
                .param("tenantId", SHARED_TENANT)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id,
                    identity_partition_brand_id, customer_account_id, issuer, subject, status,
                    linked_at)
                VALUES (:id, :tenantId, NULL, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", SHARED_TENANT)
                .param("accountId", accountId)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (id, tenant_id, brand_id,
                    customer_account_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :accountId, 'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, brand_id, customer_account_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", SHARED_TENANT)
                .param("brandId", BRAND)
                .param("accountId", accountId)
                .param("now", now)
                .update();
        return accountId;
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
                """)
                .param("id", UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", id)
                .param("mode", identityMode)
                .update();
    }

    private void brandRow(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase(Locale.ROOT))
                .update();
    }

    private static String brand(UUID tenantId, UUID brandId) {
        return "/api/v1/storefront/tenants/" + tenantId + "/brands/" + brandId;
    }

    private static String me(UUID tenantId, UUID brandId) {
        return brand(tenantId, brandId) + "/me";
    }

    private static String identity(UUID tenantId, UUID brandId) {
        return brand(tenantId, brandId) + "/identity";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String codeOf(MvcResult result) throws Exception {
        return json(result).path("code").asText();
    }
}
