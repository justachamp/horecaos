package uz.horecaos.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.migration.api.ExtractionSpec;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

/**
 * "Exact count/checksum for authoritative IDs" (ADR 0024's first mandatory rule),
 * as the two halves it has to be.
 *
 * <p>ADR 0024 rejects counts as reconciliation evidence — "counts match while
 * money, ancestry, and status are wrong" — and then requires a count anyway,
 * paired with a checksum. Both are here, and both are CRITICAL, because each is
 * useless without the other: a run that dropped one legacy row and duplicated
 * another has the right count and the wrong set, while a checksum on its own says
 * two sets differ without saying by how much.
 *
 * <p>Both sides are counted from the same definition of "in scope": the legacy
 * side is exactly what {@link ExtractionSpec} pages, filter included, and the
 * target side is the crosswalk. That is deliberate. Counting the target's own
 * table instead would count rows this migration never touched — a brand created
 * by an operator during the rehearsal — and report them as a surplus nobody could
 * explain.
 *
 * <p>Quarantined mappings count on both sides. The crosswalk holds a row for a
 * legacy record that could not be imported precisely so "seen and consciously not
 * migrated" stays distinguishable from "never seen"; excluding them here would
 * make a quarantine look like a loss, and the quarantine backlog is a separate
 * gate with its own count.
 */
public final class AuthoritativeIdRules {

    private AuthoritativeIdRules() {}

    /** Both halves for one entity family. */
    public static List<ReconciliationRule> forEntity(ExtractionSpec spec, MigrationCapability capability) {
        return List.of(new IdCount(spec, capability), new IdChecksum(spec, capability));
    }

    /** The crosswalk rows this scope holds for this entity type. */
    private static final String CROSSWALK_COUNT = """
            SELECT count(*)
            FROM migration.entity_mappings
            WHERE tenant_id = :tenantId AND scope_id = :scopeId AND entity_type = :entityType
              AND mapping_status <> 'SUPERSEDED'
            """;

    /**
     * The digest, and the reason it is computed in the database rather than in
     * Java: streaming five million identifiers into this process to hash them
     * would move the whole set across the network twice, once per side, on a rule
     * that runs at every gate.
     *
     * <p>{@code ORDER BY} inside the aggregate, because {@code string_agg} has no
     * inherent order and an unordered digest compares two sets by whatever plan
     * the optimiser chose. {@code coalesce} to the empty string, because an empty
     * scope must produce a comparable digest and not a null that reads as "not
     * measured".
     */
    private static final String CROSSWALK_CHECKSUM = """
            SELECT encode(sha256(convert_to(
                       coalesce(string_agg(legacy_id, ',' ORDER BY legacy_id), ''), 'UTF8')), 'hex')
            FROM migration.entity_mappings
            WHERE tenant_id = :tenantId AND scope_id = :scopeId AND entity_type = :entityType
              AND mapping_status <> 'SUPERSEDED'
            """;

    private static String legacyCount(ExtractionSpec spec) {
        return "SELECT count(*) FROM %s%s".formatted(spec.table(), where(spec));
    }

    private static String legacyChecksum(ExtractionSpec spec) {
        // CAST to text so the digest is taken over the same spelling the crosswalk
        // stores. A bigint key hashed as a bigint and as '4200' are different
        // digests, and the rule would fail on every run for a reason that has
        // nothing to do with the migration.
        return """
                SELECT encode(sha256(convert_to(
                           coalesce(string_agg(k, ',' ORDER BY k), ''), 'UTF8')), 'hex')
                FROM (SELECT CAST(%s AS text) AS k FROM %s%s) legacy_keys
                """.formatted(spec.stableKeyColumn(), spec.table(), where(spec));
    }

    private static String where(ExtractionSpec spec) {
        return spec.filter() == null ? "" : " WHERE (" + spec.filter() + ")";
    }

    private static Map<String, Object> crosswalkParameters(ReconciliationRule.RuleContext context) {
        return Map.of(
                "tenantId", context.tenantId(),
                "scopeId", context.scopeId(),
                "entityType", context.entityType());
    }

    /** Exact count of authoritative ids. */
    static final class IdCount implements ReconciliationRule {

        private final ExtractionSpec spec;
        private final MigrationCapability capability;

        IdCount(ExtractionSpec spec, MigrationCapability capability) {
            this.spec = spec;
            this.capability = capability;
        }

        @Override
        public String ruleCode() {
            return "AUTHORITATIVE_ID_COUNT";
        }

        @Override
        public int ruleVersion() {
            return 1;
        }

        @Override
        public MigrationCapability capability() {
            return capability;
        }

        @Override
        public String entityType() {
            return spec.entityType();
        }

        @Override
        public ReconciliationSeverity severity() {
            return ReconciliationSeverity.CRITICAL;
        }

        @Override
        public Measurement.MeasureKind measureKind() {
            return Measurement.MeasureKind.COUNT;
        }

        @Override
        public List<Measurement> evaluate(RuleContext context) {
            BigInteger expected =
                    context.legacy().exactInteger(legacyCount(spec), Map.of()).orElse(BigInteger.ZERO);
            BigInteger actual = context.target()
                    .exactInteger(CROSSWALK_COUNT, crosswalkParameters(context))
                    .orElse(BigInteger.ZERO);
            // count(*) is never null, so ZERO here is defensive rather than a
            // meaningful default — and defensive in the safe direction: a missing
            // legacy count reads as "nothing was there", which fails the rule
            // against any target that holds rows.
            return List.of(
                    new Measurement("", Measurement.MeasureKind.COUNT, expected, actual, null, null, null, null));
        }
    }

    /** Exact digest over the authoritative ids, ordered. */
    static final class IdChecksum implements ReconciliationRule {

        private final ExtractionSpec spec;
        private final MigrationCapability capability;

        IdChecksum(ExtractionSpec spec, MigrationCapability capability) {
            this.spec = spec;
            this.capability = capability;
        }

        @Override
        public String ruleCode() {
            return "AUTHORITATIVE_ID_CHECKSUM";
        }

        @Override
        public int ruleVersion() {
            return 1;
        }

        @Override
        public MigrationCapability capability() {
            return capability;
        }

        @Override
        public String entityType() {
            return spec.entityType();
        }

        @Override
        public ReconciliationSeverity severity() {
            return ReconciliationSeverity.CRITICAL;
        }

        @Override
        public Measurement.MeasureKind measureKind() {
            return Measurement.MeasureKind.CHECKSUM;
        }

        @Override
        public List<Measurement> evaluate(RuleContext context) {
            Optional<String> expected = context.legacy().text(legacyChecksum(spec), Map.of());
            Optional<String> actual = context.target().text(CROSSWALK_CHECKSUM, crosswalkParameters(context));
            if (expected.isEmpty() || actual.isEmpty()) {
                // Nothing measured, rather than a comparison that agreed. The
                // Measurement constructor would reject a null digest, and reporting
                // an empty list is what "this rule did not run" looks like.
                return List.of();
            }
            return List.of(Measurement.checksum("", expected.get(), actual.get()));
        }
    }
}
