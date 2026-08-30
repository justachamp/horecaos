/// The storefront menu, exactly as the platform serves it.
///
/// Verified against `StorefrontCatalogQuery.StorefrontMenu` and the records
/// nested inside it, not against ADR 0016's prose. Every field here exists on
/// the wire and nothing here is computed.
///
/// **What the wire does not carry, and what this file therefore does not
/// invent:**
///
/// * **No price.** Neither a variant nor a modifier option carries an amount.
///   The catalog module states plainly that it holds no money — pricing owns
///   it — and the only endpoints that produce one are the quote and the cart
///   pricing call. So no screen built on this model may show a price, and none
///   does.
/// * **No product-to-category membership.** `catalog.category_products` exists
///   in the database and the publication snapshot does not carry it, so the
///   menu cannot say which category a product is in.
/// * **No product-to-modifier-group link.** `catalog.product_modifier_groups`
///   and `catalog.variant_modifier_groups` exist and are likewise not
///   published. `modifierGroups` is a flat, brand-wide list.
/// * **No display name for a variant or for a modifier option.** Translations
///   are published for categories, products and modifier groups only.
///
/// Each of those is reported upward rather than papered over: a decoder that
/// filled in a plausible value would make a missing contract look like a
/// present one.
library;

/// One published menu for one location, in one locale.
final class StorefrontMenu {
  const StorefrontMenu({
    required this.publicationId,
    required this.locale,
    required this.categories,
    required this.products,
    required this.modifierGroups,
  });

  /// The exact snapshot served. It is also the server's `ETag`, so two menus
  /// with the same publication identifier are the same menu.
  final String publicationId;

  /// The locale the server resolved names in — not necessarily the one asked
  /// for, because publication falls back to any published name rather than
  /// failing.
  final String locale;

  final List<MenuCategory> categories;
  final List<MenuProduct> products;

  /// Brand-wide, and not attached to any product. See the library comment.
  final List<MenuModifierGroup> modifierGroups;

  static StorefrontMenu fromJson(Map<String, Object?> json) => StorefrontMenu(
    publicationId: _requireString(json, 'publicationId'),
    locale: _requireString(json, 'locale'),
    categories: _list(json, 'categories', MenuCategory.fromJson),
    products: _list(json, 'products', MenuProduct.fromJson),
    modifierGroups: _list(json, 'modifierGroups', MenuModifierGroup.fromJson),
  );
}

/// A category as published. Ordered by [sortOrder], nested by
/// [parentCategoryId].
final class MenuCategory {
  const MenuCategory({
    required this.categoryId,
    required this.code,
    required this.name,
    required this.parentCategoryId,
    required this.sortOrder,
    required this.productIds,
  });

  final String categoryId;

  /// The authoring code. Never shown to a customer; it is here because the
  /// server sends it and because it is the only identifier a support
  /// conversation can use.
  final String? code;

  final String name;

  /// Null for a root category.
  final String? parentCategoryId;

  final int sortOrder;

  /// The products in this category, in the category's own order, already
  /// filtered by the server to what this location serves. Empty on a
  /// publication written before membership was carried.
  final List<String> productIds;

  static MenuCategory fromJson(Map<String, Object?> json) => MenuCategory(
    categoryId: _requireString(json, 'categoryId'),
    code: _optionalString(json, 'code'),
    name: _requireString(json, 'name'),
    parentCategoryId: _optionalString(json, 'parentCategoryId'),
    sortOrder: _intOr(json, 'sortOrder', 0),
    productIds: _strings(json, 'productIds'),
  );
}

/// A dish, with the variants this location offers.
///
/// A product the location does not offer at all is absent from the response
/// rather than present and unavailable; that distinction is the server's and is
/// not reproduced here.
final class MenuProduct {
  const MenuProduct({
    required this.productId,
    required this.code,
    required this.name,
    required this.description,
    required this.mediaAssetIds,
    required this.variants,
    required this.modifierGroupIds,
  });

  final String productId;
  final String? code;
  final String name;
  final String? description;

  /// Identifiers only. Turning one into an image needs
  /// `/api/v1/tenants/{tenantId}/media/assets/{assetId}/download-url`, which
  /// requires `MEDIA_READ` — a staff capability. There is no unauthenticated
  /// media URL, so this list is decoded and not rendered.
  final List<String> mediaAssetIds;

  final List<MenuVariant> variants;

  /// The modifier groups this product offers, product-level only. Empty on a
  /// publication written before the link was carried.
  final List<String> modifierGroupIds;

  /// Whether anything on this product can be ordered right now.
  ///
  /// Read off the server's own `orderable` flag per variant. Nothing else is
  /// consulted: a branch stopping an item is a fact the operations console
  /// records and the server states, and a client that inferred it from stock,
  /// price or opening hours would eventually disagree with the checkout.
  bool get isOrderable => variants.any((MenuVariant v) => v.orderable);

  /// The variant a screen should preselect: the authored default when it is
  /// orderable, otherwise the first orderable one, otherwise the default,
  /// otherwise the first.
  ///
  /// Null only when the product has no variants, which the server does not
  /// currently emit — a product with no offered variant is dropped entirely.
  MenuVariant? get preferredVariant {
    if (variants.isEmpty) return null;
    for (final MenuVariant variant in variants) {
      if (variant.isDefault && variant.orderable) return variant;
    }
    for (final MenuVariant variant in variants) {
      if (variant.orderable) return variant;
    }
    for (final MenuVariant variant in variants) {
      if (variant.isDefault) return variant;
    }
    return variants.first;
  }

  static MenuProduct fromJson(Map<String, Object?> json) => MenuProduct(
    productId: _requireString(json, 'productId'),
    code: _optionalString(json, 'code'),
    name: _requireString(json, 'name'),
    description: _optionalString(json, 'description'),
    mediaAssetIds: _strings(json, 'mediaAssetIds'),
    variants: _list(json, 'variants', MenuVariant.fromJson),
    modifierGroupIds: _strings(json, 'modifierGroupIds'),
  );
}

/// One orderable form of a product at this location.
final class MenuVariant {
  const MenuVariant({
    required this.variantId,
    required this.sku,
    required this.unitCode,
    required this.isDefault,
    required this.orderable,
  });

  final String variantId;

  /// Genuinely null for many variants, and never the string `"null"` — the
  /// server was fixed for exactly that.
  final String? sku;

  /// The unit the menu was authored in, such as `PIECE` or `GRAM`. Not a
  /// customer-facing label and not a fiscal unit code.
  final String? unitCode;

  final bool isDefault;

  /// False means shown and not orderable — "sold out" — rather than hidden.
  /// The server has already removed the variants this location does not offer.
  final bool orderable;

  static MenuVariant fromJson(Map<String, Object?> json) => MenuVariant(
    variantId: _requireString(json, 'variantId'),
    sku: _optionalString(json, 'sku'),
    unitCode: _optionalString(json, 'unitCode'),
    isDefault: json['isDefault'] == true,
    orderable: json['orderable'] == true,
  );
}

/// A choice the customer makes, with the server's own bounds on it.
final class MenuModifierGroup {
  const MenuModifierGroup({
    required this.modifierGroupId,
    required this.code,
    required this.name,
    required this.required,
    required this.minimumSelections,
    required this.maximumSelections,
    required this.allowSameOptionMultipleTimes,
    required this.options,
  });

  final String modifierGroupId;
  final String? code;
  final String name;

  final bool required;
  final int minimumSelections;
  final int maximumSelections;

  /// Whether one option may be taken more than once. Without it a client
  /// cannot honour an option's `maximumQuantity` and has to pin it to one.
  final bool allowSameOptionMultipleTimes;

  final List<MenuModifierOption> options;

  static MenuModifierGroup fromJson(Map<String, Object?> json) =>
      MenuModifierGroup(
        modifierGroupId: _requireString(json, 'modifierGroupId'),
        code: _optionalString(json, 'code'),
        name: _requireString(json, 'name'),
        required: json['required'] == true,
        minimumSelections: _intOr(json, 'minimumSelections', 0),
        maximumSelections: _intOr(json, 'maximumSelections', 0),
        allowSameOptionMultipleTimes:
            json['allowSameOptionMultipleTimes'] == true,
        options: _list(json, 'options', MenuModifierOption.fromJson),
      );
}

/// One option inside a group.
final class MenuModifierOption {
  const MenuModifierOption({
    required this.optionId,
    required this.code,
    required this.maximumQuantity,
  });

  final String optionId;

  /// The authoring code, and the only text the wire carries for an option.
  /// There is no published translation for a modifier option, so a screen has
  /// nothing customer-facing to render here.
  final String? code;

  /// How many of this option one line may carry.
  ///
  /// Decoded and deliberately not acted on. The group's
  /// `allowSameOptionMultipleTimes` flag decides whether a repeat is permitted
  /// at all, and the storefront projection drops it — so honouring a maximum of
  /// three here could offer a customer a selection the server would refuse.
  /// [ModifierSelection] therefore caps every option at one. When the flag is
  /// published, that cap becomes this number.
  final int maximumQuantity;

  static MenuModifierOption fromJson(Map<String, Object?> json) =>
      MenuModifierOption(
        optionId: _requireString(json, 'optionId'),
        code: _optionalString(json, 'code'),
        maximumQuantity: _intOr(json, 'maximumQuantity', 1),
      );
}

// ---------------------------------------------------------------- decoding

/// Throws rather than substituting a placeholder.
///
/// A menu that fails to decode is a bug someone fixes. A menu that decodes with
/// an empty product name is a bug a customer reports, weeks later, as "the app
/// showed a blank item".
String _requireString(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  if (value is String && value.isNotEmpty) return value;
  throw FormatException('Menu field "$key" was missing or not a string');
}

String? _optionalString(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  return value is String && value.isNotEmpty ? value : null;
}

int _intOr(Map<String, Object?> json, String key, int fallback) {
  final Object? value = json[key];
  return value is int ? value : fallback;
}

List<String> _strings(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  if (value is! List) return const <String>[];
  return value.whereType<String>().toList(growable: false);
}

List<T> _list<T>(
  Map<String, Object?> json,
  String key,
  T Function(Map<String, Object?>) decode,
) {
  final Object? value = json[key];
  if (value is! List) return <T>[];
  return value
      .whereType<Map<String, Object?>>()
      .map(decode)
      .toList(growable: false);
}
