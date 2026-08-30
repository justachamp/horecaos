import 'package:flutter_test/flutter_test.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu.dart';
import 'package:qoida_mobile/src/features/catalogue/domain/modifier_selection.dart';

import 'menu_fixture.dart';

void main() {
  group('a single-choice group', () {
    final MenuModifierGroup group = groupFixture(
      required: true,
      minimumSelections: 1,
      maximumSelections: 1,
    );

    test('is not satisfied until it is answered', () {
      const ModifierSelection empty = ModifierSelection.empty();

      expect(empty.stateFor(group).isSatisfied, isFalse);
      expect(
        empty.stateFor(group).problem,
        ModifierSelectionProblem.tooFewSelected,
      );
      expect(empty.stateFor(group).outstanding, 1);
    });

    test('replaces rather than accumulates', () {
      final ModifierSelection chosen = const ModifierSelection.empty()
          .toggle(group, 'option-0')
          .toggle(group, 'option-1');

      expect(chosen.selectedIn(group.modifierGroupId), <String>{'option-1'});
      expect(chosen.stateFor(group).isSatisfied, isTrue);
    });

    test('can be unanswered again by tapping the answer', () {
      final ModifierSelection chosen = const ModifierSelection.empty()
          .toggle(group, 'option-0')
          .toggle(group, 'option-0');

      expect(chosen.selectedIn(group.modifierGroupId), isEmpty);
      expect(chosen.stateFor(group).isSatisfied, isFalse);
    });
  });

  group('a multi-choice group', () {
    final MenuModifierGroup group = groupFixture(
      required: false,
      minimumSelections: 0,
      maximumSelections: 2,
    );

    test('is satisfied with nothing chosen when it has no minimum', () {
      expect(const ModifierSelection.empty().stateFor(group).isSatisfied, isTrue);
    });

    test('refuses a selection beyond the maximum, and keeps the earlier two', () {
      // Refused rather than rotating the oldest out. Dropping a choice the
      // customer made, to make room for one they just made, is a change they
      // did not ask for and would only notice at the till.
      final ModifierSelection full = const ModifierSelection.empty()
          .toggle(group, 'option-0')
          .toggle(group, 'option-1');
      final ModifierSelection attempted = full.toggle(group, 'option-2');

      expect(attempted.selectedIn(group.modifierGroupId), <String>{
        'option-0',
        'option-1',
      });
      expect(attempted.stateFor(group).canToggle('option-2'), isFalse);
      // The two already chosen stay tappable, so the customer can swap.
      expect(attempted.stateFor(group).canToggle('option-0'), isTrue);
    });

    test('reports how many more a minimum still wants', () {
      final MenuModifierGroup twoOfThree = groupFixture(
        required: true,
        minimumSelections: 2,
        maximumSelections: 3,
      );
      final ModifierSelection one = const ModifierSelection.empty().toggle(
        twoOfThree,
        'option-0',
      );

      expect(one.stateFor(twoOfThree).outstanding, 1);
      expect(one.stateFor(twoOfThree).isSatisfied, isFalse);
      expect(
        one.toggle(twoOfThree, 'option-1').stateFor(twoOfThree).isSatisfied,
        isTrue,
      );
    });
  });

  group('an option the group does not contain', () {
    test('is ignored rather than accepted', () {
      final MenuModifierGroup group = groupFixture(
        required: false,
        minimumSelections: 0,
        maximumSelections: 2,
      );
      final ModifierSelection selection = const ModifierSelection.empty()
          .toggle(group, 'not-in-this-group');

      expect(selection.selectedIn(group.modifierGroupId), isEmpty);
    });
  });

  group('group health', () {
    test('accepts the bounds the database itself permits', () {
      expect(
        ModifierGroupRules.healthOf(
          groupFixture(
            required: true,
            minimumSelections: 1,
            maximumSelections: 2,
          ),
        ),
        ModifierGroupHealth.ok,
      );
    });

    test('rejects a maximum below one', () {
      // `ck_modifier_group_range` forbids it, so a menu carrying it means the
      // storefront projection lost the value rather than that a brand authored
      // it.
      expect(
        ModifierGroupRules.healthOf(
          groupFixture(
            required: false,
            minimumSelections: 0,
            maximumSelections: 0,
          ),
        ),
        ModifierGroupHealth.maximumBelowOne,
      );
    });

    test('rejects a minimum above the maximum', () {
      expect(
        ModifierGroupRules.healthOf(
          groupFixture(
            required: true,
            minimumSelections: 3,
            maximumSelections: 2,
          ),
        ),
        ModifierGroupHealth.minimumAboveMaximum,
      );
    });

    test('rejects a required group that zero answers would satisfy', () {
      expect(
        ModifierGroupRules.healthOf(
          groupFixture(
            required: true,
            minimumSelections: 0,
            maximumSelections: 2,
          ),
        ),
        ModifierGroupHealth.requiredWithNoMinimum,
      );
    });

    test('rejects a group with fewer options than its minimum', () {
      expect(
        ModifierGroupRules.healthOf(
          groupFixture(
            required: true,
            minimumSelections: 3,
            maximumSelections: 3,
            optionCount: 2,
          ),
        ),
        ModifierGroupHealth.tooFewOptions,
      );
    });

    test('makes an incoherent group unsatisfiable however it is answered', () {
      final MenuModifierGroup broken = groupFixture(
        required: true,
        minimumSelections: 3,
        maximumSelections: 2,
      );
      final ModifierSelection answered = const ModifierSelection.empty()
          .toggle(broken, 'option-0')
          .toggle(broken, 'option-1');

      expect(
        answered.stateFor(broken).problem,
        ModifierSelectionProblem.groupIsIncoherent,
      );
      expect(answered.isComplete(<MenuModifierGroup>[broken]), isFalse);
    });
  });

  group('one option is chosen at most once', () {
    test('whatever maximumQuantity says', () {
      // `allowSameOptionMultipleTimes` decides whether a repeat is allowed at
      // all and the storefront projection drops it, so a client honouring a
      // maximum quantity of three could offer what the server refuses. Capping
      // at one can only under-offer.
      expect(ModifierGroupRules.maximumPerOption, 1);

      final MenuModifierGroup extras = menuFixture().modifierGroups.last;
      final MenuModifierOption bread = extras.options.firstWhere(
        (MenuModifierOption option) => option.optionId == optionBread,
      );
      expect(bread.maximumQuantity, 3);

      final ModifierSelection twice = const ModifierSelection.empty()
          .toggle(extras, optionBread)
          .toggle(extras, optionBread);
      // The second tap removed it rather than adding a second one.
      expect(twice.selectedIn(extras.modifierGroupId), isEmpty);
    });
  });

  group('a whole product configuration', () {
    test('is not orderable when the branch has stopped the variant', () {
      final MenuProduct plov = menuFixture(plovOrderable: false).products.first;
      final ProductConfiguration configuration = ProductConfiguration(
        product: plov,
        variant: plov.preferredVariant,
        groups: const <MenuModifierGroup>[],
        selection: const ModifierSelection.empty(),
      );

      expect(configuration.isOrderable, isFalse);
    });

    test('is orderable when the variant is served and every group answered', () {
      final MenuProduct plov = menuFixture().products.first;
      final MenuModifierGroup spice = menuFixture().modifierGroups.first;

      final ProductConfiguration unanswered = ProductConfiguration(
        product: plov,
        variant: plov.preferredVariant,
        groups: <MenuModifierGroup>[spice],
        selection: const ModifierSelection.empty(),
      );
      expect(unanswered.isOrderable, isFalse);

      expect(unanswered.withToggled(spice, optionMild).isOrderable, isTrue);
    });

    test('keeps the modifier answers when the variant changes', () {
      // Nothing in the published menu says a group belongs to one variant
      // rather than another, so nothing says the answers stopped applying.
      // Clearing them would throw away the customer's work for no stated
      // reason.
      final MenuProduct plov = menuFixture().products.first;
      final MenuModifierGroup spice = menuFixture().modifierGroups.first;

      final ProductConfiguration configured = ProductConfiguration(
        product: plov,
        variant: plov.variants.first,
        groups: <MenuModifierGroup>[spice],
        selection: const ModifierSelection.empty(),
      ).withToggled(spice, optionHot).withVariant(plov.variants.last);

      expect(configured.variant?.variantId, variantPlovLarge);
      expect(configured.selection.selectedOptionIds, <String>[optionHot]);
    });

    test('offers the flat option list a cart line wants', () {
      final MenuProduct plov = menuFixture().products.first;
      final List<MenuModifierGroup> groups = menuFixture().modifierGroups;

      final ProductConfiguration configured = ProductConfiguration(
        product: plov,
        variant: plov.preferredVariant,
        groups: groups,
        selection: const ModifierSelection.empty(),
      ).withToggled(groups.first, optionMild).withToggled(groups.last, optionSalad);

      expect(
        configured.selection.selectedOptionIds,
        unorderedEquals(<String>[optionMild, optionSalad]),
      );
    });
  });

  test('the first unsatisfied group is the one to point at', () {
    final MenuModifierGroup spice = menuFixture().modifierGroups.first;
    final MenuModifierGroup extras = menuFixture().modifierGroups.last;

    final ModifierGroupState? first = const ModifierSelection.empty()
        .firstUnsatisfied(<MenuModifierGroup>[extras, spice]);

    // Extras has no minimum and is already satisfied; spice is the one to ask
    // about.
    expect(first?.group.modifierGroupId, spice.modifierGroupId);
  });
}
