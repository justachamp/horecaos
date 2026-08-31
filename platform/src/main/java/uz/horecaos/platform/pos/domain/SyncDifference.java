package uz.horecaos.platform.pos.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing the comparison had to say (ADR 0012).
 *
 * <p>A difference is a statement about a field, not an instruction. It records
 * what HorecaOS holds, what the provider sent, who owns the field under the run's
 * snapshotted policy, and what the engine recommends — and the recommendation is
 * advice that a review decision may override, never an action already taken.
 *
 * @param horecaosEntityId null for an {@code ADDITION}: the provider's entity has
 *                         not been created on the HorecaOS side yet, so there is
 *                         no id to carry
 * @param fieldPath        null for a whole-entity finding: an addition or a
 *                         removal signal is about the entity, not about one of
 *                         its fields
 * @param currentValue     what HorecaOS held at comparison time. Null when there
 *                         was nothing to hold — a new addition, or a whole-entity
 *                         finding
 * @param importedValue    what the provider sent. Null when the field itself is
 *                         absent at the provider, which {@link
 *                         DifferenceCategory#REMOVAL_SIGNAL} distinguishes from a
 *                         blank value
 * @param note             free-text detail for an operator. Null when the
 *                         category and the two values already say everything
 *                         worth saying
 */
public record SyncDifference(
        EntityType entityType,
        String externalEntityId,
        @Nullable UUID horecaosEntityId,
        DifferenceCategory category,
        @Nullable String fieldPath,
        @Nullable String currentValue,
        @Nullable String importedValue,
        FieldAuthority authority,
        Severity severity,
        RecommendedAction recommendedAction,
        @Nullable String note) {

    public enum EntityType {
        PRODUCT,
        VARIANT,
        CATEGORY,
        MODIFIER_GROUP,
        MODIFIER,
        AVAILABILITY
    }

    public enum DifferenceCategory {

        /** The provider has something HorecaOS does not. May become a draft. */
        ADDITION,

        /** A field the provider owns changed, and applying it is safe. */
        AUTHORIZED_CHANGE,

        /**
         * A field HorecaOS owns differs from the provider's value.
         *
         * <p>Never applied. This is the category that protects a curated name, a
         * photographed dish, and a price somebody set deliberately, and the
         * provider disagreeing with it is information rather than an instruction.
         */
        PROTECTED_FIELD_CHANGE,

        /**
         * The provider no longer lists something HorecaOS has mapped.
         *
         * <p>A signal, and it is only ever raised once two consecutive runs agree
         * — see {@link RemovalQuorum}. Even then it never deletes: the strongest
         * approved action is suspending an offering or retiring a mapping.
         */
        REMOVAL_SIGNAL,

        /** The mapping is ambiguous. Stops rather than resolves. */
        MAPPING_CONFLICT,

        /** The provider sent something that cannot be interpreted. */
        INVALID_SOURCE,

        /** Recorded so a run can prove it looked, rather than merely not complaining. */
        NO_CHANGE
    }

    /** Who owns a field under the run's snapshotted policy version. */
    public enum FieldAuthority {

        /** HorecaOS decides. The provider's value is evidence and never applied. */
        HORECAOS,

        /** The provider decides. Safe to apply automatically. */
        PROVIDER,

        /** Reconciliation decides: external identifiers and their mappings. */
        MAPPING,

        /** The provider proposes and a person decides. */
        REVIEWED_IMPORT
    }

    public enum Severity {
        INFO,
        WARNING,
        BLOCKING
    }

    public enum RecommendedAction {

        /** Safe without a human. Only mappings and explicitly provider-owned fields. */
        AUTO_APPLY,

        /** A person decides. */
        REVIEW,

        /** Recorded and deliberately not acted on. */
        IGNORE,

        /**
         * The run cannot proceed on this entity. A conflict, not a difference of
         * opinion about a value.
         */
        STOP
    }
}
