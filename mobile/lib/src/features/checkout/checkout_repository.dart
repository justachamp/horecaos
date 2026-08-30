import '../../api/api_client.dart';
import '../../api/idempotency_key.dart';
import '../cart/cart_models.dart';
import 'checkout_models.dart';

/// The three calls that turn a priced basket into a paid order.
///
/// `POST /checkouts` on the storefront ordering surface,
/// `POST /orders/{orderId}/payment-sessions` on the storefront payment surface,
/// and ADR 0037's delivery-fee resolver. All three go through
/// [QoidaApiClient], so idempotency, `If-Match`, token refresh, replay
/// detection and Problem Details are handled once.
final class CheckoutRepository {
  const CheckoutRepository({required this._api, required this._scope});

  final QoidaApiClient _api;
  final StorefrontScope _scope;

  /// Turns a priced cart into an order.
  ///
  /// [idempotencyKey] is a parameter and has no default, which is the whole
  /// point of the header. The key belongs to the customer's intent — one tap of
  /// "place the order" — and every attempt at that intent must carry the same
  /// one, including the attempt that follows a token refresh and the one that
  /// follows a dropped connection. The controller owns the key's lifetime and
  /// mints a new one only when the request body genuinely changes.
  ///
  /// The returned order carries `replayed`, straight off the platform's
  /// `Idempotency-Replayed` header. A replay means the order already exists.
  Future<PlacedOrder> placeOrder({
    required String cartId,
    required int cartVersion,
    required String quoteId,
    required String contextHash,
    required PaymentMethodChoice paymentMethod,
    required IdempotencyKey idempotencyKey,
  }) async {
    final ApiResponse<Map<String, Object?>> response =
        await _api.post<Map<String, Object?>>(
          '${_scope.basePath}/checkouts',
          idempotencyKey: idempotencyKey,
          body: <String, Object?>{
            'cartId': cartId,
            // ADR 0031's expected version, in the body rather than an `If-Match`
            // because the resource being created is the order and the version
            // being asserted is the cart's. A checkout built on a basket edited
            // on another device is refused here.
            'cartVersion': cartVersion,
            'quoteId': quoteId,
            // The exact hash the quote was issued with. Not a re-derivation and
            // not an approximation: checkout accepts this string and no other.
            'contextHash': contextHash,
            'paymentMethodCode': paymentMethod.code,
          },
          decode: (Map<String, Object?> json) => json,
        );

    return PlacedOrder.fromJson(
      response.value,
      replayed: response.replayed,
    );
  }

  /// Opens the payment attempt and gets its checkout surface (ADR 0013).
  ///
  /// Called after checkout leaves the order in `PAYMENT_AUTHORIZING`. Not called
  /// for cash, which has no surface and whose money arrives at handover.
  ///
  /// A customer who abandons the page and comes back is handed the same attempt,
  /// enforced by a unique index on the platform rather than by anything here.
  Future<PaymentSession> openPaymentSession({
    required String orderId,
    required IdempotencyKey idempotencyKey,
    String? language,
    Uri? returnUrl,
  }) async {
    final ApiResponse<PaymentSession> response = await _api.post<PaymentSession>(
      '${_scope.basePath}/orders/$orderId/payment-sessions',
      idempotencyKey: idempotencyKey,
      body: <String, Object?>{
        // A link, which is the only surface both providers can build. A push
        // is deliberately never requested from here: it is a mutating provider
        // call with no idempotency key anywhere in Click's MERCHANT API, so a
        // customer pressing "pay" twice would be two invoices on their phone.
        'presentation': 'PAYMENT_LINK',
        'language': ?language,
        'returnUrl': returnUrl?.toString(),
      },
      decode: PaymentSession.fromJson,
    );
    return response.value;
  }

  /// Reads one of the caller's own orders.
  ///
  /// Used after checkout for one thing only: the promised time, which
  /// `CheckoutResponse` does not carry. Best-effort — the confirmation screen
  /// is correct without it, and this endpoint declares `ORDER_READ`, which a
  /// customer principal does not hold today either.
  Future<PlacedOrder> readOrder(String orderId) async {
    final ApiResponse<Map<String, Object?>> response =
        await _api.get<Map<String, Object?>>(
          '${_scope.basePath}/orders/$orderId',
          decode: (Map<String, Object?> json) => json,
        );
    return PlacedOrder.fromJson(response.value, replayed: false);
  }

  /// Asks ADR 0037's resolver what delivery costs to one point.
  ///
  /// The stepped tariff, the peak-hour rules, the zone ranking and the free-
  /// delivery threshold live behind this endpoint. Nothing here reimplements any
  /// of them, and nothing here adds the answer to a total: the money the
  /// customer pays is the quote's, and this is the branch's published delivery
  /// charge and its minimum-basket rule.
  ///
  /// Unauthenticated on the platform, like the menu it accompanies, and writes
  /// nothing — so dragging a pin across a map leaves no row per pixel.
  Future<DeliveryFeeQuote> deliveryFee({
    required GeoPoint destination,
    required String currency,
    required int subtotalMinor,
  }) async {
    final ApiResponse<DeliveryFeeQuote> response =
        await _api.get<DeliveryFeeQuote>(
          '${_scope.basePath}/locations/${_scope.locationId}/delivery-fee',
          query: <String, String>{
            'lat': '${destination.latitude}',
            'lon': '${destination.longitude}',
            'currency': currency,
            'subtotalMinor': '$subtotalMinor',
          },
          decode: DeliveryFeeQuote.fromJson,
        );
    return response.value;
  }
}
