package uz.horecaos.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregate reads against the legacy database, for reconciliation only (ADR
 * 0024).
 *
 * <p>Separate from {@link uz.horecaos.platform.migration.application.importing.LegacySourceReader}
 * because the two do different things and must keep different guarantees.
 * Extraction pages rows through a validated {@code ExtractionSpec} and never
 * takes SQL from a caller; reconciliation is a rule's own aggregate, written by
 * the rule's author and versioned with it, which cannot be expressed as a spec.
 * Merging them would put arbitrary SQL behind the interface the importer uses.
 *
 * <p>The SQL a rule passes here is part of the rule's declared version. It is
 * never assembled from input, and there is nothing here that would let it be.
 */
public interface LegacyQuery {

    /**
     * One exact integer, or empty when the query matched nothing.
     *
     * <p>{@link BigInteger}, never a long and never a double. A platform-wide
     * money total in minor units has more headroom than anyone wants to think
     * about during a cutover window, and a total that rounds is a total that
     * reconciles by accident.
     */
    Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters);

    /** One string — a digest, a status — or empty. */
    Optional<String> text(String sql, Map<String, Object> parameters);

    /**
     * Rows of a grouped aggregate, for the rules measured per dimension.
     *
     * <p>Values arrive as the driver produced them; a rule converts. Nothing here
     * coerces a numeric to a double on the way past, which is the one conversion
     * that would silently cost money.
     */
    List<Map<String, Object>> rows(String sql, Map<String, Object> parameters);
}
