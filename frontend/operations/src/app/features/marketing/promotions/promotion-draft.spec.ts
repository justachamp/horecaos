import { describe, expect, it } from 'vitest';

import { ConditionRow, emptyConditionRow } from '../../../shared/ui/condition-types';
import {
  ActionDraft,
  NO_LOOKUPS,
  PROMOTION_CONDITION_TYPES,
  actionFromRule,
  actionTypesFor,
  andGroup,
  bodyFromDraft,
  buildConditionCatalogue,
  conditionsFromGroups,
  defaultStackingGroup,
  draftFromView,
  draftProblems,
  emptyAction,
  emptyDraft,
  groupsFromConditions,
  isoToLocalInput,
  localInputToIso,
  ruleFromAction,
  scopesFor,
} from './promotion-draft';
import { PromotionRule, PromotionView } from './promotions-api';

const CATALOGUE = buildConditionCatalogue(NO_LOOKUPS);

const ID_A = '0191f2c4-0000-7000-8000-00000000000a';
const ID_B = '0191f2c4-0000-7000-8000-00000000000b';

function rule(type: string, operands: Record<string, unknown>, sequence = 1): PromotionRule {
  return { sequence, type, operands };
}

function row(type: string, patch: Partial<ConditionRow>): ConditionRow {
  return { ...emptyConditionRow(CATALOGUE, type), ...patch };
}

function view(patch: Partial<PromotionView> = {}): PromotionView {
  return {
    promotionId: 'p1',
    version: 3,
    definitionVersion: 2,
    code: 'CLICK5',
    name: '5% off with Click',
    kind: 'DISCOUNT',
    scope: 'ORDER',
    stackingGroup: 'payment',
    exclusive: false,
    priority: 5,
    status: 'DRAFT',
    maximumDiscountMinor: 20_000,
    currency: 'UZS',
    validFrom: null,
    validUntil: null,
    maximumRedemptions: null,
    maximumPerCustomer: 1,
    consumedCount: 0,
    loyaltyAccrual: 'SUPPRESS',
    loyaltyRedemption: 'BLOCK',
    conditions: [rule('PAYMENT_METHOD', { paymentMethodCodes: ['CLICK'] })],
    actions: [rule('ORDER_PERCENTAGE_DISCOUNT', { basisPoints: 500 })],
    validatedAt: null,
    activatedAt: null,
    activatedBy: null,
    approvalId: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    versions: [],
    ...patch,
  };
}

describe('the condition catalogue', () => {
  it('lists every type the console offers exactly once, each with a label key', () => {
    expect(CATALOGUE.map((descriptor) => descriptor.type)).toEqual([...PROMOTION_CONDITION_TYPES]);
    for (const descriptor of CATALOGUE) {
      expect(descriptor.labelKey).toBe(`marketing.promotions.condition.${descriptor.type}`);
    }
  });

  it('gives a subtotal threshold only "at least", and a product list "is one of" and "is not one of"', () => {
    const operators = (type: string) => CATALOGUE.find((d) => d.type === type)?.operators;
    expect(operators('SUBTOTAL_AT_LEAST')).toEqual(['AT_LEAST']);
    expect(operators('QUANTITY_AT_LEAST')).toEqual(['AT_LEAST', 'EQUALS']);
    expect(operators('PRODUCT')).toEqual(['IN', 'NOT_IN']);
    expect(operators('CHANNEL')).toEqual(['IN']);
  });

  it('makes the long lists searchable and the short ones plain chips', () => {
    const searchable = CATALOGUE.filter((d) => d.searchable).map((d) => d.type);
    expect(searchable).toEqual(['PRODUCT', 'VARIANT']);
  });
});

describe('conditions: builder rows to wire and back', () => {
  const cases: readonly {
    name: string;
    row: ConditionRow;
    rule: Omit<PromotionRule, 'sequence'>;
  }[] = [
    {
      name: 'a subtotal threshold',
      row: row('SUBTOTAL_AT_LEAST', { numericLow: '90000' }),
      rule: { type: 'SUBTOTAL_AT_LEAST', operands: { amountMinor: 90_000 } },
    },
    {
      name: 'a quantity at least',
      row: row('QUANTITY_AT_LEAST', { operator: 'AT_LEAST', numericLow: '3' }),
      rule: { type: 'QUANTITY_AT_LEAST', operands: { quantity: 3 } },
    },
    {
      name: 'a quantity of exactly n',
      row: row('QUANTITY_AT_LEAST', { operator: 'EQUALS', numericLow: '2' }),
      rule: { type: 'QUANTITY_AT_LEAST', operands: { quantity: 2, exact: true } },
    },
    {
      name: 'products named',
      row: row('PRODUCT', { operator: 'IN', textValues: `${ID_A}, ${ID_B}` }),
      rule: { type: 'PRODUCT', operands: { productIds: [ID_A, ID_B] } },
    },
    {
      name: 'products excluded',
      row: row('PRODUCT', { operator: 'NOT_IN', textValues: ID_A }),
      rule: { type: 'PRODUCT', operands: { productIds: [ID_A], exclude: true } },
    },
    {
      name: 'categories excluded',
      row: row('CATEGORY', { operator: 'NOT_IN', textValues: ID_B }),
      rule: { type: 'CATEGORY', operands: { categoryIds: [ID_B], exclude: true } },
    },
    {
      name: 'variants named',
      row: row('VARIANT', { textValues: ID_A }),
      rule: { type: 'VARIANT', operands: { variantIds: [ID_A] } },
    },
    {
      name: 'channel codes',
      row: row('CHANNEL', { textValues: 'WEB, TG' }),
      rule: { type: 'CHANNEL', operands: { channels: ['WEB', 'TG'] } },
    },
    {
      name: 'channel types',
      row: row('CHANNEL_TYPE', { textValues: 'IOS, ANDROID' }),
      rule: { type: 'CHANNEL_TYPE', operands: { channelTypes: ['IOS', 'ANDROID'] } },
    },
    {
      name: 'branches',
      row: row('LOCATION', { textValues: ID_A }),
      rule: { type: 'LOCATION', operands: { locationIds: [ID_A] } },
    },
    {
      name: 'fulfilment modes',
      row: row('FULFILLMENT_MODE', { textValues: 'DINE_IN' }),
      rule: { type: 'FULFILLMENT_MODE', operands: { fulfillmentModes: ['DINE_IN'] } },
    },
    {
      name: 'payment methods',
      row: row('PAYMENT_METHOD', { textValues: 'CLICK' }),
      rule: { type: 'PAYMENT_METHOD', operands: { paymentMethodCodes: ['CLICK'] } },
    },
    {
      name: 'delivery zones',
      row: row('DELIVERY_ZONE', { textValues: ID_B }),
      rule: { type: 'DELIVERY_ZONE', operands: { zoneIds: [ID_B] } },
    },
    {
      name: 'customer segments',
      row: row('CUSTOMER_SEGMENT', { textValues: ID_A }),
      rule: { type: 'CUSTOMER_SEGMENT', operands: { segments: [ID_A] } },
    },
    {
      name: 'weekdays, sorted',
      row: row('DAY_OF_WEEK', { dayOfWeekValues: [5, 1, 3] }),
      rule: { type: 'DAY_OF_WEEK', operands: { daysOfWeek: [1, 3, 5] } },
    },
    {
      name: 'a lunch window in minutes of the day',
      row: row('TIME_OF_DAY', { numericLow: '720', numericHigh: '900' }),
      rule: { type: 'TIME_OF_DAY', operands: { fromMinuteOfDay: 720, toMinuteOfDay: 900 } },
    },
    {
      name: 'the first order',
      row: row('FIRST_ORDER', {}),
      rule: { type: 'FIRST_ORDER', operands: {} },
    },
    {
      name: 'the first order through this channel',
      row: row('ORDER_FIRST_CHANNEL', {}),
      rule: { type: 'ORDER_SEQUENCE', operands: { mode: 'FIRST', basis: 'CHANNEL' } },
    },
    {
      name: 'the 5th order',
      row: row('ORDER_NTH', { operator: 'EQUALS', numericLow: '5' }),
      rule: { type: 'ORDER_SEQUENCE', operands: { mode: 'NTH', n: 5, basis: 'BRAND' } },
    },
    {
      name: 'every 3rd order',
      row: row('ORDER_EVERY_NTH', { operator: 'EQUALS', numericLow: '3' }),
      rule: { type: 'ORDER_SEQUENCE', operands: { mode: 'EVERY_NTH', n: 3, basis: 'BRAND' } },
    },
  ];

  for (const testCase of cases) {
    it(`${testCase.name}: row to wire`, () => {
      expect(conditionsFromGroups([andGroup([testCase.row])])).toEqual([
        { ...testCase.rule, sequence: 1 },
      ]);
    });

    it(`${testCase.name}: wire to row to wire is the identity`, () => {
      const wire = { ...testCase.rule, sequence: 1 };
      const back = groupsFromConditions([wire], CATALOGUE);
      expect(back.unsupported).toEqual([]);
      expect(back.groups).toHaveLength(1);
      expect(back.groups[0].rows).toHaveLength(1);
      expect(back.groups[0].rows[0].type).toBe(testCase.row.type);
      expect(conditionsFromGroups(back.groups)).toEqual([wire]);
    });
  }

  it('covers every type the catalogue offers', () => {
    const covered = new Set(cases.map((c) => c.row.type));
    for (const type of PROMOTION_CONDITION_TYPES) {
      expect(covered.has(type), `${type} has no round-trip case`).toBe(true);
    }
  });

  it('numbers conditions from 1 in the order the rows are listed', () => {
    const wire = conditionsFromGroups([
      andGroup([
        row('FIRST_ORDER', {}),
        row('SUBTOTAL_AT_LEAST', { numericLow: '1000' }),
        row('PAYMENT_METHOD', { textValues: 'CASH' }),
      ]),
    ]);
    expect(wire.map((w) => w.sequence)).toEqual([1, 2, 3]);
    expect(wire.map((w) => w.type)).toEqual(['FIRST_ORDER', 'SUBTOTAL_AT_LEAST', 'PAYMENT_METHOD']);
  });

  it('leaves out a row the operator has not finished instead of sending a mistyped operand', () => {
    const wire = conditionsFromGroups([
      andGroup([
        row('SUBTOTAL_AT_LEAST', { numericLow: '' }),
        row('PAYMENT_METHOD', { textValues: '' }),
        row('TIME_OF_DAY', { numericLow: '720', numericHigh: '' }),
        row('DAY_OF_WEEK', { dayOfWeekValues: [] }),
        row('FIRST_ORDER', {}),
      ]),
    ]);
    expect(wire).toEqual([{ sequence: 1, type: 'FIRST_ORDER', operands: {} }]);
  });

  it('reads conditions back in sequence order whatever order the server listed them', () => {
    const back = groupsFromConditions(
      [rule('PAYMENT_METHOD', { paymentMethodCodes: ['CLICK'] }, 2), rule('FIRST_ORDER', {}, 1)],
      CATALOGUE,
    );
    expect(back.groups[0].rows.map((r) => r.type)).toEqual(['FIRST_ORDER', 'PAYMENT_METHOD']);
  });

  it('reports a condition it cannot represent rather than dropping it', () => {
    const back = groupsFromConditions(
      [
        rule('FIRST_ORDER', {}, 1),
        // The brand-basis FIRST sequence is FIRST_ORDER's job; the console writes that one.
        rule('ORDER_SEQUENCE', { mode: 'FIRST', basis: 'BRAND' }, 2),
        // A channel-basis Nth order has no row in this console either.
        rule('ORDER_SEQUENCE', { mode: 'NTH', n: 4, basis: 'CHANNEL' }, 3),
        // Operands this console would never write.
        rule('PRODUCT', { productIds: 'not-a-list' }, 4),
        rule('SOMETHING_NEW', {}, 5),
      ],
      CATALOGUE,
    );
    expect(back.groups[0].rows).toHaveLength(1);
    expect(back.unsupported).toEqual([
      'ORDER_SEQUENCE',
      'ORDER_SEQUENCE',
      'PRODUCT',
      'SOMETHING_NEW',
    ]);
  });
});

describe('actions: editor rows to wire and back', () => {
  function action(patch: Partial<ActionDraft> & Pick<ActionDraft, 'type'>): ActionDraft {
    return { ...emptyAction(patch.type), ...patch };
  }

  const cases: readonly {
    name: string;
    action: ActionDraft;
    rule: Omit<PromotionRule, 'sequence'>;
  }[] = [
    {
      name: 'a percentage off the order',
      action: action({ type: 'ORDER_PERCENTAGE_DISCOUNT', basisPoints: 500 }),
      rule: { type: 'ORDER_PERCENTAGE_DISCOUNT', operands: { basisPoints: 500 } },
    },
    {
      name: 'a percentage markup',
      action: action({ type: 'ITEM_PERCENTAGE_MARKUP', basisPoints: 1_250 }),
      rule: { type: 'ITEM_PERCENTAGE_MARKUP', operands: { basisPoints: 1_250 } },
    },
    {
      name: 'a fixed amount off each unit',
      action: action({ type: 'ITEM_FIXED_DISCOUNT', amountMinor: 5_000 }),
      rule: { type: 'ITEM_FIXED_DISCOUNT', operands: { amountMinor: 5_000 } },
    },
    {
      name: 'a fixed unit price',
      action: action({ type: 'ITEM_FIXED_PRICE', amountMinor: 0 }),
      rule: { type: 'ITEM_FIXED_PRICE', operands: { amountMinor: 0 } },
    },
    {
      name: 'a fixed markup',
      action: action({ type: 'ITEM_FIXED_MARKUP', amountMinor: 2_000 }),
      rule: { type: 'ITEM_FIXED_MARKUP', operands: { amountMinor: 2_000 } },
    },
    {
      name: 'free delivery',
      action: action({ type: 'FREE_DELIVERY' }),
      rule: { type: 'FREE_DELIVERY', operands: {} },
    },
    {
      name: 'a reduced delivery by percent',
      action: action({ type: 'REDUCED_DELIVERY', reduceBy: 'PERCENT', basisPoints: 5_000 }),
      rule: { type: 'REDUCED_DELIVERY', operands: { basisPoints: 5_000 } },
    },
    {
      name: 'a reduced delivery by amount',
      action: action({ type: 'REDUCED_DELIVERY', reduceBy: 'AMOUNT', amountMinor: 7_000 }),
      rule: { type: 'REDUCED_DELIVERY', operands: { amountMinor: 7_000 } },
    },
    {
      name: 'a once-only gift',
      action: action({ type: 'FREE_ITEM', variantIds: [ID_A], quantity: 1, mode: 'ONCE' }),
      rule: { type: 'FREE_ITEM', operands: { variantIds: [ID_A], quantity: 1, mode: 'ONCE' } },
    },
    {
      name: 'a gift for every n matched units',
      action: action({
        type: 'FREE_ITEM',
        variantIds: [ID_A, ID_B],
        quantity: 3,
        mode: 'PER_MULTIPLE',
        triggerQuantity: 2,
      }),
      rule: {
        type: 'FREE_ITEM',
        operands: {
          variantIds: [ID_A, ID_B],
          quantity: 3,
          mode: 'PER_MULTIPLE',
          triggerQuantity: 2,
        },
      },
    },
  ];

  for (const testCase of cases) {
    it(`${testCase.name}: editor to wire and back`, () => {
      expect(ruleFromAction(testCase.action)).toEqual(testCase.rule);
      const back = actionFromRule({ ...testCase.rule, sequence: 1 });
      expect(back).not.toBeNull();
      expect(ruleFromAction(back as ActionDraft)).toEqual(testCase.rule);
    });
  }

  it('reads a stored gift with no mode or trigger as a once-only gift with trigger 1', () => {
    const back = actionFromRule(rule('FREE_ITEM', { variantIds: [ID_A], quantity: 2 }));
    expect(back).toMatchObject({ mode: 'ONCE', triggerQuantity: 1, quantity: 2 });
  });

  it('refuses to read an action whose operands it would not write', () => {
    expect(actionFromRule(rule('ORDER_PERCENTAGE_DISCOUNT', {}))).toBeNull();
    expect(actionFromRule(rule('ORDER_FIXED_DISCOUNT', { amountMinor: 1.5 }))).toBeNull();
    expect(actionFromRule(rule('REDUCED_DELIVERY', { amountMinor: 1, basisPoints: 1 }))).toBeNull();
    expect(actionFromRule(rule('REDUCED_DELIVERY', {}))).toBeNull();
    expect(actionFromRule(rule('FREE_ITEM', { variantIds: [ID_A] }))).toBeNull();
    expect(actionFromRule(rule('UNHEARD_OF', {}))).toBeNull();
  });

  it('offers exactly the action types the validator accepts for a kind and scope', () => {
    expect(actionTypesFor('DISCOUNT', 'ITEM')).toEqual([
      'ITEM_PERCENTAGE_DISCOUNT',
      'ITEM_FIXED_DISCOUNT',
      'ITEM_FIXED_PRICE',
      'FREE_ITEM',
    ]);
    expect(actionTypesFor('DISCOUNT', 'ORDER')).toEqual([
      'ORDER_PERCENTAGE_DISCOUNT',
      'ORDER_FIXED_DISCOUNT',
    ]);
    expect(actionTypesFor('DISCOUNT', 'DELIVERY')).toEqual(['FREE_DELIVERY', 'REDUCED_DELIVERY']);
    expect(actionTypesFor('MARKUP', 'ITEM')).toEqual([
      'ITEM_PERCENTAGE_MARKUP',
      'ITEM_FIXED_MARKUP',
    ]);
    // A markup's scope is whatever the page left it at; its actions never follow it.
    expect(actionTypesFor('MARKUP', 'ORDER')).toEqual(actionTypesFor('MARKUP', 'ITEM'));
  });

  it('only offers item scope for a markup, since an order-level markup is not built', () => {
    expect(scopesFor('MARKUP')).toEqual(['ITEM']);
    expect(scopesFor('DISCOUNT')).toEqual(['ITEM', 'ORDER', 'DELIVERY']);
  });

  it('starts an item rule in a group that an order rule never shares', () => {
    expect(defaultStackingGroup('DISCOUNT', 'ITEM')).not.toBe(
      defaultStackingGroup('DISCOUNT', 'ORDER'),
    );
    expect(defaultStackingGroup('DISCOUNT', 'ORDER')).not.toBe(
      defaultStackingGroup('DISCOUNT', 'DELIVERY'),
    );
  });
});

describe('the draft: stored promotion to editor and back to a request body', () => {
  const catalogue = CATALOGUE;

  it('round-trips a stored promotion into the body that would save it unchanged', () => {
    const stored = view({
      kind: 'DISCOUNT',
      scope: 'ORDER',
      exclusive: true,
      conditions: [
        rule('PAYMENT_METHOD', { paymentMethodCodes: ['CLICK'] }, 1),
        rule('TIME_OF_DAY', { fromMinuteOfDay: 720, toMinuteOfDay: 900 }, 2),
      ],
      maximumRedemptions: 100,
      maximumPerCustomer: 1,
    });
    const { draft, unsupported } = draftFromView(stored, catalogue);
    expect(unsupported).toEqual([]);
    const body = bodyFromDraft(draft);
    expect(body).toEqual({
      code: 'CLICK5',
      name: '5% off with Click',
      kind: 'DISCOUNT',
      scope: 'ORDER',
      stackingGroup: 'payment',
      exclusive: true,
      priority: 5,
      maximumDiscountMinor: 20_000,
      currency: 'UZS',
      validFrom: null,
      validUntil: null,
      maximumRedemptions: 100,
      maximumPerCustomer: 1,
      loyaltyAccrual: 'SUPPRESS',
      loyaltyRedemption: 'BLOCK',
      conditions: stored.conditions,
      actions: stored.actions,
    });
  });

  it('sends a null cap and null limits when the operator turned them off, never zero', () => {
    const { draft } = draftFromView(
      view({ maximumDiscountMinor: null, maximumRedemptions: null, maximumPerCustomer: null }),
      catalogue,
    );
    const body = bodyFromDraft(draft);
    expect(body.maximumDiscountMinor).toBeNull();
    expect(body.maximumRedemptions).toBeNull();
    expect(body.maximumPerCustomer).toBeNull();
  });

  it('never sends a cap or exclusivity for a markup: a markup is not a discount and is never exclusive', () => {
    const draft = {
      ...emptyDraft(),
      kind: 'MARKUP' as const,
      scope: 'ITEM' as const,
      exclusive: true,
      hasCap: true,
      code: 'SURGE',
      name: 'Evening surcharge',
      actions: [emptyAction('ITEM_PERCENTAGE_MARKUP')],
    };
    const body = bodyFromDraft(draft);
    expect(body.exclusive).toBe(false);
    expect(body.maximumDiscountMinor).toBeNull();
  });

  it('trims the handle and the name and upper-cases the currency', () => {
    const body = bodyFromDraft({
      ...emptyDraft(),
      code: '  LUNCH10 ',
      name: ' Lunch ',
      currency: 'uzs',
      stackingGroup: ' menu ',
    });
    expect(body.code).toBe('LUNCH10');
    expect(body.name).toBe('Lunch');
    expect(body.currency).toBe('UZS');
    expect(body.stackingGroup).toBe('menu');
  });

  it('reports every unsupported rule of a stored promotion, conditions and actions together', () => {
    const { unsupported } = draftFromView(
      view({
        conditions: [rule('FUTURE_CONDITION', {})],
        actions: [
          rule('ORDER_PERCENTAGE_DISCOUNT', { basisPoints: 100 }, 1),
          rule('SERVICE_CHARGE', {}, 2),
        ],
      }),
      catalogue,
    );
    expect(unsupported).toEqual(['FUTURE_CONDITION', 'SERVICE_CHARGE']);
  });

  it('numbers actions from 1 and keeps their order', () => {
    const body = bodyFromDraft({
      ...emptyDraft(),
      code: 'TWO',
      name: 'Two actions',
      actions: [
        { ...emptyAction('ORDER_FIXED_DISCOUNT'), amountMinor: 1_000 },
        { ...emptyAction('ORDER_PERCENTAGE_DISCOUNT'), basisPoints: 250 },
      ],
    });
    expect(body.actions.map((a) => [a.sequence, a.type])).toEqual([
      [1, 'ORDER_FIXED_DISCOUNT'],
      [2, 'ORDER_PERCENTAGE_DISCOUNT'],
    ]);
  });
});

describe('device-local date inputs', () => {
  it('round-trips an instant at minute precision through the datetime-local value', () => {
    const local = isoToLocalInput('2026-10-05T07:30:00.000Z');
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(localInputToIso(local)).toBe('2026-10-05T07:30:00.000Z');
  });

  it('reads nothing as nothing', () => {
    expect(isoToLocalInput(null)).toBe('');
    expect(isoToLocalInput('not a date')).toBe('');
    expect(localInputToIso('')).toBeNull();
    expect(localInputToIso('yesterday')).toBeNull();
  });
});

describe('what the form can already tell is wrong', () => {
  it('flags a missing name, a bad handle, a missing group, no action and an unfinished condition', () => {
    const problems = draftProblems({
      ...emptyDraft(),
      name: '',
      code: 'x',
      stackingGroup: ' ',
      actions: [],
      conditions: [andGroup([row('SUBTOTAL_AT_LEAST', { numericLow: '' })])],
    });
    expect(problems).toEqual([
      'problem.name',
      'problem.code',
      'problem.group',
      'problem.action',
      'problem.condition',
    ]);
  });

  it('flags a gift that names no dish', () => {
    expect(
      draftProblems({
        ...emptyDraft(),
        name: 'Gift',
        code: 'GIFT',
        scope: 'ITEM',
        actions: [emptyAction('FREE_ITEM')],
      }),
    ).toEqual(['problem.gift']);
  });

  it('accepts a complete draft, including one with no condition at all', () => {
    expect(draftProblems({ ...emptyDraft(), name: 'All orders', code: 'ALL10' })).toEqual([]);
  });
});
