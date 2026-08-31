package uz.horecaos.platform.payments.payme;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeErrors;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeMerchantApi;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcProviderCallbackStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The endpoint through the real filter chain, which is the only way to prove the
 * one thing that matters most about it.
 *
 * <p><strong>Every response Payme receives must be HTTP 200</strong>, errors
 * included; Payme reads any other status as {@code -32400}. Its very first sandbox
 * test sends a bad credential and expects HTTP 200 with {@code -32504} in a
 * JSON-RPC error body. Spring Security's stock {@code httpBasic()} answers a
 * bodyless 401 and fails that test before a single som has moved — which is exactly
 * what Payme's own Java template does.
 *
 * <p>That failure cannot be caught by a test of the controller alone, because the
 * controller is never reached: the filter chain answers first. So this runs the
 * whole application, over a real database, and drives the endpoint through
 * {@code MockMvc} with the security filters in place. The collaborators behind the
 * controller are the only things replaced, because what is under test is the
 * plumbing rather than the seven methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymeMerchantApiEndpointTests {

    private static final String SEGMENT = "payme-cashbox-one";
    private static final String PATH = "/providers/payme/" + SEGMENT;

    /**
     * A test-scoped credential, supplied through ADR 0028's own resolver rather than
     * written into a row.
     *
     * <p>The rule is that a provider key never appears in a column, a log line, or a
     * fixture that stands in for one. Configuring the resolver is how the resolver is
     * meant to be given a value, and the binding below still carries only a
     * reference. Thirty-six characters, which is the length Payme documents for a
     * cashbox key.
     */
    private static final String CASHBOX_KEY = "fkWW6UNrzvzyV6DhrdHJ6aEhr3dRcvJYkaGx";

    private static final SecretReference SECRET =
            new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "payme", "cashbox-one");

    /**
     * A private database on the JVM's one shared PostgreSQL, handed to Spring as
     * properties.
     *
     * <p>Not {@code @ServiceConnection}. That annotation takes precedence over
     * every {@code spring.datasource.*} property, so a URL registered below would
     * be silently ignored and this suite would go on running against a container
     * of its own — the conversion would look done and change nothing.
     *
     * <p>Assigned in {@link #properties} rather than in a field initializer: a
     * field initializer runs at class load, which is before the {@code @BeforeAll}
     * that skips this class when Docker is absent, and would turn a clean skip
     * into an {@code ExceptionInInitializerError}.
     *
     * <p>Never closed. Hikari holds connections to it and Spring caches the
     * context past the last test in this class, so dropping the database here
     * would surface as a failure in whichever class ran next. It dies with the
     * container.
     *
     * <p>Boot's Flyway autoconfiguration is left on. Against a clone already at
     * the latest version it is a validate, not a migration, and it is the only
     * thing in this suite that would notice a clone that arrived at the wrong one.
     */
    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method
    // runs (see the javadoc above for why it cannot move to @BeforeAll instead).
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the Payme endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        // The relay would need a broker; this test is about the HTTP layer.
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.provider_payment.payme.cashbox-one", () -> CASHBOX_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentBindingResolver bindings;

    @MockitoBean
    private PaymeMerchantApi merchantApi;

    @MockitoBean
    private JdbcProviderCallbackStore callbacks;

    @BeforeEach
    void resolveTheCashbox() {
        when(bindings.byCallbackSegment(SEGMENT)).thenReturn(Optional.of(binding()));
        when(bindings.byCallbackSegment(anyString()))
                .thenAnswer(invocation ->
                        SEGMENT.equals(invocation.getArgument(0)) ? Optional.of(binding()) : Optional.empty());
    }

    // -----------------------------------------------------------------------
    // The one that fails the sandbox if it is wrong
    // -----------------------------------------------------------------------

    /**
     * No {@code Authorization} header at all.
     *
     * <p>The status assertion is the whole test. A 401 here would mean the platform's
     * bearer-token chain reached the request first, or that Basic authentication was
     * configured the ordinary way — and either would fail Payme's first sandbox
     * test.
     */
    @Test
    @DisplayName("an unauthenticated request is -32504 in an HTTP 200 body")
    void unauthenticatedIsMinus32504WithHttpTwoHundred() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.parseMediaType("text/json;charset=UTF-8"))
                        .content("""
                                {"method":"CheckPerformTransaction",
                                 "params":{"amount":150000,
                                           "account":{"order_id":"149d439536b3216fdaeeb975729fae92"}},
                                 "id":1}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value(-32504))
                // A localised object, never a string. The Russian text is the one the
                // payer actually reads, because the Payme checkout defaults to ru.
                .andExpect(jsonPath("$.error.message.ru").isNotEmpty())
                .andExpect(jsonPath("$.error.message.uz").isNotEmpty())
                .andExpect(jsonPath("$.error.message.en").isNotEmpty())
                // Echoed, which is what the sandbox checks for.
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("a wrong cashbox key is -32504 in an HTTP 200 body")
    void wrongKeyIsMinus32504() throws Exception {
        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:not-the-cashbox-key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CheckTransaction\",\"params\":{\"id\":\"x\"},\"id\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32504))
                .andExpect(jsonPath("$.id").value(7));
    }

    /**
     * An unknown cashbox is the same answer a wrong key gets.
     *
     * <p>Not a 404. Distinguishing them would turn the endpoint into an oracle for
     * which cashboxes exist, and a 404 is a {@code -32400} to Payme in any case.
     */
    @Test
    @DisplayName("an unknown cashbox segment is -32504, not a 404")
    void unknownSegmentIsMinus32504() throws Exception {
        mvc.perform(post("/providers/payme/payme-cashbox-nine")
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CheckTransaction\",\"params\":{\"id\":\"x\"},\"id\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32504));
    }

    // -----------------------------------------------------------------------
    // The rest of the envelope
    // -----------------------------------------------------------------------

    /**
     * A non-POST is {@code -32300}, and it is still a 200.
     *
     * <p>Both of Payme's templates map their endpoint to POST alone and leave the
     * framework to answer 405, losing the distinction between "you used the wrong
     * verb" and "the merchant's database is down".
     */
    @Test
    @DisplayName("a GET is -32300 rather than a 405")
    void getIsMinus32300() throws Exception {
        mvc.perform(get(PATH).header("Authorization", basic("Paycom:" + CASHBOX_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32300));
    }

    @Test
    @DisplayName("a body that will not parse is -32700, after the credential is checked")
    void unparseableIsMinus32700() throws Exception {
        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32700))
                // Present and null, rather than absent. A response with no id member
                // at all is a different thing from one that admits it could not read
                // the id, and only the second is true here.
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * An unparseable body from an unauthenticated caller is still {@code -32504}.
     *
     * <p>The order matters: a caller who cannot authenticate learns nothing about the
     * parser, the method table, or the orders behind them.
     */
    @Test
    @DisplayName("authentication is answered before anything is said about the body")
    void authenticationPrecedesParsing() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{not json at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32504));
    }

    /**
     * {@code jsonrpc} is not required on input.
     *
     * <p>The docs' own request table lists only {@code method}, {@code params} and
     * {@code id}, and their worked example carries no {@code jsonrpc} member.
     * Requiring it would reject genuine Payme traffic; only Payme's Java template's
     * README {@code curl} sends it.
     */
    @Test
    @DisplayName("a request without a jsonrpc member is accepted, and the response carries one")
    void doesNotRequireTheJsonRpcMember() throws Exception {
        when(merchantApi.dispatch(any(), any(), any())).thenReturn(Map.of("allow", true));

        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.parseMediaType("text/json;charset=UTF-8"))
                        .content("""
                                {"method":"CheckPerformTransaction",
                                 "params":{"amount":150000,
                                           "account":{"order_id":"149d439536b3216fdaeeb975729fae92"}},
                                 "id":2032}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.result.allow").value(true))
                .andExpect(jsonPath("$.id").value(2032))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("an unimplemented method is -32601 with the name in data")
    void unknownMethodIsMinus32601() throws Exception {
        when(merchantApi.dispatch(any(), any(), any())).thenThrow(PaymeErrors.methodNotFound("ChangePassword"));

        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"ChangePassword\",\"params\":{},\"id\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.data").value("ChangePassword"));
    }

    /**
     * A fault nobody predicted is {@code -32400} in a 200 body, not a 500.
     *
     * <p>Payme would read a 500 as {@code -32400} anyway — but slowly, without the
     * request id, and wrapped in an error page it cannot parse.
     */
    @Test
    @DisplayName("an unexpected fault is -32400 in an HTTP 200 body")
    void unexpectedFaultIsMinus32400() throws Exception {
        when(merchantApi.dispatch(any(), any(), any())).thenThrow(new IllegalStateException("the database is gone"));

        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"GetStatement\",\"params\":{\"from\":1,\"to\":2},\"id\":11}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32400))
                .andExpect(jsonPath("$.id").value(11));
    }

    /**
     * A body that parsed and is structurally wrong is {@code -32600}.
     *
     * <p>Named parameters only: the protocol page states that {@code params} is
     * always an object and never an array.
     */
    @Test
    @DisplayName("positional parameters are -32600")
    void positionalParametersAreMinus32600() throws Exception {
        mvc.perform(post(PATH)
                        .header("Authorization", basic("Paycom:" + CASHBOX_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CheckTransaction\",\"params\":[\"x\"],\"id\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    /**
     * The response is not negotiated.
     *
     * <p>A negotiated response answers 406 when the request's {@code Accept} header
     * does not name JSON, and Payme does not document what it sends.
     */
    @Test
    @DisplayName("an Accept header that does not name JSON does not produce a 406")
    void ignoresContentNegotiation() throws Exception {
        mvc.perform(post(PATH)
                        .header("Accept", "text/html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CheckTransaction\",\"params\":{\"id\":\"x\"},\"id\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32504));
    }

    private static String basic(String credential) {
        return "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderBinding binding() {
        return new ProviderBinding(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PaymentProviderType.PAYME,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "587f72c72cac0d162c722ae2",
                null,
                null,
                SECRET,
                SEGMENT,
                false,
                true,
                LocalDate.of(2026, 1, 1),
                null);
    }
}
