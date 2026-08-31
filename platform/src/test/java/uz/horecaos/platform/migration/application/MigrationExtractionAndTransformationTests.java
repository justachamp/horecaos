package uz.horecaos.platform.migration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.migration.api.ExtractionSpec;
import uz.horecaos.platform.migration.api.LegacyRecord;
import uz.horecaos.platform.migration.api.Transformation;
import uz.horecaos.platform.migration.api.TransformationOutcome;
import uz.horecaos.platform.migration.application.importing.RemediationRequiredException;
import uz.horecaos.platform.migration.application.importing.SourcePage;
import uz.horecaos.platform.migration.application.importing.TransformationRegistry;
import uz.horecaos.platform.migration.application.importing.TransformationRegistryStore;

/**
 * Extraction and versioned transformation (ADR 0024, steps 3 and 4).
 *
 * <p>No database. What is asserted here is the arithmetic and the refusals — the
 * decisions that are wrong in a way a schema cannot catch: a naive timestamp read
 * without a zone, a mapping changed without its version, a spec that would
 * concatenate an identifier into SQL. The paging and checkpointing against the
 * real schema belong with the control-plane integration tests.
 */
class MigrationExtractionAndTransformationTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Test
    @DisplayName("a naive legacy timestamp is read in the configured zone, not the JVM's")
    void naiveTimestampsAreReadInTheProgramsZone() {
        // Finding 2, structural: the legacy BaseModel types created/updated without
        // a timezone and defaults them to datetime.now, so the value is the legacy
        // server's local wall time and the zone is recorded nowhere.
        LegacyRecord order = record("4200", Map.of("id", 4200L, "created", LocalDateTime.of(2026, 2, 21, 13, 5)));

        Instant local = order.instantAt("created", TASHKENT);
        Instant asUtc = order.instantAt("created", ZoneOffset.UTC);

        assertThat(local).isEqualTo(Instant.parse("2026-02-21T08:05:00Z"));
        assertThat(java.time.Duration.between(local, asUtc))
                .as("five hours, which is the whole business-date problem: read as UTC, a day's "
                        + "orders renumber into the wrong day")
                .isEqualTo(java.time.Duration.ofHours(5));
    }

    // Deliberately passes a null zone below: instantAt's own Objects.requireNonNull
    // is exactly what this test asserts, so the literal has to get past the
    // compiler to reach the runtime check it is proving. The suppression has to
    // cover the whole method because NullAway's local-variable nullability tracks
    // the value from its declaration through to where it is finally passed as an
    // argument, several statements later.
    @SuppressWarnings("NullAway")
    @Test
    @DisplayName("reading a naive timestamp without a zone is not possible")
    void aZoneIsAlwaysRequired() {
        LegacyRecord order = record("4200", Map.of("created", LocalDateTime.of(2026, 2, 21, 13, 5)));

        assertThat(order.naiveTimestamp("created"))
                .as("handed back with no zone attached, so nothing can silently assume one")
                .isEqualTo(LocalDateTime.of(2026, 2, 21, 13, 5));
        ZoneId missingZone = null;
        assertThat(catchThrowable(() -> order.instantAt("created", missingZone)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a nullable column reads as null rather than as zero")
    void nullableColumnsAreNotCoercedToZero() {
        // orders.vendor_id is nullable in the legacy schema. An order with no
        // branch has no brand and no tenant, and it must quarantine rather than be
        // assigned a convenient parent — which only works if null survives the read.
        LegacyRecord order = record("4200", mapWithNulls("id", 4200L, "vendor_id", null, "packaging_price", null));

        assertThat(order.isNull("vendor_id")).isTrue();
        assertThat(order.number("packaging_price"))
                .as("getInt would answer 0, and 0 is a legal price")
                .isNull();
        assertThat(order.has("operator_id"))
                .as("a column that was not selected is a mapping error, not an empty value")
                .isFalse();
        assertThat(catchThrowable(() -> order.text("operator_id"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a legacy row never renders its columns into a string")
    void sourceDataDoesNotLeakThroughToString() {
        LegacyRecord customer = record("77", Map.of("id", 77L, "username", "+998901234567"));

        assertThat(customer.toString())
                .as("ADR 0029: one interpolation into a log line publishes a phone number")
                .doesNotContain("+998901234567")
                .contains("77");
    }

    @Test
    @DisplayName("an extraction spec refuses anything it would concatenate into SQL")
    void extractionSpecsValidateEveryIdentifier() {
        assertThat(catchThrowable(() ->
                        new ExtractionSpec("ORDER", "orders; DROP TABLE orders", "id", null, List.of("id"), null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catchThrowable(() -> new ExtractionSpec("ORDER", "orders", "id", null, List.of(), null)))
                .as("a star select puts whatever the legacy schema gains next in front of the "
                        + "transformation unreviewed")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catchThrowable(
                        () -> new ExtractionSpec("ORDER", "orders", "id", null, List.of("order_price"), null)))
                .as("the stable key is the crosswalk key and the page bound, so it must be selected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a page with rows always carries the bound the next page resumes from")
    void aPageWithRowsCarriesItsNextKey() {
        assertThat(catchThrowable(() -> new SourcePage(List.of(record("1", Map.of("id", 1L))), null, false)))
                .as("without it a restart re-reads the same page forever")
                .isInstanceOf(IllegalArgumentException.class);

        SourcePage empty = new SourcePage(List.of(), null, true);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.exhausted()).isTrue();
    }

    @Test
    @DisplayName("the digest changes when a mapping rule changes, and not otherwise")
    void theDigestTracksTheDeclaredRules() {
        Transformation<String> first = transformation(1, List.of("companies.slug becomes the brand code"));
        Transformation<String> same = transformation(1, List.of("companies.slug becomes the brand code"));
        Transformation<String> changed =
                transformation(1, List.of("companies.slug becomes the brand code, upper-cased"));
        Transformation<String> renumbered = transformation(2, List.of("companies.slug becomes the brand code"));

        assertThat(first.digest()).isEqualTo(same.digest()).matches("^[0-9a-f]{64}$");
        assertThat(first.digest()).isNotEqualTo(changed.digest());
        assertThat(first.digest())
                .as("the version is inside the hash, so two versions with identical rules differ")
                .isNotEqualTo(renumbered.digest());
    }

    @Test
    @DisplayName("a changed mapping under an unchanged version is refused, not run")
    void aChangedMappingForcesARemediation() {
        UUID program = UUID.randomUUID();
        Transformation<String> declared = transformation(1, List.of("slug becomes the code"));
        Transformation<String> edited = transformation(1, List.of("slug becomes the code, trimmed"));

        InMemoryRegistry store = new InMemoryRegistry();
        TransformationRegistry registry = new TransformationRegistry(store, Clock.systemUTC());
        registry.declare(program, declared, "Initial brand mapping", "tests");

        registry.requireCurrent(program, declared);

        Throwable refusal = catchThrowable(() -> registry.requireCurrent(program, edited));
        assertThat(refusal)
                .as("silently re-importing under the same number is the mixed semantics ADR 0024 " + "forbids")
                .isInstanceOf(RemediationRequiredException.class)
                .hasMessageContaining("remediation");
    }

    @Test
    @DisplayName("an undeclared version is refused as firmly as a changed one")
    void anUndeclaredVersionCannotRun() {
        UUID program = UUID.randomUUID();
        TransformationRegistry registry = new TransformationRegistry(new InMemoryRegistry(), Clock.systemUTC());

        assertThat(catchThrowable(() -> registry.requireCurrent(program, transformation(1, List.of("anything")))))
                .as("a crosswalk row stamped with a version nothing defines cannot be remediated")
                .isInstanceOf(RemediationRequiredException.class);
    }

    private static LegacyRecord record(String key, Map<String, Object> values) {
        return new LegacyRecord(key, null, values);
    }

    /** {@code Map.of} rejects nulls, and every nullable legacy column needs one. */
    private static Map<String, Object> mapWithNulls(@Nullable Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static Transformation<String> transformation(int version, List<String> rules) {
        return new Transformation<>() {
            @Override
            public String entityType() {
                return "BRAND";
            }

            @Override
            public int version() {
                return version;
            }

            @Override
            public List<String> rules() {
                return rules;
            }

            @Override
            public TransformationOutcome<String> transform(LegacyRecord record, ZoneId sourceZone) {
                return TransformationOutcome.of(record.stableKey());
            }
        };
    }

    /** Enough of the registry table to exercise the refusal, without a database. */
    private static final class InMemoryRegistry implements TransformationRegistryStore {

        private final Map<String, Declaration> current = new HashMap<>();

        @Override
        public Optional<Declaration> findCurrent(UUID programId, String entityType) {
            return Optional.ofNullable(current.get(programId + "/" + entityType));
        }

        @Override
        public Optional<Declaration> find(UUID programId, String entityType, int version) {
            return findCurrent(programId, entityType)
                    .filter(declaration -> declaration.transformationVersion() == version);
        }

        @Override
        public boolean declare(Declaration declaration, Instant now) {
            return current.putIfAbsent(declaration.programId() + "/" + declaration.entityType(), declaration) == null;
        }
    }
}
