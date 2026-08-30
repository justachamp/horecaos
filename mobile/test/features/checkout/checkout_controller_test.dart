import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/features/cart/cart_controller.dart';
import 'package:qoida_mobile/src/features/cart/cart_models.dart';
import 'package:qoida_mobile/src/features/cart/cart_repository.dart';
import 'package:qoida_mobile/src/features/checkout/checkout_controller.dart';
import 'package:qoida_mobile/src/features/checkout/checkout_models.dart';
import 'package:qoida_mobile/src/features/checkout/checkout_repository.dart';
import 'package:qoida_mobile/src/features/checkout/payment_launcher.dart';

import '../cart/support.dart';

/// Records what it was asked to open, and whether it managed to.
class _Launcher implements PaymentLauncher {
  _Launcher({this.succeeds = true});

  final bool succeeds;
  final List<Uri> opened = <Uri>[];

  @override
  Future<bool> open(Uri url) async {
    opened.add(url);
    return succeeds;
  }
}

/// A checkout wired to scripted transports, one queue per endpoint.
///
/// Routed by path rather than by call order, because the order is part of what
/// is under test: placing an order is followed by a best-effort read for the
/// promised time and then, for a provider method, by opening a payment attempt.
/// A single queue would make a test pass or fail on the sequence a previous
/// test happened to leave behind.
class _Harness {
  _Harness({
    required this._cartAnswers,
    List<http.Response> checkoutAnswers = const <http.Response>[],
    List<http.Response> paymentAnswers = const <http.Response>[],
    List<http.Response> feeAnswers = const <http.Response>[],
    http.Response? orderAnswer,
    FulfilmentMode mode = FulfilmentMode.pickup,
    PaymentMethodChoice method = PaymentMethodChoice.cash,
    DateTime Function()? now,
    _Launcher? launcher,
  }) : _checkoutAnswers = <http.Response>[...checkoutAnswers],
       _paymentAnswers = <http.Response>[...paymentAnswers],
       _feeAnswers = <http.Response>[...feeAnswers],
       // The order read is best-effort and, against today's platform, would be
       // refused for exactly the same reason checkout is. Refusing it by
       // default keeps every test honest about where the promise comes from.
       _orderAnswer = orderAnswer ?? problem('INSUFFICIENT_CAPABILITY', status: 403),
       launcher = launcher ?? _Launcher() {
    cart = CartController(
      now: now ?? fixtureClock,
      repository: CartRepository(
        api: client(
          (http.Request request) async => _cartAnswers.removeAt(0),
          log: cartLog,
        ),
        scope: testScope,
      ),
    );
    checkout = CheckoutController(
      cart: cart,
      repository: CheckoutRepository(
        api: client(_route, log: checkoutLog),
        scope: testScope,
      ),
      fulfilmentMode: mode,
      paymentMethod: method,
      launcher: this.launcher,
      now: now ?? fixtureClock,
    );
  }

  final List<http.Response> _cartAnswers;
  final List<http.Response> _checkoutAnswers;
  final List<http.Response> _paymentAnswers;
  final List<http.Response> _feeAnswers;
  final http.Response _orderAnswer;
  final List<Recorded> cartLog = <Recorded>[];
  final List<Recorded> checkoutLog = <Recorded>[];
  final _Launcher launcher;

  late final CartController cart;
  late final CheckoutController checkout;

  Future<http.Response> _route(http.Request request) async {
    final String path = request.url.path;
    if (path.endsWith('/checkouts')) return _checkoutAnswers.removeAt(0);
    if (path.endsWith('/payment-sessions')) return _paymentAnswers.removeAt(0);
    if (path.endsWith('/delivery-fee')) return _feeAnswers.removeAt(0);
    return _orderAnswer;
  }

  /// Loads a basket and prices it, which is the state every checkout starts in.
  Future<void> readyBasket() async {
    await cart.load('cart-1');
    await cart.refreshPrice();
  }

  List<Recorded> get checkouts => _requestsTo('/checkouts');
  List<Recorded> get paymentSessions => _requestsTo('/payment-sessions');
  List<Recorded> get feeQueries => _requestsTo('/delivery-fee');

  List<Recorded> _requestsTo(String suffix) => checkoutLog
      .where((Recorded r) => r.request.url.path.endsWith(suffix))
      .toList();

  Iterable<String> get orderKeys => checkouts.map(
    (Recorded r) => r.request.headers[QoidaApiClient.idempotencyKeyHeader]!,
  );
}

Map<String, Object?> orderJson({
  String status = 'CONFIRMED',
  String number = 'A-1042',
}) => <String, Object?>{
  'orderId': 'order-1',
  'publicOrderNumber': number,
  'status': status,
  'version': 2,
  'outcome': 'CREATED',
  'warnings': <String>[],
};

List<http.Response> basketOpening({int total = 84000}) => <http.Response>[
  jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
  jsonResponse(pricedJson(total: total)),
];

void main() {
  group('placing an order', () {
    test('a cash order is placed and the basket is let go', () async {
      final _Harness harness = _Harness(
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[jsonResponse(orderJson())],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      final CheckoutStage stage = harness.checkout.stage;
      expect(stage, isA<CheckoutPlaced>());
      expect((stage as CheckoutPlaced).order.publicOrderNumber, 'A-1042');
      expect(
        harness.cart.cart,
        isNull,
        reason: 'the platform converted the cart; keeping it would offer the '
            'customer a basket they cannot edit',
      );
    });

    test('presents the quote by identity, not by re-deriving it', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(version: 4, quoteId: 'q-7', contextHash: 'h-7')),
        ],
        checkoutAnswers: <http.Response>[jsonResponse(orderJson())],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(harness.checkouts.single.body, <String, Object?>{
        'cartId': 'cart-1',
        'cartVersion': 4,
        'quoteId': 'q-7',
        'contextHash': 'h-7',
        'paymentMethodCode': 'CASH',
      });
    });

    test('a replayed response is a success, and says so', () async {
      final _Harness harness = _Harness(
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[
          jsonResponse(
            orderJson(),
            headers: <String, String>{
              QoidaApiClient.idempotencyReplayedHeader: 'true',
            },
          ),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      final CheckoutStage stage = harness.checkout.stage;
      expect(stage, isA<CheckoutPlaced>());
      expect(
        (stage as CheckoutPlaced).order.replayed,
        isTrue,
        reason: 'a screen that treated a replay as a failure and retried would '
            'be the duplicate-order bug in person',
      );
    });
  });

  group('the idempotency key', () {
    test('is the same one after a connection failure', () async {
      final _Harness harness = _Harness(
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[
          // A transport failure leaves the server's state unknown. Repeating
          // the identical request is the only safe recovery, and the key is
          // what makes it safe.
          http.Response('', 503, headers: <String, String>{}),
          jsonResponse(orderJson()),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();
      expect(harness.checkout.stage, isA<CheckoutRefused>());

      await harness.checkout.place();

      expect(harness.orderKeys.toSet(), hasLength(1));
      expect(harness.checkout.stage, isA<CheckoutPlaced>());
    });

    test('is a new one once a different quote is being sent', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 90000)),
        ],
        checkoutAnswers: <http.Response>[
          problem('PRICE_CHANGED', reason: 'PRICE_CHANGED'),
          jsonResponse(orderJson()),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();
      await harness.checkout.place();

      // The body now names a different quote, a different hash and a different
      // cart version, so it is a different request. Reusing the key is what the
      // platform answers IDEMPOTENCY_KEY_REUSED to, and it would be right to.
      expect(harness.orderKeys.toSet(), hasLength(2));
    });
  });

  group('a quote the platform will not accept', () {
    test('PRICE_CHANGED becomes a decision, not an error toast', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(total: 84000),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 90000)),
        ],
        checkoutAnswers: <http.Response>[
          problem('PRICE_CHANGED', reason: 'PRICE_CHANGED'),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      final CheckoutStage stage = harness.checkout.stage;
      expect(stage, isA<CheckoutPriceMoved>());
      final CheckoutPriceMoved moved = stage as CheckoutPriceMoved;
      expect(moved.reason, StaleQuoteReason.priceChanged);
      expect(moved.previousTotal.amountMinor, 84000);
      expect(moved.newQuote!.total.amountMinor, 90000);
      expect(moved.awaitingConfirmation, isTrue);
      expect(moved.totalUnchanged, isFalse);
    });

    test('the new price is only placed when the customer agrees to it', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 90000)),
        ],
        checkoutAnswers: <http.Response>[
          problem('PRICE_CHANGED', reason: 'PRICE_CHANGED'),
          jsonResponse(orderJson()),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();
      expect(harness.checkouts, hasLength(1));

      await harness.checkout.place();

      expect(harness.checkouts.last.body['quoteId'], 'q-2');
      expect(harness.checkouts.last.body['contextHash'], 'h-2');
      expect(harness.checkout.stage, isA<CheckoutPlaced>());
    });

    test('a republished menu is named as such', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2')),
        ],
        checkoutAnswers: <http.Response>[
          problem('RESOURCE_CONFLICT', reason: 'PUBLICATION_CHANGED'),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(
        (harness.checkout.stage as CheckoutPriceMoved).reason,
        StaleQuoteReason.menuRepublished,
      );
    });

    test('a basket edited elsewhere is named as such', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2')),
        ],
        checkoutAnswers: <http.Response>[
          problem('STALE_VERSION', reason: 'CART_VERSION_STALE'),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(
        (harness.checkout.stage as CheckoutPriceMoved).reason,
        StaleQuoteReason.basketChanged,
      );
    });

    test('says so when the refusal did not actually move the total', () async {
      final _Harness harness = _Harness(
        cartAnswers: <http.Response>[
          ...basketOpening(total: 84000),
          jsonResponse(pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 84000)),
        ],
        checkoutAnswers: <http.Response>[
          problem('RESOURCE_CONFLICT', reason: 'PUBLICATION_CHANGED'),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      // Common: a republication invalidates a hash without changing a price.
      expect(
        (harness.checkout.stage as CheckoutPriceMoved).totalUnchanged,
        isTrue,
      );
    });

    test('a quote this client can see has lapsed is never sent', () async {
      DateTime now = DateTime.utc(2026, 8, 24, 12);
      final _Harness harness = _Harness(
        now: () => now,
        cartAnswers: <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(expiresAt: '2026-08-24T12:15:00Z')),
          jsonResponse(
            pricedJson(
              quoteId: 'q-2',
              contextHash: 'h-2',
              expiresAt: '2026-08-24T12:35:00Z',
            ),
          ),
        ],
      );
      await harness.readyBasket();

      now = DateTime.utc(2026, 8, 24, 12, 20);
      await harness.checkout.place();

      expect(
        harness.checkouts,
        isEmpty,
        reason: 'sending it would only move the same refusal one round trip '
            'later',
      );
      expect(
        (harness.checkout.stage as CheckoutPriceMoved).reason,
        StaleQuoteReason.quoteExpired,
      );
    });
  });

  group('refusals that are not about the price', () {
    Future<CheckoutRefused> refusalFrom(http.Response response) async {
      final _Harness harness = _Harness(
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[response],
      );
      await harness.readyBasket();
      await harness.checkout.place();
      return harness.checkout.stage as CheckoutRefused;
    }

    test('a full kitchen is retryable', () async {
      final CheckoutRefused refused = await refusalFrom(
        problem('RESOURCE_CONFLICT', reason: 'AT_CAPACITY'),
      );

      expect(refused.kind, CheckoutRefusalKind.atCapacity);
    });

    test('sold-out items are named', () async {
      final CheckoutRefused refused = await refusalFrom(
        problem(
          'RESOURCE_CONFLICT',
          reason: 'INSUFFICIENT_STOCK',
          extensions: <String, Object?>{
            'unavailableItems': <String>['variant-3'],
          },
        ),
      );

      expect(refused.kind, CheckoutRefusalKind.itemsUnavailable);
      expect(refused.unavailableItems, <String>['variant-3']);
    });

    test('a payment method the branch cannot take is its own answer', () async {
      final CheckoutRefused refused = await refusalFrom(
        problem('RESOURCE_CONFLICT', reason: 'PAYMENT_METHOD_UNAVAILABLE'),
      );

      expect(refused.kind, CheckoutRefusalKind.paymentMethodUnavailable);
    });

    test('403 is surfaced as itself, which is the ADR 0025 open item', () async {
      final CheckoutRefused refused = await refusalFrom(
        problem(
          'INSUFFICIENT_CAPABILITY',
          status: 403,
          extensions: <String, Object?>{'requiredCapability': 'ORDER_PLACE'},
        ),
      );

      expect(refused.kind, CheckoutRefusalKind.notPermitted);
      expect(refused.reason, 'ORDER_PLACE');
    });
  });

  group('paying', () {
    test('cash asks the platform for no surface at all', () async {
      final _Harness harness = _Harness(
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[jsonResponse(orderJson())],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(harness.launcher.opened, isEmpty);
      expect(harness.checkout.paymentStage, PaymentStage.notNeeded);
    });

    test('a provider order is handed off to the link the platform returned', () async {
      final _Harness harness = _Harness(
        method: PaymentMethodChoice.click,
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[
          jsonResponse(orderJson(status: 'PAYMENT_AUTHORIZING')),
        ],
        paymentAnswers: <http.Response>[
          jsonResponse(<String, Object?>{
            'attemptId': 'attempt-1',
            'merchantTransId': 'mt-1',
            'provider': 'CLICK',
            'presentation': 'PAYMENT_LINK',
            'checkoutUrl': 'https://my.click.uz/services/pay?x=1',
            'qrPayload': null,
            'expiresAt': '2026-08-24T13:00:00Z',
            'amountMinor': 84000,
            'currency': 'UZS',
            'rePresented': false,
            'presentationCount': 1,
          }),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(
        harness.launcher.opened.single.toString(),
        'https://my.click.uz/services/pay?x=1',
      );
      expect(harness.checkout.paymentStage, PaymentStage.handedOff);
      // Whole som, undivided, exactly as the platform's own response documents.
      expect(harness.checkout.paymentSession!.amount.amountMinor, 84000);
    });

    test('an uncertain outcome is never retried', () async {
      final _Harness harness = _Harness(
        method: PaymentMethodChoice.payme,
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[
          jsonResponse(orderJson(status: 'PAYMENT_AUTHORIZING')),
        ],
        paymentAnswers: <http.Response>[
          problem('RESOURCE_CONFLICT', reason: 'PAYMENT_OUTCOME_UNCERTAIN'),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();
      expect(harness.checkout.paymentStage, PaymentStage.outcomeUncertain);

      // A second surface would be a second charge with nothing to key it on.
      await harness.checkout.openPayment();

      expect(harness.launcher.opened, isEmpty);
      expect(harness.paymentSessions, hasLength(1));
    });

    test('a device that cannot open the link leaves the order intact', () async {
      final _Harness harness = _Harness(
        method: PaymentMethodChoice.click,
        launcher: _Launcher(succeeds: false),
        cartAnswers: basketOpening(),
        checkoutAnswers: <http.Response>[
          jsonResponse(orderJson(status: 'PAYMENT_AUTHORIZING')),
        ],
        paymentAnswers: <http.Response>[
          jsonResponse(<String, Object?>{
            'attemptId': 'attempt-1',
            'merchantTransId': 'mt-1',
            'provider': 'CLICK',
            'presentation': 'PAYMENT_LINK',
            'checkoutUrl': 'https://my.click.uz/services/pay?x=1',
            'amountMinor': 84000,
            'currency': 'UZS',
            'rePresented': false,
            'presentationCount': 1,
          }),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.place();

      expect(harness.checkout.paymentStage, PaymentStage.couldNotOpen);
      expect(harness.checkout.stage, isA<CheckoutPlaced>());
    });
  });

  group('delivery', () {
    test('the fee comes from the resolver and the shortfall from its own figures',
        () async {
      final _Harness harness = _Harness(
        mode: FulfilmentMode.delivery,
        cartAnswers: basketOpening(),
        feeAnswers: <http.Response>[
          jsonResponse(<String, Object?>{
            'outcome': 'RESOLVED',
            'reasonCode': null,
            'available': true,
            'feeMinor': 15000,
            'currency': 'UZS',
            'minBasketMinor': 100000,
            'freeDeliveryFromMinor': 200000,
            'distanceMeters': 3200,
            'distanceSource': 'ROUTING',
          }),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.setDestination(const GeoPoint(41.31, 69.28));

      expect(harness.checkout.deliveryFee!.fee!.amountMinor, 15000);
      // 100 000 minimum against an 84 000 basket. Two figures the resolver
      // published, subtracted; no fee is worked out here.
      expect(harness.checkout.deliveryShortfall!.amountMinor, 16000);
    });

    test('the point and the basket subtotal are what the resolver is asked with',
        () async {
      final _Harness harness = _Harness(
        mode: FulfilmentMode.delivery,
        cartAnswers: basketOpening(),
        feeAnswers: <http.Response>[
          jsonResponse(<String, Object?>{
            'outcome': 'RESOLVED',
            'available': true,
            'feeMinor': 15000,
            'currency': 'UZS',
          }),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.setDestination(const GeoPoint(41.31, 69.28));

      final Uri asked = harness.feeQueries.single.request.url;
      expect(asked.path, '$basePath/locations/branch-1/delivery-fee');
      expect(asked.queryParameters['lat'], '41.31');
      expect(asked.queryParameters['subtotalMinor'], '84000');
    });

    test('a point outside every zone is a refusal with no fee', () async {
      final _Harness harness = _Harness(
        mode: FulfilmentMode.delivery,
        cartAnswers: basketOpening(),
        feeAnswers: <http.Response>[
          jsonResponse(<String, Object?>{
            'outcome': 'OUT_OF_ZONE',
            'reasonCode': 'OUT_OF_ZONE',
            'available': false,
            'currency': 'UZS',
          }),
        ],
      );
      await harness.readyBasket();

      await harness.checkout.setDestination(const GeoPoint(0, 0));

      expect(harness.checkout.deliveryFee!.available, isFalse);
      expect(harness.checkout.deliveryFee!.fee, isNull);
    });
  });
}
