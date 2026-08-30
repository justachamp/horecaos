import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/features/orders/data/order_models.dart';
import 'package:horecaos_mobile/src/features/orders/presentation/orders_page.dart';
import 'package:horecaos_mobile/src/features/orders/presentation/widgets/order_card.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';

import 'orders_harness.dart';

void main() {
  Widget page(
    MockClient transport, {
    DateTime? now,
    void Function(BuildContext, OrderSummary)? onOpenOrder,
  }) => host(
    OrdersPage(
      repository: repositoryOver(transport),
      now: now,
      onOpenOrder: onOpenOrder,
    ),
  );

  Future<AppLocalizations> open(WidgetTester tester, Widget widget) async {
    await tester.pumpWidget(widget);
    await tester.pumpAndSettle();
    return localisations();
  }

  testWidgets('renders a row per order, with its number, total and status', (
    WidgetTester tester,
  ) async {
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async => jsonResponse(
            pageJson(<Map<String, Object?>>[
              orderJson(publicOrderNumber: 'A-1042', status: 'PREPARING'),
              orderJson(
                orderId: 'order-2',
                publicOrderNumber: 'A-1041',
                status: 'COMPLETED',
                closedAt: '2026-08-23T20:00:00Z',
              ),
            ]),
          ),
        ),
      ),
    );

    expect(find.byType(OrderCard), findsNWidgets(2));
    expect(find.text(l10n.ordersNumber('A-1042')), findsOneWidget);
    expect(find.text(l10n.orderStatusPreparing), findsOneWidget);
    expect(find.text(l10n.orderStatusCompleted), findsOneWidget);
    // The identifier the API uses is never on screen.
    expect(find.textContaining('018f0000'), findsNothing);
    expect(find.text('84\u00A0000\u00A0сум'), findsNWidgets(2));
  });

  testWidgets('marks a late order without hiding what it is doing', (
    WidgetTester tester,
  ) async {
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async => jsonResponse(
            pageJson(<Map<String, Object?>>[
              orderJson(status: 'PREPARING', promisedAt: '2026-08-24T09:45:00Z'),
            ]),
          ),
        ),
        now: DateTime.utc(2026, 8, 24, 10, 30),
      ),
    );

    expect(find.text(l10n.orderLate), findsOneWidget);
    expect(find.text(l10n.orderStatusPreparing), findsOneWidget);
  });

  testWidgets('an empty history is a statement, not an error', (
    WidgetTester tester,
  ) async {
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async =>
              jsonResponse(pageJson(<Map<String, Object?>>[])),
        ),
      ),
    );

    expect(find.text(l10n.ordersEmptyTitle), findsOneWidget);
    expect(find.text(l10n.ordersEmptyBody), findsOneWidget);
    // No retry: there is nothing to retry, and offering one implies a failure.
    expect(find.text(l10n.retry), findsNothing);
  });

  testWidgets('a refusal offers a retry and names no capability', (
    WidgetTester tester,
  ) async {
    // This is the state a real customer gets today: the storefront endpoints
    // declare capabilities no customer principal holds until ADR 0025 settles
    // what a non-staff principal is.
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async =>
              problemResponse(403, 'INSUFFICIENT_CAPABILITY'),
        ),
      ),
    );

    expect(find.text(l10n.ordersUnavailableTitle), findsOneWidget);
    expect(find.text(l10n.retry), findsOneWidget);
    expect(find.textContaining('order.place'), findsNothing);
    expect(find.textContaining('403'), findsNothing);
  });

  testWidgets('a missing list endpoint reads as unavailable, not as empty', (
    WidgetTester tester,
  ) async {
    // Until the storefront list is written, this call answers 404. An empty
    // state would tell the customer they have never ordered, which is a lie.
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async =>
              problemResponse(404, 'RESOURCE_NOT_FOUND'),
        ),
      ),
    );

    expect(find.text(l10n.orderNotFoundTitle), findsOneWidget);
    expect(find.text(l10n.ordersEmptyTitle), findsNothing);
  });

  testWidgets('a lost connection is told apart from a refusal', (
    WidgetTester tester,
  ) async {
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient(
          (http.Request request) async =>
              throw http.ClientException('Failed host lookup'),
        ),
      ),
    );

    expect(find.text(l10n.ordersOfflineTitle), findsOneWidget);
    expect(find.text(l10n.ordersOfflineBody), findsOneWidget);
  });

  testWidgets('the retry action asks again', (WidgetTester tester) async {
    int calls = 0;
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient((http.Request request) async {
          calls++;
          if (calls == 1) return problemResponse(503, 'INTERNAL_ERROR');
          return jsonResponse(pageJson(<Map<String, Object?>>[orderJson()]));
        }),
      ),
    );

    await tester.tap(find.text(l10n.retry));
    await tester.pumpAndSettle();

    expect(find.byType(OrderCard), findsOneWidget);
  });

  testWidgets('a failed continuation keeps the rows and offers another go', (
    WidgetTester tester,
  ) async {
    int calls = 0;
    final AppLocalizations l10n = await open(
      tester,
      page(
        MockClient((http.Request request) async {
          calls++;
          if (calls == 1) {
            return jsonResponse(
              pageJson(<Map<String, Object?>>[
                for (int i = 0; i < 8; i++)
                  orderJson(
                    orderId: 'order-$i',
                    publicOrderNumber: 'A-$i',
                  ),
              ], nextCursor: 'c1'),
            );
          }
          return problemResponse(503, 'INTERNAL_ERROR');
        }),
      ),
    );

    // Scrolling to the end triggers the continuation, which fails.
    await tester.drag(find.byType(ListView), const Offset(0, -2000));
    await tester.pumpAndSettle();

    expect(find.text(l10n.ordersLoadMore), findsOneWidget);
    expect(find.byType(OrderCard), findsWidgets, reason: 'the rows stay');
  });

  testWidgets('a row survives a phone-sized screen and a long number', (
    WidgetTester tester,
  ) async {
    // A layout overflow throws in a widget test, so this test passing is the
    // assertion. The late overlay is the widest a row ever gets.
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await open(
      tester,
      page(
        MockClient(
          (http.Request request) async => jsonResponse(
            pageJson(<Map<String, Object?>>[
              orderJson(
                publicOrderNumber: 'TASHKENT-CHILANZAR-2026-000142',
                status: 'AWAITING_APPROVAL',
                promisedAt: '2026-08-24T09:45:00Z',
              ),
            ]),
          ),
        ),
        now: DateTime.utc(2026, 8, 24, 11),
      ),
    );

    expect(find.byType(OrderCard), findsOneWidget);
  });

  testWidgets('tapping a row opens it', (WidgetTester tester) async {
    OrderSummary? opened;
    await open(
      tester,
      page(
        MockClient(
          (http.Request request) async => jsonResponse(
            pageJson(<Map<String, Object?>>[orderJson()]),
          ),
        ),
        onOpenOrder: (BuildContext context, OrderSummary order) =>
            opened = order,
      ),
    );

    await tester.tap(find.byType(OrderCard));
    await tester.pumpAndSettle();

    expect(opened?.publicOrderNumber, 'A-1042');
  });
}
