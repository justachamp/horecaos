import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/features/orders/application/order_list_controller.dart';
import 'package:qoida_mobile/src/features/orders/data/order_codes.dart';
import 'package:qoida_mobile/src/features/orders/data/order_models.dart';
import 'package:qoida_mobile/src/format/money.dart';

import 'orders_harness.dart';

void main() {
  /// A transport that answers each call from the queue, so a test can script a
  /// page, then a failure, then a retry.
  MockClient scripted(List<http.Response> responses) {
    int call = 0;
    return MockClient((http.Request request) async {
      final http.Response response =
          responses[call < responses.length ? call : responses.length - 1];
      call++;
      return response;
    });
  }

  OrderListController controllerOver(List<http.Response> responses) =>
      OrderListController(repository: repositoryOver(scripted(responses)));

  test('the first page lands and the cursor is remembered', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(
        pageJson(<Map<String, Object?>>[orderJson()], nextCursor: 'c1'),
      ),
    ]);

    await controller.load();

    expect(controller.orders, hasLength(1));
    expect(controller.hasMore, isTrue);
    expect(controller.isEmpty, isFalse);
    controller.dispose();
  });

  test('a continuation appends rather than replacing', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(
        pageJson(<Map<String, Object?>>[
          orderJson(publicOrderNumber: 'A-1'),
        ], nextCursor: 'c1'),
      ),
      jsonResponse(
        pageJson(<Map<String, Object?>>[orderJson(publicOrderNumber: 'A-2')]),
      ),
    ]);

    await controller.load();
    await controller.loadMore();

    expect(
      controller.orders.map((OrderSummary o) => o.publicOrderNumber),
      <String>['A-1', 'A-2'],
    );
    expect(controller.hasMore, isFalse);
    controller.dispose();
  });

  test('at the end of the collection there is nothing more to ask for', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(pageJson(<Map<String, Object?>>[orderJson()])),
      jsonResponse(pageJson(<Map<String, Object?>>[orderJson()])),
    ]);

    await controller.load();
    await controller.loadMore();

    // A second request would have appended a duplicate row.
    expect(controller.orders, hasLength(1));
    controller.dispose();
  });

  test('an empty first page is empty, not a failure', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(pageJson(<Map<String, Object?>>[])),
    ]);

    await controller.load();

    expect(controller.isEmpty, isTrue);
    expect(controller.failure, isNull);
    controller.dispose();
  });

  test('a failed first page keeps the failure and shows no rows', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      problemResponse(403, 'INSUFFICIENT_CAPABILITY'),
    ]);

    await controller.load();

    expect(controller.failure, isNotNull);
    expect(controller.orders, isEmpty);
    expect(controller.isEmpty, isFalse, reason: 'an error is not an empty list');
    controller.dispose();
  });

  test('a failed continuation keeps the rows already on screen', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(
        pageJson(<Map<String, Object?>>[orderJson()], nextCursor: 'c1'),
      ),
      problemResponse(503, 'INTERNAL_ERROR'),
    ]);

    await controller.load();
    await controller.loadMore();

    expect(controller.orders, hasLength(1));
    expect(controller.failure, isNotNull);
    // The cursor survives, so the retry continues the iteration instead of
    // starting the list again underneath the customer.
    expect(controller.hasMore, isTrue);
    controller.dispose();
  });

  test('a refresh restarts the iteration rather than stitching pages', () async {
    late Uri lastUrl;
    int call = 0;
    final OrderListController controller = OrderListController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          lastUrl = request.url;
          call++;
          return jsonResponse(
            pageJson(<Map<String, Object?>>[
              orderJson(publicOrderNumber: 'A-$call'),
            ], nextCursor: 'c$call'),
          );
        }),
      ),
    );

    await controller.load();
    await controller.loadMore();
    await controller.refresh();

    expect(
      lastUrl.queryParameters.containsKey('cursor'),
      isFalse,
      reason: 'a cursor encodes the sort and filters it was issued for',
    );
    expect(controller.orders, hasLength(1));
    controller.dispose();
  });

  test('a row updated on the detail screen updates in the list', () async {
    final OrderListController controller = controllerOver(<http.Response>[
      jsonResponse(
        pageJson(<Map<String, Object?>>[
          orderJson(orderId: 'order-1', status: 'PREPARING'),
        ]),
      ),
    ]);
    await controller.load();

    controller.replace(
      OrderSummary(
        orderId: 'order-1',
        publicOrderNumber: 'A-1042',
        status: OrderStatus.fulfilling,
        total: const Money(84000, 'UZS'),
        placedAt: DateTime.utc(2026, 8, 24, 9),
      ),
    );

    expect(controller.orders.single.status, OrderStatus.fulfilling);
    controller.dispose();
  });
}
