/// The catalogue: home, categories, and product detail.
///
/// One import for a host. Everything a screen outside this feature should know
/// about is exported here; nothing else in the directory is meant to be reached
/// from outside it.
///
/// ## What the server actually serves
///
/// One endpoint, unauthenticated, cacheable for thirty seconds:
///
/// ```
/// GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}
///     /locations/{locationId}/menu?locale={ru|uz|en}
/// ```
///
/// It answers with the active publication plus the location's live offerings —
/// a variant the branch does not offer is absent, one it has stopped is present
/// and `orderable: false`. That flag is the whole of availability in this
/// feature and nothing recomputes it.
///
/// ## What it does not serve, and what this feature therefore does not show
///
/// * **No price.** Nowhere in the menu. Catalog holds no money by design;
///   pricing does, and the only endpoints that produce an amount are the quote
///   and the cart pricing call.
/// * **No product-to-category membership**, so category browse is built and
///   switched off rather than shipped empty.
/// * **No product-to-modifier-group link**, so product detail shows variants
///   and no groups.
/// * **No display name for a variant or a modifier option.**
///
/// Each is stated where it bites, in the file that would otherwise have had to
/// invent something.
library;

export 'catalogue_home.dart' show CatalogueHome;
export 'data/catalogue_scope.dart' show CatalogueScope;
export 'data/menu.dart'
    show
        MenuCategory,
        MenuModifierGroup,
        MenuModifierOption,
        MenuProduct,
        MenuVariant,
        StorefrontMenu;
export 'data/menu_index.dart' show MenuIndex;
export 'data/menu_repository.dart' show MenuRepository;
export 'data/pickup_location.dart' show PickupLocation, PickupSearchPoint;
export 'data/pickup_location_repository.dart' show PickupLocationRepository;
export 'domain/modifier_selection.dart'
    show
        ModifierGroupHealth,
        ModifierGroupRules,
        ModifierGroupState,
        ModifierSelection,
        ModifierSelectionProblem,
        ProductConfiguration;
export 'catalogue_controller.dart'
    show
        CatalogueController,
        MenuFailed,
        MenuFailureKind,
        MenuLoading,
        MenuReady,
        MenuState;
export 'pickup_location_controller.dart'
    show
        PickupLocationsController,
        PickupLocationsFailed,
        PickupLocationsFailureKind,
        PickupLocationsLoading,
        PickupLocationsReady,
        PickupLocationsState;
export 'storefront_home.dart' show StorefrontHome;
export 'ui/category_screen.dart' show CategoryScreen;
export 'ui/menu_screen.dart' show MenuScreen;
export 'ui/product_detail_screen.dart' show ProductDetailScreen;
export 'ui/pickup_location_picker.dart' show PickupLocationPicker;
