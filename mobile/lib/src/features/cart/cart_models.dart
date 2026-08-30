import '../../format/money.dart';

/// How the order leaves the location (`uz.qoida.platform.tenancy.api.FulfillmentMode`).
///
/// Fixed when the cart is opened and never changed on it: `CartService.create`
/// takes the mode, and nothing in ADR 0019 moves a cart between modes. Changing
/// mode is opening another cart, and the checkout screen says so rather than
/// offering a control that would silently drop the basket.
enum FulfilmentMode {
  delivery('DELIVERY'),
  pickup('PICKUP'),
  dineIn('DINE_IN');

  const FulfilmentMode(this.wireName);

  /// The exact spelling the platform's enum uses. Not `name.toUpperCase()`:
  /// `dineIn` would become `DINEIN` and the request would fail validation.
  final String wireName;

  static FulfilmentMode? fromWire(String? value) {
    for (final FulfilmentMode mode in FulfilmentMode.values) {
      if (mode.wireName == value) return mode;
    }
    return null;
  }
}

/// The cart lifecycle, kept as the server's string rather than a Dart enum.
///
/// Same reasoning as `ApiErrorCode` in the API package: ADR 0031 evolves a major
/// version additively, so a new state must not crash a client that has not
/// shipped yet. Only the one question this application asks — may the customer
/// still edit it — is decided here.
final class CartStatus {
  const CartStatus(this.value);

  final String value;

  static const CartStatus active = CartStatus('ACTIVE');
  static const CartStatus checkoutInProgress = CartStatus('CHECKOUT_IN_PROGRESS');
  static const CartStatus converted = CartStatus('CONVERTED');
  static const CartStatus expired = CartStatus('EXPIRED');
  static const CartStatus abandoned = CartStatus('ABANDONED');

  /// `CartStatus.editable()` on the platform: `ACTIVE` and nothing else.
  bool get isEditable => value == active.value;

  /// The cart became an order. Not a failure — the customer's basket succeeded.
  bool get isConverted => value == converted.value;

  @override
  bool operator ==(Object other) => other is CartStatus && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value;
}

/// One line, exactly as `CartLineResponse` sends it.
///
/// There is no name and no price here, and that is the server's shape rather
/// than an omission in the decoder: the cart endpoint returns the line key, the
/// variant, the quantity and whether a note exists. Names are resolved against
/// the published menu (see `cart_item_naming.dart`) and the money comes from the
/// quote, because those are the two places that own them.
final class CartLine {
  const CartLine({
    required this.lineKey,
    required this.variantId,
    required this.quantity,
    required this.hasCustomerNote,
    this.modifierOptionIds = const <String>[],
  });

  /// Stable within the cart, and the identifier every mutation addresses.
  final String lineKey;

  final String variantId;
  final int quantity;

  /// Whether a note exists, never the note itself. The text is personal data
  /// under ADR 0029 and the storefront endpoint deliberately does not return it.
  final bool hasCustomerNote;

  /// What this client last sent for the line.
  ///
  /// Not echoed by `CartLineResponse`, so it survives only in memory and is
  /// empty after a cold read of a cart. Kept because a quantity change has to
  /// re-send the modifier selection — `PUT /lines/{lineKey}` replaces the line
  /// rather than patching it, so posting an empty list to change a quantity
  /// would quietly strip the customer's choices.
  final List<String> modifierOptionIds;

  factory CartLine.fromJson(Map<String, Object?> json) => CartLine(
    lineKey: json['lineKey']! as String,
    variantId: json['variantId']! as String,
    quantity: (json['quantity']! as num).toInt(),
    hasCustomerNote: json['hasCustomerNote'] as bool? ?? false,
  );

  CartLine withModifiers(List<String> modifierOptionIds) => CartLine(
    lineKey: lineKey,
    variantId: variantId,
    quantity: quantity,
    hasCustomerNote: hasCustomerNote,
    modifierOptionIds: modifierOptionIds,
  );
}

/// A cart, as `CartResponse` sends it.
final class Cart {
  const Cart({
    required this.cartId,
    required this.locationId,
    required this.status,
    required this.currency,
    required this.version,
    required this.lines,
    this.quoteId,
    this.contextHash,
    this.expiresAt,
  });

  final String cartId;
  final String locationId;
  final CartStatus status;
  final String currency;

  /// The aggregate version, and the `If-Match` for the next mutation.
  ///
  /// Taken from the body rather than the `ETag`, because `POST /carts` returns
  /// no `ETag` and the field is present on every response. One source, so a
  /// screen never has to know which endpoint it came from.
  final int version;

  final List<CartLine> lines;

  /// The quote bound to this cart, or null when there is none.
  ///
  /// Every edit clears it in the same statement that bumps the version — the
  /// platform does that, not this client — so a non-null value here means the
  /// price on screen belongs to the basket on screen.
  final String? quoteId;
  final String? contextHash;

  /// When the cart itself lapses (four hours). Not the quote's expiry, which is
  /// fifteen minutes and lives on [PricedCart].
  final DateTime? expiresAt;

  bool get isEmpty => lines.isEmpty;
  bool get isPriced => quoteId != null && contextHash != null;

  bool isExpiredAt(DateTime now) =>
      expiresAt != null && !expiresAt!.isAfter(now);

  factory Cart.fromJson(Map<String, Object?> json) => Cart(
    cartId: json['cartId']! as String,
    locationId: json['locationId']! as String,
    status: CartStatus(json['status']! as String),
    currency: json['currency']! as String,
    version: (json['version']! as num).toInt(),
    quoteId: json['quoteId'] as String?,
    contextHash: json['contextHash'] as String?,
    expiresAt: _instant(json['expiresAt']),
    lines: <CartLine>[
      for (final Object? line in (json['lines'] as List<Object?>? ?? const <Object?>[]))
        if (line is Map<String, Object?>) CartLine.fromJson(line),
    ],
  );

  /// Carries the client-side modifier memory across a response that does not
  /// echo it.
  Cart rememberingModifiersFrom(Cart? previous) {
    if (previous == null) return this;
    final Map<String, List<String>> known = <String, List<String>>{
      for (final CartLine line in previous.lines)
        if (line.modifierOptionIds.isNotEmpty)
          line.lineKey: line.modifierOptionIds,
    };
    if (known.isEmpty) return this;
    return Cart(
      cartId: cartId,
      locationId: locationId,
      status: status,
      currency: currency,
      version: version,
      quoteId: quoteId,
      contextHash: contextHash,
      expiresAt: expiresAt,
      lines: <CartLine>[
        for (final CartLine line in lines)
          known.containsKey(line.lineKey)
              ? line.withModifiers(known[line.lineKey]!)
              : line,
      ],
    );
  }
}

/// The quote bound to a cart, as `PricedCartResponse` sends it (ADR 0018).
///
/// **This is the price.** The context hash is what checkout accepts, and the
/// three amounts are the server's own arithmetic. Nothing in this application
/// adds them up, adjusts them, or infers a fourth figure from them.
///
/// There is no fee and no discount member here because the endpoint does not
/// send them, even though `QuoteSnapshot` carries both. A client that displayed
/// `total - subtotal - tax` as "delivery" would be inventing a breakdown the
/// server never published.
final class PricedCart {
  const PricedCart({
    required this.cartId,
    required this.cartVersion,
    required this.quoteId,
    required this.contextHash,
    required this.currency,
    required this.subtotal,
    required this.tax,
    required this.total,
    required this.expiresAt,
  });

  final String cartId;
  final int cartVersion;
  final String quoteId;

  /// Covers the catalog publication, the price book, the tax profile and the
  /// zone rules. Checkout accepts this exact string and no other.
  final String contextHash;

  final String currency;
  final Money subtotal;
  final Money tax;
  final Money total;

  /// Fifteen minutes from pricing, per ADR 0018.
  final DateTime expiresAt;

  bool isExpiredAt(DateTime now) => !expiresAt.isAfter(now);

  factory PricedCart.fromJson(Map<String, Object?> json) {
    final String currency = json['currency']! as String;
    Money amount(String key) =>
        Money((json[key]! as num).toInt(), currency);
    return PricedCart(
      cartId: json['cartId']! as String,
      cartVersion: (json['cartVersion']! as num).toInt(),
      quoteId: json['quoteId']! as String,
      contextHash: json['contextHash']! as String,
      currency: currency,
      subtotal: amount('subtotalMinor'),
      tax: amount('taxMinor'),
      total: amount('totalMinor'),
      expiresAt: _instant(json['expiresAt'])!,
    );
  }
}

/// Which tenant, brand, location and channel this storefront is.
///
/// Every storefront path carries a tenant and a brand — the platform's
/// authorization and idempotency interceptors both derive their scope from those
/// path variables — so there is no call in this feature that can be made without
/// one of these. Injected rather than read from a global, because a test builds
/// its own and because a brand switch is a scope swap and not a restart.
final class StorefrontScope {
  const StorefrontScope({
    required this.tenantId,
    required this.brandId,
    required this.locationId,
    required this.channel,
  });

  final String tenantId;
  final String brandId;

  /// The branch the cart is opened at. A cart belongs to one location for its
  /// whole life; moving is `POST /carts/{cartId}/location`, which rebuilds it.
  final String locationId;

  /// The ADR 0036 sales channel code. Decides both the menu that is priced and
  /// the price plane that prices it.
  final String channel;

  String get basePath =>
      '/api/v1/storefront/tenants/$tenantId/brands/$brandId';

  StorefrontScope atLocation(String newLocationId) => StorefrontScope(
    tenantId: tenantId,
    brandId: brandId,
    locationId: newLocationId,
    channel: channel,
  );
}

/// ADR 0031 sends instants as RFC 3339 UTC.
DateTime? _instant(Object? value) =>
    value is String ? DateTime.parse(value).toUtc() : null;
