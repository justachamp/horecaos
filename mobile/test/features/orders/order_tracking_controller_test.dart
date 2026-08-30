import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/features/orders/application/order_tracking_controller.dart';
import 'package:horecaos_mobile/src/features/orders/data/order_codes.dart';

import 'orders_harness.dart';

/// The live view's polling, at a compressed interval.
///
/// The interval is a constructor parameter so a test can run in milliseconds
/// what production runs in twenty seconds. The number itself — twenty — is
/// asserted separately, because it is ADR 0045's and not an implementation
/// detail somebody may tune away.
void main() {
  const Duration tick = Duration(milliseconds: 20);

  /// Waits for a handful of poll intervals to elapse.
  Future<void> waitTicks(int count) =>
      Future<void>.delayed(tick * count + const Duration(milliseconds: 10));

  test('the production cadence is ADR 0045\'s twenty seconds', () {
    expect(
      OrderTrackingController.defaultPollInterval,
      const Duration(seconds: 20),
    );
  });

  test('reads once on start', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          return jsonResponse(orderJson());
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();

    expect(calls, 1);
    expect(controller.order?.status, OrderStatus.preparing);
    expect(controller.updatedAt, isNotNull);
    controller.dispose();
  });

  test('keeps polling while the order is live', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          return jsonResponse(orderJson());
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    await waitTicks(3);
    controller.dispose();

    expect(calls, greaterThan(1));
  });

  test('stops the moment the order reaches a terminal status', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          // Live on the first read, delivered on the second.
          return jsonResponse(
            orderJson(
              status: calls == 1 ? 'FULFILLING' : 'COMPLETED',
              closedAt: '2026-08-24T09:51:00Z',
            ),
          );
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    await waitTicks(4);
    final int afterTerminal = calls;
    await waitTicks(4);

    expect(controller.order?.status, OrderStatus.completed);
    expect(controller.isLive, isFalse);
    expect(
      calls,
      afterTerminal,
      reason: 'nothing about a finished order changes again',
    );
    controller.dispose();
  });

  test('a failing poll keeps the last good order on screen', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          if (calls == 1) return jsonResponse(orderJson());
          return problemResponse(503, 'INTERNAL_ERROR');
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    await waitTicks(2);

    expect(controller.order, isNotNull, reason: 'a failed poll unmakes nothing');
    expect(controller.failure, isNotNull);
    controller.dispose();
  });

  test('backs off after consecutive failures instead of hammering', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          return problemResponse(503, 'INTERNAL_ERROR');
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    await waitTicks(12);
    controller.dispose();

    // Twelve intervals without backoff would be thirteen calls. The skipping
    // takes it to roughly a third of that; the exact number depends on timer
    // scheduling, so the assertion is on the shape and not on a count.
    expect(calls, lessThan(8));
    expect(calls, greaterThan(1), reason: 'it does keep trying');
  });

  test('a retry clears the backoff and asks immediately', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          if (calls <= 2) return problemResponse(503, 'INTERNAL_ERROR');
          return jsonResponse(orderJson());
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    await controller.retry();
    await controller.retry();

    expect(controller.order, isNotNull);
    expect(controller.failure, isNull);
    controller.dispose();
  });

  test('pause stops the timer and keeps what was read', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          return jsonResponse(orderJson());
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    controller.pause();
    final int paused = calls;
    await waitTicks(4);

    expect(calls, paused, reason: 'a backgrounded screen polls nothing');
    expect(controller.order, isNotNull);

    await controller.resume();
    expect(calls, greaterThan(paused), reason: 'resuming reads at once');
    controller.dispose();
  });

  test('disposing stops everything', () async {
    int calls = 0;
    final OrderTrackingController controller = OrderTrackingController(
      repository: repositoryOver(
        MockClient((http.Request request) async {
          calls++;
          return jsonResponse(orderJson());
        }),
      ),
      orderId: 'order-1',
      pollInterval: tick,
    );

    await controller.start();
    controller.dispose();
    final int atDispose = calls;
    await waitTicks(4);

    expect(calls, atDispose);
  });
}
