package uz.qoida.platform.tenancy.infrastructure.migration;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uz.qoida.platform.migration.api.ExtractionSpec;
import uz.qoida.platform.migration.api.ImportPort;
import uz.qoida.platform.migration.api.LegacyRecord;
import uz.qoida.platform.migration.api.Transformation;
import uz.qoida.platform.migration.api.TransformationOutcome;
import uz.qoida.platform.tenancy.api.TenantId;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.BrandView;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.CreateBrandCommand;

/**
 * Legacy {@code companies} become Qoida brands (ADR 0024, wave 1).
 *
 * <p>The reference import port, and the one that proves the contract: it writes
 * through {@link TenantControlPlaneService#createBrand} and holds no SQL. Every
 * invariant that service enforces — the slug's shape, the code and slug being
 * unique inside the tenant, the ADR 0027 audit row — applies to an imported brand
 * exactly as it applies to one an operator creates. That is the whole reason ADR
 * 0024 rejected change capture writing into target tables.
 *
 * <p>It is also the port that demonstrates the suppression is load-bearing:
 * {@code createBrand} publishes a {@code BrandCreated} event, {@code
 * TenancyOutboxEventListener} would append it to the outbox, and the relay would
 * put one on {@code tenancy.events} for every company in the estate. Under
 * {@link uz.qoida.platform.migration.api.ImportContext} the append is skipped and
 * the brand row, the audit row and the crosswalk row are all still written.
 *
 * <p><strong>It lives in {@code tenancy} and not in {@code migration}, and that
 * placement is the contract made structural.</strong> Spring Modulith refuses a
 * dependency from {@code migration} onto {@code tenancy.application}, so a port
 * written on the migration side physically cannot call the domain service — it
 * would have to reach for {@code JdbcClient}, which is the ad hoc target SQL ADR
 * 0024 rejected. Putting the port beside the service it calls turns "an import
 * writes through a domain service" from a rule somebody has to remember into one
 * the module verification enforces. {@link ImportPort} is exposed from {@code
 * migration.api} for exactly this: every wave's port belongs to the module that
 * owns the aggregate it writes.
 *
 * <p>The settled mapping fact behind this, from {@code
 * docs/domains/legacy-profile-findings.md}: <strong>all companies map to one
 * Qoida tenant and each company becomes a brand.</strong> The tenant comes from
 * the scope, so this port never chooses one — a row whose tenant cannot be proved
 * is a quarantine, never a convenient default.
 */
@Component
public class LegacyBrandImportPort implements ImportPort<LegacyBrandImportPort.BrandCommand> {

    private final TenantControlPlaneService tenancy;
    private final Transformation<BrandCommand> transformation = new BrandTransformation();

    public LegacyBrandImportPort(TenantControlPlaneService tenancy) {
        this.tenancy = tenancy;
    }

    @Override
    public ExtractionSpec extraction() {
        // `updated` is the watermark: the legacy BaseModel sets it on every write,
        // which is what makes an incremental catch-up possible at all. `image` and
        // `background_image` are deliberately not selected — media moves under ADR
        // 0010 with its own scan and verification, and a column nothing maps is a
        // column somebody maps by accident.
        return new ExtractionSpec(
                "BRAND", "companies", "id", "updated",
                List.of("id", "slug", "name", "updated"),
                null);
    }

    @Override
    public Transformation<BrandCommand> transformation() {
        return transformation;
    }

    @Override
    public ImportResult importOne(ImportTarget target, BrandCommand command) {
        TenantId tenantId = new TenantId(target.tenantId());

        // Idempotence on the crosswalk first. A page retried after a lost commit
        // presents the same company with the mapping already pointing at a brand,
        // and calling createBrand again would fail on the code-or-slug conflict —
        // turning a safe retry into a failed run.
        if (!target.isFirstImport()) {
            return ImportResult.unchanged(target.existingTargetId(), null);
        }

        // The second line of defence, for the case the crosswalk cannot cover: a
        // brand created by hand before the import, or a rebuilt control plane. The
        // conflict createBrand raises is a 409 an operator would have to resolve by
        // reading two systems, where converging on the existing brand is both
        // correct and what the crosswalk would have said.
        Optional<BrandView> existing = tenancy.getBrands(tenantId).stream()
                .filter(brand -> brand.code().equals(command.code()))
                .findFirst();
        if (existing.isPresent()) {
            return ImportResult.unchanged(existing.get().id(), null);
        }

        BrandView created = tenancy.createBrand(tenantId,
                new CreateBrandCommand(command.code(), command.slug(), command.displayName()));
        return ImportResult.created(created.id(), null);
    }

    /** What one legacy company says about the brand it becomes. */
    public record BrandCommand(String code, String slug, String displayName) { }

    /**
     * The mapping, version 1.
     *
     * <p>{@link #rules()} is the versioned artefact and not documentation: the
     * digest is taken over it, so a change here without a version bump is refused
     * by {@code TransformationRegistry} rather than discovered by a reconciliation
     * months later. Every decision that can change an output has a line.
     */
    static final class BrandTransformation implements Transformation<BrandCommand> {

        @Override
        public String entityType() {
            return "BRAND";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public List<String> rules() {
            return List.of(
                    "companies.id is the crosswalk legacy id, as text",
                    "companies.slug becomes both the brand code, upper-cased, and the brand slug",
                    "companies.slug is required: companies.slug carries no unique constraint in "
                            + "the legacy schema, so a blank one cannot be made into a code",
                    "companies.name is a JSONB language map and is not parsed here; the display "
                            + "name is the slug until the locale policy for brand names is decided",
                    "no company is assigned a tenant: the scope supplies it, and a row whose "
                            + "tenant cannot be proved is quarantined rather than defaulted",
                    "companies.image and background_image are not migrated by this version; media "
                            + "moves under ADR 0010 with its own scan and verification");
        }

        @Override
        public TransformationOutcome<BrandCommand> transform(LegacyRecord record, ZoneId sourceZone) {
            String slug = record.text("slug");
            if (slug == null || slug.isBlank()) {
                // `companies.slug` has no unique constraint and no not-null in the
                // legacy schema, and `customers.company` is matched against it by
                // string equality. A blank slug is therefore a company nothing can
                // be crosswalked to, which is a quarantine and not a default.
                return TransformationOutcome.quarantine("MISSING_BRAND_SLUG", null);
            }

            String normalized = slug.strip().toLowerCase(Locale.ROOT);
            if (!normalized.matches("^[a-z0-9][a-z0-9-]{0,62}$")) {
                return TransformationOutcome.quarantine("UNMAPPABLE_BRAND_SLUG", null);
            }

            // The display name is the slug, and that is a stated gap rather than an
            // oversight. `companies.name` is free JSONB carrying a language-presence
            // validator and nothing that checks it against the key, so choosing one
            // locale's value as the brand's display name is a product decision. The
            // slug is at least the string the legacy application itself matches
            // customers on.
            return TransformationOutcome.of(new BrandCommand(
                    normalized.toUpperCase(Locale.ROOT).replace('-', '_'),
                    normalized,
                    normalized));
        }
    }
}
