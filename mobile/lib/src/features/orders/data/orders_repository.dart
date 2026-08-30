import '../../../api/api_client.dart';
import '../../../api/page.dart';
import 'order_models.dart';

/// The customer's own orders, over the ADR 0031 client.
///
/// Nothing here speaks HTTP. `HorecaOSApiClient` already owns the bearer token and
/// its refresh, the correlation identifier, Problem Details, `ETag` parsing and
/// cursor pagination; a screen that reached for `package:http` would be
/// re-implementing all five, and would get the idempotency rules wrong on the
/// way past.
///
/// ## What the platform serves today, exactly
///
/// `StorefrontOrderingController` is the contract of record for this surface.
/// Read against it on 2026-08-24, three things are true and are worth stating
/// here rather than discovering at integration:
///
/// 1. **`GET .../orders` does not exist.** The controller has `GET
///    /orders/{orderId}` and no list. The path and shape below are ADR 0031's
///    documented conventions — `?cursor=&limit=`, `{items, nextCursor}` — and
///    the member names are the ones
///    `OperationsOrderController.OrderSummaryResponse` already uses for the
///    same facts. Note that the operations list is *not* cursor-paginated: it
///    returns a bare JSON array under a `limit`, which contradicts ADR 0031 and
///    is not a precedent a customer-facing list should copy. Until the
///    storefront list is written, this call answers 404.
/// 2. **Both endpoints will answer 403 to a real customer.** The cart and
///    checkout endpoints declare `ORDER_PLACE` and the order read declares
///    `ORDER_READ`; a customer principal holds neither, and ADR 0025 has not
///    settled what a non-staff principal is. That is a recorded open item. This
///    client sends no header and takes no fallback path to work around it — an
///    invented header would be a client asserting an authorization decision,
///    which is the one thing ADR 0025 says a client never does. The screens
///    render the refusal as an ordinary "unavailable" state.
/// 3. **The detail response is missing five facts these screens need**:
///    `fulfillmentMode`, `promisedAt` (V0023, stored since ADR 0036),
///    `paymentStatus`, the terminal `outcome` (ADR 0039), and `closedAt`. Every
///    one of them is decoded as optional here, so adding them server-side is
///    the additive change ADR 0031 requires and needs no client release. Each
///    screen is built to be correct while they are absent rather than to look
///    broken until they arrive.
final class OrdersRepository {
  const OrdersRepository({
    required this.api,
    required this.tenantId,
    required this.brandId,
  });

  final HorecaOSApiClient api;

  /// The brand this build is the storefront for. Both identifiers are in the
  /// path because the platform's authorization and idempotency interceptors
  /// derive their scope from path variables; a storefront path without them is
  /// the one family of endpoints whose capability decision cannot be evaluated.
  final String tenantId;
  final String brandId;

  /// ADR 0031 requires a documented default and maximum per endpoint. Twenty
  /// rows is about three phone screens, which is enough that the first
  /// continuation happens after the customer has actually scrolled.
  static const int defaultPageSize = 20;

  String get _base =>
      '/api/v1/storefront/tenants/$tenantId/brands/$brandId/orders';

  /// One page of the customer's orders, newest first.
  ///
  /// The account is never a parameter. `StorefrontOrderingController` resolves
  /// it from the caller's own verified token precisely so that naming one is
  /// impossible: a client that could name an account could read somebody
  /// else's orders.
  Future<Page<OrderSummary>> list({
    String? cursor,
    int limit = defaultPageSize,
  }) async {
    final ApiResponse<Page<OrderSummary>> response = await api
        .getPage<OrderSummary>(
          _base,
          cursor: cursor,
          limit: limit,
          decodeItem: OrderSummary.fromJson,
        );
    return response.value;
  }

  /// One order, with the version from its `ETag`.
  Future<OrderDetail> read(String orderId) async {
    final ApiResponse<Map<String, Object?>> response = await api
        .get<Map<String, Object?>>(
          '$_base/$orderId',
          decode: (Map<String, Object?> json) => json,
        );
    // The `ETag` is the authority on the version, not the body: `AggregateVersion`
    // renders it on every read of a versioned aggregate, and it is what an
    // `If-Match` has to echo.
    return OrderDetail.fromJson(response.value, version: response.version);
  }
}
