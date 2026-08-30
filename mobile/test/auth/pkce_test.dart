import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/auth/pkce.dart';

void main() {
  group('PKCE', () {
    test('matches the RFC 7636 appendix B test vector', () {
      // The specification's own vector. If this passes, the S256 derivation is
      // right; if it does not, no amount of testing against our own output
      // would have caught it, because our output would be consistently wrong.
      final PkcePair pair = PkcePair.fromVerifier(
        'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk',
      );
      expect(pair.challenge, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
    });

    test('offers only S256', () {
      expect(PkcePair.method, 'S256');
    });

    test('generates a verifier of the length the RFC allows', () {
      final PkcePair pair = PkcePair.generate();
      expect(pair.verifier.length, greaterThanOrEqualTo(43));
      expect(pair.verifier.length, lessThanOrEqualTo(128));
    });

    test('generates only unreserved characters', () {
      // A verifier containing a character that needs percent-encoding survives
      // the authorization request and fails at the token endpoint, which is a
      // long way from the cause.
      final PkcePair pair = PkcePair.generate();
      expect(pair.verifier, matches(RegExp(r'^[A-Za-z0-9\-._~]+$')));
      expect(pair.challenge, matches(RegExp(r'^[A-Za-z0-9\-._~]+$')));
    });

    test('does not repeat a verifier', () {
      final Set<String> seen = <String>{
        for (int i = 0; i < 64; i++) PkcePair.generate().verifier,
      };
      expect(seen.length, 64);
    });

    test('rejects a verifier shorter than the RFC allows', () {
      expect(() => PkcePair.fromVerifier('too-short'), throwsArgumentError);
    });
  });

  group('state and nonce', () {
    test('are URL-safe and do not repeat', () {
      final Set<String> seen = <String>{
        for (int i = 0; i < 64; i++) randomUrlSafeToken(),
      };
      expect(seen.length, 64);
      for (final String token in seen) {
        expect(token, matches(RegExp(r'^[A-Za-z0-9\-_]+$')));
      }
    });
  });
}
