package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.application.OperatorCustomerLookupService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0039's phone lookup (orders.md &sect;5.3), in the {@code
 * VoiceModuleIntegrationTest} genre: real Postgres and a real {@code
 * AuditRecorder}, hand-wired application service, fake {@code
 * CustomerPhoneLookup} and {@code OrderDirectory} — the same two ports {@code
 * ScreenPopQueryService} already proves over real implementations elsewhere,
 * so faking them here is not re-testing the hashed lookup, only {@link
 * OperatorCustomerLookupService}'s own orchestration and its audit obligation.
 *
 * <p>{@code OperatorOrderingService#place} — placing the order once a customer
 * is resolved — is a separate class and out of scope here; {@code
 * CartCheckoutAndOrderTests} proves it over the real checkout stack.
 */
class OperatorOrderingLookupTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String OPERATOR = "operator-subject-1";
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FakeCustomerPhoneLookup customers;
    private FakeOrderDirectory orders;
    private OperatorCustomerLookupService lookup;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);

        customers = new FakeCustomerPhoneLookup();
        orders = new FakeOrderDirectory();
        lookup = new OperatorCustomerLookupService(customers, orders, audit, clock);
    }

    @Test
    void aMatchedLookupReturnsAMaskedNameAndOrderHistory() {
        UUID accountId = UUID.randomUUID();
        customers.registerAccount("+998901234567", accountId, "Alisher Karimov");
        orders.registerRecentOrder(
                accountId,
                new OrderDirectory.RecentOrder(
                        UUID.randomUUID(),
                        "A-1001",
                        LOCATION,
                        "COMPLETED",
                        "UZS",
                        45_000,
                        Instant.parse("2026-09-01T12:00:00Z")));
        orders.registerRecentOrder(
                accountId,
                new OrderDirectory.RecentOrder(
                        UUID.randomUUID(),
                        "A-1002",
                        LOCATION,
                        "COMPLETED",
                        "UZS",
                        30_000,
                        Instant.parse("2026-09-05T09:00:00Z")));

        List<OperatorCustomerLookupService.PhoneLookupCandidate> found =
                lookup.lookupByPhone(TENANT, BRAND, LOCATION, "+998901234567", operatorActor(), "customer.read");

        assertThat(found).hasSize(1);
        OperatorCustomerLookupService.PhoneLookupCandidate candidate = found.get(0);
        assertThat(candidate.accountId()).isEqualTo(accountId);
        assertThat(candidate.maskedDisplayName())
                .as("recognisable, not readable whole — this is a search across the whole customer base")
                .isEqualTo("A****** K******")
                .isNotEqualTo("Alisher Karimov");
        assertThat(candidate.recentOrderCount()).isEqualTo(2);
        // The most recently placed order, not the first one registered.
        assertThat(candidate.lastOrderAt()).isEqualTo(Instant.parse("2026-09-05T09:00:00Z"));
    }

    @Test
    void anUnmatchedLookupReturnsEmptyAndIsStillAudited() {
        List<OperatorCustomerLookupService.PhoneLookupCandidate> found =
                lookup.lookupByPhone(TENANT, BRAND, LOCATION, "+998900000000", operatorActor(), "customer.read");

        assertThat(found).isEmpty();
        assertThat(auditFactCount())
                .as("a lookup that finds nobody is still a read against the whole customer base")
                .isEqualTo(1L);
    }

    @Test
    void everyLookupIsASecurityClassAuditFactNamingTheOperatorAndTheCapability() {
        UUID accountId = UUID.randomUUID();
        customers.registerAccount("+998901111111", accountId, "Zarina Yusupova");

        lookup.lookupByPhone(TENANT, BRAND, LOCATION, "+998901111111", operatorActor(), "customer.read");

        String row = jdbc.sql("""
                        SELECT audit_class, actor_type, actor_subject, capability_used, reason
                        FROM audit.audit_events
                        WHERE action_code = 'customer.phone_lookup.performed' AND tenant_id = :tenantId
                        """)
                .param("tenantId", TENANT)
                .query((rs, rowNum) -> "%s|%s|%s|%s|%s"
                        .formatted(
                                rs.getString("audit_class"),
                                rs.getString("actor_type"),
                                rs.getString("actor_subject"),
                                rs.getString("capability_used"),
                                rs.getString("reason") == null ? "" : "has-reason"))
                .single();

        assertThat(row).isEqualTo("SECURITY|USER|%s|customer.read|has-reason".formatted(OPERATOR));
    }

    @Test
    void aLookupNeverWritesTheSearchedPhoneNumberIntoTheAuditTrail() {
        customers.registerAccount("+998907654321", UUID.randomUUID(), "Bekzod Rashidov");

        lookup.lookupByPhone(TENANT, BRAND, LOCATION, "+998907654321", operatorActor(), "customer.read");

        String changeDocument =
                jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'customer.phone_lookup.performed' AND tenant_id = :tenantId
                        """).param("tenantId", TENANT).query(String.class).single();

        assertThat(changeDocument).doesNotContain("998907654321");
    }

    @Test
    void aLookupCarriesTheRequestCorrelationSoItTiesToTheOrderItPrecedes() {
        customers.registerAccount("+998905555555", UUID.randomUUID(), "Dilnoza Tosheva");

        MDC.put("correlationId", "req-77f0");
        try {
            lookup.lookupByPhone(TENANT, BRAND, LOCATION, "+998905555555", operatorActor(), "customer.read");
        } finally {
            MDC.remove("correlationId");
        }

        String correlation =
                jdbc.sql("""
                        SELECT correlation_id FROM audit.audit_events
                        WHERE action_code = 'customer.phone_lookup.performed' AND tenant_id = :tenantId
                        """).param("tenantId", TENANT).query(String.class).single();

        assertThat(correlation)
                .as("a per-call random id would sever the lookup from the order placed seconds later, "
                        + "which is the trail an investigation into customer-data misuse follows")
                .isEqualTo("req-77f0");
    }

    private long auditFactCount() {
        return jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                        WHERE action_code = 'customer.phone_lookup.performed' AND tenant_id = :tenantId
                        """).param("tenantId", TENANT).query(Long.class).single();
    }

    private static ActorRef operatorActor() {
        return ActorRef.user(OPERATOR, null);
    }

    private static final class FakeCustomerPhoneLookup implements CustomerPhoneLookup {
        private final Map<String, UUID> accountsByPhone = new LinkedHashMap<>();
        private final Map<UUID, String> namesByAccount = new LinkedHashMap<>();

        void registerAccount(String phone, UUID accountId, String displayName) {
            accountsByPhone.put(phone, accountId);
            namesByAccount.put(accountId, displayName);
        }

        @Override
        public List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber) {
            UUID accountId = accountsByPhone.get(rawPhoneNumber);
            return accountId == null ? List.of() : List.of(new CustomerAccountRef(accountId, tenantId));
        }

        @Override
        public Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
            String name = namesByAccount.get(accountId);
            return name == null ? Optional.empty() : Optional.of(new CardProfile(accountId, name));
        }
    }

    private static final class FakeOrderDirectory implements OrderDirectory {
        private final Map<UUID, List<OrderDirectory.RecentOrder>> byAccount = new LinkedHashMap<>();

        void registerRecentOrder(UUID accountId, OrderDirectory.RecentOrder order) {
            byAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(order);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }

        @Override
        public List<OrderDirectory.RecentOrder> recentForCustomer(
                UUID tenantId, UUID brandId, UUID customerAccountId, int limit) {
            return byAccount.getOrDefault(customerAccountId, List.of()).stream()
                    .sorted((a, b) -> b.placedAt().compareTo(a.placedAt()))
                    .limit(limit)
                    .toList();
        }
    }
}
