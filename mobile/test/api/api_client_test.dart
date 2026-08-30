import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/api/api_exception.dart';
import 'package:qoida_mobile/src/api/api_telemetry.dart';
import 'package:qoida_mobile/src/api/idempotency_key.dart';
import 'package:qoida_mobile/src/api/page.dart';
import 'package:qoida_mobile/src/api/problem_details.dart';
import 'package:qoida_mobile/src/format/money.dart';

/// A token source with no Keycloak behind it.
class _StubTokens implements AccessTokens {
  _StubTokens(this._current, {this._refreshed});

  String? _current;
  final String? _refreshed;
  int refreshes = 0;

  @override
  Future<String?> current() async => _current;

  @override
  Future<String?> refresh() async {
    refreshes++;
    _current = _refreshed;
    return _refreshed;
  }
}

class _RecordingTelemetry implements ApiTelemetry {
  final List<ApiCallRecord> calls = <ApiCallRecord>[];

  @override
  void record(ApiCallRecord call) => calls.add(call);
}

QoidaApiClient _client(
  MockClient transport, {
  AccessTokens? tokens,
  ApiTelemetry? telemetry,
}) => QoidaApiClient(
  baseUri: Uri.parse('https://api.example.test'),
  httpClient: transport,
  tokens: tokens ?? _StubTokens('at_1'),
  telemetry: telemetry ?? const NullApiTelemetry(),
  correlationIds: () => 'cid-fixed',
);

http.Response _json(Object body, {int status = 200, Map<String, String>? headers}) =>
    http.Response(
      jsonEncode(body),
      status,
      headers: <String, String>{
        'content-type': 'application/json',
        ...?headers,
      },
    );

Map<String, Object?> _identity(Map<String, Object?> json) => json;

void main() {
  group('every request', () {
    test('carries the bearer token and a correlation identifier', () async {
      late http.BaseRequest seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request;
          return _json(<String, Object?>{'ok': true});
        }),
      );

      await client.get('/api/v1/storefront/ping', decode: _identity);

      expect(seen.headers['Authorization'], 'Bearer at_1');
      expect(seen.headers[QoidaApiClient.correlationIdHeader], 'cid-fixed');
    });

    test('accepts problem+json as well as json', () async {
      late http.BaseRequest seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request;
          return _json(<String, Object?>{});
        }),
      );

      await client.get('/ping', decode: _identity);

      expect(seen.headers['Accept'], contains('application/problem+json'));
    });
  });

  group('idempotency', () {
    test("sends the caller's key on a mutation", () async {
      late http.BaseRequest seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request;
          return _json(<String, Object?>{'orderId': 'o1'});
        }),
      );

      await client.post(
        '/api/v1/storefront/tenants/t/brands/b/checkouts',
        idempotencyKey: const IdempotencyKey('intent-1'),
        body: <String, Object?>{'cartId': 'c1'},
        decode: _identity,
      );

      expect(seen.headers[QoidaApiClient.idempotencyKeyHeader], 'intent-1');
    });

    test('does not send one on a read', () async {
      late http.BaseRequest seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request;
          return _json(<String, Object?>{});
        }),
      );

      await client.get('/orders', decode: _identity);

      expect(
        seen.headers.containsKey(QoidaApiClient.idempotencyKeyHeader),
        isFalse,
      );
    });

    test('reports a replayed response so a screen does not retry it', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => _json(
            <String, Object?>{'orderId': 'o1'},
            headers: <String, String>{
              QoidaApiClient.idempotencyReplayedHeader: 'true',
            },
          ),
        ),
      );

      final ApiResponse<Map<String, Object?>> response = await client.post(
        '/checkouts',
        idempotencyKey: const IdempotencyKey('intent-1'),
        decode: _identity,
      );

      // A replay means the order already exists. A screen that treated this as
      // a failure and retried would be the duplicate-order bug in person.
      expect(response.replayed, isTrue);
    });

    test('reuses the same key on the retry that follows a token refresh', () async {
      final List<String?> keysSeen = <String?>[];
      var attempt = 0;
      final _StubTokens tokens = _StubTokens('stale', refreshed: 'fresh');

      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          attempt++;
          keysSeen.add(request.headers[QoidaApiClient.idempotencyKeyHeader]);
          if (attempt == 1) {
            return _json(<String, Object?>{
              'code': 'UNAUTHENTICATED',
            }, status: 401);
          }
          return _json(<String, Object?>{'orderId': 'o1'});
        }),
        tokens: tokens,
      );

      await client.post(
        '/checkouts',
        idempotencyKey: const IdempotencyKey('intent-1'),
        decode: _identity,
      );

      // This retry is only safe because the key is stable. Generating one per
      // HTTP call would satisfy the header requirement and place two orders.
      expect(keysSeen, <String>['intent-1', 'intent-1']);
      expect(tokens.refreshes, 1);
    });

    test('gives up after one refresh rather than looping', () async {
      var attempts = 0;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          attempts++;
          return _json(<String, Object?>{'code': 'UNAUTHENTICATED'}, status: 401);
        }),
        tokens: _StubTokens('stale', refreshed: 'fresh'),
      );

      await expectLater(
        client.get('/orders', decode: _identity),
        throwsA(isA<ApiException>()),
      );
      expect(attempts, 2);
    });
  });

  group('optimistic concurrency', () {
    test('sends the expected version as a weak If-Match', () async {
      late http.BaseRequest seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request;
          return _json(<String, Object?>{});
        }),
      );

      await client.put(
        '/carts/c1/lines/l1',
        idempotencyKey: const IdempotencyKey('intent-1'),
        expectedVersion: 7,
        decode: _identity,
      );

      // The platform's AggregateVersion renders a weak validator, because two
      // responses at one version are equivalent without being byte-identical.
      expect(seen.headers['If-Match'], 'W/"7"');
    });

    test('reads the version back out of the ETag', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => _json(
            <String, Object?>{},
            headers: <String, String>{'etag': 'W/"9"'},
          ),
        ),
      );

      final ApiResponse<Map<String, Object?>> response = await client.get(
        '/carts/c1',
        decode: _identity,
      );

      expect(response.version, 9);
    });

    test('surfaces the current version from a STALE_VERSION problem', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'type': 'https://docs.qoida.uz/problems/stale-version',
              'title': 'Stale version',
              'status': 409,
              'detail': 'The resource has changed since version 7 was read',
              'code': 'STALE_VERSION',
              'correlationId': '01J8',
              'expectedVersion': 7,
              'currentVersion': 9,
            }),
            409,
            headers: <String, String>{
              'content-type': 'application/problem+json',
            },
          ),
        ),
      );

      try {
        await client.put(
          '/carts/c1/lines/l1',
          idempotencyKey: const IdempotencyKey('intent-1'),
          expectedVersion: 7,
          decode: _identity,
        );
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.isStaleVersion, isTrue);
        // Re-reading at this version is what resolves the conflict, so the
        // number has to reach the caller rather than being flattened to "409".
        expect(failure.problem.currentVersion, 9);
        expect(failure.problem.correlationId, '01J8');
      }
    });
  });

  group('problem details', () {
    test('branches on code, not on title', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'status': 403,
              'title': 'Insufficient capability',
              'code': 'INSUFFICIENT_CAPABILITY',
              'requiredCapability': 'ORDER_PLACE',
              'requiredScope': 'BRAND',
            }),
            403,
            headers: <String, String>{
              'content-type': 'application/problem+json',
            },
          ),
        ),
      );

      try {
        await client.get('/orders', decode: _identity);
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.code, ApiErrorCode.insufficientCapability);
        expect(failure.problem.requiredCapability, 'ORDER_PLACE');
      }
    });

    test('keeps field errors as codes rather than prose', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'status': 400,
              'code': 'VALIDATION_FAILED',
              'errors': <Map<String, Object?>>[
                <String, Object?>{
                  'field': 'lines[0].quantity',
                  'code': 'MUST_BE_POSITIVE',
                },
              ],
            }),
            400,
            headers: <String, String>{
              'content-type': 'application/problem+json',
            },
          ),
        ),
      );

      try {
        await client.post(
          '/carts',
          idempotencyKey: const IdempotencyKey('k'),
          decode: _identity,
        );
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.problem.errors.single.field, 'lines[0].quantity');
        expect(failure.problem.errors.single.code, 'MUST_BE_POSITIVE');
      }
    });

    test('tolerates an error code it has never heard of', () async {
      // ADR 0031 evolves a major version additively and permits new enum
      // values. A client that threw on decoding one would turn an additive
      // server change into a crash.
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'status': 409,
              'code': 'SOMETHING_ADDED_LATER',
            }),
            409,
            headers: <String, String>{
              'content-type': 'application/problem+json',
            },
          ),
        ),
      );

      try {
        await client.get('/orders', decode: _identity);
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.code.value, 'SOMETHING_ADDED_LATER');
        expect(failure.status, 409);
      }
    });

    test('does not pretend a gateway HTML page was Problem Details', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            '<html>502 Bad Gateway</html>',
            502,
            headers: <String, String>{'content-type': 'text/html'},
          ),
        ),
      );

      try {
        await client.get('/orders', decode: _identity);
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.code, ApiErrorCode.unparseable);
        expect(failure.status, 502);
      }
    });

    test('reads Retry-After on a 429', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'status': 429,
              'code': 'RATE_LIMIT_EXCEEDED',
            }),
            429,
            headers: <String, String>{
              'content-type': 'application/problem+json',
              'retry-after': '30',
            },
          ),
        ),
      );

      try {
        await client.get('/orders', decode: _identity);
        fail('expected an ApiException');
      } on ApiException catch (failure) {
        expect(failure.retryAfter, const Duration(seconds: 30));
        expect(failure.isRetryable, isTrue);
      }
    });

    test('an unparseable Retry-After yields null, not zero', () async {
      // Zero would mean "retry immediately", which is the opposite of what a
      // server asking for backoff wants.
      expect(
        QoidaApiClient.parseRetryAfter('Wed, 21 Oct 2026 07:28:00 GMT'),
        isNull,
      );
    });
  });

  group('cursor pagination', () {
    test('passes the cursor and limit, and reads the next cursor back', () async {
      late Uri seen;
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          seen = request.url;
          return _json(<String, Object?>{
            'items': <Map<String, Object?>>[
              <String, Object?>{'orderId': 'o1'},
              <String, Object?>{'orderId': 'o2'},
            ],
            'nextCursor': 'opaque-2',
          });
        }),
      );

      final ApiResponse<Page<Map<String, Object?>>> response = await client
          .getPage(
            '/api/v1/storefront/orders',
            decodeItem: _identity,
            cursor: 'opaque-1',
            limit: 20,
          );

      expect(seen.queryParameters['cursor'], 'opaque-1');
      expect(seen.queryParameters['limit'], '20');
      expect(response.value.items.length, 2);
      expect(response.value.nextCursor, 'opaque-2');
      expect(response.value.hasMore, isTrue);
    });

    test('a null next cursor is the end of the collection', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => _json(<String, Object?>{
            'items': <Map<String, Object?>>[],
            'nextCursor': null,
          }),
        ),
      );

      final ApiResponse<Page<Map<String, Object?>>> response = await client
          .getPage('/orders', decodeItem: _identity);

      expect(response.value.hasMore, isFalse);
    });
  });

  group('money on the wire', () {
    test('decodes an ADR 0031 money object out of a response', () async {
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => _json(<String, Object?>{
            'total': <String, Object?>{
              'amountMinor': 84000,
              'currency': 'UZS',
            },
          }),
        ),
      );

      final ApiResponse<Money> response = await client.get(
        '/carts/c1',
        decode: (Map<String, Object?> json) =>
            Money.fromJson(json['total']! as Map<String, Object?>),
      );

      expect(response.value, const Money(84000, 'UZS'));
    });
  });

  group('telemetry carries no personal data', () {
    test('redacts identifiers out of the recorded path', () {
      expect(
        QoidaApiClient.redactPath(
          '/api/v1/storefront/tenants/018f1a2b-3c4d-5e6f-8a9b-0c1d2e3f4a5b'
          '/brands/019a1a2b-3c4d-5e6f-8a9b-0c1d2e3f4a5b/orders/42',
        ),
        '/api/v1/storefront/tenants/{id}/brands/{id}/orders/{id}',
      );
    });

    test('records no query, no body, and no token', () async {
      final _RecordingTelemetry telemetry = _RecordingTelemetry();
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => _json(<String, Object?>{
            'customerName': 'Aziza Karimova',
            'phone': '+998901234567',
          }),
        ),
        telemetry: telemetry,
      );

      await client.post(
        '/api/v1/storefront/orders/018f1a2b-3c4d-5e6f-8a9b-0c1d2e3f4a5b',
        idempotencyKey: const IdempotencyKey('intent-1'),
        body: <String, Object?>{'phone': '+998901234567'},
        query: <String, String>{'address': 'Amir Temur 1'},
        decode: _identity,
      );

      final String recorded = telemetry.calls.single.toString();
      for (final String forbidden in <String>[
        'Aziza',
        '998901234567',
        'Amir Temur',
        'at_1',
        'intent-1',
      ]) {
        expect(recorded, isNot(contains(forbidden)), reason: forbidden);
      }
      expect(recorded, contains('cid-fixed'));
      expect(recorded, contains('{id}'));
    });

    test('records the error code, which is a registry constant', () async {
      final _RecordingTelemetry telemetry = _RecordingTelemetry();
      final QoidaApiClient client = _client(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'status': 404,
              'code': 'RESOURCE_NOT_FOUND',
              'detail': 'No such order',
            }),
            404,
            headers: <String, String>{
              'content-type': 'application/problem+json',
            },
          ),
        ),
        telemetry: telemetry,
      );

      await expectLater(
        client.get('/orders/1', decode: _identity),
        throwsA(isA<ApiException>()),
      );

      expect(telemetry.calls.single.errorCode, 'RESOURCE_NOT_FOUND');
      // The developer-facing detail is not telemetry. It is written for a
      // person reading a response, and it is not translated or logged.
      expect(telemetry.calls.single.toString(), isNot(contains('No such order')));
    });
  });

  group('transport failure', () {
    test('is distinct from an API error and names no URL', () async {
      final QoidaApiClient client = _client(
        MockClient((http.Request request) async {
          throw http.ClientException('Failed host lookup');
        }),
      );

      try {
        await client.get('/orders/018f1a2b-3c4d-5e6f-8a9b-0c1d2e3f4a5b',
            decode: _identity);
        fail('expected an ApiTransportException');
      } on ApiTransportException catch (failure) {
        expect(failure.reason, 'Failed host lookup');
        expect(failure.toString(), isNot(contains('018f1a2b')));
      }
    });
  });
}
