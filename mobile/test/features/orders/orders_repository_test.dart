import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/api/api_exception.dart';
import 'package:horecaos_mobile/src/api/page.dart';
import 'package:horecaos_mobile/src/features/orders/data/order_codes.dart';
import 'package:horecaos_mobile/src/features/orders/data/order_models.dart';
import 'package:horecaos_mobile/src/features/orders/data/orders_repository.dart';
import 'package:horecaos_mobile/src/format/money.dart';

import 'orders_harness.dart';

void main() {
  group('the list', () {
    test('calls the storefront path with the cursor parameters', () async {
      late Uri seen;
      final OrdersRepository repository = repositoryOver(
        MockClient((http.Request request) async {
          seen = request.url;
          return jsonResponse(pageJson(<Map<String, Object?>>[]));
        }),
      );

      await repository.list(cursor: 'opaque-cursor', limit: 20);

      expect(seen.path, ordersPath);
      expect(seen.queryParameters['cursor'], 'opaque-cursor');
      expect(seen.queryParameters['limit'], '20');
      // No page number, no offset, and no account: the storefront resolves the
      // account from the caller's own token, so a client cannot name one.
      expect(seen.queryParameters.containsKey('page'), isFalse);
      expect(seen.queryParameters.containsKey('offset'), isFalse);
      expect(seen.queryParameters.containsKey('accountId'), isFalse);
    });

    test('the first page carries no cursor at all', () async {
      late Uri seen;
      final OrdersRepository repository = repositoryOver(
        MockClient((http.Request request) async {
          seen = request.url;
          return jsonResponse(pageJson(<Map<String, Object?>>[]));
        }),
      );

      await repository.list();

      expect(seen.queryParameters.containsKey('cursor'), isFalse);
    });

    test('decodes the envelope and stops at a null cursor', () async {
      final OrdersRepository repository = repositoryOver(
        MockClient(
          (http.Request request) async => jsonResponse(
            pageJson(<Map<String, Object?>>[orderJson()], nextCursor: null),
          ),
        ),
      );

      final Page<OrderSummary> page = await repository.list();

      expect(page.items.single.publicOrderNumber, 'A-1042');
      expect(page.hasMore, isFalse);
    });
  });

  group('one order', () {
    Future<OrderDetail> read(Map<String, Object?> body) => repositoryOver(
      MockClient(
        (http.Request request) async =>
            jsonResponse(body, headers: <String, String>{'etag': 'W/"7"'}),
      ),
    ).read('018f0000-0000-7000-8000-00000000000a');

    test('takes the version from the ETag, not from the body', () async {
      final OrderDetail order = await read(orderJson(version: 3));
      expect(order.version, 7);
    });

    test('UZS is not divided by a hundred anywhere on the way in', () async {
      final OrderDetail order = await read(orderJson(totalMinor: 84000));
      // The platform's minor unit for som is the som (ADR 0018). 84000 stays
      // 84000; a client that asked ICU would have made this 840.
      expect(order.total.amountMinor, 84000);
      expect(order.total.currency, 'UZS');
    });

    test('decodes lines with the names the controllers actually use', () async {
      final OrderDetail order = await read(orderJson());
      final OrderLine line = order.lines.single;
      expect(line.productName, 'Lagman');
      expect(line.variantName, 'Katta');
      expect(line.quantity, 2);
      expect(line.total.amountMinor, 78000);
      expect(line.unitAmount?.amountMinor, 39000);
      expect(line.modifiers, <String>['Achchiq']);
    });

    test('an absent discount stays absent instead of becoming zero', () async {
      final OrderDetail order = await read(orderJson());
      expect(order.discount, isNull);
      expect(order.fee, isNull);
      expect(order.tax?.amountMinor, 6000);
    });

    test('survives the response the platform sends today', () async {
      // Today's `StorefrontOrderingController.OrderResponse` has no fulfilment
      // mode, no promise, no payment projection and no outcome. Every one of
      // them is optional, so the screen degrades rather than fails.
      final OrderDetail order = await read(
        orderJson(
          fulfillmentMode: null,
          promisedAt: null,
          paymentStatus: null,
          confirmedAt: null,
        ),
      );

      expect(order.fulfillmentMode, isNull);
      expect(order.promisedAt, isNull);
      expect(order.payment, isNull);
      expect(order.outcome, isNull);
      expect(order.status, OrderStatus.preparing);
      expect(order.total.amountMinor, 84000);
    });

    test('an unknown status decodes rather than throwing', () async {
      final OrderDetail order = await read(orderJson(status: 'REHEATING'));
      expect(order.status.isKnown, isFalse);
      expect(order.status.isTerminal, isFalse);
    });

    test('reads the outcome, and only its customer-facing members', () async {
      final OrderDetail order = await read(
        orderJson(
          status: 'CANCELLED',
          closedAt: '2026-08-24T09:20:00Z',
          outcome: <String, Object?>{
            'kind': 'CANCELLED',
            'systemCategory': 'ITEM_UNAVAILABLE',
            'customerRefund': 'FULL',
            'occurredAt': '2026-08-24T09:20:00Z',
            'reasonCustomerText': 'Мы отменили заказ: блюдо закончилось.',
            // Members an operations response also carries. Nothing in the model
            // can hold them, which is the point.
            'stockDisposition': 'RETURN_TO_STOCK',
            'liabilityParty': 'TENANT',
            'reasonId': '018f0000-0000-7000-8000-00000000000c',
          },
        ),
      );

      final OrderOutcome outcome = order.outcome!;
      expect(outcome.kind, TerminalOutcomeKind.cancelled);
      expect(outcome.category, OutcomeCategory.itemUnavailable);
      expect(outcome.refund, RefundPosture.full);
      expect(outcome.customerText, 'Мы отменили заказ: блюдо закончилось.');
    });

    test('a terminal order with no outcome row still says how it ended', () async {
      final OrderDetail order = await read(
        orderJson(status: 'REJECTED', closedAt: '2026-08-24T09:10:00Z'),
      );

      expect(order.outcome, isNull);
      // Derived from the status, which ADR 0039 writes in the same transaction
      // as the outcome — the same fact read from the other column.
      expect(order.effectiveOutcome?.kind, TerminalOutcomeKind.rejected);
      // And nothing is invented about why.
      expect(order.effectiveOutcome?.category, isNull);
      expect(order.effectiveOutcome?.customerText, isNull);
    });
  });

  group('lateness', () {
    final DateTime promised = DateTime.utc(2026, 8, 24, 9, 45);

    OrderSummary summary(String status) => OrderSummary(
      orderId: 'o',
      publicOrderNumber: 'A-1',
      status: OrderStatus.parse(status),
      total: const Money(84000, 'UZS'),
      placedAt: DateTime.utc(2026, 8, 24, 9),
      promisedAt: promised,
    );

    test('is the promise against the clock, and only while it is running', () {
      expect(
        summary('PREPARING').isLate(promised.add(const Duration(minutes: 1))),
        isTrue,
      );
      expect(
        summary('PREPARING').isLate(promised.subtract(const Duration(minutes: 1))),
        isFalse,
      );
      // A finished order is not late. It is finished, and the promise stopped
      // being a commitment when it ended.
      expect(
        summary('COMPLETED').isLate(promised.add(const Duration(hours: 2))),
        isFalse,
      );
      expect(
        summary('CANCELLED').isLate(promised.add(const Duration(hours: 2))),
        isFalse,
      );
    });

    test('an order with no promise is never late', () {
      final OrderSummary unpromised = OrderSummary(
        orderId: 'o',
        publicOrderNumber: 'A-1',
        status: OrderStatus.preparing,
        total: const Money(84000, 'UZS'),
        placedAt: DateTime.utc(2026, 8, 24, 9),
      );
      expect(unpromised.isLate(DateTime.utc(2030)), isFalse);
    });
  });

  group('failures reach the caller as they came', () {
    test('a 403 from the ADR 0025 gap is an ApiException, not a crash', () async {
      final OrdersRepository repository = repositoryOver(
        MockClient(
          (http.Request request) async =>
              problemResponse(403, 'INSUFFICIENT_CAPABILITY'),
        ),
      );

      await expectLater(
        repository.list(),
        throwsA(
          isA<ApiException>()
              .having((ApiException e) => e.isForbidden, 'isForbidden', isTrue)
              .having(
                (ApiException e) => e.problem.code.value,
                'code',
                'INSUFFICIENT_CAPABILITY',
              ),
        ),
      );
    });

    test('a 404 on one order is distinguishable from any other failure', () async {
      final OrdersRepository repository = repositoryOver(
        MockClient(
          (http.Request request) async =>
              problemResponse(404, 'RESOURCE_NOT_FOUND'),
        ),
      );

      await expectLater(
        repository.read('missing'),
        throwsA(
          isA<ApiException>().having(
            (ApiException e) => e.isNotFound,
            'isNotFound',
            isTrue,
          ),
        ),
      );
    });
  });
}
