import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/api/api_exception.dart';
import 'package:horecaos_mobile/src/api/problem_details.dart';
import 'package:horecaos_mobile/src/features/profile/data/notification_preference.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address.dart';
import 'package:horecaos_mobile/src/features/profile/profile_area.dart';
import 'package:horecaos_mobile/src/features/profile/profile_routes.dart';
import 'package:horecaos_mobile/src/features/profile/settings/language_choice_store.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';
import 'package:horecaos_mobile/src/l10n/supported_locales.dart';

import 'profile_harness.dart';

const ApiException _refused = ApiException(
  ProblemDetails(status: 403, code: ApiErrorCode.insufficientCapability),
);

void main() {
  group('the profile root', () {
    testWidgets('shows the account reference once it resolves', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
      );

      expect(find.text(l10n.profileAccountReference), findsOneWidget);
      expect(find.text(testAccount.accountId), findsOneWidget);
    });

    testWidgets('says plainly when the platform refuses, and offers no retry', (
      WidgetTester tester,
    ) async {
      // The endpoints declare staff capabilities no customer principal holds
      // (ADR 0025, open). A retry loop against a capability denial is an
      // application that appears to be trying while achieving nothing.
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(accounts: FakeAccounts(failure: _refused)),
      );

      expect(find.text(l10n.profileUnavailableTitle), findsOneWidget);
      expect(find.text(l10n.retry), findsNothing);
    });

    testWidgets('offers a retry when the network is the problem', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(
          accounts: FakeAccounts(
            failure: const ApiTransportException('timeout'),
          ),
        ),
      );

      expect(find.text(l10n.profileOfflineTitle), findsOneWidget);
      expect(find.text(l10n.retry), findsOneWidget);
    });

    testWidgets('the settings still work when the account will not resolve', (
      WidgetTester tester,
    ) async {
      // Resolving an account needs the platform. Changing the interface
      // language does not, and one failure must not take the other down.
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(accounts: FakeAccounts(failure: _refused)),
      );

      expect(find.text(l10n.profileLanguage), findsOneWidget);
      expect(find.text(l10n.profileAddresses), findsOneWidget);
    });

    testWidgets('signing out asks first', (WidgetTester tester) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
      );

      await tester.tap(find.text(l10n.signOut));
      await tester.pumpAndSettle();

      expect(find.text(l10n.profileSignOutConfirmTitle), findsOneWidget);
      // And says what does and does not survive it.
      expect(find.text(l10n.profileSignOutConfirmBody), findsOneWidget);
    });

    testWidgets('renders in the customer\'s language', (
      WidgetTester tester,
    ) async {
      // There is no English-only fallback screen in this application.
      final AppLocalizations russian = await pumpProfile(
        tester,
        area: fakeArea(),
        locale: SupportedLocales.russian,
      );

      expect(find.text(russian.profileTitle), findsOneWidget);
      expect(russian.profileTitle, 'Профиль');
    });
  });

  group('the address list', () {
    testWidgets('says how an address is found, in three ways not two', (
      WidgetTester tester,
    ) async {
      final FakeAddresses addresses = FakeAddresses(
        addresses: <SavedAddress>[
          const SavedAddress(
            id: 'a-1',
            label: 'Uy',
            fields: AddressFields(line1: 'Amir Temur 12', landmark: 'Dorixona'),
            coordinateSource: CoordinateSource.landmarkOnly,
          ),
          const SavedAddress(
            id: 'a-2',
            fields: AddressFields(line1: 'Bunyodkor 4'),
            coordinateSource: CoordinateSource.notGeocoded,
          ),
        ],
      );
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(addresses: addresses),
        location: ProfileRoutes.addresses,
      );

      // A finished address and an unfinished one, told apart. Showing both as
      // "no pin" would tell a customer their complete address is incomplete.
      expect(find.text(l10n.profileAddressPinByLandmark), findsOneWidget);
      expect(find.text(l10n.profileAddressPinNone), findsOneWidget);
    });

    testWidgets('shows the landmark and the door detail', (
      WidgetTester tester,
    ) async {
      final FakeAddresses addresses = FakeAddresses(
        addresses: <SavedAddress>[
          const SavedAddress(
            id: 'a-1',
            fields: AddressFields(
              line1: 'Chilonzor 9',
              entrance: '3',
              floor: '5',
              apartment: '44',
              landmark: 'Dorixona',
            ),
            coordinateSource: CoordinateSource.landmarkOnly,
          ),
        ],
      );
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(addresses: addresses),
        location: ProfileRoutes.addresses,
      );

      expect(
        find.text('${l10n.profileAddressFieldLandmark}: Dorixona'),
        findsOneWidget,
      );
      expect(
        find.textContaining('${l10n.profileAddressFieldEntrance} 3'),
        findsOneWidget,
      );
    });

    testWidgets('offers no action the platform cannot complete', (
      WidgetTester tester,
    ) async {
      // `CustomerController` has no update and no delete. An affordance that
      // fails is worse than an absent one.
      final FakeAddresses addresses = FakeAddresses(
        addresses: <SavedAddress>[
          const SavedAddress(
            id: 'a-1',
            fields: AddressFields(line1: 'Amir Temur 12'),
            coordinateSource: CoordinateSource.notGeocoded,
          ),
        ],
      );
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(addresses: addresses),
        location: ProfileRoutes.addresses,
      );

      expect(find.text(l10n.profileAddressEdit), findsNothing);
      expect(find.text(l10n.profileAddressRemoveAction), findsNothing);
      // Adding one is offered, because adding one works.
      expect(find.text(l10n.profileAddressAdd), findsOneWidget);
    });

    testWidgets('offers them once the platform has the endpoints', (
      WidgetTester tester,
    ) async {
      final FakeAddresses addresses = FakeAddresses(
        addresses: <SavedAddress>[
          const SavedAddress(
            id: 'a-1',
            fields: AddressFields(line1: 'Amir Temur 12'),
            coordinateSource: CoordinateSource.notGeocoded,
          ),
        ],
        supportsReplace: true,
        supportsRemove: true,
      );
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(addresses: addresses),
        location: ProfileRoutes.addresses,
      );

      expect(find.text(l10n.profileAddressEdit), findsOneWidget);
      await tester.tap(find.text(l10n.profileAddressRemoveAction));
      await tester.pumpAndSettle();

      // Deleting is confirmed, and the confirmation says what it does not
      // touch.
      expect(find.text(l10n.profileAddressRemoveTitle), findsOneWidget);
      await tester.tap(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.widgetWithText(
            TextButton,
            l10n.profileAddressRemoveAction,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(addresses.removed, <String>['a-1']);
    });

    testWidgets('an empty list explains what to do about it', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.addresses,
      );

      expect(find.text(l10n.profileAddressesEmptyTitle), findsOneWidget);
      expect(find.text(l10n.profileAddressAdd), findsOneWidget);
    });
  });

  group('the language picker', () {
    testWidgets('offers the device language and the three the app ships', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.language,
      );

      expect(find.text(l10n.profileLanguageSystem), findsOneWidget);
      // Each in its own language: nobody looking for Uzbek looks for
      // "узбекский".
      expect(find.text('Русский'), findsOneWidget);
      expect(find.text("O'zbekcha"), findsOneWidget);
      expect(find.text('English'), findsOneWidget);
    });

    testWidgets('a choice is persisted', (WidgetTester tester) async {
      final InMemoryLanguageChoiceStore store = InMemoryLanguageChoiceStore();
      final ProfileArea area = fakeArea(languageStore: store);
      await area.locale.load();
      await pumpProfile(
        tester,
        area: area,
        location: ProfileRoutes.language,
      );

      await tester.tap(find.text("O'zbekcha"));
      await tester.pumpAndSettle();

      expect(area.locale.selected, SupportedLocales.uzbekLatin);
      expect(await store.read(), 'uz-Latn');
    });
  });

  group('the notification preferences', () {
    testWidgets('lists what is always sent, without a switch for it', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.notifications,
      );

      expect(find.text(l10n.profileNotificationsOrderTitle), findsOneWidget);
      expect(find.text(l10n.profileNotificationsSecurityTitle), findsOneWidget);
      // Two switchable classes on one wired channel. If a required class had
      // been given a switch there would be more.
      expect(find.byType(Switch), findsNWidgets(2));
    });

    testWidgets('offers a switch for the two classes a customer may refuse', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.notifications,
      );

      expect(find.text(l10n.profileNotificationsExtraTitle), findsOneWidget);
      expect(find.text(l10n.profileNotificationsOffersTitle), findsOneWidget);
    });

    testWidgets('never shows an operations alert', (
      WidgetTester tester,
    ) async {
      // It targets an on-call route. There is no data subject in the ADR 0015
      // sense and it is not the customer's message.
      final FakeNotifications notifications = FakeNotifications(
        preferences: const NotificationPreferences(<NotificationPreference>[
          NotificationPreference(
            notificationClass: NotificationClass.operationsAlert,
            channel: NotificationChannel.sms,
            enabled: true,
          ),
        ]),
      );
      await pumpProfile(
        tester,
        area: fakeArea(notifications: notifications),
        location: ProfileRoutes.notifications,
      );

      expect(find.byType(Switch), findsNWidgets(2));
    });

    testWidgets('says that marketing is a preference and not consent', (
      WidgetTester tester,
    ) async {
      // Consent is the customer's, it is append-only, and it carries a policy
      // version and a date. A switch must never stand in for one.
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.notifications,
      );

      expect(find.text(l10n.profileNotificationsOffersBody), findsOneWidget);
    });

    testWidgets('says which channel these are sent on', (
      WidgetTester tester,
    ) async {
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.notifications,
      );

      expect(find.text(l10n.profileNotificationsSmsOnly), findsOneWidget);
    });

    testWidgets('a switch with nothing stored behind it reads as on', (
      WidgetTester tester,
    ) async {
      // ADR 0020's default. A customer who never expressed a preference has
      // not opted out of anything.
      await pumpProfile(
        tester,
        area: fakeArea(),
        location: ProfileRoutes.notifications,
      );

      final Iterable<Switch> switches = tester.widgetList<Switch>(
        find.byType(Switch),
      );
      expect(switches.every((Switch control) => control.value), isTrue);
    });

    testWidgets('switching one off writes it', (WidgetTester tester) async {
      final FakeNotifications notifications = FakeNotifications();
      await pumpProfile(
        tester,
        area: fakeArea(notifications: notifications),
        location: ProfileRoutes.notifications,
      );

      await tester.tap(find.byType(Switch).last);
      await tester.pumpAndSettle();

      final PreferenceWrite write = notifications.writes.single;
      expect(write.notificationClass, NotificationClass.marketing);
      expect(write.channel, NotificationChannel.sms);
      expect(write.enabled, isFalse);
    });

    testWidgets('a failed write puts the switch back', (
      WidgetTester tester,
    ) async {
      // A switch showing a setting the platform does not hold is a lie the
      // customer acts on.
      final FakeNotifications notifications = FakeNotifications();
      final AppLocalizations l10n = await pumpProfile(
        tester,
        area: fakeArea(notifications: notifications),
        location: ProfileRoutes.notifications,
      );
      notifications.failure = const ApiTransportException('timeout');

      await tester.tap(find.byType(Switch).last);
      await tester.pumpAndSettle();

      expect(find.text(l10n.profileNotificationsSaveFailed), findsOneWidget);
      final Switch marketing = tester.widgetList<Switch>(
        find.byType(Switch),
      ).last;
      expect(marketing.value, isTrue);
    });
  });
}
