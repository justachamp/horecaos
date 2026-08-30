import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:horecaos_mobile/src/features/cart/cart_controller.dart';
import 'package:horecaos_mobile/src/features/cart/cart_repository.dart';

import 'support.dart';

/// The rules that keep a price honest, and the one race a shared cart runs into.
void main() {
  late List<Recorded> log;
  late List<http.Response> answers;

  CartRepository scripted(List<http.Response> responses) {
    log = <Recorded>[];
    answers = responses;
    return CartRepository(
      api: client(
        (http.Request request) async => answers.removeAt(0),
        log: log,
      ),
      scope: testScope,
    );
  }

  group('the held quote', () {
    test('is dropped the moment the basket is edited', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(version: 2)),
          // The platform clears the binding in the same statement that bumps
          // the version, so the edit comes back with no quote on it.
          jsonResponse(
            cartJson(
              version: 3,
              lines: <Map<String, Object?>>[lineJson(quantity: 2)],
            ),
          ),
        ]),
      );

      await controller.load('cart-1');
      await controller.refreshPrice();
      expect(controller.quote, isNotNull);

      await controller.setQuantity('line-1', 2);

      expect(
        controller.quote,
        isNull,
        reason: 'a price shown beside a basket it was not computed for is the '
            'exact failure ADR 0018 exists to prevent',
      );
      expect(controller.hasUsableQuote, isFalse);
    });

    test('is unusable once its fifteen minutes have run out', () async {
      DateTime now = DateTime.utc(2026, 8, 24, 12);
      final CartController controller = CartController(
        now: () => now,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(expiresAt: '2026-08-24T12:15:00Z')),
        ]),
      );

      await controller.load('cart-1');
      await controller.refreshPrice();
      expect(controller.hasUsableQuote, isTrue);

      now = DateTime.utc(2026, 8, 24, 12, 16);

      expect(controller.hasUsableQuote, isFalse);
    });

    test('survives a re-price, and moves the cart version with it', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(version: 2, quoteId: 'quote-9')),
        ]),
      );

      await controller.load('cart-1');
      await controller.refreshPrice();

      expect(controller.cart!.version, 2);
      expect(controller.cart!.quoteId, 'quote-9');
      expect(controller.hasUsableQuote, isTrue);
    });
  });

  group('two devices on one basket', () {
    test('retries a stale edit once, against the version the server names', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          problem(
            'STALE_VERSION',
            extensions: <String, Object?>{'currentVersion': 6},
          ),
          jsonResponse(
            cartJson(
              version: 7,
              lines: <Map<String, Object?>>[lineJson(quantity: 3)],
            ),
          ),
        ]),
      );

      await controller.load('cart-1');
      await controller.setQuantity('line-1', 3);

      expect(controller.problem, isNull);
      expect(controller.cart!.version, 7);
      // Two writes: the one that lost the race, then the one that used the
      // server's own version. Not a loop.
      expect(log.where((Recorded r) => r.request.method == 'PUT').length, 2);
      expect(log.last.request.headers['If-Match'], 'W/"6"');
    });

    test('does not retry a second time', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          problem(
            'STALE_VERSION',
            extensions: <String, Object?>{'currentVersion': 6},
          ),
          problem(
            'STALE_VERSION',
            extensions: <String, Object?>{'currentVersion': 9},
          ),
        ]),
      );

      await controller.load('cart-1');
      await controller.setQuantity('line-1', 3);

      // A basket that keeps moving is a basket somebody else is actively
      // editing. Winning that race on the tenth attempt would be worse than
      // saying what happened.
      expect(controller.problem, isNotNull);
      expect(log.where((Recorded r) => r.request.method == 'PUT').length, 2);
    });
  });

  group('a quantity change', () {
    test('carries the modifier selection back with it', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson()),
          jsonResponse(
            cartJson(
              version: 2,
              lines: <Map<String, Object?>>[lineJson()],
            ),
          ),
          jsonResponse(
            cartJson(
              version: 3,
              lines: <Map<String, Object?>>[lineJson(quantity: 2)],
            ),
          ),
        ]),
      );

      await controller.load('cart-1');
      await controller.putLine(
        lineKey: 'line-1',
        variantId: 'variant-1',
        quantity: 1,
        modifierOptionIds: <String>['no-onions'],
      );
      await controller.setQuantity('line-1', 2);

      // The endpoint replaces a line rather than patching it. Sending only the
      // quantity would strip the choices, and the customer would find out at
      // the counter.
      expect(log.last.body['modifierOptionIds'], <String>['no-onions']);
    });

    test('zero removes the line', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(cartJson(version: 2)),
        ]),
      );

      await controller.load('cart-1');
      await controller.setQuantity('line-1', 0);

      expect(log.last.request.method, 'DELETE');
    });
  });

  group('refusals the customer has to understand', () {
    Future<CartProblem?> problemFrom(http.Response response) async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          response,
        ]),
      );
      await controller.load('cart-1');
      await controller.refreshPrice();
      return controller.problem;
    }

    test('a closed branch is not an error', () async {
      final CartProblem? found = await problemFrom(
        problem('RESOURCE_CONFLICT', reason: 'NOT_SERVICEABLE'),
      );

      expect(found!.kind, CartProblemKind.notServiceable);
    });

    test('an expired basket clears the basket', () async {
      final CartController controller = CartController(
        now: fixtureClock,
        repository: scripted(<http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          problem('RESOURCE_CONFLICT', reason: 'CART_EXPIRED'),
        ]),
      );

      await controller.load('cart-1');
      await controller.refreshPrice();

      expect(controller.problem!.kind, CartProblemKind.cartGone);
      expect(controller.cart, isNull);
    });

    test('an unpriceable item names the item', () async {
      final CartProblem? found = await problemFrom(
        problem(
          'VALIDATION_FAILED',
          status: 422,
          reason: 'NO_ACTIVE_PRICE',
          extensions: <String, Object?>{'subjectId': 'variant-7'},
        ),
      );

      expect(found!.kind, CartProblemKind.pricingRefused);
      expect(found.subjectId, 'variant-7');
    });

    test('a refused principal is its own state, not a network error', () async {
      // The expected answer for every real customer today: the storefront
      // ordering endpoints declare ORDER_PLACE, which no customer principal
      // holds. Disguising it would send people to retry what cannot succeed.
      final CartProblem? found = await problemFrom(
        problem(
          'INSUFFICIENT_CAPABILITY',
          status: 403,
          extensions: <String, Object?>{'requiredCapability': 'ORDER_PLACE'},
        ),
      );

      expect(found!.kind, CartProblemKind.notPermitted);
    });

    test('a correlation identifier survives for support', () async {
      final CartProblem? found = await problemFrom(
        problem('INTERNAL_ERROR', status: 500),
      );

      expect(found!.correlationId, 'cid-1');
    });
  });

  test('a converted basket stops being editable', () async {
    final CartController controller = CartController(
      repository: scripted(<http.Response>[
        jsonResponse(cartJson(status: 'CONVERTED')),
      ]),
    );

    await controller.load('cart-1');

    expect(controller.problem!.kind, CartProblemKind.cartGone);
  });
}
