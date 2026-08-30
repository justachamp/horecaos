import '../../api/api_client.dart';
import '../../api/idempotency_key.dart';
import 'cart_models.dart';

/// Every storefront cart call, and nothing else.
///
/// A thin layer over [HorecaOSApiClient] rather than a second client: idempotency
/// keys, `If-Match`, token refresh, replay detection and Problem Details are all
/// already solved there, and a repository that reached for `http` directly would
/// be re-solving them worse. What this class adds is the path shapes, the wire
/// bodies, and the decision about which mutation carries which version.
///
/// It throws whatever the client throws. Mapping a refusal to a screen state is
/// the controller's job, because the same `RESOURCE_CONFLICT` means "the branch
/// closed" in one place and "somebody else edited this cart" in another, and
/// only the caller knows which question it asked.
final class CartRepository {
  const CartRepository({required this._api, required this._scope});

  final HorecaOSApiClient _api;
  final StorefrontScope _scope;

  /// Opens a cart at this scope's location for one fulfilment mode.
  ///
  /// The idempotency key is minted here rather than taken from the caller: a
  /// retried "open a cart" that produced two carts would leave the customer's
  /// items split across baskets they cannot see.
  Future<Cart> createCart(FulfilmentMode mode) async {
    final ApiResponse<Cart> response = await _api.post<Cart>(
      '${_scope.basePath}/carts',
      idempotencyKey: IdempotencyKey.generate(),
      body: <String, Object?>{
        'locationId': _scope.locationId,
        'channel': _scope.channel,
        'fulfillmentMode': mode.wireName,
      },
      decode: Cart.fromJson,
    );
    return response.value;
  }

  Future<Cart> readCart(String cartId) async {
    final ApiResponse<Cart> response = await _api.get<Cart>(
      '${_scope.basePath}/carts/$cartId',
      decode: Cart.fromJson,
    );
    return response.value;
  }

  /// Adds or replaces one line.
  ///
  /// A replace, not a patch — that is the platform's semantics for
  /// `PUT /lines/{lineKey}` — so [modifierOptionIds] must carry the whole
  /// selection every time, including on a quantity change.
  Future<Cart> putLine({
    required String cartId,
    required int expectedVersion,
    required String lineKey,
    required String variantId,
    required int quantity,
    List<String> modifierOptionIds = const <String>[],
    String? customerNote,
  }) async {
    final ApiResponse<Cart> response = await _api.put<Cart>(
      '${_scope.basePath}/carts/$cartId/lines/$lineKey',
      idempotencyKey: IdempotencyKey.generate(),
      expectedVersion: expectedVersion,
      body: <String, Object?>{
        'variantId': variantId,
        'quantity': quantity,
        'modifierOptionIds': modifierOptionIds,
        'customerNote': customerNote,
      },
      decode: Cart.fromJson,
    );
    return response.value;
  }

  Future<Cart> removeLine({
    required String cartId,
    required int expectedVersion,
    required String lineKey,
  }) async {
    final ApiResponse<Cart> response = await _api.delete<Cart>(
      '${_scope.basePath}/carts/$cartId/lines/$lineKey',
      idempotencyKey: IdempotencyKey.generate(),
      expectedVersion: expectedVersion,
      decode: Cart.fromJson,
    );
    return response.value;
  }

  /// Rebuilds the cart at another branch.
  ///
  /// Returns a **different** cart: the old one is abandoned and the lines are
  /// copied unpriced, because catalog, availability, tax, fee and promise all
  /// change with the branch. Callers must adopt the returned cart id.
  Future<Cart> moveToLocation({
    required String cartId,
    required int expectedVersion,
    required String locationId,
  }) async {
    final ApiResponse<Cart> response = await _api.post<Cart>(
      '${_scope.basePath}/carts/$cartId/location',
      idempotencyKey: IdempotencyKey.generate(),
      expectedVersion: expectedVersion,
      body: <String, Object?>{'locationId': locationId},
      decode: Cart.fromJson,
    );
    return response.value;
  }

  /// Prices the cart and binds the quote to it (ADR 0018).
  ///
  /// The returned context hash is what checkout will present. Re-pricing an
  /// unchanged cart returns the same quote rather than a second one, because the
  /// platform keys the request on the cart and its version.
  Future<PricedCart> price({
    required String cartId,
    required int expectedVersion,
  }) async {
    final ApiResponse<PricedCart> response = await _api.post<PricedCart>(
      '${_scope.basePath}/carts/$cartId/pricing',
      idempotencyKey: IdempotencyKey.generate(),
      expectedVersion: expectedVersion,
      decode: PricedCart.fromJson,
    );
    return response.value;
  }
}
