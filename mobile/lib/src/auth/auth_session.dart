import 'package:flutter/foundation.dart';

import '../api/api_client.dart' show AccessTokens;
import 'auth_config.dart';
import 'authorization_browser.dart';
import 'pkce.dart';
import 'token_endpoint.dart';
import 'token_store.dart';
import 'tokens.dart';

/// What the router needs to know, and all it needs to know.
enum AuthStatus {
  /// Before [AuthSession.restore] has finished. The router shows nothing and
  /// navigates nowhere: sending a returning customer to the sign-in screen for
  /// the half second it takes to read the keystore is a visible flash of the
  /// wrong screen.
  unknown,
  signedOut,
  signedIn,
}

/// The application's session (ADR 0003, ADR 0035).
///
/// Authorization Code with PKCE against the HorecaOS realm, through the system
/// browser. The access token is held here, in memory, and dies with the
/// process; the refresh token is the only thing on the device.
///
/// A `ChangeNotifier` rather than a state-management package: go_router takes a
/// `Listenable` for `refreshListenable` directly, so a package would add a
/// dependency without adding a capability.
final class AuthSession extends ChangeNotifier implements AccessTokens {
  AuthSession({
    required this._config,
    required TokenEndpoint tokenEndpoint,
    required RefreshTokenStore refreshTokens,
    this._browser = const SystemAuthorizationBrowser(),
  }) : _endpoint = tokenEndpoint,
       _store = refreshTokens;

  final AuthConfig _config;
  final TokenEndpoint _endpoint;
  final RefreshTokenStore _store;
  final AuthorizationBrowser _browser;

  TokenSet? _tokens;
  AuthStatus _status = AuthStatus.unknown;

  /// Collapses concurrent refreshes.
  ///
  /// Several requests can fail with 401 in the same frame. Without this each
  /// would redeem the refresh token, and under Keycloak's refresh-token
  /// rotation the second redemption invalidates the first — signing the
  /// customer out in the middle of an order because two screens loaded at once.
  Future<String?>? _refreshInFlight;

  /// Which session a token response belongs to.
  ///
  /// Redeeming a refresh token is several awaits long — read the keystore, post
  /// to the realm, write the rotated token back — and sign-out can land in any
  /// of the gaps. `package:http` offers no way to cancel a request in flight,
  /// so the response is discarded on arrival instead: [signOut] increments this
  /// counter and [_adopt] refuses anything minted under an older one. Without
  /// it the late response repopulates [_tokens] and writes a fresh refresh
  /// token to the keystore, and the session the customer ended comes back by
  /// itself at the next launch.
  int _generation = 0;

  AuthStatus get status => _status;
  bool get isSignedIn => _status == AuthStatus.signedIn;

  /// Restores a session from the stored refresh token. Call once at startup.
  Future<void> restore() async {
    final int generation = _generation;
    final String? stored = await _store.read();
    if (stored == null) {
      _setStatus(AuthStatus.signedOut);
      return;
    }
    try {
      if (await _redeem(stored, generation) != null) {
        _setStatus(AuthStatus.signedIn);
      }
    } on AuthException {
      if (generation != _generation) {
        // Sign-out already dealt with the session this was restoring. Clearing
        // again would wipe whatever has replaced it.
        return;
      }
      // Any failure to redeem at startup ends as signed out, including a
      // transport failure. Treating "the network is down" as "still signed in"
      // would leave the application in a state where every request 401s and
      // nothing explains why.
      await _store.clear();
      _tokens = null;
      _setStatus(AuthStatus.signedOut);
    }
  }

  /// Runs the full authorization code flow.
  ///
  /// Throws [AuthException] with `user_cancelled` when the customer dismisses
  /// the browser, which callers should treat as a non-event.
  Future<void> signIn({String? uiLocale}) async {
    final int generation = _generation;
    final PkcePair pkce = PkcePair.generate();
    final String state = randomUrlSafeToken();
    final String nonce = randomUrlSafeToken();

    final Uri redirect = await _browser.authorize(
      authorizationUri: _config.authorizationUri(
        codeChallenge: pkce.challenge,
        state: state,
        nonce: nonce,
        uiLocale: uiLocale,
      ),
      callbackUrlScheme: _config.callbackUrlScheme,
    );

    final Map<String, String> parameters = redirect.queryParameters;

    final String? error = parameters['error'];
    if (error != null) {
      throw AuthException(error, description: parameters['error_description']);
    }

    // Checked before the code is read, not after. An unchecked `state` is a
    // login-CSRF: an attacker gets the customer's browser to deliver an
    // attacker's authorization code, and the application signs the customer
    // into the attacker's account without either of them noticing.
    if (parameters['state'] != state) {
      throw const AuthException('state_mismatch');
    }

    final String? code = parameters['code'];
    if (code == null || code.isEmpty) {
      throw const AuthException('missing_authorization_code');
    }

    final TokenSet issued = await _endpoint.exchangeCode(
      code: code,
      codeVerifier: pkce.verifier,
    );
    // A sign-out that landed while the browser was open leaves the customer
    // signed out, which is the last thing they asked for. Reporting it as a
    // failed sign-in would be the application arguing with them.
    if (await _adopt(issued, generation)) {
      _setStatus(AuthStatus.signedIn);
    }
  }

  /// Ends the session on this device, and asks the realm to end it too.
  ///
  /// Local state is cleared first and unconditionally. A sign-out that fails
  /// because the network is down and leaves the customer signed in is the wrong
  /// failure to have.
  ///
  /// It is also final: a redemption already on the wire is repudiated here
  /// rather than merely detached, so its response cannot re-establish the
  /// session after the fact. See [_generation].
  Future<void> signOut() async {
    // Before the first await, so nothing can be adopted from the moment the
    // customer's tap is observed.
    _generation++;
    final String? refreshToken = _tokens?.refreshToken ?? await _store.read();
    _tokens = null;
    _refreshInFlight = null;
    await _store.clear();
    _setStatus(AuthStatus.signedOut);
    if (refreshToken != null) {
      await _endpoint.endSession(refreshToken);
    }
  }

  /// The current access token, refreshed proactively if it is about to expire.
  @override
  Future<String?> current() async {
    final TokenSet? held = _tokens;
    if (held == null) {
      return null;
    }
    if (held.needsRefresh()) {
      return refresh();
    }
    return held.accessToken;
  }

  @override
  Future<String?> refresh() {
    final Future<String?>? inFlight = _refreshInFlight;
    if (inFlight != null) {
      return inFlight;
    }
    final Future<String?> attempt = _refresh();
    _refreshInFlight = attempt;
    return attempt.whenComplete(() {
      // Only if it is still this attempt. A sign-out clears the slot and a
      // later refresh may already have claimed it, and clearing that one would
      // let the next caller start a second concurrent redemption — the very
      // thing the slot exists to prevent.
      if (identical(_refreshInFlight, attempt)) {
        _refreshInFlight = null;
      }
    });
  }

  Future<String?> _refresh() async {
    final int generation = _generation;
    final String? refreshToken = _tokens?.refreshToken ?? await _store.read();
    if (refreshToken == null) {
      await _endSessionLocally();
      return null;
    }
    try {
      return await _redeem(refreshToken, generation);
    } on AuthException catch (failure) {
      if (generation != _generation) {
        // The session this was refreshing is already over; whatever the realm
        // said about its refresh token no longer decides anything.
        return null;
      }
      if (failure.isSessionOver) {
        await _endSessionLocally();
        return null;
      }
      // A transport failure is not a signed-out customer. Rethrowing keeps the
      // session and lets the caller retry, rather than throwing the customer
      // out of an order because a tunnel dropped.
      rethrow;
    }
  }

  /// @returns null when the response outlived the session that asked for it.
  Future<String?> _redeem(String refreshToken, int generation) async {
    final TokenSet issued = await _endpoint.refresh(refreshToken);
    return await _adopt(issued, generation) ? issued.accessToken : null;
  }

  /// @returns false when [generation] is no longer current, meaning the tokens
  ///          were discarded rather than adopted.
  Future<bool> _adopt(TokenSet issued, int generation) async {
    if (generation != _generation) {
      return false;
    }
    _tokens = issued;
    final String? rotated = issued.refreshToken;
    if (rotated != null) {
      // Keycloak rotates the refresh token on every redemption by default. The
      // new one must land before the old one is used again, or the next
      // refresh presents a token the realm has already retired.
      await _store.write(rotated);
      if (generation != _generation) {
        // Sign-out landed during the write, so its `clear` may have run first.
        // The keystore is the only thing that survives the process; a token
        // left there is a session that returns at the next launch.
        _tokens = null;
        await _store.clear();
        return false;
      }
    }
    return true;
  }

  Future<void> _endSessionLocally() async {
    _tokens = null;
    await _store.clear();
    _setStatus(AuthStatus.signedOut);
  }

  void _setStatus(AuthStatus next) {
    if (_status == next) {
      return;
    }
    _status = next;
    notifyListeners();
  }
}
