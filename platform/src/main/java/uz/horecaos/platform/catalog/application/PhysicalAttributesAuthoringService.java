package uz.horecaos.platform.catalog.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcPhysicalAttributesStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcPhysicalAttributesStore.Stored;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Authoring a variant's physical and nutritional attributes (ADR 0137).
 *
 * <p>One upsert of the whole row under an expected version, not a field-by-field
 * patch: the facts are authored together on one tab and published as one payload,
 * and a patch would let two authors each change a half that is only valid with the
 * other.
 *
 * <p>The versioning has one convention worth stating, because the row is optional.
 * A variant with no row reads as {@code version 0}; writing the first row expects
 * {@code 0}, writing a later one expects what the read returned. Clearing the row
 * (an empty attribute set) removes it, so "no row" stays the single representation
 * of "a fixed unit sold whole".
 *
 * <p>The marking exclusion is deliberately not enforced here. ADR 0137 puts it in
 * {@code CatalogValidator} at publication, the one place every cross-domain
 * catalog rule is reconciled, because the fiscal classification it is checked
 * against is authored on a different screen at a different time.
 */
@Service
public class PhysicalAttributesAuthoringService {

    private final JdbcCatalogStore catalog;
    private final JdbcPhysicalAttributesStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public PhysicalAttributesAuthoringService(
            JdbcCatalogStore catalog, JdbcPhysicalAttributesStore store, AuditRecorder audit, Clock clock) {
        this.catalog = catalog;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What the editor reads: the attributes (null when the variant carries none)
     * and the version to quote when writing them.
     */
    public record View(@Nullable PhysicalAttributes attributes, int version) {}

    @Transactional(readOnly = true)
    public View read(UUID tenantId, UUID brandId, UUID variantId) {
        requireVariant(tenantId, brandId, variantId);
        return store.find(tenantId, brandId, variantId)
                .map(stored -> new View(stored.attributes(), stored.version()))
                .orElse(new View(null, 0));
    }

    /**
     * Replaces the variant's attributes.
     *
     * @param attributes the whole new set; null or {@link PhysicalAttributes#isEmpty() empty}
     *     clears the row
     * @param expectedVersion {@code 0} when the variant has no row
     * @throws StalePhysicalAttributesException when the version moved on
     */
    @Transactional
    public View replace(
            UUID tenantId,
            UUID brandId,
            UUID variantId,
            @Nullable PhysicalAttributes attributes,
            int expectedVersion,
            String actorSubject) {
        requireVariant(tenantId, brandId, variantId);

        Optional<Stored> current = store.find(tenantId, brandId, variantId);
        int currentVersion = current.map(Stored::version).orElse(0);
        if (currentVersion != expectedVersion) {
            throw new StalePhysicalAttributesException(expectedVersion, currentVersion);
        }

        View result;
        if (attributes == null || attributes.isEmpty()) {
            if (current.isEmpty()) {
                // Nothing stored and nothing asked for: no row, no audit fact.
                return new View(null, 0);
            }
            if (!store.delete(tenantId, brandId, variantId, expectedVersion)) {
                throw new StalePhysicalAttributesException(
                        expectedVersion, currentOrZero(tenantId, brandId, variantId));
            }
            result = new View(null, 0);
        } else if (current.isEmpty()) {
            int version = store.insert(tenantId, brandId, variantId, attributes)
                    .orElseThrow(() -> new StalePhysicalAttributesException(
                            expectedVersion, currentOrZero(tenantId, brandId, variantId)));
            result = new View(attributes, version);
        } else {
            int version = store.update(tenantId, brandId, variantId, attributes, expectedVersion)
                    .orElseThrow(() -> new StalePhysicalAttributesException(
                            expectedVersion, currentOrZero(tenantId, brandId, variantId)));
            result = new View(attributes, version);
        }

        audit.record(AuditFact.of("catalog.variantPhysicalAttributes.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("VariantPhysicalAttributes", variantId)
                .because(
                        result.attributes() == null ? "Cleared the physical attributes" : "Set the physical attributes")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(
                        summary(current.map(Stored::attributes).orElse(null)), summary(result.attributes())))
                .correlatedBy(variantId.toString())
                .occurredAt(clock.instant())
                .build());
        return result;
    }

    private int currentOrZero(UUID tenantId, UUID brandId, UUID variantId) {
        return store.find(tenantId, brandId, variantId).map(Stored::version).orElse(0);
    }

    private void requireVariant(UUID tenantId, UUID brandId, UUID variantId) {
        if (!catalog.entityExistsInBrand(tenantId, brandId, EntityType.VARIANT, variantId)) {
            throw new CatalogAuthoringService.UnknownCatalogEntityException(EntityType.VARIANT, variantId);
        }
    }

    /**
     * The attributes as an audit document: every field, so the diff names exactly
     * which ones changed. None of it is personal data (ADR 0029) -- it describes a
     * dish, not a person.
     */
    private static Map<String, Object> summary(@Nullable PhysicalAttributes attributes) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (attributes == null) {
            return summary;
        }
        summary.put("netWeightGrams", attributes.netWeightGrams());
        summary.put("netVolumeMillilitres", attributes.netVolumeMillilitres());
        summary.put("catchweight", attributes.catchweight());
        summary.put("catchweightQuantumGrams", attributes.catchweightQuantumGrams());
        summary.put("catchweightNominalGrams", attributes.catchweightNominalGrams());
        summary.put("splittable", attributes.splittable());
        summary.put(
                "portionSize",
                attributes.portionSize() == null
                        ? null
                        : attributes.portionSize().toPlainString());
        summary.put("caloriesKcalPer100", plain(attributes.caloriesKcalPer100()));
        summary.put("proteinGramsPer100", plain(attributes.proteinGramsPer100()));
        summary.put("fatGramsPer100", plain(attributes.fatGramsPer100()));
        summary.put("carbohydratesGramsPer100", plain(attributes.carbohydratesGramsPer100()));
        return summary;
    }

    private static @Nullable String plain(@Nullable BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** The version the caller quoted is no longer the stored one. */
    public static final class StalePhysicalAttributesException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int expected;
        private final int actual;

        public StalePhysicalAttributesException(int expected, int actual) {
            super("The physical attributes are at version " + actual + ", not " + expected);
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }
}
