package uz.horecaos.platform.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditArchivalNotVerifiedException;
import uz.horecaos.platform.audit.api.AuditArchiveStore;
import uz.horecaos.platform.audit.api.AuditConfigurationKeys;
import uz.horecaos.platform.audit.infrastructure.storage.S3AuditArchiveStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationValueAuthor;

/**
 * ADR 0027's archival guarantee, end to end: a closed partition's evidence
 * survives in protected storage before the live table forgets it, and never
 * before.
 */
class AuditPartitionArchiverTests {

    private static TestDatabase.Handle db;
    private static GenericContainer<?> minio;
    private static S3Client s3;
    private static final String BUCKET = "horecaos-audit-archive-archiver-test";

    private JdbcClient jdbc;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");

        db = TestDatabase.migrated();

        minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "horecaos")
                .withEnv("MINIO_ROOT_PASSWORD", "horecaos-local-secret")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();

        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("horecaos", "horecaos-local-secret")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder()
                    .bucket(BUCKET)
                    .objectLockEnabledForBucket(true)
                    .build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Reused across runs; every test keys on its own year.
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        if (s3 != null) {
            s3.close();
        }
        if (minio != null) {
            minio.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE audit.partition_archives").update();
        // A platform-scoped retention value one test sets (see
        // archivesUnderTheLongerOfBothConfiguredRetentionPeriods) must not leak
        // into another test's computed retainUntil — this suite shares one
        // database across methods the way DatabasePrivilegeTests does.
        jdbc.sql("""
                        DELETE FROM tenant.configuration_values
                         WHERE key_code IN (:security, :business)
                        """)
                .param("security", AuditConfigurationKeys.SECURITY_RETENTION_DAYS_CODE)
                .param("business", AuditConfigurationKeys.BUSINESS_RETENTION_DAYS_CODE)
                .update();
    }

    @Test
    void archivesAndDropsAPartitionWhoseYearHasFullyPassed() {
        int year = 2024;
        ensurePartition(year);
        insertRow(year, 3, 15, "tenant.suspended");
        insertRow(year, 11, 1, "tenant.renamed");
        assertThat(liveRowCount(year)).isEqualTo(2);

        Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
        AuditPartitionArchiver archiver = new AuditPartitionArchiver(jdbc, new S3AuditArchiveStore(s3, BUCKET), clock);

        List<Integer> processed = archiver.archiveOnce();

        assertThat(processed).containsExactly(year);
        assertThat(partitionExists(year))
                .as("the live partition is gone once its archive is verified")
                .isFalse();

        Map<String, Object> row = jdbc.sql("SELECT * FROM audit.partition_archives WHERE year = :year")
                .param("year", year)
                .query()
                .singleRow();
        assertThat(row)
                .as("the row carries the archive's own evidence through to DROPPED, "
                        + "not just while it briefly read VERIFIED")
                .containsEntry("status", "DROPPED")
                .containsEntry("row_count", 2L);
        assertThat((String) row.get("bucket")).isEqualTo(BUCKET);
        assertThat((String) row.get("object_key")).contains(String.valueOf(year));

        // The archive itself, read back independently of the class under test —
        // not merely that partition_archives says VERIFIED, but that the bytes
        // are actually the two rows this test wrote.
        String archived = new String(
                s3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key((String) row.get("object_key")))
                        .asByteArray(),
                StandardCharsets.UTF_8);
        assertThat(archived).contains("tenant.suspended").contains("tenant.renamed");
        assertThat(archived.lines().count()).isEqualTo(2);
    }

    @Test
    void leavesAPartitionAloneUntilItsYearHasActuallyPassed() {
        // A year already safely in the real past, deliberately — the guarded
        // drop function re-checks the partition's bound against the database's
        // OWN clock (V0075/V0080's own discipline: an injected clock must never
        // be the thing that decides a drop), so a fictional Java clock can only
        // ever narrow the "before" half of this test, never manufacture the
        // "after" half against a partition whose real bound has not passed yet.
        int year = 2020;
        ensurePartition(year);
        insertRow(year, 1, 10, "tenant.created");

        AuditPartitionArchiver archiver = new AuditPartitionArchiver(
                jdbc,
                new S3AuditArchiveStore(s3, BUCKET),
                Clock.fixed(Instant.parse("2020-06-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(archiver.archiveOnce())
                .as("2020 has not finished yet on this clock")
                .isEmpty();
        assertThat(partitionExists(year)).isTrue();
        assertThat(archiveRowExists(year))
                .as("an open partition gets no row at all yet")
                .isFalse();

        // The same archiver, the same partition, a clock moved past the
        // partition's own upper bound — a duration advanced rather than an
        // instant asserted twice.
        AuditPartitionArchiver later = new AuditPartitionArchiver(
                jdbc,
                new S3AuditArchiveStore(s3, BUCKET),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));

        assertThat(later.archiveOnce())
                .as("now 2020 is closed, on this clock and on the database's own")
                .containsExactly(year);
        assertThat(partitionExists(year)).isFalse();
    }

    @Test
    void neverDropsTheLivePartitionWhenTheArchiveCannotBeVerified() {
        int year = 2023;
        ensurePartition(year);
        insertRow(year, 6, 1, "tenant.suspended");

        AuditPartitionArchiver archiver = new AuditPartitionArchiver(
                jdbc, alwaysUnverified(), Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));

        assertThat(archiver.archiveOnce())
                .as("an archive that cannot be verified must not be treated as done")
                .isEmpty();

        assertThat(partitionExists(year))
                .as("the one table where dropping before verification is unrecoverable")
                .isTrue();
        assertThat(liveRowCount(year)).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("SELECT status FROM audit.partition_archives WHERE year = :year")
                .param("year", year)
                .query()
                .singleRow();
        assertThat(row).containsEntry("status", "PENDING");
    }

    @Test
    void theDatabaseItselfRefusesToDropAYearWithNoVerifiedArchive() {
        int year = 2022;
        ensurePartition(year);
        insertRow(year, 4, 4, "tenant.created");

        Assertions.assertThatThrownBy(() -> jdbc.sql("SELECT audit.drop_archived_partition(:year)")
                        .param("year", year)
                        .query(Boolean.class)
                        .single())
                .as("no VERIFIED row exists for this year, and the function will not take the "
                        + "caller's word for it")
                .rootCause()
                .hasMessageContaining("VERIFIED");

        assertThat(partitionExists(year)).isTrue();
    }

    @Test
    void archivesUnderTheLongerOfBothConfiguredRetentionPeriods() {
        int year = 2021;
        ensurePartition(year);
        insertRow(year, 2, 2, "tenant.created");

        // The real ADR 0030 writer, not a raw INSERT — this proves
        // AuditPartitionArchiver's plain-SQL read actually lines up with what
        // the control plane's own author writes, not merely with a shape this
        // test invented. tenancy.domain.configuration.ConfigurationKeys is the
        // registry AuditConfigurationKeys' two string codes are kept identical
        // to (AuditConfigurationKeyTests); reachable from test code, which
        // Spring Modulith's boundary check does not scan the way it scans
        // src/main/java.
        JdbcAuditRecorder audit =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        JdbcConfigurationResolver resolver = new JdbcConfigurationResolver(jdbc);
        JdbcConfigurationValueAuthor author = new JdbcConfigurationValueAuthor(
                jdbc, audit, Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC), resolver);

        author.set(
                ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS,
                ResourceScope.platform(),
                100,
                false,
                null,
                ActorRef.systemJob("test-fixture"),
                "test");
        author.set(
                ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS,
                ResourceScope.platform(),
                9000,
                false,
                null,
                ActorRef.systemJob("test-fixture"),
                "test");

        AuditPartitionArchiver archiver = new AuditPartitionArchiver(
                jdbc,
                new S3AuditArchiveStore(s3, BUCKET),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));

        archiver.archiveOnce();

        Instant retainUntil = jdbc.sql("SELECT retain_until FROM audit.partition_archives WHERE year = :year")
                .param("year", year)
                .query((rs, rowNum) ->
                        rs.getObject("retain_until", OffsetDateTime.class).toInstant())
                .single();

        Instant partitionUpperBound = Instant.parse("2022-01-01T00:00:00Z");
        assertThat(retainUntil)
                .as("the LONGER of the two configured periods (9000 days), not the shorter, "
                        + "and not the code default either")
                .isEqualTo(partitionUpperBound.plus(Duration.ofDays(9000)));
    }

    @Test
    void archivingTwiceDoesNothingTheSecondTime() {
        int year = 2020;
        ensurePartition(year);
        insertRow(year, 1, 1, "tenant.created");

        AuditPartitionArchiver archiver = new AuditPartitionArchiver(
                jdbc,
                new S3AuditArchiveStore(s3, BUCKET),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));

        assertThat(archiver.archiveOnce()).containsExactly(year);
        assertThat(archiver.archiveOnce())
                .as("already DROPPED; nothing left to do and nothing re-uploaded")
                .isEmpty();
    }

    /** Always fails verification, standing in for a store — or a provider — that cannot lock. */
    private static AuditArchiveStore alwaysUnverified() {
        return (key, content, retainUntil) -> {
            throw new AuditArchivalNotVerifiedException("test double: never verifies");
        };
    }

    private void ensurePartition(int year) {
        jdbc.sql("SELECT audit.ensure_event_partition(:year)")
                .param("year", year)
                .query(Boolean.class)
                .single();
    }

    private void insertRow(int year, int month, int day, String actionCode) {
        Instant recordedAt =
                LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant();
        jdbc.sql("""
                        INSERT INTO audit.audit_events (
                            id, tenant_id, audit_class, action_code,
                            actor_type, actor_subject, actor_display, on_behalf_of_subject,
                            scope_type, scope_id, target_type, target_id, target_version,
                            outcome, reason, change_document, evidence_reference,
                            capability_used, approval_request_id,
                            correlation_id, causation_id, request_id, occurred_at, recorded_at)
                        VALUES (
                            :id, NULL, 'BUSINESS', :actionCode,
                            'SYSTEM_JOB', 'fixture', NULL, NULL,
                            'PLATFORM', NULL, NULL, NULL, NULL,
                            'SUCCEEDED', NULL, NULL, NULL,
                            NULL, NULL,
                            :correlationId, NULL, NULL, :occurredAt, :recordedAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("actionCode", actionCode)
                .param("correlationId", "fixture-" + UUID.randomUUID())
                .param("occurredAt", recordedAt.atOffset(ZoneOffset.UTC))
                .param("recordedAt", recordedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private long liveRowCount(int year) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events_" + year)
                .query(Long.class)
                .single();
    }

    private boolean partitionExists(int year) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = 'audit' AND c.relname = :name)
                        """)
                .param("name", "audit_events_" + year)
                .query(Boolean.class)
                .single());
    }

    private boolean archiveRowExists(int year) {
        return jdbc.sql("SELECT count(*) FROM audit.partition_archives WHERE year = :year")
                        .param("year", year)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
