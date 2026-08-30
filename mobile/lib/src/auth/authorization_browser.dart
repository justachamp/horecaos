import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';

import 'tokens.dart';

/// Opens the authorization endpoint and returns the redirect it lands on.
///
/// An interface, so the session can be tested without a browser, a platform
/// channel, or a realm.
abstract interface class AuthorizationBrowser {
  /// Returns the full redirect URI, including `code` and `state`.
  ///
  /// Throws [AuthException] with `user_cancelled` if the customer dismisses the
  /// sheet — an ordinary outcome, not an error to report.
  Future<Uri> authorize({
    required Uri authorizationUri,
    required String callbackUrlScheme,
  });
}

/// The system browser: `ASWebAuthenticationSession` on iOS, Custom Tabs on
/// Android.
///
/// Not a `WebView`. A WebView would put the customer's Keycloak password inside
/// this process, where the application could read it, which is precisely what
/// the redirect flow exists to prevent.
///
/// Single sign-on with the web surfaces is given up separately, by asking for
/// an ephemeral session below; the reasoning is there.
final class SystemAuthorizationBrowser implements AuthorizationBrowser {
  const SystemAuthorizationBrowser();

  @override
  Future<Uri> authorize({
    required Uri authorizationUri,
    required String callbackUrlScheme,
  }) async {
    try {
      final String result = await FlutterWebAuth2.authenticate(
        url: authorizationUri.toString(),
        callbackUrlScheme: callbackUrlScheme,
        options: const FlutterWebAuth2Options(
          // An ephemeral session: no cookie is read from the system browser and
          // none is left behind in it when the sheet closes.
          //
          // The cost is single sign-on. A customer already signed in to HorecaOS
          // in Safari or Chrome types their password again here, and signing
          // out of the application no longer leaves a realm session the
          // browser could silently re-establish — which is the point. The
          // alternative leaves a Keycloak SSO cookie in the device's shared
          // browser, so the next person to open the application taps "sign in"
          // and lands in the previous customer's account without a prompt.
          // Phones are shared here routinely, and one extra login is a smaller
          // harm than one stranger's order history.
          preferEphemeral: true,
          // The customer dismissing the sheet is not a failure state to hang on.
          timeout: 300,
        ),
      );
      return Uri.parse(result);
    } on Exception catch (failure) {
      // The plugin raises a platform exception for a user dismissal as well as
      // for a real failure, and the two are indistinguishable from the type
      // alone. Both end the sign-in attempt without a session, which is the
      // same outcome; the code says which so the UI can stay silent for one and
      // speak for the other.
      final String message = failure.toString().toLowerCase();
      throw AuthException(
        message.contains('cancel') ? 'user_cancelled' : 'browser_failure',
        description: failure.runtimeType.toString(),
      );
    }
  }
}
