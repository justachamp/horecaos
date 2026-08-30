import '../data/menu.dart';

/// Whether a group's own bounds make sense.
///
/// These mirror `ck_modifier_group_range` and `ck_modifier_group_required` on
/// `catalog.modifier_groups`, which is not duplication for its own sake: those
/// constraints are what guarantee a customer can finish a choice, and a client
/// that trusted a group violating them would render a picker nobody can
/// satisfy. A group that arrives incoherent is reported rather than repaired,
/// because repairing it means guessing which of the two numbers was wrong.
enum ModifierGroupHealth {
  ok,

  /// `maximumSelections` below one. The database forbids it; a menu carrying it
  /// means the storefront projection lost the value.
  maximumBelowOne,

  /// `minimumSelections` above `maximumSelections` — a choice with no valid
  /// answer.
  minimumAboveMaximum,

  /// Required, and yet zero selections would satisfy it.
  requiredWithNoMinimum,

  /// Fewer options than the minimum demands, so the group cannot be completed
  /// however the customer answers.
  tooFewOptions,
}

/// The rules a group states, read off the server and never inferred.
abstract final class ModifierGroupRules {
  /// How many of one option a single line may carry.
  ///
  /// Always one, and deliberately not [MenuModifierOption.maximumQuantity].
  /// The group's `allowSameOptionMultipleTimes` now arrives on the wire, so the
  /// contract no longer forces this — the selection model does: a group's
  /// answer is a `Set` of option ids, which can say chosen or not chosen and
  /// cannot say twice. Honouring a maximum quantity means carrying counts
  /// through the picker, its widgets and its validation, which is a larger
  /// change than reading one flag.
  ///
  /// Until then this can only under-offer. A group's `maximumSelections` still
  /// bounds the total, so nothing here offers a selection checkout would
  /// refuse; a customer wanting two of something adds the line twice.
  static const int maximumPerOption = 1;

  static ModifierGroupHealth healthOf(MenuModifierGroup group) {
    if (group.maximumSelections < 1) return ModifierGroupHealth.maximumBelowOne;
    if (group.minimumSelections > group.maximumSelections) {
      return ModifierGroupHealth.minimumAboveMaximum;
    }
    if (group.required && group.minimumSelections < 1) {
      return ModifierGroupHealth.requiredWithNoMinimum;
    }
    // Capacity is one per option, so the option count is the capacity.
    if (group.minimumSelections > group.options.length) {
      return ModifierGroupHealth.tooFewOptions;
    }
    return ModifierGroupHealth.ok;
  }

  static bool isCoherent(MenuModifierGroup group) =>
      healthOf(group) == ModifierGroupHealth.ok;

  /// True when exactly one answer is expected, which is a radio and not a set
  /// of checkboxes.
  static bool isSingleChoice(MenuModifierGroup group) =>
      group.maximumSelections == 1;
}

/// Why a configuration cannot be ordered.
enum ModifierSelectionProblem {
  /// A required group, or one with a minimum, has too few selections.
  tooFewSelected,

  /// More selected than the group permits. Reachable only by constructing a
  /// selection directly; [ModifierSelection.toggle] will not produce one.
  tooManySelected,

  /// The group's own bounds are unsatisfiable — see [ModifierGroupHealth].
  groupIsIncoherent,
}

/// One group's state inside a selection.
final class ModifierGroupState {
  const ModifierGroupState({
    required this.group,
    required this.selectedOptionIds,
    required this.problem,
  });

  final MenuModifierGroup group;
  final Set<String> selectedOptionIds;

  /// Null when this group is satisfied.
  final ModifierSelectionProblem? problem;

  bool get isSatisfied => problem == null;

  int get selectedCount => selectedOptionIds.length;

  /// How many more must be chosen before this group is satisfied. Zero when it
  /// already is.
  int get outstanding {
    final int missing = group.minimumSelections - selectedCount;
    return missing > 0 ? missing : 0;
  }

  bool isSelected(String optionId) => selectedOptionIds.contains(optionId);

  /// Whether tapping this option would do anything.
  ///
  /// Selecting inside a full multi-choice group does nothing, so the option is
  /// disabled rather than tappable-and-inert. A single-choice group is always
  /// enabled: tapping there replaces the current answer.
  bool canToggle(String optionId) {
    if (isSelected(optionId)) return true;
    if (ModifierGroupRules.isSingleChoice(group)) return true;
    return selectedCount < group.maximumSelections;
  }
}

/// What the customer has chosen on a product detail screen.
///
/// Immutable: [toggle] returns a new selection rather than mutating, so a
/// screen's state is one object and an accidental in-place edit cannot make the
/// rendered picker and the validated selection disagree.
///
/// It validates and it does not price. There is no `total` here and there will
/// not be one — ADR 0018 makes the quote authoritative, and a client that added
/// up option prices would eventually disagree with it, after the customer had
/// already read the first number.
final class ModifierSelection {
  const ModifierSelection._(this._byGroup);

  /// Nothing chosen yet.
  const ModifierSelection.empty() : _byGroup = const <String, Set<String>>{};

  final Map<String, Set<String>> _byGroup;

  Set<String> selectedIn(String groupId) =>
      Set<String>.unmodifiable(_byGroup[groupId] ?? const <String>{});

  /// Every option chosen across every group, in no particular order.
  ///
  /// This is the shape a cart line wants: `PutLineRequest.modifierOptionIds` is
  /// a flat list of option identifiers.
  List<String> get selectedOptionIds => <String>[
    for (final Set<String> options in _byGroup.values) ...options,
  ];

  /// Adds or removes one option, honouring the group's own bounds.
  ///
  /// Single-choice groups replace. Multi-choice groups refuse a selection that
  /// would exceed the maximum, returning `this` unchanged rather than silently
  /// dropping somebody else's earlier choice.
  ModifierSelection toggle(MenuModifierGroup group, String optionId) {
    final bool belongs = group.options.any(
      (MenuModifierOption option) => option.optionId == optionId,
    );
    if (!belongs) return this;

    final Set<String> current = <String>{...?_byGroup[group.modifierGroupId]};

    if (current.contains(optionId)) {
      current.remove(optionId);
    } else if (ModifierGroupRules.isSingleChoice(group)) {
      current
        ..clear()
        ..add(optionId);
    } else if (current.length < group.maximumSelections) {
      current.add(optionId);
    } else {
      return this;
    }

    final Map<String, Set<String>> next = <String, Set<String>>{..._byGroup};
    if (current.isEmpty) {
      next.remove(group.modifierGroupId);
    } else {
      next[group.modifierGroupId] = current;
    }
    return ModifierSelection._(next);
  }

  /// The state of each group in [groups], in the order given.
  List<ModifierGroupState> statesFor(List<MenuModifierGroup> groups) =>
      <ModifierGroupState>[
        for (final MenuModifierGroup group in groups) stateFor(group),
      ];

  ModifierGroupState stateFor(MenuModifierGroup group) {
    final Set<String> selected = <String>{...?_byGroup[group.modifierGroupId]};

    ModifierSelectionProblem? problem;
    if (!ModifierGroupRules.isCoherent(group)) {
      problem = ModifierSelectionProblem.groupIsIncoherent;
    } else if (selected.length < group.minimumSelections) {
      problem = ModifierSelectionProblem.tooFewSelected;
    } else if (selected.length > group.maximumSelections) {
      problem = ModifierSelectionProblem.tooManySelected;
    }

    return ModifierGroupState(
      group: group,
      selectedOptionIds: Set<String>.unmodifiable(selected),
      problem: problem,
    );
  }

  /// Whether every group in [groups] is satisfied.
  bool isComplete(List<MenuModifierGroup> groups) =>
      statesFor(groups).every((ModifierGroupState state) => state.isSatisfied);

  /// The first group that is not satisfied, or null.
  ///
  /// One at a time: a screen that lists every outstanding requirement at once
  /// reads as a form that rejected you, which is not what a menu should feel
  /// like.
  ModifierGroupState? firstUnsatisfied(List<MenuModifierGroup> groups) {
    for (final ModifierGroupState state in statesFor(groups)) {
      if (!state.isSatisfied) return state;
    }
    return null;
  }
}

/// A product, a chosen variant, and the modifier answers — the whole of what a
/// product detail screen holds.
final class ProductConfiguration {
  const ProductConfiguration({
    required this.product,
    required this.variant,
    required this.groups,
    required this.selection,
  });

  final MenuProduct product;

  /// Null when the product has no variants at all, which the server does not
  /// currently emit.
  final MenuVariant? variant;

  final List<MenuModifierGroup> groups;
  final ModifierSelection selection;

  /// Whether this configuration could be added to a cart.
  ///
  /// The variant's `orderable` is the server's word and is the first thing
  /// checked. Everything after it is the customer's own answers being complete.
  bool get isOrderable {
    final MenuVariant? chosen = variant;
    if (chosen == null || !chosen.orderable) return false;
    return selection.isComplete(groups);
  }

  ProductConfiguration withVariant(MenuVariant next) => ProductConfiguration(
    product: product,
    variant: next,
    groups: groups,
    // Modifier answers survive a variant change. The published menu links
    // groups to neither product nor variant, so there is nothing that says the
    // answers stopped applying — and clearing them would throw away work the
    // customer did for no stated reason.
    selection: selection,
  );

  ProductConfiguration withToggled(MenuModifierGroup group, String optionId) =>
      ProductConfiguration(
        product: product,
        variant: variant,
        groups: groups,
        selection: selection.toggle(group, optionId),
      );
}
