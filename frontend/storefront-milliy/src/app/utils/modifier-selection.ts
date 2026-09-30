import type { MenuItemModifierGroup } from '../types/home.types';

/**
 * What a guest has chosen so far in each modifier group: group id to the option
 * ids picked within it.
 */
export type ModifierChoices = Readonly<Record<string, readonly string[]>>;

/**
 * The rules of a modifier group, in one place -- the product page and the table's
 * picker both apply them, and the platform enforces the same three
 * (`CartService.requireSelectionRules`: a required group needs at least one
 * selection whatever its minimum says, and no group takes more than its
 * maximum). A client that guessed them differently would either send a line the
 * platform refuses or hold back one it would have taken.
 *
 * A `maximumSelections` of zero or less is read as "the menu sets no ceiling",
 * as `frontend/storefront` reads it.
 */

/** The fewest options the group needs chosen. A required group needs one, whatever its minimum says. */
export function minimumSelections(group: MenuItemModifierGroup): number {
  return group.required ? Math.max(1, group.minimumSelections) : group.minimumSelections;
}

/** The most options the group takes; infinite when the menu sets no ceiling. */
export function maximumSelections(group: MenuItemModifierGroup): number {
  return group.maximumSelections > 0 ? group.maximumSelections : Number.POSITIVE_INFINITY;
}

/** Whether the group must be chosen from before the dish can be ordered. */
export function isMandatory(group: MenuItemModifierGroup): boolean {
  return minimumSelections(group) > 0;
}

/**
 * Whether a guest could satisfy the group at all: it offers at least as many
 * options as it demands. A mandatory group that does not (a menu published with
 * an empty group) can never be ordered from a screen, only helped along by staff.
 */
export function canBeSatisfied(group: MenuItemModifierGroup): boolean {
  return group.options.length >= minimumSelections(group);
}

/**
 * The choices after the guest taps an option.
 *
 * A tap on a chosen option takes it back. A one-choice group replaces rather than
 * refuses -- a second tap plainly means "not that one, this one". A group already
 * at its ceiling refuses a further option and returns the choices it was given.
 */
export function toggleOption(
  choices: ModifierChoices,
  group: MenuItemModifierGroup,
  optionId: string,
): ModifierChoices {
  const current = choices[group.id] ?? [];
  let next: readonly string[];
  if (current.includes(optionId)) {
    next = current.filter((id) => id !== optionId);
  } else if (group.maximumSelections === 1) {
    next = [optionId];
  } else if (current.length >= maximumSelections(group)) {
    return choices;
  } else {
    next = [...current, optionId];
  }
  return { ...choices, [group.id]: next };
}

/** The groups whose choice count is outside their own bounds, in the dish's order. */
export function unsatisfiedGroups(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
): readonly MenuItemModifierGroup[] {
  return groups.filter((group) => {
    const count = (choices[group.id] ?? []).length;
    return count < minimumSelections(group) || count > maximumSelections(group);
  });
}

/**
 * The same check for a selection that arrives as a flat list of option ids, as a
 * cart line carries it: each id is counted against the group that offers it. An id
 * no group offers is not counted anywhere (the platform refuses it, as
 * `MODIFIER_NOT_OFFERED`).
 */
export function unsatisfiedGroupsFor(
  groups: readonly MenuItemModifierGroup[],
  optionIds: readonly string[],
): readonly MenuItemModifierGroup[] {
  const choices: Record<string, string[]> = {};
  for (const group of groups) {
    choices[group.id] = optionIds.filter((id) => group.options.some((option) => option.id === id));
  }
  return unsatisfiedGroups(groups, choices);
}

/** The chosen option ids as one list, in the dish's group order and each group's own choice order. */
export function chosenOptionIds(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
): readonly string[] {
  return groups.flatMap((group) => choices[group.id] ?? []);
}
