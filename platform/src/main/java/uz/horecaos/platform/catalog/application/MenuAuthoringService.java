package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.api.CatalogNameLocales;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.BranchMenuBindingRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuItemRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuRow;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
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
    private final BrandLocaleLookup brandLocales;
    private final String defaultLocale;

    /**
     * @param brandLocales  the brand's own default language, which a membership list names its
     *                      products in first (row 10.12)
     * @param defaultLocale {@code horecaos.catalog.default-locale} -- where a name is looked for
     *                      when the brand's own default language has none
     */
    @Autowired
    public MenuAuthoringService(
            JdbcMenuStore menus,
            JdbcCatalogStore catalog,
            SalesChannelLookup channels,
            AuditRecorder audit,
            Clock clock,
            BrandLocaleLookup brandLocales,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.menus = menus;
        this.catalog = catalog;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
        this.brandLocales = brandLocales;
        this.defaultLocale = defaultLocale;
    }

    /** A menu authoring service for callers with no tenancy to ask: no brand has a default of its own. */
    public MenuAuthoringService(
            JdbcMenuStore menus,
            JdbcCatalogStore catalog,
            SalesChannelLookup channels,
            AuditRecorder audit,
            Clock clock) {
        this(menus, catalog, channels, audit, clock, BrandLocaleLookup.platformFallback(), "uz");
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
                // Staff 9.3a: a brand-new menu, no prior state to diff against.
                .changed(ChangeDocuments.created(Map.of("name", name, "status", row.status())))
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
                // Staff 9.3a: a brand-new menu (createMenu, above, already
                // wrote its own creation fact); this one records what it was
                // copied from.
                .changed(ChangeDocuments.created(Map.of("sourceMenuId", sourceMenuId.toString(), "itemCount", copied)))
                .correlatedBy(copy.id().toString())
                .occurredAt(clock.instant())
                .build());
        return copy;
    }

    // ------------------------------------------------------------- membership

    /**
     * A menu's membership, each product named in the brand's own default language and, where it
     * has no name there, in the server's configured one (row 10.12) -- the same order the
     * console's list screens read a name in.
     */
    public List<MenuItemRow> listItems(UUID tenantId, UUID brandId, UUID menuId) {
        requireMenu(tenantId, brandId, menuId);
        return membership(tenantId, brandId, menuId);
    }

    private List<MenuItemRow> membership(UUID tenantId, UUID brandId, UUID menuId) {
        CatalogNameLocales locales = CatalogNameLocales.of(brandLocales, tenantId, brandId, defaultLocale);
        return menus.listItems(tenantId, brandId, menuId, locales.preferred(), locales.fallback());
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
        // Staff 9.3a: read this variant's current membership row before the
        // upsert below overwrites it -- empty distinguishes a fresh add from
        // a re-default.
        Optional<MenuItemRow> before = membership(tenantId, brandId, menuId).stream()
                .filter(item -> item.variantId().equals(variantId))
                .findFirst();
        menus.upsertItem(tenantId, brandId, menuId, variantId, sortOrder, availabilityDefault);
        Map<String, Object> beforeFields = before.isEmpty()
                ? Map.of()
                : Map.of(
                        "variantId",
                        variantId.toString(),
                        "sortOrder",
                        before.get().sortOrder(),
                        "availabilityDefault",
                        before.get().availabilityDefault());
        Map<String, Object> afterFields = Map.of(
                "variantId", variantId.toString(), "sortOrder", sortOrder, "availabilityDefault", availabilityDefault);
        audit.record(AuditFact.of("catalog.menu.item-added", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Added a variant to a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: diff(Map.of(), after) is created(after) --
                // before.isEmpty() means this call added a fresh row.
                .changed(ChangeDocuments.diff(beforeFields, afterFields))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** Idempotent — removing a variant already off the menu still resolves. */
    @Transactional
    public void removeItem(UUID tenantId, UUID brandId, UUID menuId, UUID variantId, String actorSubject) {
        // Staff 9.3a: read the row before deleteItem removes it.
        Optional<MenuItemRow> before = membership(tenantId, brandId, menuId).stream()
                .filter(item -> item.variantId().equals(variantId))
                .findFirst();
        boolean removed = menus.deleteItem(tenantId, brandId, menuId, variantId);
        if (!removed) {
            return;
        }
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("variantId", variantId.toString());
        beforeDoc.put(
                "availabilityDefault",
                before.map(MenuItemRow::availabilityDefault).orElse(null));
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("variantId", null);
        afterDoc.put("availabilityDefault", null);
        audit.record(AuditFact.of("catalog.menu.item-removed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Menu", menuId)
                .because("Removed a variant from a named menu")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(beforeDoc, afterDoc))
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
                // Staff 9.3a: a bulk gesture over however many rows the filter
                // matched -- the filter and the count it produced are the
                // fact, not a diffable field on any one entity.
                .changed(ChangeDocuments.created(Map.of(
                        "categoryId", categoryId == null ? "" : categoryId.toString(),
                        "search", search == null ? "" : search,
                        "itemCount", added)))
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
        // Staff 9.3a: read this exact scope's current binding before the
        // upsert below replaces it -- empty means the scope was unbound.
        Optional<UUID> before = menus.findBinding(tenantId, brandId, locationId, channelId);
        try {
            menus.upsertBinding(tenantId, brandId, locationId, channelId, menuId, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw asApiException(JdbcMenuStore.explain(violation));
        }
        String channelScope = channelId == null ? "" : channelId.toString();
        Map<String, Object> beforeFields =
                before.isEmpty() ? Map.of() : Map.of("menuId", before.get().toString(), "channelId", channelScope);
        Map<String, Object> afterFields = Map.of("menuId", menuId.toString(), "channelId", channelScope);
        audit.record(AuditFact.of("catalog.menu.bound", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Menu", menuId)
                .because("Bound a named menu to a branch")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: diff(Map.of(), after) is created(after) --
                // before.isEmpty() means this scope was unbound.
                .changed(ChangeDocuments.diff(beforeFields, afterFields))
                .correlatedBy(menuId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** Idempotent — unbinding a scope that already carries no binding still resolves. */
    @Transactional
    public void unbindBranch(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId, String actorSubject) {
        // Staff 9.3a: read which menu this scope was bound to before
        // deleteBinding removes the row.
        Optional<UUID> before = menus.findBinding(tenantId, brandId, locationId, channelId);
        boolean removed = menus.deleteBinding(tenantId, brandId, locationId, channelId);
        if (!removed) {
            return;
        }
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("menuId", before.map(UUID::toString).orElse(null));
        beforeDoc.put("channelId", channelId == null ? "" : channelId.toString());
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("menuId", null);
        afterDoc.put("channelId", channelId == null ? "" : channelId.toString());
        audit.record(AuditFact.of("catalog.menu.unbound", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Location", locationId)
                .because("Unbound a named menu from a branch")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(beforeDoc, afterDoc))
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
