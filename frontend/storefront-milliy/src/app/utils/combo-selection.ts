import type { MenuItemComboComponent, MenuItemComboGroup } from '../types/home.types';

/**
 * What the customer has picked so far in a combo (ADR 0136): component id to how many times it was
 * picked. A component absent from the record was not picked.
 *
 * The rules here are the ones the platform enforces when a combo line is put in the cart
 * (`CartService.requireCompositeSelection`): each group takes between its minimum and its maximum
 * picks, and one component may be picked more than once only where the group says so. Applied first
 * so the product page never sends a line the platform would refuse for a rule it already knew.
 */
export type ComboPicks = Readonly<Record<string, number>>;

/** One pick as the wire carries it. */
export interface ComboPickWire {
  readonly componentId: string;
  readonly quantity: number;
}

export function selectedInGroup(group: MenuItemComboGroup, picks: ComboPicks): number {
  return group.components.reduce((sum, component) => sum + (picks[component.id] ?? 0), 0);
}

/** A group that takes one pick is a radio choice. */
export function isSingleChoice(group: MenuItemComboGroup): boolean {
  return group.maximumSelections === 1;
}

/** A component carries a quantity stepper where the group repeats components and has room for more than one. */
export function isStepper(group: MenuItemComboGroup): boolean {
  return group.allowSameComponentMultipleTimes && group.maximumSelections > 1;
}

/** The most this component can be picked now: what the group has left, plus what it already holds. */
export function ceilingOf(
  group: MenuItemComboGroup,
  component: MenuItemComboComponent,
  picks: ComboPicks,
): number {
  const held = picks[component.id] ?? 0;
  return Math.max(0, group.maximumSelections - selectedInGroup(group, picks) + held);
}

function without(picks: ComboPicks, componentId: string): Record<string, number> {
  const { [componentId]: _removed, ...rest } = picks;
  return rest;
}

/**
 * A tap on a component. A one-pick group replaces its pick (and, with a minimum of zero, a second tap
 * takes it back); a wider group toggles while it still has room.
 */
export function toggleComponent(
  picks: ComboPicks,
  group: MenuItemComboGroup,
  component: MenuItemComboComponent,
): ComboPicks {
  if (!component.active) {
    return picks;
  }
  const held = picks[component.id] ?? 0;
  if (isSingleChoice(group)) {
    let next: Record<string, number> = { ...picks };
    for (const sibling of group.components) {
      next = without(next, sibling.id);
    }
    return held === 0 || group.minimumSelections > 0 ? { ...next, [component.id]: 1 } : next;
  }
  if (held > 0) {
    return without(picks, component.id);
  }
  return selectedInGroup(group, picks) < group.maximumSelections
    ? { ...picks, [component.id]: 1 }
    : picks;
}

/** A stepper's value, held to the group's ceiling. */
export function setComponentQuantity(
  picks: ComboPicks,
  group: MenuItemComboGroup,
  component: MenuItemComboComponent,
  quantity: number,
): ComboPicks {
  if (!component.active) {
    return picks;
  }
  const bounded = Math.min(Math.max(0, quantity), ceilingOf(group, component, picks));
  return bounded === 0 ? without(picks, component.id) : { ...picks, [component.id]: bounded };
}

/** The groups still short of their minimum. */
export function unmetGroups(
  groups: readonly MenuItemComboGroup[],
  picks: ComboPicks,
): readonly MenuItemComboGroup[] {
  return groups.filter((group) => selectedInGroup(group, picks) < group.minimumSelections);
}

/** Whether every group is within its range: the whole combo can be added. */
export function comboValid(groups: readonly MenuItemComboGroup[], picks: ComboPicks): boolean {
  return groups.every((group) => {
    const count = selectedInGroup(group, picks);
    return count >= group.minimumSelections && count <= group.maximumSelections;
  });
}

/** Whether the orderable components could fill the group's minimum at all. */
export function canBeSatisfied(group: MenuItemComboGroup): boolean {
  if (group.minimumSelections <= 0) {
    return true;
  }
  const orderable = group.components.filter((component) => component.active).length;
  return group.allowSameComponentMultipleTimes
    ? orderable > 0
    : orderable >= group.minimumSelections;
}

/** The picks as the wire carries them, sorted by component so the same choice is always the same list. */
export function picksOnTheWire(
  groups: readonly MenuItemComboGroup[],
  picks: ComboPicks,
): readonly ComboPickWire[] {
  const wire: ComboPickWire[] = [];
  for (const group of groups) {
    for (const component of group.components) {
      const quantity = picks[component.id] ?? 0;
      if (quantity > 0) {
        wire.push({ componentId: component.id, quantity });
      }
    }
  }
  return wire.sort((a, b) => a.componentId.localeCompare(b.componentId));
}

/** Two pick lists are the same combo whatever order they were made in. */
export function samePicks(a: readonly ComboPickWire[], b: readonly ComboPickWire[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  const sorted = (picks: readonly ComboPickWire[]) =>
    [...picks].sort((x, y) => x.componentId.localeCompare(y.componentId));
  const left = sorted(a);
  const right = sorted(b);
  return left.every(
    (pick, index) =>
      pick.componentId === right[index].componentId && pick.quantity === right[index].quantity,
  );
}

/**
 * What one combo costs: every pick's component price per unit, times units per pick, times how many
 * times it was picked. Null when a picked component has no price -- a combo has no price of its own
 * to fall back on, so a missing component price is a missing price and never a smaller total.
 */
export function comboUnitAmountMinor(
  groups: readonly MenuItemComboGroup[],
  picks: ComboPicks,
): number | null {
  let total = 0;
  for (const group of groups) {
    for (const component of group.components) {
      const quantity = picks[component.id] ?? 0;
      if (quantity === 0) {
        continue;
      }
      if (component.amountMinor === null) {
        return null;
      }
      total += component.amountMinor * component.defaultQuantity * quantity;
    }
  }
  return total;
}

/**
 * The least a combo can cost: each choice's cheapest way of reaching its minimum. Null when that
 * cannot be said (a needed component is unpriced, or a group cannot reach its minimum), which the
 * menu shows as no price rather than as the sum of the ones it knows.
 */
export function comboFromAmountMinor(groups: readonly MenuItemComboGroup[]): number | null {
  let total = 0;
  for (const group of groups) {
    if (group.minimumSelections <= 0) {
      continue;
    }
    const costs = group.components
      .filter((component) => component.active)
      .map((component) =>
        component.amountMinor === null ? null : component.amountMinor * component.defaultQuantity,
      );
    if (costs.some((cost) => cost === null)) {
      // An unpriced component may or may not be the cheapest one: nothing honest to say.
      return null;
    }
    const known = (costs as number[]).sort((a, b) => a - b);
    if (known.length === 0) {
      return null;
    }
    if (group.allowSameComponentMultipleTimes) {
      total += known[0] * group.minimumSelections;
    } else if (known.length >= group.minimumSelections) {
      total += known.slice(0, group.minimumSelections).reduce((sum, cost) => sum + cost, 0);
    } else {
      return null;
    }
  }
  return total;
}

/**
 * A short, stable key for one combo selection (cyrb53, 53 bits, as 14 hex digits).
 *
 * A cart line is addressed by a key the platform limits to sixty characters when the line carries
 * combo picks, and a container's id alone is thirty-six: the picks cannot be spelled out in it, so
 * they are hashed. The same choice always gives the same key, so choosing it again lands on the same
 * line; two different choices differ with a probability of one in 2^53.
 */
export function comboKeyHash(
  picks: readonly ComboPickWire[],
  extra: readonly string[] = [],
): string {
  const text =
    [...extra].sort().join('.') +
    '|' +
    [...picks]
      .sort((a, b) => a.componentId.localeCompare(b.componentId))
      .map((pick) => `${pick.componentId}x${pick.quantity}`)
      .join('.');
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    h1 = Math.imul(h1 ^ code, 2654435761);
    h2 = Math.imul(h2 ^ code, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  const value = 4294967296 * (2097151 & h2) + (h1 >>> 0);
  return value.toString(16).padStart(14, '0');
}
