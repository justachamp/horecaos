package uz.horecaos.platform.pos.domain;

import java.util.List;

/**
 * Something the comparison refused to decide (ADR 0012).
 *
 * <p>A conflict is not a severe difference. A difference says two systems hold
 * different values for a field somebody owns; a conflict says the engine cannot
 * establish which rows are even being compared, and no amount of review of the
 * values will help until that is settled. ADR 0012's rule is that conflicts stop
 * rather than resolve, which produces manual work in exchange for never
 * corrupting a catalog silently.
 *
 * @param candidateEntityIds what the engine could not choose between. Present
 *                           because "ambiguous" is not actionable and "ambiguous
 *                           between these two" is
 */
public record SyncConflict(
        SyncDifference.EntityType entityType,
        String externalEntityId,
        Kind kind,
        String detail,
        List<String> candidateEntityIds) {

    public SyncConflict {
        candidateEntityIds = List.copyOf(candidateEntityIds == null ? List.of() : candidateEntityIds);
    }

    public enum Kind {

        /**
         * The provider sent one identifier twice in one snapshot.
         *
         * <p>Almost always a paging fault rather than provider corruption: an
         * offset walk over a catalog being edited can return the same row on two
         * pages as easily as it can skip one. Either way the snapshot is not a
         * consistent picture and the engine must not diff against it.
         */
        DUPLICATE_EXTERNAL_ID,

        /** More than one HorecaOS entity claims this external identifier. */
        AMBIGUOUS_TARGET,

        /** A child arrived whose parent is not in the snapshot. */
        MISSING_PARENT,

        /** The referenced entity belongs to another brand. */
        CROSS_BRAND_REFERENCE,

        /**
         * The provider's model cannot express what arrived.
         *
         * <p>The one the first real provider produces most. Its modifiers attach
         * only to a dish, so a modifier group on any other kind is a structure
         * that cannot be represented — and dropping it silently would leave a
         * customer unable to order something the restaurant sells, with no record
         * anywhere of why.
         */
        UNREPRESENTABLE_STRUCTURE
    }
}
