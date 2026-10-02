import { MessageKey } from '../../core/i18n/messages.en';
import {
  DispatchAction,
  DispatchConditions,
  DispatchRule,
  DispatchRulesDocument,
  IntRange,
  SourcingMode,
  TimeWindow,
} from './dispatch-rules-api';

/**
 * The dispatch rules editor's pure half (ADR 0142): the shapes a blank rule starts from, the
 * immutable edits the screen makes to a draft, and the comparison that says whether the draft is
 * any different from what is published. No Angular, no network -- so the rules that are easy to
 * get subtly wrong (what counts as "unchanged", what a window that ends at midnight is stored as)
 * are tested without a component.
 *
 * The server owns validation (`DispatchRulesValidator`); this file mirrors only what a screen needs
 * to avoid sending a body the server can only call malformed.
 */

/** The sentence for each way of sending an order (ADR 0142 Decision 9). */
export const MODE_LABELS: Readonly<Record<SourcingMode, MessageKey>> = {
  FLEET_FIRST: 'delivery.rules.mode.FLEET_FIRST',
  PARTNER_FIRST: 'delivery.rules.mode.PARTNER_FIRST',
  FLEET_ONLY: 'delivery.rules.mode.FLEET_ONLY',
  PARTNER_ONLY: 'delivery.rules.mode.PARTNER_ONLY',
  MANUAL: 'delivery.rules.mode.MANUAL',
};

/** A rule id is lower case letters, digits and dashes, at most 63 characters. Mirrors `DispatchRulesValidator.RULE_ID`. */
export const RULE_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/;

/** The longest rule list a document may hold. Mirrors `DispatchRulesDocument.MAX_RULES`. */
export const MAX_RULES = 100;

/** Modes that ask a delivery partner at all, so the partner block means something. */
export const PARTNER_MODES: readonly SourcingMode[] = [
  'FLEET_FIRST',
  'PARTNER_FIRST',
  'PARTNER_ONLY',
];

/** Modes that ask the in-house fleet, so grouping (a bias on its ranking) means something. */
export const FLEET_MODES: readonly SourcingMode[] = ['FLEET_FIRST', 'PARTNER_FIRST', 'FLEET_ONLY'];

export function usesPartners(mode: SourcingMode): boolean {
  return PARTNER_MODES.includes(mode);
}

export function usesFleet(mode: SourcingMode): boolean {
  return FLEET_MODES.includes(mode);
}

export function emptyConditions(): DispatchConditions {
  return {
    sources: [],
    channelIds: [],
    zoneIds: [],
    locationIds: [],
    prepMinutes: null,
    distanceMeters: null,
    localTime: null,
    prepaid: null,
  };
}

/** Today's behaviour, exactly: the fleet first, partners in binding order and the cheapest price, sourcing at the lead. */
export function builtInAction(): DispatchAction {
  return {
    mode: 'FLEET_FIRST',
    partners: { order: [], exclude: [], selection: 'CHEAPEST' },
    dispatchAt: { basis: 'LEAD', offsetSeconds: 0 },
    grouping: null,
  };
}

/**
 * A new rule, matching every order and doing what the default does until the operator edits it.
 * Its id is the first `rule-N` not already taken, because a rule id is what a plan records and has
 * to be unique inside the document.
 */
export function newRule(existingIds: readonly string[], name: string): DispatchRule {
  let sequence = existingIds.length + 1;
  while (existingIds.includes(`rule-${sequence}`)) {
    sequence++;
  }
  return {
    id: `rule-${sequence}`,
    name,
    enabled: true,
    when: emptyConditions(),
    then: builtInAction(),
  };
}

/** The part of a view that is the document: what is sent back on publish. */
export function documentOf(source: DispatchRulesDocument): DispatchRulesDocument {
  return { rules: source.rules, default: source.default };
}

export function replaceRule(
  document: DispatchRulesDocument,
  id: string,
  replacement: DispatchRule,
): DispatchRulesDocument {
  return {
    ...document,
    rules: document.rules.map((rule) => (rule.id === id ? replacement : rule)),
  };
}

export function removeRule(document: DispatchRulesDocument, id: string): DispatchRulesDocument {
  return { ...document, rules: document.rules.filter((rule) => rule.id !== id) };
}

/** Reorders to exactly `ids`, which `q-rule-list` emits as the full new order. A rule not named keeps its place at the end. */
export function orderRules(
  document: DispatchRulesDocument,
  ids: readonly string[],
): DispatchRulesDocument {
  const byId = new Map(document.rules.map((rule) => [rule.id, rule]));
  const ordered = ids.flatMap((id) => {
    const rule = byId.get(id);
    return rule ? [rule] : [];
  });
  const named = new Set(ids);
  return {
    ...document,
    rules: [...ordered, ...document.rules.filter((rule) => !named.has(rule.id))],
  };
}

export function setEnabled(
  document: DispatchRulesDocument,
  id: string,
  enabled: boolean,
): DispatchRulesDocument {
  return {
    ...document,
    rules: document.rules.map((rule) => (rule.id === id ? { ...rule, enabled } : rule)),
  };
}

/** Key-sorted JSON, so two documents with the same content compare equal whatever order their keys were written in. */
export function canonical(value: unknown): string {
  return JSON.stringify(value, (_key, nested: unknown) => {
    if (nested !== null && typeof nested === 'object' && !Array.isArray(nested)) {
      return Object.fromEntries(
        Object.entries(nested as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)),
      );
    }
    return nested;
  });
}

/** Whether two documents say the same thing. A draft that was edited and edited back is not dirty. */
export function sameDocument(a: DispatchRulesDocument, b: DispatchRulesDocument): boolean {
  return canonical(documentOf(a)) === canonical(documentOf(b));
}

// ----------------------------------------------------------------- unit helpers

export function secondsToMinutes(seconds: number): number {
  return Math.round(seconds / 60);
}

export function minutesToSeconds(minutes: number): number {
  return Math.round(minutes * 60);
}

// ----------------------------------------------------------------- time windows

/**
 * A native `<input type="time">` cannot show `24:00`, the stored end-of-day. It shows `00:00`, and
 * the pair is stored back as `24:00` when a window that does not start at midnight ends there.
 */
export function toInputTime(stored: string): string {
  return stored === '24:00' ? '00:00' : stored;
}

export function fromInputEnd(entered: string, from: string): string {
  return entered === '00:00' && from !== '00:00' ? '24:00' : entered;
}

export function defaultWindow(): TimeWindow {
  return { days: [], from: '18:00', to: '23:00' };
}

// ----------------------------------------------------------------------- ranges

/** `null` when both ends are empty, because an empty range is "no condition", not a range of nothing. */
export function rangeOf(min: number | null, max: number | null): IntRange | null {
  return min === null && max === null ? null : { min, max };
}

/** A box's text as a number, or null for an empty or non-numeric box. */
export function parseOptionalInt(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return null;
  }
  const value = Number(trimmed);
  return Number.isInteger(value) ? value : null;
}

/** Toggles a value's membership in a list, keeping the order everything else was in. */
export function toggled<T>(list: readonly T[], value: T): readonly T[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

/** Moves one entry of a list by `delta` places, clamped, leaving the list alone when it cannot move. */
export function moved<T>(list: readonly T[], index: number, delta: number): readonly T[] {
  const target = index + delta;
  if (index < 0 || index >= list.length || target < 0 || target >= list.length) {
    return list;
  }
  const next = [...list];
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item);
  return next;
}
