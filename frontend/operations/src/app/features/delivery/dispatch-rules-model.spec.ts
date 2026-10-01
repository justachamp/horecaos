import { describe, expect, it } from 'vitest';

import { DispatchRule, DispatchRulesDocument } from './dispatch-rules-api';
import {
  RULE_ID_PATTERN,
  builtInAction,
  canonical,
  documentOf,
  emptyConditions,
  fromInputEnd,
  moved,
  newRule,
  orderRules,
  parseOptionalInt,
  rangeOf,
  removeRule,
  replaceRule,
  sameDocument,
  setEnabled,
  toInputTime,
  toggled,
  usesFleet,
  usesPartners,
} from './dispatch-rules-model';

function rule(id: string, patch: Partial<DispatchRule> = {}): DispatchRule {
  return {
    id,
    name: id,
    enabled: true,
    when: emptyConditions(),
    then: builtInAction(),
    ...patch,
  };
}

function document(...rules: DispatchRule[]): DispatchRulesDocument {
  return { rules, default: builtInAction() };
}

describe('the built-in default (ADR 0142)', () => {
  it('is today’s behaviour: the fleet first, partners in binding order, the cheapest price, the lead start', () => {
    const action = builtInAction();

    expect(action.mode).toBe('FLEET_FIRST');
    expect(action.partners).toEqual({ order: [], exclude: [], selection: 'CHEAPEST' });
    expect(action.dispatchAt).toEqual({ basis: 'LEAD', offsetSeconds: 0 });
    expect(action.grouping).toBeNull();
  });

  it('knows which modes ask partners and which ask the fleet', () => {
    expect(usesPartners('PARTNER_FIRST')).toBe(true);
    expect(usesPartners('FLEET_ONLY')).toBe(false);
    expect(usesPartners('MANUAL')).toBe(false);
    expect(usesFleet('PARTNER_FIRST')).toBe(true);
    expect(usesFleet('PARTNER_ONLY')).toBe(false);
  });
});

describe('newRule', () => {
  it('takes the first rule-N not already used, so a deleted rule’s id is not silently reused as a duplicate', () => {
    expect(newRule([], 'New').id).toBe('rule-1');
    expect(newRule(['rule-1', 'rule-2'], 'New').id).toBe('rule-3');
    // One rule exists and it is rule-2: the next is rule-2 only if that id is free, and it is not.
    expect(newRule(['rule-2'], 'New').id).toBe('rule-3');
  });

  it('starts matching every order and behaving as the default does', () => {
    const created = newRule([], 'New rule');

    expect(created.when).toEqual(emptyConditions());
    expect(created.then).toEqual(builtInAction());
    expect(created.enabled).toBe(true);
    expect(created.name).toBe('New rule');
    expect(RULE_ID_PATTERN.test(created.id)).toBe(true);
  });

  it('never produces an id the server would refuse', () => {
    const ids: string[] = [];
    for (let i = 0; i < 120; i++) {
      const created = newRule(ids, 'n');
      expect(RULE_ID_PATTERN.test(created.id)).toBe(true);
      expect(ids).not.toContain(created.id);
      ids.push(created.id);
    }
  });
});

describe('rule id pattern', () => {
  it('accepts lower case, digits and dashes, and refuses the rest', () => {
    expect(RULE_ID_PATTERN.test('far-zone-yandex-first')).toBe(true);
    expect(RULE_ID_PATTERN.test('0-first')).toBe(true);
    expect(RULE_ID_PATTERN.test('Far Zone')).toBe(false);
    expect(RULE_ID_PATTERN.test('-leading')).toBe(false);
    expect(RULE_ID_PATTERN.test('')).toBe(false);
    expect(RULE_ID_PATTERN.test('a'.repeat(63))).toBe(true);
    expect(RULE_ID_PATTERN.test('a'.repeat(64))).toBe(false);
  });
});

describe('editing a draft', () => {
  it('replaces one rule and leaves the rest and their order alone', () => {
    const edited = replaceRule(
      document(rule('a'), rule('b'), rule('c')),
      'b',
      rule('b', { name: 'renamed' }),
    );

    expect(edited.rules.map((r) => r.id)).toEqual(['a', 'b', 'c']);
    expect(edited.rules[1].name).toBe('renamed');
  });

  it('removes a rule', () => {
    expect(removeRule(document(rule('a'), rule('b')), 'a').rules.map((r) => r.id)).toEqual(['b']);
  });

  it('reorders to the exact order q-rule-list emits, and keeps a rule it was not told about', () => {
    const doc = document(rule('a'), rule('b'), rule('c'));

    expect(orderRules(doc, ['c', 'a', 'b']).rules.map((r) => r.id)).toEqual(['c', 'a', 'b']);
    expect(orderRules(doc, ['b']).rules.map((r) => r.id)).toEqual(['b', 'a', 'c']);
    expect(orderRules(doc, ['zzz', 'b', 'a']).rules.map((r) => r.id)).toEqual(['b', 'a', 'c']);
  });

  it('switches one rule on or off without touching another', () => {
    const next = setEnabled(document(rule('a'), rule('b')), 'b', false);

    expect(next.rules.map((r) => r.enabled)).toEqual([true, false]);
  });
});

describe('sameDocument', () => {
  it('is true for the same content in a different key order', () => {
    const reordered = {
      default: builtInAction(),
      rules: [
        { then: builtInAction(), when: emptyConditions(), enabled: true, name: 'a', id: 'a' },
      ],
    } as DispatchRulesDocument;

    expect(sameDocument(document(rule('a')), reordered)).toBe(true);
  });

  it('ignores everything on a view that is not the document', () => {
    const view = {
      ...document(rule('a')),
      versionAtScope: 4,
      isBuiltIn: false,
    } as DispatchRulesDocument;

    expect(sameDocument(view, document(rule('a')))).toBe(true);
    expect(documentOf(view)).toEqual(document(rule('a')));
  });

  it('is false when a rule’s order, flag or content changed, and true again when it is changed back', () => {
    const base = document(rule('a'), rule('b'));

    expect(sameDocument(base, document(rule('b'), rule('a')))).toBe(false);
    expect(sameDocument(base, setEnabled(base, 'a', false))).toBe(false);
    const edited = replaceRule(base, 'a', rule('a', { name: 'x' }));
    expect(sameDocument(base, edited)).toBe(false);
    expect(sameDocument(base, replaceRule(edited, 'a', rule('a')))).toBe(true);
  });

  it('canonicalises nested keys', () => {
    expect(canonical({ b: 1, a: { d: 1, c: 2 } })).toBe('{"a":{"c":2,"d":1},"b":1}');
  });
});

describe('time windows', () => {
  it('shows the stored end-of-day as midnight and stores a window ending at midnight as 24:00', () => {
    expect(toInputTime('24:00')).toBe('00:00');
    expect(toInputTime('23:00')).toBe('23:00');
    expect(fromInputEnd('00:00', '18:00')).toBe('24:00');
    expect(fromInputEnd('02:00', '22:00')).toBe('02:00');
    expect(fromInputEnd('00:00', '00:00')).toBe('00:00');
  });
});

describe('numeric boxes', () => {
  it('turns two empty boxes into no condition at all, and keeps an open end open', () => {
    expect(rangeOf(null, null)).toBeNull();
    expect(rangeOf(5, null)).toEqual({ min: 5, max: null });
    expect(rangeOf(null, 45)).toEqual({ min: null, max: 45 });
    expect(rangeOf(0, 45)).toEqual({ min: 0, max: 45 });
  });

  it('reads a box as a whole number or as empty, never as zero', () => {
    expect(parseOptionalInt('')).toBeNull();
    expect(parseOptionalInt('  ')).toBeNull();
    expect(parseOptionalInt('12')).toBe(12);
    expect(parseOptionalInt('0')).toBe(0);
    expect(parseOptionalInt('1.5')).toBeNull();
    expect(parseOptionalInt('abc')).toBeNull();
  });
});

describe('list helpers', () => {
  it('toggles membership', () => {
    expect(toggled(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggled(['a', 'b'], 'a')).toEqual(['b']);
  });

  it('moves an entry by one and refuses to move off either end', () => {
    expect(moved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    expect(moved(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c']);
    expect(moved(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c']);
  });
});
