import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// A PKCE verifier and its S256 challenge (RFC 7636).
///
/// PKCE is not optional here and not a nicety for public clients: a mobile
/// application cannot keep a client secret, so the proof key is the only thing
/// binding the authorization code to the process that asked for it. Without it
/// an application that registers the same custom URL scheme can intercept the
/// redirect and redeem the code.
final class PkcePair {
  const PkcePair({required this.verifier, required this.challenge});

  final String verifier;
  final String challenge;

  /// The only method this client offers.
  ///
  /// `plain` is in the specification and is a downgrade: it puts the verifier
  /// in the authorization request, which is the value the challenge exists to
  /// keep out of it.
  static const String method = 'S256';

  /// A fresh pair from a cryptographically secure source.
  ///
  /// 32 bytes, base64url without padding, which is 43 characters — the minimum
  /// the specification allows and the length that gives the full 256 bits of
  /// the underlying entropy. `Random.secure()` and not `Random()`: a seeded
  /// PRNG makes the verifier predictable, which is the whole attack.
  factory PkcePair.generate() {
    final Random random = Random.secure();
    final List<int> bytes = List<int>.generate(32, (_) => random.nextInt(256));
    return PkcePair.fromVerifier(_base64UrlNoPad(bytes));
  }

  /// Derives the challenge for a given verifier. Exposed so the RFC's own test
  /// vector can be asserted against it.
  factory PkcePair.fromVerifier(String verifier) {
    if (verifier.length < 43 || verifier.length > 128) {
      throw ArgumentError.value(
        verifier.length,
        'verifier.length',
        'RFC 7636 requires 43 to 128 characters',
      );
    }
    final Digest digest = sha256.convert(ascii.encode(verifier));
    return PkcePair(
      verifier: verifier,
      challenge: _base64UrlNoPad(digest.bytes),
    );
  }

  static String _base64UrlNoPad(List<int> bytes) =>
      base64UrlEncode(bytes).replaceAll('=', '');
}

/// An opaque random value for the `state` and `nonce` parameters.
///
/// `state` is what makes a redirect arriving at the application attributable to
/// a request the application actually made; an unchecked `state` is a login-CSRF.
String randomUrlSafeToken([int bytes = 24]) {
  final Random random = Random.secure();
  return base64UrlEncode(
    List<int>.generate(bytes, (_) => random.nextInt(256)),
  ).replaceAll('=', '');
}
