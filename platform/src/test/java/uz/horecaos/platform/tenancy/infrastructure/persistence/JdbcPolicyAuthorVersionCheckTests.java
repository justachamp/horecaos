package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@code PolicyAuthor}'s optimistic version check (wave 16, gap map row {@code X.39}): the
 * append-only policy writer never lost an update at the database -- every write is a new version --
 * but it let a second operator's whole-document replace silently discard the first's, because
 * nothing compared what the writer had seen with what was in force.
 *
 * <p>Built directly, against real SQL, for the reason {@code JdbcConfigurationValueAuthorTests}
 * gives: the property is in the statements and the unique index, not in a mock of them.
 */
class JdbcPolicyAuthorVersionCheckTests {

    /** A synthetic document and key: this class tests the writer, not any module's policy. */
    record Probe(int seconds) {}

    private static final PolicyKey<Probe> PROBE = new PolicyKey<>(
            "testing.author_probe",
            Probe.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "testing",
            false,
            "A probe document for the writer's version check.");

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131601");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131602");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131603");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131701");

    private static final ActorRef OPERATOR = ActorRef.user("op-1", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPolicyAuthor author;
    private JdbcPolicyResolver resolver;
    private TransactionTemplate transactions;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        // The writer is @Transactional in production; built directly it is not, so the race below runs
        // each publication in the transaction Spring would have given it.
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        JsonMapper mapper = JsonMapper.builder().build();
        resolver = new JdbcPolicyResolver(jdbc, mapper);
        List<AuditFact> discarded = new ArrayList<>();
        author = new JdbcPolicyAuthor(
                jdbc,
                mapper,
                fact -> {
                    synchronized (discarded) {
                        discarded.add(fact);
                    }
                },
                Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC),
                (keyCode, scope) -> {});

        insertTenant(TENANT, "policy-check");
        insertTenant(OTHER_TENANT, "policy-check-other");
        insertBrandAndLocation(TENANT, BRAND, LOCATION);
    }

    @Test
    void expectingNothingPublishesVersionOneAndTheNextWriterMustHaveSeenIt() {
        ResourceScope tenant = ResourceScope.tenant(TENANT);

        ResolvedPolicy<Probe> first = author.author(PROBE, tenant, new Probe(600), 0, OPERATOR, "first");
        ResolvedPolicy<Probe> second = author.author(PROBE, tenant, new Probe(300), 1, OPERATOR, "second");

        assertThat(first.policyVersion()).isEqualTo(1);
        assertThat(second.policyVersion()).isEqualTo(2);
        assertThat(author.currentVersion(PROBE, tenant)).isEqualTo(2);
        assertThat(resolver.resolve(PROBE, tenant).orElseThrow().document().seconds())
                .isEqualTo(300);
    }

    @Test
    void aWriterHoldingAnOlderVersionIsRefusedWithBothVersionsAndPublishesNothing() {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        author.author(PROBE, tenant, new Probe(600), 0, OPERATOR, "first");
        author.author(PROBE, tenant, new Probe(300), 1, OPERATOR, "second, by someone else");

        assertThatThrownBy(() -> author.author(PROBE, tenant, new Probe(120), 1, OPERATOR, "from a stale form"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(error.properties()).containsEntry("expectedVersion", 1L);
                    assertThat(error.properties()).containsEntry("currentVersion", 2L);
                });

        assertThat(author.currentVersion(PROBE, tenant))
                .as("the refused write left no version")
                .isEqualTo(2);
        assertThat(resolver.resolve(PROBE, tenant).orElseThrow().document().seconds())
                .as("and the first two writers' work stands")
                .isEqualTo(300);
    }

    @Test
    void expectingNothingIsStaleOnceAnythingWasAuthoredAtThatScope() {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        author.author(PROBE, tenant, new Probe(600), 0, OPERATOR, "first");

        assertThatThrownBy(() -> author.author(PROBE, tenant, new Probe(120), 0, OPERATOR, "second opener"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    @Test
    void theCurrentVersionIsThisScopesOwnLatestAndNeverAnAncestors() {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        ResourceScope brand = ResourceScope.brand(TENANT, BRAND);
        author.author(PROBE, tenant, new Probe(600), 0, OPERATOR, "tenant default");
        author.author(PROBE, tenant, new Probe(500), 1, OPERATOR, "tenant default, again");

        assertThat(author.currentVersion(PROBE, tenant)).isEqualTo(2);
        assertThat(author.currentVersion(PROBE, brand))
                .as("the brand only inherits: nothing authored at exactly the brand")
                .isZero();
        assertThat(author.currentVersion(PROBE, ResourceScope.platform())).isZero();

        // A scope that only inherits opens at 0, whatever version its ancestor is on.
        ResolvedPolicy<Probe> override = author.author(PROBE, brand, new Probe(90), 0, OPERATOR, "brand override");
        assertThat(override.policyVersion()).isEqualTo(1);
        assertThat(author.currentVersion(PROBE, brand)).isEqualTo(1);
        assertThat(author.currentVersion(PROBE, tenant))
                .as("the ancestor is untouched")
                .isEqualTo(2);
    }

    @Test
    void theUnconditionalPathStillPublishesWithoutAnyExpectation() {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        author.author(PROBE, tenant, new Probe(600), 0, OPERATOR, "checked");

        ResolvedPolicy<Probe> unconditional = author.author(PROBE, tenant, new Probe(45), OPERATOR, "no check");

        assertThat(unconditional.policyVersion()).isEqualTo(2);
    }

    @Test
    void aBrandThatIsNotTheTenantsIsARefusalNotAServerError() {
        assertThatThrownBy(() -> author.author(
                        PROBE, ResourceScope.brand(OTHER_TENANT, BRAND), new Probe(90), 0, OPERATOR, "wrong tenant"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    assertThat(error.getMessage()).contains("brand");
                });
        assertThatThrownBy(() -> author.author(
                        PROBE,
                        ResourceScope.location(OTHER_TENANT, BRAND, LOCATION),
                        new Probe(90),
                        0,
                        OPERATOR,
                        "wrong tenant"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    /**
     * Two operators open the same form at the same version and both press publish. However the two
     * statements interleave -- one sees the other's row and fails the check, or both pass it and the
     * unique index refuses the second -- exactly one wins and every loser is told it is stale, never
     * a bare conflict or a server error.
     */
    @RepeatedTest(10)
    void ofManyWritersHoldingTheSameVersionExactlyOneWinsAndTheRestAreStale() throws Exception {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                int seconds = 100 + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return Objects.requireNonNull(transactions.execute(status -> author.author(
                                        PROBE, tenant, new Probe(seconds), 0, OPERATOR, "race " + seconds)
                                .policyVersion()));
                    } catch (ApiException refused) {
                        assertThat(refused.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                        stale.incrementAndGet();
                        return -1;
                    }
                }));
            }
            start.countDown();
            List<Integer> versions = new ArrayList<>();
            for (Future<Integer> result : results) {
                versions.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(versions.stream().filter(version -> version > 0)).containsExactly(1);
            assertThat(stale.get()).isEqualTo(writers - 1);
            assertThat(author.currentVersion(PROBE, tenant)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'testing.author_probe'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The same race without an expectation: concurrent publishers of one scope each get a version of their
     * own. Before publications were serialised, two of them could both take "N + 1" -- two rows, one version
     * number -- and the pointer landed on whichever committed last.
     */
    @RepeatedTest(10)
    void concurrentUnconditionalPublicationsOfOneScopeGetDistinctVersions() throws Exception {
        ResourceScope tenant = ResourceScope.tenant(TENANT);
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                int seconds = 200 + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return Objects.requireNonNull(transactions.execute(
                            status -> author.author(PROBE, tenant, new Probe(seconds), OPERATOR, "unchecked " + seconds)
                                    .policyVersion()));
                }));
            }
            start.countDown();
            List<Integer> versions = new ArrayList<>();
            for (Future<Integer> result : results) {
                versions.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(versions).doesNotHaveDuplicates().containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
            assertThat(author.currentVersion(PROBE, tenant)).isEqualTo(6);
            assertThat(jdbc.sql(
                                    "SELECT policy_version FROM tenant.policy_current WHERE key_code = 'testing.author_probe'")
                            .query(Integer.class)
                            .single())
                    .as("the pointer names the newest version, whichever thread committed last")
                    .isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertBrandAndLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_POLICY_CHECK', 'brand-policy-check', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC_POLICY_CHECK', 'loc-policy-check', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }
}
