import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:horecaos_mobile/src/api/api_client.dart';
import 'package:horecaos_mobile/src/features/cart/cart_controller.dart';
import 'package:horecaos_mobile/src/features/cart/cart_models.dart';
import 'package:horecaos_mobile/src/features/cart/cart_repository.dart';
import 'package:horecaos_mobile/src/features/checkout/checkout_controller.dart';
import 'package:horecaos_mobile/src/features/checkout/checkout_models.dart';
import 'package:horecaos_mobile/src/features/checkout/checkout_page.dart';
import 'package:horecaos_mobile/src/features/checkout/checkout_repository.dart';

import '../cart/support.dart';

/// A checkout screen over scripted transports, routed by path.
class _Screen {
  _Screen({
    required this._cartAnswers,
    List<http.Response> checkoutAnswers = const <http.Response>[],
    FulfilmentMode mode = FulfilmentMode.pickup,
  }) : _checkoutAnswers = <http.Response>[...checkoutAnswers] {
    cart = CartController(
      now: fixtureClock,
      repository: CartRepository(
        api: client((http.Request request) async => _cartAnswers.removeAt(0)),
        scope: testScope,
      ),
    );
    checkout = CheckoutController(
      cart: cart,
      repository: CheckoutRepository(
        api: client((http.Request request) async {
          if (request.url.path.endsWith('/checkouts')) {
            return _checkoutAnswers.removeAt(0);
          }
          return problem('INSUFFICIENT_CAPABILITY', status: 403);
        }),
        scope: testScope,
      ),
      fulfilmentMode: mode,
      now: fixtureClock,
    );
  }

  final List<http.Response> _cartAnswers;
  final List<http.Response> _checkoutAnswers;

  late final CartController cart;
  late final CheckoutController checkout;

  Future<void> readyBasket() async {
    await cart.load('cart-1');
    await cart.refreshPrice();
  }

  Widget get page => CheckoutPage(controller: checkout, cart: cart);
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

void main() {
  testWidgets('the fulfilment mode is shown and explained, not offered as a control',
      (WidgetTester tester) async {
    final _Screen screen = _Screen(
      mode: FulfilmentMode.pickup,
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson()),
      ],
    );
    await screen.readyBasket();
    await pumpScreen(tester, screen.page);

    expect(find.text('Pickup'), findsOneWidget);
    expect(
      find.text('Chosen when the basket was opened. Changing it starts a new basket.'),
      findsOneWidget,
    );
  });

  testWidgets('the total on the button is the server\'s, in whole som', (
    WidgetTester tester,
  ) async {
    final _Screen screen = _Screen(
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson(subtotal: 84000, tax: 0, total: 84000)),
      ],
    );
    await screen.readyBasket();
    await pumpScreen(tester, screen.page);

    expect(find.text('To pay'), findsOneWidget);
    expect(find.text(uzs('84 000')), findsWidgets);
  });

  group('a price that moved', () {
    testWidgets('shows both totals and asks again, rather than charging quietly',
        (WidgetTester tester) async {
      final _Screen screen = _Screen(
        cartAnswers: <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(total: 84000)),
          jsonResponse(
            pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 90000),
          ),
        ],
        checkoutAnswers: <http.Response>[
          problem('PRICE_CHANGED', reason: 'PRICE_CHANGED'),
        ],
      );
      await screen.readyBasket();
      await pumpScreen(tester, screen.page);

      await screen.checkout.place();
      await tester.pump();

      expect(find.text('The price changed'), findsOneWidget);
      expect(
        find.text('The order was not placed. Look at the new price before you decide.'),
        findsOneWidget,
      );
      expect(find.text('Before'), findsOneWidget);
      expect(find.text(uzs('84 000')), findsOneWidget);
      expect(find.text('Now'), findsOneWidget);
      expect(find.text(uzs('90 000')), findsWidgets);
      expect(
        find.widgetWithText(TextButton, 'Accept and place the order'),
        findsOneWidget,
      );
    });

    testWidgets('says the total is unchanged rather than printing it twice', (
      WidgetTester tester,
    ) async {
      final _Screen screen = _Screen(
        cartAnswers: <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(total: 84000)),
          jsonResponse(
            pricedJson(quoteId: 'q-2', contextHash: 'h-2', total: 84000),
          ),
        ],
        checkoutAnswers: <http.Response>[
          problem('RESOURCE_CONFLICT', reason: 'PUBLICATION_CHANGED'),
        ],
      );
      await screen.readyBasket();
      await pumpScreen(tester, screen.page);

      await screen.checkout.place();
      await tester.pump();

      expect(find.text('The menu changed'), findsOneWidget);
      expect(find.text('The total is the same as before.'), findsOneWidget);
      expect(find.text('Before'), findsNothing);
    });
  });

  group('once there is an order', () {
    Future<void> placeWith(
      WidgetTester tester,
      http.Response answer, {
      Map<String, String>? headers,
    }) async {
      final _Screen screen = _Screen(
        cartAnswers: <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson()),
        ],
        checkoutAnswers: <http.Response>[answer],
      );
      await screen.readyBasket();
      await pumpScreen(tester, screen.page);
      await screen.checkout.place();
      await tester.pump();
    }

    testWidgets('the number is what the customer and the branch say out loud', (
      WidgetTester tester,
    ) async {
      await placeWith(tester, jsonResponse(orderJson()));

      expect(find.text('Order placed'), findsOneWidget);
      expect(find.text('Order A-1042'), findsOneWidget);
      expect(find.text('Accepted'), findsOneWidget);
    });

    testWidgets('a promise that was not published is said to be absent', (
      WidgetTester tester,
    ) async {
      await placeWith(tester, jsonResponse(orderJson()));

      // No customer-facing endpoint publishes the promised time today. Saying
      // so is the honest answer; a client-side estimate would contradict the
      // number the branch is actually measured against.
      expect(find.text('No time was promised for this order.'), findsOneWidget);
    });

    testWidgets('a replay is reported as a success and not as a duplicate', (
      WidgetTester tester,
    ) async {
      await placeWith(
        tester,
        jsonResponse(
          orderJson(),
          headers: <String, String>{
            HorecaOSApiClient.idempotencyReplayedHeader: 'true',
          },
        ),
      );

      expect(
        find.text('This order was already placed. It has not been placed twice.'),
        findsOneWidget,
      );
    });

    testWidgets('a cash order is told to pay at handover, with nothing to open', (
      WidgetTester tester,
    ) async {
      await placeWith(tester, jsonResponse(orderJson()));

      expect(find.text('Pay when you get the order.'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Pay now'), findsNothing);
    });
  });

  testWidgets('choosing a payment method changes what will be sent', (
    WidgetTester tester,
  ) async {
    final _Screen screen = _Screen(
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson()),
      ],
    );
    await screen.readyBasket();
    await pumpScreen(tester, screen.page);

    expect(screen.checkout.paymentMethod, PaymentMethodChoice.cash);
    // A brand name, spelled the same in all three locales, and therefore not
    // an ARB message.
    await tester.tap(find.text('Click'));
    await tester.pump();

    expect(screen.checkout.paymentMethod, PaymentMethodChoice.click);
  });

  testWidgets('a refused principal explains that nothing was ordered', (
    WidgetTester tester,
  ) async {
    final _Screen screen = _Screen(
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson()),
      ],
      checkoutAnswers: <http.Response>[
        problem(
          'INSUFFICIENT_CAPABILITY',
          status: 403,
          extensions: <String, Object?>{'requiredCapability': 'ORDER_PLACE'},
        ),
      ],
    );
    await screen.readyBasket();
    await pumpScreen(tester, screen.page);

    await screen.checkout.place();
    await tester.pump();

    expect(find.text('This account cannot place orders yet'), findsOneWidget);
    expect(
      find.text('Nothing was ordered and nothing was charged.'),
      findsOneWidget,
    );
  });

  testWidgets('the delivery section only appears for a delivery basket', (
    WidgetTester tester,
  ) async {
    final _Screen pickup = _Screen(
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson()),
      ],
    );
    await pickup.readyBasket();
    await pumpScreen(tester, pickup.page);

    expect(find.text('Where we deliver'), findsNothing);

    final _Screen delivery = _Screen(
      mode: FulfilmentMode.delivery,
      cartAnswers: <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
        jsonResponse(pricedJson()),
      ],
    );
    await delivery.readyBasket();
    await pumpScreen(tester, delivery.page);

    expect(find.text('Where we deliver'), findsOneWidget);
    expect(find.text('Not set yet'), findsOneWidget);
  });
}
