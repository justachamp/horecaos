package uz.horecaos.platform.catalog.domain;

import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;

/**
 * One reason a catalog cannot be published, or one thing worth knowing about it
 * (ADR 0016).
 *
 * <p>Codes are stable strings rather than messages, so an operator UI can
 * translate them and a support engineer can search for them. The entity path is
 * included because "a product has no variant" is unactionable without knowing
 * which product; it is null only for findings about the catalog as a whole.
 */
public record ValidationFinding(
        Severity severity, String code, EntityType entityType, UUID entityId, String entityCode, String detail) {

    public enum Severity {
        /** Publication is refused. */
        BLOCKER,
        /** Publication proceeds; someone should look. */
        WARNING
    }

    public static ValidationFinding blocker(String code, EntityType type, UUID id, String entityCode, String detail) {
        return new ValidationFinding(Severity.BLOCKER, code, type, id, entityCode, detail);
    }

    public static ValidationFinding warning(String code, EntityType type, UUID id, String entityCode, String detail) {
        return new ValidationFinding(Severity.WARNING, code, type, id, entityCode, detail);
    }

    /** The outcome of validating a whole catalog. */
    public record Report(List<ValidationFinding> findings) {

        public List<ValidationFinding> blockers() {
            return findings.stream()
                    .filter(f -> f.severity() == Severity.BLOCKER)
                    .toList();
        }

        public boolean publishable() {
            return blockers().isEmpty();
        }
    }
}
