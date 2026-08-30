import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/api/idempotency_key.dart';
import 'package:qoida_mobile/src/features/profile/data/notification_preference.dart';
import 'package:qoida_mobile/src/features/profile/data/notification_preference_repository.dart';

import 'profile_harness.dart';

class _StubTokens implements AccessTokens {
  @override
  Future<String?> current() async => 'at_1';

  @override
  Future<String?> refresh() async => 'at_1';
}

void main() {
  group('what a customer may refuse', () {
    test('a required transactional message is not switchable', () {
      // The whole reason this enum is mirrored in the client. A screen has to
      // know before it draws a switch, because drawing one and then letting
      // the server refuse the write is an interface that lied.
      expect(NotificationClass.transactionalRequired.isSwitchable, isFalse);
      expect(NotificationClass.transactionalRequired.isAlwaysSent, isTrue);
    });

    test('a security message is not switchable', () {
      expect(NotificationClass.security.isSwitchable, isFalse);
      expect(NotificationClass.security.isAlwaysSent, isTrue);
    });

    test('optional and marketing messages are', () {
      expect(NotificationClass.transactionalOptional.isSwitchable, isTrue);
      expect(NotificationClass.marketing.isSwitchable, isTrue);
    });

    test('an operations alert is neither, because it is not the customer\'s',
        () {
      expect(NotificationClass.operationsAlert.isSwitchable, isFalse);
      expect(NotificationClass.operationsAlert.isAlwaysSent, isFalse);
      expect(NotificationClass.operationsAlert.addressedToCustomer, isFalse);
    });

    test('a class this build does not know is never given a switch', () {
      final NotificationClass added = NotificationClass.fromWire(
        'SOMETHING_ADDED_LATER',
      );

      expect(added, NotificationClass.unknown);
      // The client cannot tell whether an unknown class is one a customer may
      // refuse, and guessing yes is how a required message becomes
      // suppressible.
      expect(added.isSwitchable, isFalse);
      expect(added.isAlwaysSent, isFalse);
    });
  });

  group('channels', () {
    test('only SMS has an adapter behind it', () {
      expect(NotificationChannel.sms.isWired, isTrue);
      expect(NotificationChannel.email.isWired, isFalse);
      expect(NotificationChannel.push.isWired, isFalse);
      expect(NotificationChannel.messagingApp.isWired, isFalse);
    });

    test('an unrecognised channel decodes rather than throwing', () {
      expect(
        NotificationChannel.fromWire('CARRIER_PIGEON'),
        NotificationChannel.unknown,
      );
    });
  });

  group('defaulting', () {
    test('no row at all means enabled', () {
      // ADR 0020's default is on. A customer who never expressed a preference
      // has not opted out of anything, and rendering that as off would show
      // them a refusal they never made.
      const NotificationPreferences none = NotificationPreferences.empty();

      expect(
        none.isEnabled(
          NotificationClass.marketing,
          NotificationChannel.sms,
        ),
        isTrue,
      );
    });

    test('a stored row wins over the default', () {
      const NotificationPreferences stored = NotificationPreferences(
        <NotificationPreference>[
          NotificationPreference(
            notificationClass: NotificationClass.marketing,
            channel: NotificationChannel.sms,
            enabled: false,
          ),
        ],
      );

      expect(
        stored.isEnabled(NotificationClass.marketing, NotificationChannel.sms),
        isFalse,
      );
    });

    test('a brand row wins over the tenant-wide one', () {
      const NotificationPreferences stored = NotificationPreferences(
        <NotificationPreference>[
          NotificationPreference(
            notificationClass: NotificationClass.marketing,
            channel: NotificationChannel.sms,
            enabled: true,
          ),
          NotificationPreference(
            brandId: 'brand-1',
            notificationClass: NotificationClass.marketing,
            channel: NotificationChannel.sms,
            enabled: false,
          ),
        ],
      );

      expect(
        stored.isEnabled(
          NotificationClass.marketing,
          NotificationChannel.sms,
          brandId: 'brand-1',
        ),
        isFalse,
      );
      expect(
        stored.isEnabled(NotificationClass.marketing, NotificationChannel.sms),
        isTrue,
      );
    });
  });

  group('writing one', () {
    late List<http.BaseRequest> sent;

    HttpNotificationPreferenceRepository repository() {
      sent = <http.BaseRequest>[];
      return HttpNotificationPreferenceRepository(
        api: QoidaApiClient(
          baseUri: Uri.parse('https://api.example.test'),
          httpClient: MockClient((http.Request request) async {
            sent.add(request);
            return http.Response('', 204);
          }),
          tokens: _StubTokens(),
        ),
        scope: testScope,
        accountId: testAccount.accountId,
      );
    }

    test('puts the class and channel in the path, with a key', () async {
      await repository().set(
        notificationClass: NotificationClass.marketing,
        channel: NotificationChannel.sms,
        enabled: false,
        idempotencyKey: const IdempotencyKey('intent-1'),
      );

      final http.BaseRequest request = sent.single;
      expect(request.method, 'PUT');
      expect(request.url.path, endsWith('/notification-preferences/MARKETING/SMS'));
      expect(
        request.headers[QoidaApiClient.idempotencyKeyHeader],
        'intent-1',
      );
      expect(
        jsonDecode((request as http.Request).body),
        <String, Object?>{'brandId': null, 'enabled': false},
      );
    });

    test('refuses a class the customer cannot switch off, without sending it',
        () async {
      final HttpNotificationPreferenceRepository preferences = repository();

      await expectLater(
        preferences.set(
          notificationClass: NotificationClass.transactionalRequired,
          channel: NotificationChannel.sms,
          enabled: false,
          idempotencyKey: IdempotencyKey.generate(),
        ),
        throwsArgumentError,
      );
      expect(sent, isEmpty);
    });

    test('refuses a channel nothing sends on, without sending it', () async {
      final HttpNotificationPreferenceRepository preferences = repository();

      await expectLater(
        preferences.set(
          notificationClass: NotificationClass.marketing,
          channel: NotificationChannel.email,
          enabled: true,
          idempotencyKey: IdempotencyKey.generate(),
        ),
        throwsArgumentError,
      );
      expect(sent, isEmpty);
    });
  });
}
