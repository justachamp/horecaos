import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:horecaos_mobile/src/api/api_client.dart';
import 'package:horecaos_mobile/src/features/cart/cart_models.dart';
import 'package:horecaos_mobile/src/features/cart/cart_repository.dart';

import 'support.dart';

/// What goes on the wire, checked against the platform's own controller rather
/// than against an imagined REST API.
void main() {
  late List<Recorded> log;

  CartRepository repositoryAnswering(Object body, {int status = 200}) {
    log = <Recorded>[];
    return CartRepository(
      api: client(
        (http.Request request) async => jsonResponse(body, status: status),
        log: log,
      ),
      scope: testScope,
    );
  }

  group('opening a cart', () {
    test('posts the location, the channel and the platform enum spelling', () async {
      final CartRepository repository = repositoryAnswering(cartJson());

      await repository.createCart(FulfilmentMode.dineIn);

      expect(log.single.request.url.path, '$basePath/carts');
      expect(log.single.body, <String, Object?>{
        'locationId': 'branch-1',
        'channel': 'MOBILE_APP',
        // `DINE_IN`, not `DINEIN`. The enum's own spelling, because
        // `name.toUpperCase()` would fail the platform's validation.
        'fulfillmentMode': 'DINE_IN',
      });
    });

    test('carries an idempotency key, because a retry must not open two carts', () async {
      final CartRepository repository = repositoryAnswering(cartJson());

      await repository.createCart(FulfilmentMode.pickup);

      expect(
        log.single.request.headers[HorecaOSApiClient.idempotencyKeyHeader],
        isNotEmpty,
      );
    });
  });

  group('editing a line', () {
    test('sends the expected version as a weak validator', () async {
      final CartRepository repository = repositoryAnswering(cartJson(version: 4));

      await repository.putLine(
        cartId: 'cart-1',
        expectedVersion: 3,
        lineKey: 'line-1',
        variantId: 'variant-1',
        quantity: 2,
      );

      expect(log.single.request.method, 'PUT');
      expect(log.single.request.url.path, '$basePath/carts/cart-1/lines/line-1');
      expect(log.single.request.headers['If-Match'], 'W/"3"');
    });

    test('re-sends the whole selection, because PUT replaces a line', () async {
      final CartRepository repository = repositoryAnswering(cartJson());

      await repository.putLine(
        cartId: 'cart-1',
        expectedVersion: 1,
        lineKey: 'line-1',
        variantId: 'variant-1',
        quantity: 3,
        modifierOptionIds: <String>['option-a', 'option-b'],
      );

      expect(log.single.body['modifierOptionIds'], <String>[
        'option-a',
        'option-b',
      ]);
      expect(log.single.body['quantity'], 3);
    });

    test('removing a line is a DELETE with the version', () async {
      final CartRepository repository = repositoryAnswering(cartJson(version: 5));

      await repository.removeLine(
        cartId: 'cart-1',
        expectedVersion: 4,
        lineKey: 'line-1',
      );

      expect(log.single.request.method, 'DELETE');
      expect(log.single.request.headers['If-Match'], 'W/"4"');
    });
  });

  group('decoding a cart', () {
    test('takes the version from the body, since POST /carts sends no ETag', () async {
      final CartRepository repository = repositoryAnswering(cartJson(version: 7));

      final Cart cart = await repository.createCart(FulfilmentMode.delivery);

      expect(cart.version, 7);
    });

    test('an unpriced cart has no quote binding', () async {
      final CartRepository repository = repositoryAnswering(cartJson());

      final Cart cart = await repository.readCart('cart-1');

      expect(cart.isPriced, isFalse);
      expect(cart.quoteId, isNull);
    });

    test('a line carries no name and no price, which is the contract', () async {
      final CartRepository repository = repositoryAnswering(
        cartJson(lines: <Map<String, Object?>>[lineJson(quantity: 2)]),
      );

      final Cart cart = await repository.readCart('cart-1');

      expect(cart.lines.single.variantId, 'variant-1');
      expect(cart.lines.single.quantity, 2);
    });

    test('tolerates a cart status this build has never heard of', () async {
      final CartRepository repository = repositoryAnswering(
        cartJson(status: 'SOMETHING_ADDED_LATER'),
      );

      final Cart cart = await repository.readCart('cart-1');

      expect(cart.status.value, 'SOMETHING_ADDED_LATER');
      expect(cart.status.isEditable, isFalse);
    });
  });

  group('pricing', () {
    test('posts to the pricing path with the cart version', () async {
      final CartRepository repository = repositoryAnswering(pricedJson());

      await repository.price(cartId: 'cart-1', expectedVersion: 2);

      expect(log.single.request.url.path, '$basePath/carts/cart-1/pricing');
      expect(log.single.request.headers['If-Match'], 'W/"2"');
    });

    test('reads whole som, undivided', () async {
      // 84 000 som. A client that asked ICU for the UZS exponent would divide
      // this by a hundred and quote 840 som for a meal.
      final CartRepository repository = repositoryAnswering(
        pricedJson(subtotal: 84000, tax: 0, total: 84000),
      );

      final PricedCart priced = await repository.price(
        cartId: 'cart-1',
        expectedVersion: 2,
      );

      expect(priced.subtotal.amountMinor, 84000);
      expect(priced.total.amountMinor, 84000);
      expect(priced.total.currency, 'UZS');
    });

    test('keeps the context hash exactly as issued', () async {
      final CartRepository repository = repositoryAnswering(
        pricedJson(contextHash: 'a1b2c3'),
      );

      final PricedCart priced = await repository.price(
        cartId: 'cart-1',
        expectedVersion: 2,
      );

      expect(priced.contextHash, 'a1b2c3');
      expect(priced.quoteId, 'quote-1');
    });
  });
}
