import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/features/orders/presentation/order_detail_page.dart';
import 'package:horecaos_mobile/src/features/orders/presentation/widgets/milestone_rail.dart';
import 'package:horecaos_mobile/src/format/horecaos_formats.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';

import 'orders_harness.dart';

void main() {
  /// Long enough that `pumpAndSettle` — which advances the clock in 100ms
  /// steps — never fires a poll a test did not ask for.
  const Duration tick = Duration(seconds: 10);

  Widget page(
    MockClient transport, {
    DateTime? now,
    Duration pollInterval = tick,
  }) => host(
    OrderDetailPage(
      repository: repositoryOver(transport),
      orderId: 'order-1',
      publicOrderNumber: 'A-1042',
      now: now,
      pollInterval: pollInterval,
    ),
  );

  /// Finds a label inside the milestone rail rather than anywhere on the
  /// screen: "On the way" is legitimately both a milestone and the status
  /// pill's text, and a bare text finder cannot tell which one it matched.
  Finder onRail(String label) => find.descendant(
    of: find.byType(MilestoneRail),
    matching: find.text(label),
  );

  MockClient serving(Map<String, Object?> body) =>
      MockClient((http.Request request) async => jsonResponse(body));

  /// Pumps the screen, lets the first read land, and returns the localisations
  /// the assertions read their expected strings from.
  Future<AppLocalizations> open(
    WidgetTester tester,
    Widget widget,
  ) async {
    // A phone-shaped viewport builds only the rows in view, so an assertion
    // about the payment block at the bottom of a long order would pass whether
    // it was rendered or missing. The surface is made tall enough to hold the
    // whole screen, which is what makes `findsNothing` mean something.
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(widget);
    await tester.pumpAndSettle();
    return localisations();
  }

  /// Tears the screen down so the poll timer does not outlive the test.
  Future<void> close(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }

  String at(DateTime instant) => HorecaOSFormats.time(
    HorecaOSFormats.toLocal(instant),
    locale: 'ru',
  );

  group('the live view', () {
    testWidgets('draws the milestones ADR 0045 names, and only those', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(serving(orderJson(status: 'FULFILLING'))),
      );

      expect(onRail(l10n.orderMilestoneAccepted), findsOneWidget);
      expect(onRail(l10n.orderMilestonePreparing), findsOneWidget);
      expect(onRail(l10n.orderMilestoneReadyDelivery), findsOneWidget);
      expect(onRail(l10n.orderMilestoneOnTheWay), findsOneWidget);
      expect(onRail(l10n.orderMilestoneDelivered), findsOneWidget);

      await close(tester);
    });

    testWidgets('names the courier when the platform published a first name', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(orderJson(status: 'FULFILLING', courierFirstName: 'Азиз')),
        ),
      );

      expect(onRail(l10n.orderMilestoneOnTheWayWith('Азиз')), findsOneWidget);
      // A first name is the whole of what a customer gets about a courier.
      expect(find.textContaining('+998'), findsNothing);

      await close(tester);
    });

    testWidgets('a pickup order never shows the courier step', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(serving(orderJson(status: 'READY', fulfillmentMode: 'PICKUP'))),
      );

      expect(onRail(l10n.orderMilestoneReadyPickup), findsOneWidget);
      expect(onRail(l10n.orderMilestoneOnTheWay), findsNothing);

      await close(tester);
    });

    testWidgets('shows the stored promise as an instant, not a countdown', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(orderJson(promisedAt: '2026-08-24T09:45:00Z')),
          now: DateTime.utc(2026, 8, 24, 9, 30),
        ),
      );

      expect(
        find.text(l10n.orderPromisedBy(at(DateTime.utc(2026, 8, 24, 9, 45)))),
        findsOneWidget,
      );
      expect(find.text(l10n.orderLate), findsNothing);

      await close(tester);
    });

    testWidgets('says so when nothing was promised', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(serving(orderJson(promisedAt: null))),
      );

      // "We never promised" and "on time" must not look the same.
      expect(find.text(l10n.orderPromisedNone), findsOneWidget);

      await close(tester);
    });

    testWidgets('marks an order late once the promise has passed', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(orderJson(promisedAt: '2026-08-24T09:45:00Z')),
          now: DateTime.utc(2026, 8, 24, 10, 5),
        ),
      );

      // Twice: once as the pill's overlay, once against the step in progress.
      expect(find.text(l10n.orderLate), findsNWidgets(2));
      // And the status itself is still there — being prepared and being late
      // are two facts, not one.
      expect(find.text(l10n.orderStatusPreparing), findsOneWidget);

      await close(tester);
    });

    testWidgets('an order awaiting the restaurant claims no progress', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(serving(orderJson(status: 'AWAITING_APPROVAL', confirmedAt: null))),
      );

      expect(find.text(l10n.orderAwaitingRestaurant), findsOneWidget);
      expect(onRail(l10n.orderMilestoneAccepted), findsOneWidget);

      await close(tester);
    });

    testWidgets('a poll that changes the status changes the screen', (
      WidgetTester tester,
    ) async {
      int calls = 0;
      final AppLocalizations l10n = await open(
        tester,
        page(
          MockClient((http.Request request) async {
            calls++;
            return jsonResponse(
              orderJson(status: calls == 1 ? 'PREPARING' : 'FULFILLING'),
            );
          }),
        ),
      );

      expect(find.text(l10n.orderStatusPreparing), findsOneWidget);

      // One poll interval, then a frame for the read to land.
      await tester.pump(tick);
      await tester.pumpAndSettle();

      expect(find.text(l10n.orderStatusFulfilling), findsWidgets);

      await close(tester);
    });
  });

  group('what it cost and how it is paid', () {
    testWidgets('renders UZS whole and unrounded', (WidgetTester tester) async {
      await open(tester, page(serving(orderJson(totalMinor: 84000))));

      // 84 000 so'm, with a no-break space. Not 840, which is what asking ICU
      // for the UZS exponent produces.
      expect(find.text('84\u00A0000\u00A0сум'), findsWidgets);
      expect(find.textContaining('840,00'), findsNothing);

      await close(tester);
    });

    testWidgets('shows the lines, the totals and the payment', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            orderJson(
              status: 'COMPLETED',
              closedAt: '2026-08-24T09:51:00Z',
              paymentStatus: 'CAPTURED',
              paymentMethodName: 'Click',
            ),
          ),
        ),
      );

      expect(find.text('Lagman'), findsOneWidget);
      expect(find.text('2×'), findsOneWidget);
      expect(find.text('Achchiq'), findsOneWidget);
      expect(find.text(l10n.orderSubtotal), findsOneWidget);
      expect(find.text(l10n.orderTotal), findsOneWidget);
      expect(find.text(l10n.orderPaymentCaptured), findsOneWidget);
      expect(find.text('Click'), findsOneWidget);
    });

    testWidgets('an order with no payment projection shows no payment block', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(serving(orderJson(status: 'COMPLETED', paymentStatus: null))),
      );

      expect(find.text(l10n.orderPaymentHeading), findsNothing);
    });
  });

  group('an order that ended, without a map to explain it', () {
    Map<String, Object?> ended(
      String status,
      String kind, {
      String? category,
      String? refund,
      String? customerText,
    }) => orderJson(
      status: status,
      closedAt: '2026-08-24T09:20:00Z',
      outcome: <String, Object?>{
        'kind': kind,
        'systemCategory': ?category,
        'customerRefund': ?refund,
        'reasonCustomerText': ?customerText,
        'occurredAt': '2026-08-24T09:20:00Z',
      },
    );

    testWidgets('a cancellation says it was cancelled, and why, and the refund', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            ended(
              'CANCELLED',
              'CANCELLED',
              category: 'ITEM_UNAVAILABLE',
              refund: 'FULL',
            ),
          ),
        ),
      );

      expect(find.text(l10n.orderOutcomeCancelledTitle), findsOneWidget);
      expect(find.text(l10n.orderReasonItemUnavailable), findsOneWidget);
      expect(find.text(l10n.orderRefundFull), findsOneWidget);
      // No rail: how far a cancelled order got is not knowable from a status.
      expect(onRail(l10n.orderMilestonePreparing), findsNothing);
    });

    testWidgets('a rejection is not worded as a cancellation', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            ended('REJECTED', 'REJECTED', category: 'RESTAURANT_REFUSED'),
          ),
        ),
      );

      expect(find.text(l10n.orderOutcomeRejectedTitle), findsOneWidget);
      expect(find.text(l10n.orderOutcomeCancelledTitle), findsNothing);
      expect(find.text(l10n.orderReasonRestaurant), findsOneWidget);
    });

    testWidgets('an expiry says nobody answered, not that anybody refused', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            ended('EXPIRED', 'EXPIRED', category: 'APPROVAL_DEADLINE_LAPSED'),
          ),
        ),
      );

      expect(find.text(l10n.orderOutcomeExpiredTitle), findsOneWidget);
      expect(find.text(l10n.orderOutcomeRejectedTitle), findsNothing);
      expect(find.text(l10n.orderReasonDeadline), findsOneWidget);
    });

    testWidgets('the tenant\'s own customer wording wins over the category', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            ended(
              'CANCELLED',
              'CANCELLED',
              category: 'CUSTOMER_UNREACHABLE',
              customerText: 'Мы не смогли связаться с вами по указанному номеру.',
            ),
          ),
        ),
      );

      expect(
        find.text('Мы не смогли связаться с вами по указанному номеру.'),
        findsOneWidget,
      );
      expect(find.text(l10n.orderReasonUnreachable), findsNothing);
    });

    testWidgets('a suspected-fraud cancellation is not announced as one', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(ended('CANCELLED', 'CANCELLED', category: 'SUSPECTED_FRAUD')),
        ),
      );

      expect(find.text(l10n.orderReasonUnspecified), findsOneWidget);
      expect(find.textContaining('SUSPECTED'), findsNothing);
    });

    testWidgets('a terminal order with no outcome row still says how it ended', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            orderJson(status: 'CANCELLED', closedAt: '2026-08-24T09:20:00Z'),
          ),
        ),
      );

      expect(find.text(l10n.orderOutcomeCancelledTitle), findsOneWidget);
      // Nothing is invented about why.
      expect(find.text(l10n.orderReasonUnspecified), findsNothing);
    });

    testWidgets('a completed order needs no outcome panel', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await open(
        tester,
        page(
          serving(
            orderJson(status: 'COMPLETED', closedAt: '2026-08-24T09:51:00Z'),
          ),
        ),
      );

      expect(onRail(l10n.orderMilestoneDelivered), findsOneWidget);
      expect(find.text(l10n.orderOutcomeCancelledTitle), findsNothing);
    });
  });

  group('failures', () {
    testWidgets('a refusal is one honest sentence and no capability name', (
      WidgetTester tester,
    ) async {
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
      expect(find.textContaining('order.read'), findsNothing);
      expect(find.textContaining('INSUFFICIENT'), findsNothing);
      expect(find.text(l10n.retry), findsOneWidget);
    });

    testWidgets('a missing order says so', (WidgetTester tester) async {
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
    });

    testWidgets('a transport failure is told apart, because it can be acted on', (
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
    });
  });

  group('on a phone-sized screen', () {
    testWidgets('nothing overflows at 375 by 812, late marker and all', (
      WidgetTester tester,
    ) async {
      // The tall viewport the other tests use would hide a row that does not
      // fit. A layout overflow throws in a widget test, so this test passing is
      // the assertion.
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        page(
          serving(
            orderJson(
              status: 'FULFILLING',
              courierFirstName: 'Абдурахмон',
              promisedAt: '2026-08-24T09:45:00Z',
            ),
          ),
          now: DateTime.utc(2026, 8, 24, 10, 30),
        ),
      );
      await tester.pumpAndSettle();

      final AppLocalizations l10n = await localisations();
      expect(find.text(l10n.orderStatusFulfilling), findsOneWidget);
      expect(find.text(l10n.orderLate), findsWidgets);

      await close(tester);
    });

    testWidgets('nothing overflows at the largest text the scale allows', (
      WidgetTester tester,
    ) async {
      // The application clamps `textScaler` to 1.5. A screen that only works at
      // 1.0 is a screen that breaks for the customers who need it most.
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.5)),
          child: page(serving(orderJson(status: 'FULFILLING'))),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(MilestoneRail), findsOneWidget);

      await close(tester);
    });
  });

  group('the map that is not here', () {
    test('nothing in this feature reaches for a position or a map', () {
      // ADR 0045 is a policy decision, and a policy decision that is only
      // written in an ADR grows back. This is the guard: a courier coordinate,
      // a map binding or a route would have to be added past this test.
      final RegExp forbidden = RegExp(
        r'\b('
        r'yandex|mapkit|GoogleMap|MapController|LatLng|'
        r'latitude|longitude|geohash|polyline|'
        r'courierPosition|courierPhone|vehicle|plateNumber'
        r')\b',
        caseSensitive: false,
      );

      final List<String> offences = <String>[];
      for (final File file in Directory('lib/src/features/orders')
          .listSync(recursive: true)
          .whereType<File>()
          .where((File file) => file.path.endsWith('.dart'))) {
        final List<String> lines = file.readAsLinesSync();
        for (int i = 0; i < lines.length; i++) {
          final String trimmed = lines[i].trimLeft();
          // Comments are skipped: this decision is explained in prose in
          // several of these files, and a scan that flagged its own reasoning
          // would be deleted within a week.
          if (trimmed.startsWith('//') ||
              trimmed.startsWith('///') ||
              trimmed.startsWith('*')) {
            continue;
          }
          if (forbidden.hasMatch(lines[i])) {
            offences.add('${file.path}:${i + 1}: ${lines[i].trim()}');
          }
        }
      }

      expect(
        offences,
        isEmpty,
        reason:
            'ADR 0045: customers see status milestones only. A courier\'s '
            'position is never published.\n${offences.join('\n')}',
      );
    });
  });
}
