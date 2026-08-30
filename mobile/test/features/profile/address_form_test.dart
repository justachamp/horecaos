import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address.dart';
import 'package:horecaos_mobile/src/features/profile/profile_area.dart';
import 'package:horecaos_mobile/src/features/profile/profile_routes.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';

import 'profile_harness.dart';

/// Scrolls a control into view before tapping it.
///
/// The form is longer than a phone screen, which is the point of it: the
/// fields it asks for are the ones that actually find a door.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The address form, and the two questions it exists to get right: whether the
/// landmark is treated as a first-class field, and whether a customer can
/// finish without a pin.
void main() {
  Future<AppLocalizations> pumpForm(
    WidgetTester tester,
    FakeAddresses addresses,
  ) async {
    final ProfileArea area = fakeArea(addresses: addresses);
    return pumpProfile(
      tester,
      area: area,
      location: ProfileRoutes.newAddress,
    );
  }

  testWidgets('the landmark is a labelled field with its own explanation', (
    WidgetTester tester,
  ) async {
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    expect(find.text(l10n.profileAddressFieldLandmark), findsOneWidget);
    // Not a placeholder inside "anything else": it says why it is being asked
    // for, because for many addresses here it is the only thing that finds the
    // door.
    expect(find.text(l10n.profileAddressFieldLandmarkHelp), findsOneWidget);
  });

  testWidgets('the two meanings of a missing pin are both offered', (
    WidgetTester tester,
  ) async {
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    expect(find.text(l10n.profileAddressPinLater), findsOneWidget);
    expect(find.text(l10n.profileAddressPinNever), findsOneWidget);
    // No map, so no control that pretends there is one.
    expect(find.text(l10n.profileAddressPinSet), findsNothing);
  });

  testWidgets('a landmark-only address saves with no coordinate', (
    WidgetTester tester,
  ) async {
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldStreet),
      'Chilonzor 9-kvartal',
    );
    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldLandmark),
      "Dorixona ro'parasida, ko'k darvoza",
    );
    await tapVisible(tester, find.text(l10n.profileAddressPinNever));
    await tapVisible(tester, find.text(l10n.profileAddressSave));

    final AddressDraft saved = addresses.added.single;
    expect(saved.coordinateSource, CoordinateSource.landmarkOnly);
    expect(saved.latitude, isNull);
    expect(saved.longitude, isNull);
    expect(saved.fields.landmark, "Dorixona ro'parasida, ko'k darvoza");
  });

  testWidgets('a new address defaults to "not geocoded", not "no pin ever"', (
    WidgetTester tester,
  ) async {
    // The honest default: nothing has been attempted yet. LANDMARK_ONLY is a
    // statement about the place that only the customer can make, and a form
    // that made it for them would stop the address ever being geocoded.
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldStreet),
      'Chilonzor 9-kvartal',
    );
    await tapVisible(tester, find.text(l10n.profileAddressSave));

    expect(
      addresses.added.single.coordinateSource,
      CoordinateSource.notGeocoded,
    );
  });

  testWidgets('a landmark-only address with no landmark is refused here', (
    WidgetTester tester,
  ) async {
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldStreet),
      'Chilonzor 9-kvartal',
    );
    await tapVisible(tester, find.text(l10n.profileAddressPinNever));
    await tapVisible(tester, find.text(l10n.profileAddressSave));

    // Neither a point nor a description is not something anybody can deliver
    // to. The platform would have accepted it.
    expect(find.text(l10n.profileAddressLandmarkNeeded), findsOneWidget);
    expect(addresses.added, isEmpty);
  });

  testWidgets('an address with neither a street nor a landmark is refused', (
    WidgetTester tester,
  ) async {
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldCity),
      'Toshkent',
    );
    await tapVisible(tester, find.text(l10n.profileAddressSave));

    expect(find.text(l10n.profileAddressNothingToFind), findsOneWidget);
    expect(addresses.added, isEmpty);
  });

  testWidgets('подъезд, этаж and квартира are their own fields', (
    WidgetTester tester,
  ) async {
    // A courier standing in a Soviet-era block cannot find a flat from a
    // street line. Folded into one line these cannot be shown as a checklist
    // or carried to a partner adapter that has fields for them.
    final FakeAddresses addresses = FakeAddresses();
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldStreet),
      'Chilonzor 9-kvartal',
    );
    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldEntrance),
      '3',
    );
    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldFloor),
      '5',
    );
    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldApartment),
      '44',
    );
    await tapVisible(tester, find.text(l10n.profileAddressSave));

    final AddressFields fields = addresses.added.single.fields;
    expect(fields.entrance, '3');
    expect(fields.floor, '5');
    expect(fields.apartment, '44');
  });

  testWidgets('a failed save keeps the same idempotency key on the retry', (
    WidgetTester tester,
  ) async {
    // A save that failed on a dropped connection may well have reached the
    // platform. A fresh key on the retry is how a customer ends up with the
    // same address saved twice.
    final FakeAddresses addresses = FakeAddresses(
      failure: StateError('connection lost'),
    );
    final AppLocalizations l10n = await pumpForm(tester, addresses);

    await tester.enterText(
      find.widgetWithText(TextField, l10n.profileAddressFieldStreet),
      'Chilonzor 9-kvartal',
    );
    await tapVisible(tester, find.text(l10n.profileAddressSave));
    expect(find.text(l10n.profileAddressSaveFailed), findsOneWidget);

    await tapVisible(tester, find.text(l10n.profileAddressSave));

    expect(addresses.keys, hasLength(2));
    expect(addresses.keys.first, addresses.keys.last);
  });

  testWidgets('a point somebody else placed is kept and not relabelled', (
    WidgetTester tester,
  ) async {
    const SavedAddress geocoded = SavedAddress(
      id: 'a-1',
      fields: AddressFields(line1: 'Amir Temur 12'),
      latitude: 41.31,
      longitude: 69.24,
      coordinateSource: CoordinateSource.geocoder,
    );
    final FakeAddresses addresses = FakeAddresses(
      addresses: <SavedAddress>[geocoded],
      supportsReplace: true,
    );
    final ProfileArea area = fakeArea(addresses: addresses);
    final AppLocalizations l10n = await pumpProfile(
      tester,
      area: area,
      location: ProfileRoutes.editAddressPath('a-1'),
      extra: geocoded,
    );

    // There is no map in this build, so the pin is shown and left alone
    // rather than offered as something to move.
    expect(find.text(l10n.profileAddressPinSet), findsOneWidget);
    expect(find.text(l10n.profileAddressPinLater), findsNothing);
  });
}
