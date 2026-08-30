import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/api/api_client.dart';
import 'package:horecaos_mobile/src/api/api_telemetry.dart';
import 'package:horecaos_mobile/src/api/idempotency_key.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address_repository.dart';

import 'profile_harness.dart';

/// Every request in this feature goes through `HorecaOSApiClient`.
///
/// The point of these tests is the wire: the path, the reveal purpose, the
/// idempotency key, and what telemetry is allowed to see afterwards.
class _StubTokens implements AccessTokens {
  @override
  Future<String?> current() async => 'at_1';

  @override
  Future<String?> refresh() async => 'at_1';
}

class _RecordingTelemetry implements ApiTelemetry {
  final List<ApiCallRecord> calls = <ApiCallRecord>[];

  @override
  void record(ApiCallRecord call) => calls.add(call);
}

void main() {
  late List<http.BaseRequest> sent;
  late _RecordingTelemetry telemetry;

  HttpSavedAddressRepository repository(
    Future<http.Response> Function(http.Request request) handler,
  ) {
    sent = <http.BaseRequest>[];
    telemetry = _RecordingTelemetry();
    return HttpSavedAddressRepository(
      api: HorecaOSApiClient(
        baseUri: Uri.parse('https://api.example.test'),
        httpClient: MockClient((http.Request request) {
          sent.add(request);
          return handler(request);
        }),
        tokens: _StubTokens(),
        telemetry: telemetry,
      ),
      scope: testScope,
      accountId: testAccount.accountId,
    );
  }

  http.Response json(Object body, {int status = 200}) => http.Response(
    jsonEncode(body),
    status,
    headers: <String, String>{'content-type': 'application/json'},
  );

  group('reading the list', () {
    test('asks the tenant-scoped customer path for this account', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async =>
            json(<String, Object?>{'items': <Object?>[], 'nextCursor': null}),
      );

      await addresses.list();

      expect(
        sent.single.url.path,
        '/api/v1/tenants/${testScope.tenantId}/customers/'
        '${testAccount.accountId}/addresses',
      );
    });

    test('states a purpose, because reading one decrypts it', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async =>
            json(<String, Object?>{'items': <Object?>[], 'nextCursor': null}),
      );

      await addresses.list();

      expect(
        sent.single.url.queryParameters['purpose'],
        HttpSavedAddressRepository.revealPurpose,
      );
    });

    test('decodes the ADR 0031 collection envelope', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async => json(<String, Object?>{
          'items': <Object?>[
            <String, Object?>{
              'id': 'a-1',
              'label': 'Uy',
              'fields': <String, Object?>{'landmark': 'Dorixona'},
              'coordinateSource': 'LANDMARK_ONLY',
            },
          ],
          'nextCursor': null,
        }),
      );

      final List<SavedAddress> found = await addresses.list();

      expect(found, hasLength(1));
      expect(found.single.coordinateSource, CoordinateSource.landmarkOnly);
    });

    test('tells telemetry the shape of the call and none of the data', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async =>
            json(<String, Object?>{'items': <Object?>[], 'nextCursor': null}),
      );

      await addresses.list();

      final ApiCallRecord record = telemetry.calls.single;
      expect(
        record.redactedPath,
        '/api/v1/tenants/{id}/customers/{id}/addresses',
      );
      // The identifiers are gone, and there was never anywhere for a street
      // name to go: the record has no field for a query string or a body.
      expect(record.redactedPath, isNot(contains(testScope.tenantId)));
      expect(record.toString(), isNot(contains('purpose')));
    });
  });

  group('adding one', () {
    test('carries the caller\'s idempotency key', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async => json(<String, Object?>{'id': 'a-2'}),
      );
      const IdempotencyKey key = IdempotencyKey('intent-1');

      await addresses.add(
        const AddressDraft(
          fields: AddressFields(landmark: 'Dorixona'),
          coordinateSource: CoordinateSource.landmarkOnly,
        ),
        idempotencyKey: key,
      );

      expect(
        sent.single.headers[HorecaOSApiClient.idempotencyKeyHeader],
        'intent-1',
      );
    });

    test('sends a landmark-only address with no coordinate', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async => json(<String, Object?>{'id': 'a-2'}),
      );

      await addresses.add(
        const AddressDraft(
          label: 'Uy',
          fields: AddressFields(
            line1: 'Amir Temur 12',
            landmark: 'Dorixona ro\'parasida',
          ),
          coordinateSource: CoordinateSource.landmarkOnly,
        ),
        idempotencyKey: IdempotencyKey.generate(),
      );

      final Map<String, Object?> body =
          jsonDecode((sent.single as http.Request).body)
              as Map<String, Object?>;
      expect(body['coordinateSource'], 'LANDMARK_ONLY');
      expect(body['latitude'], isNull);
      expect(body['longitude'], isNull);
      expect(
        (body['fields']! as Map<String, Object?>)['landmark'],
        "Dorixona ro'parasida",
      );
    });

    test('refuses a draft the platform would refuse, without sending it',
        () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async => json(<String, Object?>{'id': 'a-2'}),
      );

      await expectLater(
        addresses.add(
          // Says there is no pin, and gives nothing to find the place by.
          const AddressDraft(
            fields: AddressFields(line1: 'Amir Temur 12'),
            coordinateSource: CoordinateSource.landmarkOnly,
          ),
          idempotencyKey: IdempotencyKey.generate(),
        ),
        throwsArgumentError,
      );
      expect(sent, isEmpty);
    });
  });

  group('changing or deleting one', () {
    test('is declared unsupported rather than attempted', () async {
      final HttpSavedAddressRepository addresses = repository(
        (http.Request _) async => json(<String, Object?>{}),
      );

      // `CustomerController` exposes POST and GET on this collection and
      // nothing else. A guessed DELETE would 404, and a 404 on a delete reads
      // to a customer as "your address could not be removed" when the truth is
      // that removal was never wired.
      expect(addresses.supportsRemove, isFalse);
      expect(addresses.supportsReplace, isFalse);

      await expectLater(
        addresses.remove('a-1', idempotencyKey: IdempotencyKey.generate()),
        throwsA(isA<AddressOperationUnavailable>()),
      );
      await expectLater(
        addresses.replace(
          'a-1',
          const AddressDraft(
            fields: AddressFields(landmark: 'Dorixona'),
            coordinateSource: CoordinateSource.landmarkOnly,
          ),
          idempotencyKey: IdempotencyKey.generate(),
        ),
        throwsA(isA<AddressOperationUnavailable>()),
      );
      expect(sent, isEmpty);
    });
  });
}
