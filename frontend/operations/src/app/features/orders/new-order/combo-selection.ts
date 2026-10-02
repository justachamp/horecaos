import { MenuComboComponent, MenuComboGroup } from './new-order-api';
import { BasketComboPick } from './new-order-total';

/**
 * What the operator has picked so far in a combo: component id to how many times it was picked.
 * A component absent from the map was not picked.
 *
 * The rules below are the ones `CompositePricing` and `CartService.requireCompositeSelection`
 * enforce on the server (ADR 0136) — each group takes between its minimum and maximum picks, and
 * one component may be picked more than once only where the group says so — applied here first so
 * a request this screen produces is never one the platform refuses for a rule it already knew.
 */
export type ComboQuantities = ReadonlyMap<string, number>;

export function selectedInGroup(group: MenuComboGroup, picked: ComboQuantities): number {
  return group.components.reduce(
    (sum, component) => sum + (picked.get(component.componentId) ?? 0),
    0,
  );
}

/** A group that takes one pick is a radio choice; anything wider is a checkbox or, where repeats are allowed, a stepper. */
export function isSingleChoice(group: MenuComboGroup): boolean {
  return group.maximumSelections === 1;
}

/** Whether a component carries a quantity stepper: the group repeats components and has room for more than one. */
export function isStepper(group: MenuComboGroup): boolean {
  return group.allowSameComponentMultipleTimes && group.maximumSelections > 1;
}

/** The most this component can be picked right now: what is left in the group, plus what it already holds. */
export function ceilingOf(
  group: MenuComboGroup,
  component: MenuComboComponent,
  picked: ComboQuantities,
): number {
  const held = picked.get(component.componentId) ?? 0;
  return Math.max(0, group.maximumSelections - selectedInGroup(group, picked) + held);
}

/** A tap on a component: a radio replaces its group's pick, a checkbox toggles it where the group still has room. */
export function toggleComponent(
  picked: ComboQuantities,
  group: MenuComboGroup,
  component: MenuComboComponent,
): ComboQuantities {
  if (!component.orderable) {
    return picked;
  }
  const next = new Map(picked);
  const held = picked.get(component.componentId) ?? 0;
  if (isSingleChoice(group)) {
    // A one-pick group with a minimum of zero lets the same tap take the pick back.
    for (const sibling of group.components) {
      next.delete(sibling.componentId);
    }
    if (held === 0 || group.minimumSelections > 0) {
      next.set(component.componentId, 1);
    }
    return next;
  }
  if (held > 0) {
    next.delete(component.componentId);
  } else if (selectedInGroup(group, picked) < group.maximumSelections) {
    next.set(component.componentId, 1);
  }
  return next;
}

/** A stepper's value, held to the group's ceiling. */
export function setComponentQuantity(
  picked: ComboQuantities,
  group: MenuComboGroup,
  component: MenuComboComponent,
  quantity: number,
): ComboQuantities {
  if (!component.orderable) {
    return picked;
  }
  const next = new Map(picked);
  const bounded = Math.min(Math.max(0, quantity), ceilingOf(group, component, picked));
  if (bounded === 0) {
    next.delete(component.componentId);
  } else {
    next.set(component.componentId, bounded);
  }
  return next;
}

/** The groups still short of their minimum — what the dialog's own error line names. */
export function unmetGroups(
  groups: readonly MenuComboGroup[],
  picked: ComboQuantities,
): readonly MenuComboGroup[] {
  return groups.filter((group) => selectedInGroup(group, picked) < group.minimumSelections);
}

/**
 * Whether the orderable components could fill the group's minimum at all. A group that cannot be
 * filled (every component stopped) cannot be satisfied from this screen, only by staff choosing
 * another combo.
 */
export function canBeSatisfied(group: MenuComboGroup): boolean {
  const orderable = group.components.filter((component) => component.orderable);
  if (group.minimumSelections <= 0) {
    return true;
  }
  return group.allowSameComponentMultipleTimes
    ? orderable.length > 0
    : orderable.length >= group.minimumSelections;
}

/** The picks as basket entries, in the groups' own order. */
export function picksOf(
  groups: readonly MenuComboGroup[],
  picked: ComboQuantities,
): readonly BasketComboPick[] {
  const picks: BasketComboPick[] = [];
  for (const group of groups) {
    for (const component of group.components) {
      const quantity = picked.get(component.componentId) ?? 0;
      if (quantity > 0) {
        picks.push({
          componentId: component.componentId,
          name: component.variantName
            ? `${component.name} ${component.variantName}`
            : component.name,
          pickQuantity: quantity,
          unitQuantity: component.defaultQuantity,
          amountMinor: component.amountMinor,
        });
      }
    }
  }
  return picks;
}

/**
 * Resolves the picks a repeat (or a stored selection) names into basket entries, or null when any
 * of them is no longer offered by these groups — a combo that has moved on cannot be rebuilt from a
 * stale pick, and guessing a replacement would sell something the customer did not choose.
 */
export function resolvePicks(
  groups: readonly MenuComboGroup[],
  wanted: readonly { readonly componentId: string; readonly quantity: number }[],
): readonly BasketComboPick[] | null {
  const picked = new Map<string, number>();
  for (const pick of wanted) {
    const known = groups.some((group) =>
      group.components.some((component) => component.componentId === pick.componentId),
    );
    if (!known) {
      return null;
    }
    picked.set(pick.componentId, (picked.get(pick.componentId) ?? 0) + pick.quantity);
  }
  return picksOf(groups, picked);
}
