import type { MenuItem, MenuItemModifierGroup, MenuItemModifierOption } from '../types/home.types';
import { shortHash } from './combo-selection';

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
 * What the group asks of the guest, as a translation key and its parameters, or
 * null when it asks nothing worth saying (optional, no ceiling). Read from the
 * effective bounds, so a group authored `required=false` with a minimum says what
 * a required one would.
 */
export function selectionRule(
  group: MenuItemModifierGroup,
): { key: string; params: Record<string, number> } | null {
  const min = minimumSelections(group);
  const max = maximumSelections(group);
  if (Number.isFinite(max)) {
    if (min === max) {
      return { key: 'dineIn.pickerExactly', params: { count: min } };
    }
    return min > 0
      ? { key: 'dineIn.pickerBetween', params: { min, max } }
      : { key: 'dineIn.pickerUpTo', params: { count: max } };
  }
  return min > 0 ? { key: 'dineIn.pickerAtLeast', params: { count: min } } : null;
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

// ------------------------------------------------------------------ ADR 0136: variants and the second level

/**
 * The modifier groups a portion is offered with: its own complete list when the menu published one
 * (the product's groups and the ones the portion carries itself, each with the rules this portion
 * holds the customer to), otherwise the product's. The cart enforces exactly this list for the
 * variant chosen, so asking anything else would send a line the platform refuses, or hold back one
 * it would take.
 */
export function groupsForVariant(
  item: Pick<MenuItem, 'modifierGroups' | 'variants'>,
  variantId: string | null,
): readonly MenuItemModifierGroup[] {
  const variant = variantId === null ? undefined : item.variants.find((v) => v.id === variantId);
  return variant?.modifierGroups ?? item.modifierGroups;
}

/**
 * What a guest has chosen under first-level options that open choices of their own: parent option
 * id to (nested group id to the option ids picked in it). One level and no more -- the platform
 * refuses a third.
 */
export type NestedChoices = Readonly<Record<string, ModifierChoices>>;

/** One second-level selection as the platform's cart line carries it. */
export interface NestedModifierWire {
  readonly parentOptionId: string;
  readonly optionId: string;
}

/** A chosen first-level option that opens choices, and those choices. */
export interface OpenedGroups {
  readonly parent: MenuItemModifierOption;
  readonly groups: readonly MenuItemModifierGroup[];
}

/** The chosen options that open choices of their own, in the dish's group order. */
export function openedGroups(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
): readonly OpenedGroups[] {
  const opened: OpenedGroups[] = [];
  for (const group of groups) {
    const chosen = choices[group.id] ?? [];
    for (const option of group.options) {
      if (chosen.includes(option.id) && (option.nestedGroups ?? []).length > 0) {
        opened.push({ parent: option, groups: option.nestedGroups ?? [] });
      }
    }
  }
  return opened;
}

/**
 * What is left of the choices once the dish is offered `groups` instead: choices in a group that is
 * no longer offered, or of an option it no longer holds, are dropped so they are never sent.
 */
export function keepOffered(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
): ModifierChoices {
  const kept: Record<string, readonly string[]> = {};
  for (const group of groups) {
    const chosen = (choices[group.id] ?? []).filter((id) =>
      group.options.some((option) => option.id === id),
    );
    if (chosen.length > 0) {
      kept[group.id] = chosen;
    }
  }
  return kept;
}

/** A tap on an option of a group opened under `parentOptionId`; the same rules as {@link toggleOption}. */
export function toggleNested(
  nested: NestedChoices,
  parentOptionId: string,
  group: MenuItemModifierGroup,
  optionId: string,
): NestedChoices {
  const under = nested[parentOptionId] ?? {};
  const next = toggleOption(under, group, optionId);
  return next === under ? nested : { ...nested, [parentOptionId]: next };
}

/** Forgets the answers given under an option that is no longer chosen, so nothing stale is sent. */
export function pruneNested(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
  nested: NestedChoices,
): NestedChoices {
  const open = new Set(openedGroups(groups, choices).map((entry) => entry.parent.id));
  const kept = Object.entries(nested).filter(([parentId]) => open.has(parentId));
  return kept.length === Object.keys(nested).length ? nested : Object.fromEntries(kept);
}

/** A second-level group whose choice count is outside its own bounds. */
export interface UnsatisfiedNested {
  readonly parent: MenuItemModifierOption;
  readonly group: MenuItemModifierGroup;
}

/**
 * The nested groups still to be answered: for each chosen option that opens choices, the groups
 * whose picks fall short of their minimum or past their maximum. An option nobody chose asks nothing.
 */
export function unsatisfiedNested(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
  nested: NestedChoices,
): readonly UnsatisfiedNested[] {
  const missing: UnsatisfiedNested[] = [];
  for (const { parent, groups: opened } of openedGroups(groups, choices)) {
    const under = nested[parent.id] ?? {};
    for (const group of opened) {
      const count = (under[group.id] ?? []).length;
      if (count < minimumSelections(group) || count > maximumSelections(group)) {
        missing.push({ parent, group });
      }
    }
  }
  return missing;
}

/**
 * The same check for a selection as a cart line carries it: flat first-level option ids and
 * parent/option pairs. A pair whose group the parent does not open is not counted anywhere (the
 * platform refuses it as `MODIFIER_NESTED_OPTION_NOT_OFFERED`).
 */
export function unsatisfiedNestedFor(
  groups: readonly MenuItemModifierGroup[],
  optionIds: readonly string[],
  nested: readonly NestedModifierWire[],
): readonly UnsatisfiedNested[] {
  const choices: Record<string, string[]> = {};
  for (const group of groups) {
    choices[group.id] = optionIds.filter((id) => group.options.some((option) => option.id === id));
  }
  const answers: Record<string, Record<string, string[]>> = {};
  for (const { parent, groups: opened } of openedGroups(groups, choices)) {
    const under: Record<string, string[]> = {};
    for (const group of opened) {
      under[group.id] = nested
        .filter(
          (pair) =>
            pair.parentOptionId === parent.id &&
            group.options.some((option) => option.id === pair.optionId),
        )
        .map((pair) => pair.optionId);
    }
    answers[parent.id] = under;
  }
  return unsatisfiedNested(groups, choices, answers);
}

/**
 * The second-level answers as the platform's line carries them: each under its parent, parents in
 * the dish's order and answers in each group's own order. Anything under an option that is no longer
 * chosen is left out.
 */
export function nestedOnTheWire(
  groups: readonly MenuItemModifierGroup[],
  choices: ModifierChoices,
  nested: NestedChoices,
): readonly NestedModifierWire[] {
  const wire: NestedModifierWire[] = [];
  for (const { parent, groups: opened } of openedGroups(groups, choices)) {
    const under = nested[parent.id] ?? {};
    for (const group of opened) {
      for (const optionId of under[group.id] ?? []) {
        wire.push({ parentOptionId: parent.id, optionId });
      }
    }
  }
  return wire;
}

/**
 * A short, stable key for a line that carries second-level answers.
 *
 * A cart line is addressed by a key the platform stores in sixty-four characters, and a variant's id
 * alone is thirty-six: the first-level options and the answers under them cannot be spelled out in
 * it, so they are hashed, as a combo's picks are. The same selection in any tap order gives the same
 * key, so choosing it again lands on the same line. What the line holds is read back from the cart's
 * own echo of it (`modifierOptionIds`, `nestedModifiers`), never decoded from the key.
 */
export function nestedKeyHash(
  modifierOptionIds: readonly string[],
  nested: readonly NestedModifierWire[],
): string {
  const first = [...modifierOptionIds].sort().join('.');
  const second = nested
    .map((pair) => `${pair.parentOptionId}>${pair.optionId}`)
    .sort()
    .join('.');
  return shortHash(`${first}|n|${second}`);
}
