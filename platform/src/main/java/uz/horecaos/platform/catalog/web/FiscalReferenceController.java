package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    public FiscalReferenceController(JdbcCatalogStore catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Search the ИКПУ/MXIK reference by code or label",
            description = "Currently-valid rows only. Empty when the official list has never been "
                    + "imported — that is a named gap, not a search that found nothing to match.")
    Page<MxikReferenceRow> search(
            @RequestParam @Nullable String query, @RequestParam(required = false) @Nullable Integer limit) {
        if (query == null || query.trim().length() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "query must be at least 2 characters");
        }
        int pageSize = limit == null ? 20 : Math.clamp(limit, 1, MAXIMUM_RESULTS);
        return Page.last(catalog.searchMxikReference(query, pageSize));
    }

    @GetMapping("/status")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Whether the official ИКПУ/MXIK list has been imported at all")
    ReferenceStatus status() {
        return new ReferenceStatus(catalog.mxikReferenceIsLoaded());
    }

    /** @param loaded false means every classification lookup will find nothing, not that none exist */
    public record ReferenceStatus(boolean loaded) {}
}
