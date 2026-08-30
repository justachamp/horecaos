import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/auth/auth_session.dart';
import 'package:horecaos_mobile/src/routing/app_router.dart';
import 'package:horecaos_mobile/src/routing/routes.dart';

void main() {
  group('while the session is being restored', () {
    test('holds on the starting route', () {
      expect(guard(AuthStatus.unknown, Routes.starting), isNull);
    });

    test('sends anything else to the starting route', () {
      // Not to sign-in. A returning customer whose keystore read takes half a
      // second would otherwise see the one screen they should never see again.
      expect(guard(AuthStatus.unknown, Routes.menu), Routes.starting);
      expect(guard(AuthStatus.unknown, Routes.orders), Routes.starting);
      expect(guard(AuthStatus.unknown, Routes.signIn), Routes.starting);
    });
  });

  group('signed out', () {
    test('keeps public browse routes open and protects the personal one', () {
      expect(guard(AuthStatus.signedOut, Routes.menu), isNull);
      expect(guard(AuthStatus.signedOut, Routes.orders), Routes.signIn);
      expect(guard(AuthStatus.signedOut, Routes.starting), Routes.menu);
    });

    test('sign-in itself does not redirect', () {
      // A guard that redirects its own destination is an infinite loop, and
      // go_router reports it as a redirect-limit error rather than a blank
      // screen, which is the only reason it is ever noticed quickly.
      expect(guard(AuthStatus.signedOut, Routes.signIn), isNull);
    });
  });

  group('signed in', () {
    test('leaves an in-application route alone', () {
      expect(guard(AuthStatus.signedIn, Routes.menu), isNull);
      expect(guard(AuthStatus.signedIn, Routes.orders), isNull);
    });

    test('moves off sign-in and off the starting route', () {
      expect(guard(AuthStatus.signedIn, Routes.signIn), Routes.menu);
      expect(guard(AuthStatus.signedIn, Routes.starting), Routes.menu);
    });
  });

  test('never returns the location it was given', () {
    // The property that matters more than any individual case: a redirect to
    // where you already are is the loop.
    for (final AuthStatus status in AuthStatus.values) {
      for (final String location in <String>[
        Routes.starting,
        Routes.signIn,
        Routes.menu,
        Routes.orders,
      ]) {
        expect(
          guard(status, location),
          isNot(location),
          reason: '$status $location',
        );
      }
    }
  });
}
