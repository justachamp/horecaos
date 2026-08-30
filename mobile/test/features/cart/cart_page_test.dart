import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:qoida_mobile/src/features/cart/cart_controller.dart';
import 'package:qoida_mobile/src/features/cart/cart_item_naming.dart';
import 'package:qoida_mobile/src/features/cart/cart_page.dart';
import 'package:qoida_mobile/src/features/cart/cart_repository.dart';

import 'support.dart';

/// Names for the two variants these tests put in a basket.
final CartItemNaming _naming = DelegatedCartItemNaming(
  variantNames: (String variantId) => switch (variantId) {
    'variant-1' => const CartItemName(productName: 'Lagman', orderable: true),
    'variant-2' => const CartItemName(
      productName: 'Somsa',
      orderable: false,
      variantLabel: 'SOM-L',
    ),
    _ => null,
  },
  optionLabels: (String optionId) =>
      optionId == 'no-onions' ? 'Without onions' : null,
);

void main() {
  CartController controllerFor(List<http.Response> answers) => CartController(
    now: fixtureClock,
    repository: CartRepository(
      api: client((http.Request request) async => answers.removeAt(0)),
      scope: testScope,
    ),
  );

  Future<CartController> loaded(
    WidgetTester tester,
    List<http.Response> answers, {
    bool price = false,
  }) async {
    final CartController controller = controllerFor(answers);
    await controller.load('cart-1');
    if (price) {
      await controller.refreshPrice();
    }
    await pumpScreen(
      tester,
      CartPage(controller: controller, naming: _naming),
    );
    return controller;
  }

  testWidgets('an empty basket says so, and does not look like a failure', (
    WidgetTester tester,
  ) async {
    await loaded(tester, <http.Response>[jsonResponse(cartJson())]);

    expect(find.text('Your basket is empty'), findsOneWidget);
  });

  testWidgets('a line shows its name, its choices and its quantity', (
    WidgetTester tester,
  ) async {
    final CartController controller = await loaded(tester, <http.Response>[
      jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
      jsonResponse(
        cartJson(
          version: 2,
          lines: <Map<String, Object?>>[lineJson(quantity: 2)],
        ),
      ),
    ]);
    await controller.putLine(
      lineKey: 'line-1',
      variantId: 'variant-1',
      quantity: 2,
      modifierOptionIds: <String>['no-onions'],
    );
    await tester.pump();

    expect(find.text('Lagman'), findsOneWidget);
    expect(find.text('Without onions'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
  });

  testWidgets('a variant the menu does not name gets a neutral label', (
    WidgetTester tester,
  ) async {
    await loaded(tester, <http.Response>[
      jsonResponse(
        cartJson(
          lines: <Map<String, Object?>>[lineJson(variantId: 'variant-unknown')],
        ),
      ),
    ]);

    // "We do not know what this is" and "this was withdrawn" are different
    // facts, and only the first one is true here.
    expect(find.text('Item'), findsOneWidget);
  });

  testWidgets('a sold-out line is marked with a dot and words, not colour alone', (
    WidgetTester tester,
  ) async {
    await loaded(tester, <http.Response>[
      jsonResponse(
        cartJson(
          lines: <Map<String, Object?>>[lineJson(variantId: 'variant-2')],
        ),
      ),
    ]);

    expect(find.text('Sold out at this branch'), findsOneWidget);
  });

  group('the totals block', () {
    testWidgets('renders whole som, grouped and undivided', (
      WidgetTester tester,
    ) async {
      await loaded(
        tester,
        <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson(subtotal: 84000, tax: 0, total: 84000)),
        ],
        price: true,
      );

      // 84 000 so'm, with no-break spaces so a price never wraps mid-number.
      // A formatter that asked ICU for the UZS exponent would print 840 here.
      expect(find.text(uzs('84 000')), findsWidgets);
    });

    testWidgets('shows nothing at all until the server has priced the basket', (
      WidgetTester tester,
    ) async {
      await loaded(tester, <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
      ]);

      expect(find.text('The price is not confirmed yet.'), findsOneWidget);
      expect(find.textContaining("so'm"), findsNothing);
    });
  });

  group('the primary action', () {
    testWidgets('offers to confirm the price while there is none', (
      WidgetTester tester,
    ) async {
      await loaded(tester, <http.Response>[
        jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
      ]);

      expect(find.widgetWithText(FilledButton, 'Confirm the price'), findsOneWidget);
    });

    testWidgets('offers checkout only once a quote is bound', (
      WidgetTester tester,
    ) async {
      await loaded(
        tester,
        <http.Response>[
          jsonResponse(cartJson(lines: <Map<String, Object?>>[lineJson()])),
          jsonResponse(pricedJson()),
        ],
        price: true,
      );

      expect(find.widgetWithText(FilledButton, 'Checkout'), findsOneWidget);
    });
  });

  testWidgets('a refused principal is explained, not disguised', (
    WidgetTester tester,
  ) async {
    final CartController controller = controllerFor(<http.Response>[
      problem(
        'INSUFFICIENT_CAPABILITY',
        status: 403,
        extensions: <String, Object?>{'requiredCapability': 'ORDER_PLACE'},
      ),
    ]);
    await controller.load('cart-1');
    await pumpScreen(tester, CartPage(controller: controller));

    expect(
      find.text('Ordering is not open to this account yet'),
      findsOneWidget,
    );
    expect(find.text('Reference cid-1'), findsOneWidget);
  });

  testWidgets('the whole screen renders in Russian', (WidgetTester tester) async {
    final CartController controller = controllerFor(<http.Response>[
      jsonResponse(cartJson()),
    ]);
    await controller.load('cart-1');
    await pumpScreen(
      tester,
      CartPage(controller: controller),
      locale: const Locale('ru'),
    );

    // The interface language is the customer's. There is no English-only
    // screen anywhere in this application.
    expect(find.text('Корзина'), findsOneWidget);
    expect(find.text('Корзина пуста'), findsOneWidget);
  });
}
