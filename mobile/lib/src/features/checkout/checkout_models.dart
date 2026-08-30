import '../../format/money.dart';

/// How the customer pays (`uz.qoida.platform.payments.domain.PaymentMethod`).
///
/// Code-owned on the platform and code-owned here, for the reason the platform
/// gives: an unknown method must fail rather than resolve to something. A
/// channel chooses which of these to offer and never invents a new one.
enum PaymentMethodChoice {
  /// The majority tender in this market, and a tender rather than the absence
  /// of one. Captured on handover, so there is nothing to open and nowhere to
  /// send the customer.
  cash('CASH', providerName: null),

  click('CLICK', providerName: 'Click'),

  payme('PAYME', providerName: 'Payme');

  const PaymentMethodChoice(this.code, {required this.providerName});

  final String code;

  /// The provider's own name, or null for cash.
  ///
  /// Not an ARB message. It is a brand name in the same class as the
  /// application's own title: "Click" is spelled Click in ru and uz, and putting
  /// it in the localisations would invite somebody to translate it.
  final String? providerName;

  /// Whether placing an order with this method leaves the order waiting for
  /// money before the branch is even asked (ADR 0013's `BEFORE_CONFIRMATION`).
  bool get needsProvider => providerName != null;

  static PaymentMethodChoice? fromCode(String? code) {
    for (final PaymentMethodChoice method in PaymentMethodChoice.values) {
      if (method.code == code) return method;
    }
    return null;
  }
}

/// An order status, kept as the server's string.
///
/// Tolerant of a value this build has never heard of, per ADR 0031's additive
/// evolution rule. Only the states the confirmation screen distinguishes are
/// named.
final class OrderStatusCode {
  const OrderStatusCode(this.value);

  final String value;

  static const OrderStatusCode received = OrderStatusCode('RECEIVED');
  static const OrderStatusCode paymentAuthorizing = OrderStatusCode(
    'PAYMENT_AUTHORIZING',
  );
  static const OrderStatusCode awaitingApproval = OrderStatusCode(
    'AWAITING_APPROVAL',
  );
  static const OrderStatusCode confirmed = OrderStatusCode('CONFIRMED');

  /// The order exists and is waiting for the customer to pay. This is the only
  /// status that asks the application to do something next.
  bool get isAwaitingPayment => value == paymentAuthorizing.value;

  @override
  bool operator ==(Object other) =>
      other is OrderStatusCode && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value;
}

/// An order that exists, as `CheckoutResponse` sends it.
final class PlacedOrder {
  const PlacedOrder({
    required this.orderId,
    required this.publicOrderNumber,
    required this.status,
    required this.version,
    required this.outcome,
    required this.warnings,
    required this.replayed,
    this.promisedAt,
  });

  final String orderId;

  /// What the customer and the branch call it out loud.
  final String publicOrderNumber;

  final OrderStatusCode status;
  final int version;

  /// `CREATED` or `REPLAYED` from the platform's own outcome enum. Distinct
  /// from [replayed], which is the HTTP-level `Idempotency-Replayed` header;
  /// either one means the same thing to a customer and neither is a failure.
  final String outcome;

  /// Platform gaps that apply to this order, such as an unwired payments port.
  /// Developer-facing, and never rendered to a customer.
  final List<String> warnings;

  /// True when the platform returned its stored response instead of placing a
  /// second order.
  ///
  /// **This is a success.** A screen that treated it as a failure and retried
  /// would be the duplicate-order bug; the client reports it so the screen can
  /// say "already placed" rather than "try again".
  final bool replayed;

  /// The promised time (ADR 0036, V0023), decided at checkout and never
  /// recomputed.
  ///
  /// Null today for every order, because no customer-facing endpoint returns
  /// it: `CheckoutResponse` has no such member and neither does the storefront
  /// `OrderResponse`, although `ordering.orders` stores it in four columns. It
  /// is decoded here so the day the field is published the screen shows it, and
  /// **nothing in this application estimates one in the meantime** — a client
  /// guess is the number ADR 0036 warns about, with none of the evidence.
  final DateTime? promisedAt;

  factory PlacedOrder.fromJson(
    Map<String, Object?> json, {
    required bool replayed,
  }) => PlacedOrder(
    orderId: json['orderId']! as String,
    publicOrderNumber: json['publicOrderNumber'] as String? ?? '',
    status: OrderStatusCode(json['status']! as String),
    version: (json['version'] as num?)?.toInt() ?? 0,
    outcome: json['outcome'] as String? ?? '',
    warnings: <String>[
      for (final Object? warning
          in (json['warnings'] as List<Object?>? ?? const <Object?>[]))
        if (warning is String) warning,
    ],
    replayed: replayed,
    promisedAt: parseInstant(json['promisedAt']),
  );

  PlacedOrder withPromise(DateTime? at) => PlacedOrder(
    orderId: orderId,
    publicOrderNumber: publicOrderNumber,
    status: status,
    version: version,
    outcome: outcome,
    warnings: warnings,
    replayed: replayed,
    promisedAt: at,
  );
}

/// The checkout surface for one payment attempt (ADR 0013).
final class PaymentSession {
  const PaymentSession({
    required this.attemptId,
    required this.provider,
    required this.presentation,
    required this.amount,
    required this.rePresented,
    this.checkoutUrl,
    this.qrPayload,
    this.expiresAt,
  });

  final String attemptId;

  /// `CLICK`, `PAYME`, `TELEGRAM`.
  final String provider;

  /// `PAYMENT_LINK`, `QR`, `INVOICE_PUSH`, and the rest of the platform's
  /// `PresentationKind`.
  final String presentation;

  /// Where to send the browser. Null for a push, which arrives on the phone
  /// instead.
  final Uri? checkoutUrl;

  final String? qrPayload;
  final DateTime? expiresAt;

  /// Whole som (ADR 0018), and the platform's response says so in as many
  /// words. Not divided by anything on the way in.
  final Money amount;

  /// True when the customer came back to an attempt that already existed. A
  /// second attempt is refused by a unique index on the platform, because two
  /// payable links against one order is a double charge waiting to happen.
  final bool rePresented;

  factory PaymentSession.fromJson(Map<String, Object?> json) => PaymentSession(
    attemptId: json['attemptId']! as String,
    provider: json['provider'] as String? ?? '',
    presentation: json['presentation'] as String? ?? '',
    checkoutUrl: switch (json['checkoutUrl']) {
      final String url when url.isNotEmpty => Uri.tryParse(url),
      _ => null,
    },
    qrPayload: json['qrPayload'] as String?,
    expiresAt: parseInstant(json['expiresAt']),
    amount: Money(
      (json['amountMinor']! as num).toInt(),
      json['currency']! as String,
    ),
    rePresented: json['rePresented'] as bool? ?? false,
  );

  /// Whether the platform expects the customer to be sent somewhere.
  bool get hasSurface => checkoutUrl != null;

  /// A payment request was pushed to the customer's phone. There is nothing to
  /// open, and asking for another one is a second charge.
  bool get isPush => presentation == 'INVOICE_PUSH';
}

/// A point on the map, for asking whether a branch delivers to it.
final class GeoPoint {
  const GeoPoint(this.latitude, this.longitude);

  final double latitude;
  final double longitude;
}

/// What delivery costs here, from ADR 0037's resolver and from nowhere else.
///
/// The stepped tariff, the peak-hour table, the zone ranking and the free-
/// delivery threshold are all the resolver's, and this application asks it
/// rather than reproducing any of them. `outcome` is a stable code and is never
/// rendered; the wording is chosen from it.
final class DeliveryFeeQuote {
  const DeliveryFeeQuote({
    required this.outcome,
    required this.available,
    this.reasonCode,
    this.fee,
    this.minimumBasket,
    this.freeDeliveryFrom,
    this.distanceMeters,
  });

  final String outcome;
  final bool available;
  final String? reasonCode;

  /// The fee the branch charges to this point. Read, never computed.
  final Money? fee;

  /// Below this, the zone will not deliver at all.
  final Money? minimumBasket;

  final Money? freeDeliveryFrom;
  final int? distanceMeters;

  factory DeliveryFeeQuote.fromJson(Map<String, Object?> json) {
    final String? currency = json['currency'] as String?;
    Money? amount(String key) {
      final Object? raw = json[key];
      if (raw is! num || currency == null) return null;
      return Money(raw.toInt(), currency);
    }

    return DeliveryFeeQuote(
      outcome: json['outcome'] as String? ?? '',
      available: json['available'] as bool? ?? false,
      reasonCode: json['reasonCode'] as String?,
      fee: amount('feeMinor'),
      minimumBasket: amount('minBasketMinor'),
      freeDeliveryFrom: amount('freeDeliveryFromMinor'),
      distanceMeters: (json['distanceMeters'] as num?)?.toInt(),
    );
  }
}

/// Why the platform would not accept the quote that was presented.
///
/// Every value is ADR 0018 working: the price is reproducible from a context
/// hash, and a hash that no longer describes the world is refused rather than
/// honoured or silently repriced. The customer is owed the reason, because the
/// four are not the same event.
enum StaleQuoteReason {
  /// `PRICE_CHANGED`. A price book, a promotion, a tax profile or a zone rule
  /// moved under the quote.
  priceChanged,

  /// `QUOTE_EXPIRED`. Fifteen minutes passed.
  quoteExpired,

  /// `PUBLICATION_CHANGED`. The branch republished its menu, so the dish is not
  /// the dish that was priced.
  menuRepublished,

  /// `CART_VERSION_STALE` or `QUOTE_NOT_BOUND_TO_CART`. The basket changed —
  /// usually on the customer's other device — so the quote belongs to a basket
  /// that no longer exists.
  basketChanged,
}

/// Everything else the platform can refuse a checkout with.
enum CheckoutRefusalKind {
  /// The kitchen is at its concurrent-order limit.
  atCapacity,

  /// Stock ran out between pricing and placing.
  itemsUnavailable,

  /// This branch cannot take the chosen method.
  paymentMethodUnavailable,

  /// The branch closed, or the channel stopped selling, while the customer was
  /// deciding. Re-resolved inside the checkout transaction, which is why it can
  /// appear here having passed in the basket.
  notServiceable,

  /// The basket lapsed or was already converted.
  cartGone,

  /// `ORDER_PLACE` is not held by this principal. The expected answer for every
  /// real customer until ADR 0025 settles what a non-staff principal is.
  notPermitted,

  /// The request never reached the platform. The idempotency key makes
  /// repeating it safe, and repeating it is the correct recovery.
  offline,

  unexpected,
}

/// ADR 0031 sends instants as RFC 3339 UTC.
DateTime? parseInstant(Object? value) =>
    value is String ? DateTime.parse(value).toUtc() : null;
