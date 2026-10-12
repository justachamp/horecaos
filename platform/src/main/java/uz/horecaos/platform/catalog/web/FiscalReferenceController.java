package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.MxikReferenceStatusService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.MxikReferenceRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Browsing the ИКПУ/MXIK product classification reference itself
 * (control-plane IA 6.2), as distinct from setting one catalog item's
 * classification.
 *
 * <p>{@code CatalogAuthoringController}'s own {@code fiscal-classification}
 * endpoints (ADR 0038) let a tenant's catalog author assign a code to one
 * fee, variant, or modifier option; none of them let anyone browse or search
 * the code list those assignments draw from. This controller is that list,
 * read without a tenant in the path because {@code catalog.mxik_reference} is
 * a national dataset the platform imports once, not a tenant's own data.
 */
@RestController
@RequestMapping("/api/v1/control-plane/fiscal-reference/mxik")
@Tag(name = "Fiscal reference", description = "The ИКПУ/MXIK product classification reference")
public class FiscalReferenceController {

    private static final int MAXIMUM_RESULTS = 100;

    private final JdbcCatalogStore catalog;
    private final MxikReferenceStatusService statuses;

    public FiscalReferenceController(JdbcCatalogStore catalog, MxikReferenceStatusService statuses) {
        this.catalog = catalog;
        this.statuses = statuses;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Search the ИКПУ/MXIK reference by code or label",
            description = "Currently-valid rows only, by the one definition of valid (ADR 0173): valid_from "
                    + "<= today and today < valid_until. With asOf (a date) the search is judged on that date "
                    + "and also returns the codes withdrawn by then, each marked withdrawn with the successor "
                    + "the list names; without it a row says nothing about withdrawal. Empty when the official "
                    + "list has never been imported — that is a named gap, not a search that found nothing to "
                    + "match; status says which.")
    Page<MxikReferenceRow> search(
            @RequestParam @Nullable String query,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable LocalDate asOf) {
        if (query == null || query.trim().length() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "query must be at least 2 characters");
        }
        int pageSize = limit == null ? 20 : Math.clamp(limit, 1, MAXIMUM_RESULTS);
        return Page.last(catalog.searchMxikReference(query, pageSize, asOf));
    }

    @GetMapping("/status")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Whether the official ИКПУ/MXIK list is loaded, which dataset it is, and how old",
            description = "loaded false means every classification lookup will find nothing, not that none "
                    + "exist. Once loaded: the dataset version, when it was imported, the source's own date "
                    + "if the administrator entered it, the valid and withdrawn row counts, its age in days "
                    + "and whether that exceeds catalog.mxik_reference_max_age_days.")
    ReferenceStatus status() {
        return ReferenceStatus.of(statuses.status());
    }

    /**
     * @param loaded false means every classification lookup will find nothing, not that none exist
     */
    public record ReferenceStatus(
            boolean loaded,
            @Nullable Integer datasetVersion,
            @Nullable Instant importedAt,
            @Nullable LocalDate sourcePublishedOn,
            int validRows,
            int withdrawnRows,
            @Nullable Long ageDays,
            @Nullable Integer maxAgeDays,
            boolean stale) {

        static ReferenceStatus of(MxikReferenceStatusService.Status status) {
            return new ReferenceStatus(
                    status.loaded(),
                    status.datasetVersion(),
                    status.importedAt(),
                    status.sourcePublishedOn(),
                    status.validRows(),
                    status.withdrawnRows(),
                    status.ageDays(),
                    status.maxAgeDays(),
                    status.stale());
        }
    }
}
