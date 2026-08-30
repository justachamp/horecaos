import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/api/api_client.dart';
import 'package:horecaos_mobile/src/design/horecaos_theme.dart';
import 'package:horecaos_mobile/src/features/catalogue/catalogue_controller.dart';
import 'package:horecaos_mobile/src/features/catalogue/data/catalogue_scope.dart';
import 'package:horecaos_mobile/src/features/catalogue/data/menu.dart';
import 'package:horecaos_mobile/src/features/catalogue/data/menu_index.dart';
import 'package:horecaos_mobile/src/features/catalogue/data/menu_repository.dart';
import 'package:horecaos_mobile/src/features/catalogue/domain/modifier_selection.dart';
import 'package:horecaos_mobile/src/features/catalogue/ui/category_screen.dart';
import 'package:horecaos_mobile/src/features/catalogue/ui/menu_screen.dart';
import 'package:horecaos_mobile/src/features/catalogue/ui/product_detail_screen.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';
import 'package:horecaos_mobile/src/l10n/supported_locales.dart';

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

/// Every screen under the real theme and the real localisations.
///
/// Not a bare `MaterialApp`: `context.horecaos` throws without `HorecaOSTokens` in
/// the theme, which is deliberate — a widget that renders with Material's own
/// palette looks almost right, and almost right is what ships.
Widget _host(Widget child) => MaterialApp(
  theme: HorecaOSTheme.light(),
  locale: SupportedLocales.english,
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: SupportedLocales.all,
  home: child,
);

CatalogueController _controller(Map<String, Object?> body, {int status = 200}) =>
    CatalogueController(
      repository: MenuRepository(
        api: HorecaOSApiClient(
          baseUri: Uri.parse('https://api.example.test'),
          httpClient: MockClient(
            (http.Request request) async => http.Response(
              jsonEncode(body),
              status,
              headers: <String, String>{'content-type': 'application/json'},
            ),
          ),
          tokens: _NoTokens(),
        ),
      ),
      scope: _scope,
      locale: 'en',
    );

void main() {
  group('the menu screen', () {
    testWidgets('lists what the branch offers', (WidgetTester tester) async {
      final CatalogueController controller = _controller(menuJson());
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('Plov'), findsOneWidget);
      expect(find.text('Green tea'), findsOneWidget);
      expect(find.text('Rice, lamb, carrot.'), findsOneWidget);
    });

    testWidgets('shows no price, because the server quotes none here', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(menuJson());
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      // Two products, both orderable, both carrying the caption that stands
      // where a price would be. Nothing on this screen is a number.
      expect(find.text('Price is confirmed in your basket'), findsNWidgets(2));
      expect(find.textContaining(RegExp(r'\d')), findsNothing);
    });

    testWidgets('says an item is sold out rather than hiding it', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(
        menuJson(plovOrderable: false),
      );
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('Plov'), findsOneWidget);
      expect(find.text('Sold out'), findsOneWidget);
    });

    testWidgets('offers the category browse once the menu carries membership', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(menuJson());
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          MenuScreen(
            controller: controller,
            onOpenCategory: (MenuCategory _) {},
          ),
        ),
      );
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('Categories'), findsOneWidget);
      expect(find.text('Hot dishes'), findsOneWidget);
    });

    testWidgets('offers none on a publication written before membership was '
        'carried', (WidgetTester tester) async {
      final CatalogueController controller = _controller(
        menuJson(carriesMembership: false),
      );
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          MenuScreen(
            controller: controller,
            onOpenCategory: (MenuCategory _) {},
          ),
        ),
      );
      await controller.load();
      await tester.pumpAndSettle();

      // The categories are in the response and the heading is not on screen:
      // that publication carries no membership, so a category row would be a
      // tap that goes nowhere.
      expect(find.text('Categories'), findsNothing);
      expect(find.text('Hot dishes'), findsNothing);
    });

    testWidgets('tells an unpublished menu apart from an empty one', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(<String, Object?>{
        'status': 404,
        'code': 'RESOURCE_NOT_FOUND',
      }, status: 404);
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('No menu published'), findsOneWidget);
      // Nothing the customer taps will make a brand publish, so no retry.
      expect(find.text('Try again'), findsNothing);
    });

    testWidgets('offers a retry when the failure is one', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(<String, Object?>{
        'status': 500,
        'code': 'INTERNAL_ERROR',
      }, status: 500);
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('The menu did not load'), findsOneWidget);
      expect(find.text('Try again'), findsOneWidget);
    });

    testWidgets('says a published menu with no items is empty', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(<String, Object?>{
        'publicationId': publicationId,
        'locale': 'en',
        'categories': <Object?>[],
        'products': <Object?>[],
        'modifierGroups': <Object?>[],
      });
      addTearDown(controller.dispose);

      await tester.pumpWidget(_host(MenuScreen(controller: controller)));
      await controller.load();
      await tester.pumpAndSettle();

      expect(find.text('Nothing on the menu'), findsOneWidget);
    });

    testWidgets('opens a product when a row is tapped', (
      WidgetTester tester,
    ) async {
      final CatalogueController controller = _controller(menuJson());
      addTearDown(controller.dispose);
      MenuProduct? opened;

      await tester.pumpWidget(
        _host(
          MenuScreen(
            controller: controller,
            onOpenProduct: (MenuProduct product) => opened = product,
          ),
        ),
      );
      await controller.load();
      await tester.pumpAndSettle();

      await tester.tap(find.text('Green tea'));
      expect(opened?.productId, productTea);
    });
  });

  group('product detail', () {
    Widget detail({
      bool plovOrderable = true,
      List<MenuModifierGroup> groups = const <MenuModifierGroup>[],
      void Function(ProductConfiguration)? onAddToBasket,
    }) => _host(
      _DetailHarness(
        product: menuFixture(plovOrderable: plovOrderable).products.first,
        groups: groups,
        plovOrderable: plovOrderable,
        onAddToBasket: onAddToBasket,
      ),
    );

    testWidgets('shows the variants the branch offers, and which are stopped', (
      WidgetTester tester,
    ) async {
      await tester.pumpWidget(detail());
      await tester.pumpAndSettle();

      expect(find.text('Choose an option'), findsOneWidget);
      // Labelled by SKU, because the published menu carries no display name for
      // a variant. Recorded as a contract gap rather than accepted as good.
      expect(find.text('PLOV-REG'), findsOneWidget);
      expect(find.text('PLOV-LRG'), findsOneWidget);
      expect(find.text('Sold out'), findsOneWidget);
    });

    testWidgets('does not ask a question with one answer', (
      WidgetTester tester,
    ) async {
      final MenuIndex index = MenuIndex.of(menuFixture());
      await tester.pumpWidget(
        _host(
          ProductDetailScreen(
            index: index,
            product: index.productById(productTea)!,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Choose an option'), findsNothing);
    });

    testWidgets('shows no price and no total', (WidgetTester tester) async {
      await tester.pumpWidget(detail());
      await tester.pumpAndSettle();

      expect(find.text('Price is confirmed in your basket'), findsOneWidget);
    });

    testWidgets('explains a product the branch has stopped entirely', (
      WidgetTester tester,
    ) async {
      await tester.pumpWidget(detail(plovOrderable: false));
      await tester.pumpAndSettle();

      expect(
        find.text('This item is not being served at this branch right now.'),
        findsOneWidget,
      );
    });

    testWidgets('will not let a stopped product be added', (
      WidgetTester tester,
    ) async {
      await tester.pumpWidget(
        detail(plovOrderable: false, onAddToBasket: (ProductConfiguration _) {}),
      );
      await tester.pumpAndSettle();

      final FilledButton add = tester.widget<FilledButton>(
        find.byType(FilledButton),
      );
      expect(add.onPressed, isNull);
    });

    testWidgets('holds the action until a required group is answered', (
      WidgetTester tester,
    ) async {
      final MenuModifierGroup spice = menuFixture().modifierGroups.first;
      ProductConfiguration? added;

      await tester.pumpWidget(
        detail(
          groups: <MenuModifierGroup>[spice],
          onAddToBasket: (ProductConfiguration configuration) =>
              added = configuration,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Spice level'), findsOneWidget);
      expect(find.text('Required'), findsOneWidget);
      expect(find.text('Choose 1'), findsOneWidget);

      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );

      await tester.tap(find.text('MILD'));
      await tester.pumpAndSettle();

      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull,
      );

      await tester.tap(find.text('Add to basket'));
      expect(added?.selection.selectedOptionIds, <String>[optionMild]);
    });

    testWidgets('stops an optional group at its maximum', (
      WidgetTester tester,
    ) async {
      final MenuModifierGroup extras = menuFixture().modifierGroups.last;

      await tester.pumpWidget(detail(groups: <MenuModifierGroup>[extras]));
      await tester.pumpAndSettle();

      expect(find.text('Optional'), findsOneWidget);
      expect(find.text('Choose up to 2'), findsOneWidget);

      await tester.tap(find.text('SALAD'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('BREAD'));
      await tester.pumpAndSettle();
      // The third is refused rather than rotating one of the first two out.
      await tester.tap(find.text('SAUCE'));
      await tester.pumpAndSettle();

      expect(find.text('SAUCE'), findsOneWidget);
    });

    testWidgets('refuses to render a picker nobody could satisfy', (
      WidgetTester tester,
    ) async {
      final MenuModifierGroup broken = groupFixture(
        required: true,
        minimumSelections: 3,
        maximumSelections: 2,
      );

      await tester.pumpWidget(detail(groups: <MenuModifierGroup>[broken]));
      await tester.pumpAndSettle();

      expect(find.text('This choice is unavailable right now'), findsOneWidget);
      expect(find.text('OPTION_0'), findsNothing);
    });
  });

  group('the category screen', () {
    testWidgets('lists a category\'s products once the server carries them', (
      WidgetTester tester,
    ) async {
      final MenuIndex index = MenuIndex.of(
        menuFixture(),
        productIdsByCategory: <String, List<String>>{
          categoryDrinks: <String>[productTea],
        },
      );

      await tester.pumpWidget(
        _host(
          CategoryScreen(
            index: index,
            category: index.rootCategories.first,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Drinks'), findsOneWidget);
      expect(find.text('Green tea'), findsOneWidget);
    });

    testWidgets('says so when a category holds nothing', (
      WidgetTester tester,
    ) async {
      // Reached from a publication carrying no membership, where every category
      // is empty. The server drops an empty category from a menu it does carry
      // membership for, so this is the only way to land on one.
      final MenuIndex index = MenuIndex.of(
        menuFixture(carriesMembership: false),
      );

      await tester.pumpWidget(
        _host(
          CategoryScreen(
            index: index,
            category: index.rootCategories.first,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Nothing on the menu'), findsOneWidget);
    });
  });
}

/// Product detail with an explicit set of modifier groups.
///
/// The groups are passed rather than read off the index so that a picker case
/// — a required group, an unsatisfiable one, a full multi-choice group — can be
/// built directly instead of being authored into the shared fixture.
class _DetailHarness extends StatelessWidget {
  const _DetailHarness({
    required this.product,
    required this.groups,
    required this.plovOrderable,
    this.onAddToBasket,
  });

  final MenuProduct product;
  final List<MenuModifierGroup> groups;
  final bool plovOrderable;
  final void Function(ProductConfiguration)? onAddToBasket;

  @override
  Widget build(BuildContext context) {
    final StorefrontMenu base = menuFixture(plovOrderable: plovOrderable);
    // The groups under test replace the menu's own, so a group identifier
    // resolves to the group this test wrote rather than to the fixture's.
    final StorefrontMenu menu = StorefrontMenu(
      publicationId: base.publicationId,
      locale: base.locale,
      categories: base.categories,
      products: base.products,
      modifierGroups: groups.isEmpty ? base.modifierGroups : groups,
    );
    final MenuIndex index = MenuIndex.of(
      menu,
      modifierGroupIdsByProduct: <String, List<String>>{
        product.productId: <String>[
          for (final MenuModifierGroup group in groups) group.modifierGroupId,
        ],
      },
    );
    return ProductDetailScreen(
      index: index,
      product: product,
      onAddToBasket: onAddToBasket,
    );
  }
}
