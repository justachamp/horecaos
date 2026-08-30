import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/api/api_exception.dart';
import 'package:qoida_mobile/src/features/catalogue/catalogue_controller.dart';
import 'package:qoida_mobile/src/features/catalogue/data/catalogue_scope.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu_repository.dart';

import 'menu_fixture.dart';

const CatalogueScope _scope = CatalogueScope(
  tenantId: '0192d4b2-0000-7000-8000-0000000000t1',
  brandId: '0192d4b2-0000-7000-8000-0000000000r1',
  locationId: '0192d4b2-0000-7000-8000-0000000000l1',
);

class _NoTokens implements AccessTokens {
  @override
  Future<String?> current() async => null;

  @override
  Future<String?> refresh() async => null;
}

MenuRepository _repository(MockClient transport) => MenuRepository(
  api: QoidaApiClient(
    baseUri: Uri.parse('https://api.example.test'),
    httpClient: transport,
    // The storefront catalog endpoint is unauthenticated by design — it is the
    // menu a customer browses before they have an account — so the repository
    // is exercised with no token at all.
    tokens: _NoTokens(),
    correlationIds: () => 'cid-fixed',
  ),
);

http.Response _json(Object body, {int status = 200}) => http.Response(
  jsonEncode(body),
  status,
  headers: <String, String>{'content-type': 'application/json'},
);

void main() {
  group('the request', () {
    test('is the storefront menu path for this branch, with the locale', () async {
      late Uri seen;
      final MenuRepository repository = _repository(
        MockClient((http.Request request) async {
          seen = request.url;
          return _json(menuJson());
        }),
      );

      await repository.menu(scope: _scope, locale: 'ru');

      expect(
        seen.path,
        '/api/v1/storefront/tenants/${_scope.tenantId}'
        '/brands/${_scope.brandId}/locations/${_scope.locationId}/menu',
      );
      expect(seen.queryParameters['locale'], 'ru');
    });

    test('decodes the published menu', () async {
      final MenuRepository repository = _repository(
        MockClient((http.Request request) async => _json(menuJson())),
      );

      final StorefrontMenu menu = await repository.menu(
        scope: _scope,
        locale: 'en',
      );

      expect(menu.publicationId, publicationId);
      expect(menu.products, hasLength(2));
    });
  });

  group('the controller', () {
    test('holds the menu once it arrives', () async {
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient((http.Request request) async => _json(menuJson())),
        ),
        scope: _scope,
        locale: 'en',
      );
      addTearDown(controller.dispose);

      expect(controller.state, isA<MenuLoading>());
      await controller.load();

      final MenuState state = controller.state;
      expect(state, isA<MenuReady>());
      expect((state as MenuReady).index.products, hasLength(2));
    });

    test('tells "no menu published" apart from "something went wrong"', () async {
      // A 404 here is the platform saying this brand has never published. It is
      // not an error the customer can retry away, and the screen says so.
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient(
            (http.Request request) async => _json(<String, Object?>{
              'status': 404,
              'code': 'RESOURCE_NOT_FOUND',
              'title': 'This brand has no published menu',
            }, status: 404),
          ),
        ),
        scope: _scope,
        locale: 'en',
      );
      addTearDown(controller.dispose);

      await controller.load();

      expect(
        (controller.state as MenuFailed).kind,
        MenuFailureKind.notPublished,
      );
    });

    test('reports a refusal as a refusal', () async {
      // The catalog endpoint is unauthenticated today, so this should not
      // happen. It is carried because ADR 0025 has not settled what a non-staff
      // principal is, the storefront's ordering endpoints answer 403 to a real
      // customer, and if the menu ever joins them the customer must see a
      // sentence rather than a stack trace.
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient(
            (http.Request request) async => _json(<String, Object?>{
              'status': 403,
              'code': 'FORBIDDEN',
            }, status: 403),
          ),
        ),
        scope: _scope,
        locale: 'en',
      );
      addTearDown(controller.dispose);

      await controller.load();

      expect((controller.state as MenuFailed).kind, MenuFailureKind.forbidden);
    });

    test('reports a transport failure as offline, not as a server error', () async {
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient(
            (http.Request request) async =>
                throw http.ClientException('Failed host lookup'),
          ),
        ),
        scope: _scope,
        locale: 'en',
      );
      addTearDown(controller.dispose);

      await controller.load();

      expect((controller.state as MenuFailed).kind, MenuFailureKind.offline);
    });

    test('reports a menu that does not decode rather than half-rendering it', () async {
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient(
            (http.Request request) async => _json(<String, Object?>{
              'publicationId': publicationId,
              'locale': 'en',
              'categories': <Object?>[],
              'modifierGroups': <Object?>[],
              'products': <Object?>[
                // No name. A screen showing a blank row would be worse than a
                // screen saying the menu did not load.
                <String, Object?>{'productId': productPlov},
              ],
            }),
          ),
        ),
        scope: _scope,
        locale: 'en',
      );
      addTearDown(controller.dispose);

      await controller.load();

      expect(
        (controller.state as MenuFailed).kind,
        MenuFailureKind.unavailable,
      );
    });

    test('reloads in the new language when the customer switches locale', () async {
      final List<String?> locales = <String?>[];
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient((http.Request request) async {
            locales.add(request.url.queryParameters['locale']);
            return _json(menuJson());
          }),
        ),
        scope: _scope,
        locale: 'ru',
      );
      addTearDown(controller.dispose);

      await controller.load();
      await controller.setLocale('uz');
      // Setting the same locale again is not a reload: the response would be
      // identical and the list would flicker for nothing.
      await controller.setLocale('uz');

      expect(locales, <String>['ru', 'uz']);
    });

    test('ignores a superseded load', () async {
      // Two loads in flight, the first answering last. Without a generation
      // check the stale menu would win and the screen would show the language
      // the customer just switched away from.
      final List<Completer<http.Response>> pending =
          <Completer<http.Response>>[];
      final CatalogueController controller = CatalogueController(
        repository: _repository(
          MockClient((http.Request request) {
            final Completer<http.Response> completer =
                Completer<http.Response>();
            pending.add(completer);
            return completer.future;
          }),
        ),
        scope: _scope,
        locale: 'ru',
      );
      addTearDown(controller.dispose);

      final Future<void> first = controller.load();
      final Future<void> second = controller.load();

      // Both loads read the token before they send, so neither request exists
      // until the microtask queue has drained.
      while (pending.length < 2) {
        await Future<void>.delayed(Duration.zero);
      }

      pending[1].complete(_json(menuJson()));
      await second;
      expect(controller.state, isA<MenuReady>());

      pending[0].complete(
        _json(<String, Object?>{'status': 500, 'code': 'INTERNAL_ERROR'},
            status: 500),
      );
      await first;

      expect(
        controller.state,
        isA<MenuReady>(),
        reason: 'the superseded load must not overwrite the current menu',
      );
    });
  });

  test('an ApiException carries the platform\'s own problem code', () async {
    final MenuRepository repository = _repository(
      MockClient(
        (http.Request request) async => _json(<String, Object?>{
          'status': 404,
          'code': 'RESOURCE_NOT_FOUND',
        }, status: 404),
      ),
    );

    await expectLater(
      repository.menu(scope: _scope, locale: 'en'),
      throwsA(isA<ApiException>().having((ApiException e) => e.status, 'status', 404)),
    );
  });
}
