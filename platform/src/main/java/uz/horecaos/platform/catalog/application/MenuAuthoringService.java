package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.BranchMenuBindingRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuItemRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuRow;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring the named {@code Menu} entity (row 4.4a).
 *
 * <p>See {@code catalog/infrastructure/persistence/JdbcMenuStore}'s own class
 * doc, and {@code V0389}/{@code V0390}'s own headers, for why this is
 * additive to ADR 0016's {@code location_offerings}/publication model — a
 * menu is a named, copyable, bindable <em>source</em> of per-branch offering
 * data, never a second publication mechanism. {@link StorefrontCatalogQuery}
 * is the one place that reads a binding at all; everything else here is
 * ordinary draft authoring, exactly as unpublished as
 * {@code CatalogAuthoringService}'s own tables.
 */
@Service
public class MenuAuthoringService {

    private final JdbcMenuStore menus;
    private final JdbcCatalogStore catalog;
    private final SalesChannelLookup channels;
    private final AuditRecorder audit;
    private final Clock clock;

    public MenuAuthoringService(
            JdbcMenuStore menus,
            JdbcCatalogStore catalog,
            SalesChannelLookup channels,
            AuditRecorder audit,
            Clock clock) {
        this.menus = menus;
        this.catalog = catalog;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ menus

    @Transactional
    public MenuRow createMenu(UUID tenantId, UUID brandId, String name, String actorSubject) {
        Instant now = clock.instant();
        MenuRow row = new MenuRow(Ids.newId(), tenantId, brandId, name, "DRAFT", 1, now);
        try {
            menus.insertMenu(row);
        } catch (DataIntegrityViolationException violation) {
            throw asApiException(JdbcMenuStore.explain(violation));
        }
        audit.record(AuditFact.of("catalog.menu.created", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", row.id())
                .because("Created a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("name", name))
                .correlatedBy(row.id().toString())
                .occurredAt(now)
                .build());
        return row;
    }

    public List<MenuRow> listMenus(UUID tenantId, UUID brandId) {
        return menus.list(tenantId, brandId);
    }

    public MenuRow requireMenu(UUID tenantId, UUID brandId, UUID menuId) {
        return menus.find(tenantId, brandId, menuId).orElseThrow(() -> new UnknownMenuException(menuId));
    }

    @Transactional
    public MenuRow updateMenu(
            UUID tenantId,
            UUID brandId,
            UUID menuId,
            String name,
            String status,
            int expectedVersion,
            String actorSubject) {
        MenuRow existing = requireMenu(tenantId, brandId, menuId);
        if (existing.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }
        Instant now = clock.instant();
        int newVersion;
        try {
            newVersion = menus.update(tenantId, brandId, menuId, name, status, expectedVersion, now)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_CONFLICT, "This menu was changed while this edit was being made"));
        } catch (DataIntegrityViolationException violation) {
            throw asApiException(JdbcMenuStore.explain(violation));
        }
        audit.record(AuditFact.of("catalog.menu.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Edited a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: a per-field diff — "name"/"status" were already
                // known before the write (existing), not only after it.
                .changed(ChangeDocuments.diff(
                        Map.of("name", existing.name(), "status", existing.status()),
                        Map.of("name", name, "status", status)))
                .correlatedBy(menuId.toString())
                .occurredAt(now)
                .build());
        return new MenuRow(menuId, tenantId, brandId, name, status, newVersion, existing.createdAt());
    }

    /**
     * A new menu carrying the same membership as {@code sourceMenuId} — the
     * gap map row's own "copy a menu" gesture, for rolling one assortment
     * out to a further branch without re-authoring it from scratch.
     */
    @Transactional
    public MenuRow copyMenu(UUID tenantId, UUID brandId, UUID sourceMenuId, String newName, String actorSubject) {
        requireMenu(tenantId, brandId, sourceMenuId);
        MenuRow copy = createMenu(tenantId, brandId, newName, actorSubject);
        int copied = menus.copyMembership(tenantId, brandId, sourceMenuId, copy.id());
        audit.record(AuditFact.of("catalog.menu.copied", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", copy.id())
                .because("Copied a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("sourceMenuId", sourceMenuId.toString(), "itemCount", copied))
                .correlatedBy(copy.id().toString())
                .occurredAt(clock.instant())
                .build());
        return copy;
    }

    // ------------------------------------------------------------- membership

    public List<MenuItemRow> listItems(UUID tenantId, UUID brandId, UUID menuId) {
        requireMenu(tenantId, brandId, menuId);
        return menus.listItems(tenantId, brandId, menuId);
    }

    @Transactional
    public void addItem(
            UUID tenantId,
            UUID brandId,
            UUID menuId,
            UUID variantId,
            int sortOrder,
            String availabilityDefault,
            String actorSubject) {
        requireMenu(tenantId, brandId, menuId);
        if (!catalog.entityExistsInBrand(tenantId, brandId, EntityType.VARIANT, variantId)) {
            throw new UnknownVariantException(variantId);
        }
        menus.upsertItem(tenantId, brandId, menuId, variantId, sortOrder, availabilityDefault);
        audit.record(AuditFact.of("catalog.menu.item-added", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Added a variant to a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("variantId", variantId.toString(), "availabilityDefault", availabilityDefault))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** Idempotent — removing a variant already off the menu still resolves. */
    @Transactional
    public void removeItem(UUID tenantId, UUID brandId, UUID menuId, UUID variantId, String actorSubject) {
        boolean removed = menus.deleteItem(tenantId, brandId, menuId, variantId);
        if (!removed) {
            return;
        }
        audit.record(AuditFact.of("catalog.menu.item-removed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Removed a variant from a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("variantId", variantId.toString()))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Row 4.4a's "add products by filtered select-all": every active
     * variant matching an optional category and/or search, added to the
     * menu in one command rather than one gesture per product. Tag
     * filtering is not offered — {@code catalog} carries no tag vocabulary
     * yet (gap map row 4.7 stays BLOCKED on the same unanswered ADR 0016
     * classification question this row's own membership table does not
     * touch), so a caller asking to filter by tag has nothing to filter
     * against; category and search cover what the schema can actually answer.
     *
     * @return how many variants were added or re-defaulted
     */
    @Transactional
    public int addByFilter(
            UUID tenantId,
            UUID brandId,
            UUID menuId,
            @Nullable UUID categoryId,
            @Nullable String search,
            String availabilityDefault,
            String locale,
            String actorSubject) {
        requireMenu(tenantId, brandId, menuId);
        if (categoryId != null && !catalog.entityExistsInBrand(tenantId, brandId, EntityType.CATEGORY, categoryId)) {
            throw new UnknownCategoryException(categoryId);
        }
        int added = menus.addByFilter(tenantId, brandId, menuId, categoryId, search, availabilityDefault, locale);
        audit.record(AuditFact.of("catalog.menu.items-added-by-filter", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Added products to a named menu by filter")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of(
                        "categoryId", categoryId == null ? "" : categoryId.toString(),
                        "search", search == null ? "" : search,
                        "itemCount", added))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
        return added;
    }

    // --------------------------------------------------------------- binding

    /**
     * Binds a menu to a branch, for one channel or — {@code channelId} null
     * — as the branch's default across every channel. Requires the menu to
     * be ACTIVE: {@link StorefrontCatalogQuery} reads a bound menu's
     * membership live, with no publish step of its own, so binding is the
     * one moment a menu goes from draft authoring to customer-facing, and it
     * has to happen deliberately. A fresh menu starts DRAFT (see {@code
     * MenuController#create}) and would otherwise go live with whatever
     * partial membership it happens to carry the instant an operator binds
     * it mid-edit; ARCHIVED is refused for the separate reason {@code
     * V0389}'s own header gives — archiving is a deliberate two-step, never
     * an implicit unbind, so a menu already bound stays bound when it is
     * archived, but a fresh bind cannot start from an archived one either.
     */
    @Transactional
    public void bindToBranch(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId, UUID menuId, String actorSubject) {
        MenuRow menu = requireMenu(tenantId, brandId, menuId);
        if (!"ACTIVE".equals(menu.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Cannot bind a menu to a branch unless it is ACTIVE (currently " + menu.status() + ")");
        }
        if (channelId != null && channels.byId(tenantId, channelId).isEmpty()) {
            throw new UnknownChannelException(channelId);
        }
        try {
            menus.upsertBinding(tenantId, brandId, locationId, channelId, menuId, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw asApiException(JdbcMenuStore.explain(violation));
        }
        audit.record(AuditFact.of("catalog.menu.bound", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Menu", menuId)
                .because("Bound a named menu to a branch")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("channelId", channelId == null ? "" : channelId.toString()))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** Idempotent — unbinding a scope that already carries no binding still resolves. */
    @Transactional
    public void unbindBranch(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId, String actorSubject) {
        boolean removed = menus.deleteBinding(tenantId, brandId, locationId, channelId);
        if (!removed) {
            return;
        }
        audit.record(AuditFact.of("catalog.menu.unbound", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Location", locationId)
                .because("Unbound a named menu from a branch")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("channelId", channelId == null ? "" : channelId.toString()))
                .correlatedBy(locationId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    public List<BranchMenuBindingRow> listBindings(UUID tenantId, UUID brandId) {
        return menus.listBindings(tenantId, brandId);
    }

    // --------------------------------------------------------------------- helpers

    private static ApiException asApiException(RuntimeException explained) {
        if (explained instanceof ApiException apiException) {
            return apiException;
        }
        ErrorCode code =
                explained instanceof IllegalStateException ? ErrorCode.RESOURCE_CONFLICT : ErrorCode.VALIDATION_FAILED;
        return new ApiException(code, explained.getMessage());
    }

    // --------------------------------------------------------------------- exceptions

    public static final class UnknownMenuException extends RuntimeException {
        public UnknownMenuException(UUID menuId) {
            super("No such menu " + menuId + " for this brand");
        }
    }

    public static final class UnknownVariantException extends RuntimeException {
        public UnknownVariantException(UUID variantId) {
            super("No such variant " + variantId + " for this brand");
        }
    }

    public static final class UnknownCategoryException extends RuntimeException {
        public UnknownCategoryException(UUID categoryId) {
            super("No such category " + categoryId + " for this brand");
        }
    }

    public static final class UnknownChannelException extends RuntimeException {
        public UnknownChannelException(UUID channelId) {
            super("No such sales channel " + channelId + " for this tenant");
        }
    }
}
