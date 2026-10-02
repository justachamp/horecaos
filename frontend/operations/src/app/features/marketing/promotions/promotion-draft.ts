import { MessageKey } from '../../../core/i18n/messages.en';
import { PromotionTextKey } from './promotion-texts';
import {
  ConditionFixedValue,
  ConditionGroup,
  ConditionRow,
  ConditionTypeDescriptor,
  emptyConditionRow,
} from '../../../shared/ui/condition-types';
import { nextDomId } from '../../../shared/ui/overlay';
import {
  LoyaltyAccrual,
  LoyaltyRedemption,
  PromotionBody,
  PromotionKind,
  PromotionRule,
  PromotionScope,
  PromotionView,
} from './promotions-api';

/**
 * The promotion editor's model, and its two-way translation to the wire shape
 * (ADR 0140).
 *
 * A promotion is data in a closed vocabulary (ADR 0018): AND-only conditions and
 * typed actions whose operands travel as a map. The editor holds those as
 * editable strings and numbers, and this module is the one place that knows how a
 * `q-condition-builder` row becomes a backend condition and back. Three rules
 * keep it honest:
 *
 * - **Nothing is dropped silently.** A condition or action this console cannot
 *   represent (a shape authored through the API, or by a newer server) is
 *   reported in `unsupported`, and the editor refuses to save over it. The
 *   alternative, rendering what it understands and saving that, would quietly
 *   delete the rest of a live rule.
 * - **The console offers a subset on purpose.** `ORDER_SEQUENCE` has three modes
 *   and two bases; the console offers "first order through this channel",
 *   "Nth order" and "every Nth order" (brand basis) as separate, readable
 *   conditions, and plain "first order" is the existing `FIRST_ORDER`.
 * - **Money is whole minor units and percentages are basis points**, both
 *   integers, exactly as the server stores them. Nothing here ever holds a float
 *   for money.
 */

// ------------------------------------------------------------------ vocabulary

/** The condition types the editor's catalogue offers, in the order it lists them. */
export const PROMOTION_CONDITION_TYPES = [
  'SUBTOTAL_AT_LEAST',
  'QUANTITY_AT_LEAST',
  'PRODUCT',
  'CATEGORY',
  'VARIANT',
  'CHANNEL',
  'CHANNEL_TYPE',
  'LOCATION',
  'FULFILLMENT_MODE',
  'PAYMENT_METHOD',
  'DELIVERY_ZONE',
  'CUSTOMER_SEGMENT',
  'DAY_OF_WEEK',
  'TIME_OF_DAY',
  'FIRST_ORDER',
  'ORDER_FIRST_CHANNEL',
  'ORDER_NTH',
  'ORDER_EVERY_NTH',
] as const;

export type PromotionConditionType = (typeof PROMOTION_CONDITION_TYPES)[number];

/** `tenant.sales_channels.system_type`, minus AGGREGATOR: those orders are externally priced and never reach the engine (ADR 0040). */
export const CHANNEL_TYPES = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'POS',
] as const;

export const FULFILLMENT_MODES = ['DELIVERY', 'PICKUP', 'DINE_IN'] as const;

/** Every action type, by what it does. The editor offers the ones its kind and scope allow. */
export const PROMOTION_ACTION_TYPES = [
  'ITEM_PERCENTAGE_DISCOUNT',
  'ITEM_FIXED_DISCOUNT',
  'ITEM_FIXED_PRICE',
  'FREE_ITEM',
  'ORDER_PERCENTAGE_DISCOUNT',
  'ORDER_FIXED_DISCOUNT',
  'FREE_DELIVERY',
  'REDUCED_DELIVERY',
  'ITEM_PERCENTAGE_MARKUP',
  'ITEM_FIXED_MARKUP',
] as const;

export type PromotionActionType = (typeof PROMOTION_ACTION_TYPES)[number];

/** Which action types a kind and scope may carry: `PromotionValidator`'s `ACTION_SCOPE_MISMATCH`, mirrored so the select never offers a refusal. */
export function actionTypesFor(
  kind: PromotionKind,
  scope: PromotionScope,
): readonly PromotionActionType[] {
  if (kind === 'MARKUP') {
    return ['ITEM_PERCENTAGE_MARKUP', 'ITEM_FIXED_MARKUP'];
  }
  switch (scope) {
    case 'ITEM':
      return ['ITEM_PERCENTAGE_DISCOUNT', 'ITEM_FIXED_DISCOUNT', 'ITEM_FIXED_PRICE', 'FREE_ITEM'];
    case 'ORDER':
      return ['ORDER_PERCENTAGE_DISCOUNT', 'ORDER_FIXED_DISCOUNT'];
    case 'DELIVERY':
      return ['FREE_DELIVERY', 'REDUCED_DELIVERY'];
  }
}

/** Markups land on items only: an order-level markup is a service line whose fiscal treatment is still open (ADR 0140). */
export function scopesFor(kind: PromotionKind): readonly PromotionScope[] {
  return kind === 'MARKUP' ? ['ITEM'] : ['ITEM', 'ORDER', 'DELIVERY'];
}

/** The stacking group a new promotion starts in: item rules may not share a group with order or delivery ones (`STACKING_GROUP_MIXES_SCOPES`). */
export function defaultStackingGroup(kind: PromotionKind, scope: PromotionScope): string {
  if (kind === 'MARKUP') {
    return 'markup';
  }
  switch (scope) {
    case 'ITEM':
      return 'menu';
    case 'ORDER':
      return 'order';
    case 'DELIVERY':
      return 'delivery';
  }
}

// ----------------------------------------------------------------------- model

/** One action row, every operand held so switching type does not lose what was typed. */
export interface ActionDraft {
  readonly id: string;
  readonly type: PromotionActionType;
  readonly basisPoints: number;
  readonly amountMinor: number;
  readonly variantIds: readonly string[];
  /** `FREE_ITEM`'s bound: units given free, per promotion across every line. */
  readonly quantity: number;
  /** `FREE_ITEM` in `PER_MULTIPLE` mode: matched units needed per free unit. */
  readonly triggerQuantity: number;
  readonly mode: 'ONCE' | 'PER_MULTIPLE';
  /** `REDUCED_DELIVERY` takes exactly one of an amount or a percentage. */
  readonly reduceBy: 'AMOUNT' | 'PERCENT';
}

export interface PromotionDraft {
  readonly code: string;
  readonly name: string;
  readonly kind: PromotionKind;
  readonly scope: PromotionScope;
  readonly stackingGroup: string;
  readonly exclusive: boolean;
  readonly priority: number;
  readonly hasCap: boolean;
  readonly maximumDiscountMinor: number;
  readonly currency: string;
  /** `YYYY-MM-DDTHH:mm` in the device's time zone, or empty for "effective at once". */
  readonly validFrom: string;
  /** Same shape; empty for "never lapses". */
  readonly validUntil: string;
  readonly hasTotalLimit: boolean;
  readonly maximumRedemptions: number;
  readonly hasCustomerLimit: boolean;
  readonly maximumPerCustomer: number;
  readonly loyaltyAccrual: LoyaltyAccrual;
  readonly loyaltyRedemption: LoyaltyRedemption;
  /** One AND group; the engine has no OR. */
  readonly conditions: readonly ConditionGroup[];
  readonly actions: readonly ActionDraft[];
}

/** The one AND group a promotion's conditions live in: the engine has no OR (ADR 0018). It may hold no row at all, which means every order. */
export function andGroup(rows: readonly ConditionRow[]): ConditionGroup {
  return { id: nextDomId('condition-group'), combinator: 'AND', rows };
}

export function emptyAction(type: PromotionActionType): ActionDraft {
  return {
    id: nextDomId('promotion-action'),
    type,
    basisPoints: 1_000,
    amountMinor: 10_000,
    variantIds: [],
    quantity: 1,
    triggerQuantity: 1,
    mode: 'ONCE',
    reduceBy: 'PERCENT',
  };
}

export function emptyDraft(currency = 'UZS'): PromotionDraft {
  return {
    code: '',
    name: '',
    kind: 'DISCOUNT',
    scope: 'ORDER',
    stackingGroup: defaultStackingGroup('DISCOUNT', 'ORDER'),
    exclusive: false,
    priority: 0,
    hasCap: false,
    maximumDiscountMinor: 50_000,
    currency,
    validFrom: '',
    validUntil: '',
    hasTotalLimit: false,
    maximumRedemptions: 100,
    hasCustomerLimit: false,
    maximumPerCustomer: 1,
    loyaltyAccrual: 'ACCRUE',
    loyaltyRedemption: 'ALLOW',
    conditions: [andGroup([])],
    actions: [emptyAction('ORDER_PERCENTAGE_DISCOUNT')],
  };
}

// ----------------------------------------------------------------- catalogue

/** Each lookup the condition catalogue offers as chips, already translated and sorted by the caller. */
export interface PromotionLookups {
  readonly channels: readonly ConditionFixedValue[];
  readonly channelTypes: readonly ConditionFixedValue[];
  readonly locations: readonly ConditionFixedValue[];
  readonly fulfillmentModes: readonly ConditionFixedValue[];
  readonly paymentMethods: readonly ConditionFixedValue[];
  readonly zones: readonly ConditionFixedValue[];
  readonly categories: readonly ConditionFixedValue[];
  readonly products: readonly ConditionFixedValue[];
  readonly variants: readonly ConditionFixedValue[];
  readonly segments: readonly ConditionFixedValue[];
}

export const NO_LOOKUPS: PromotionLookups = {
  channels: [],
  channelTypes: [],
  locations: [],
  fulfillmentModes: [],
  paymentMethods: [],
  zones: [],
  categories: [],
  products: [],
  variants: [],
  segments: [],
};

const SET_OPERATORS = ['IN', 'NOT_IN'] as const;

function labelKey(type: PromotionConditionType): MessageKey {
  return `marketing.promotions.condition.${type}` as MessageKey;
}

/**
 * The closed condition catalogue `q-condition-builder` and `q-rule-simulator`
 * work from: `PromotionEvaluator`'s condition set, mirrored one for one (every
 * addition on the server is a migration, a branch and a test together, and so is
 * one here).
 *
 * A lookup that came back empty (a principal who cannot read channels, say) still
 * renders as an empty chip list, which the builder shows as "no choices" rather
 * than a free-text box: an id typed by hand into a rule is exactly the mistyped
 * operand the validator exists to refuse.
 */
export function buildConditionCatalogue(
  lookups: PromotionLookups,
): readonly ConditionTypeDescriptor[] {
  const reference = (
    type: PromotionConditionType,
    values: readonly ConditionFixedValue[],
    options: { readonly exclude?: boolean; readonly searchable?: boolean } = {},
  ): ConditionTypeDescriptor => ({
    type,
    labelKey: labelKey(type),
    valueKind: 'REFERENCE',
    fixedValues: values,
    operators: options.exclude ? SET_OPERATORS : ['IN'],
    searchable: options.searchable,
  });
  return [
    {
      type: 'SUBTOTAL_AT_LEAST',
      labelKey: labelKey('SUBTOTAL_AT_LEAST'),
      valueKind: 'MONEY_MINOR',
      operators: ['AT_LEAST'],
    },
    {
      type: 'QUANTITY_AT_LEAST',
      labelKey: labelKey('QUANTITY_AT_LEAST'),
      valueKind: 'NUMERIC',
      operators: ['AT_LEAST', 'EQUALS'],
    },
    reference('PRODUCT', lookups.products, { exclude: true, searchable: true }),
    reference('CATEGORY', lookups.categories, { exclude: true }),
    reference('VARIANT', lookups.variants, { exclude: true, searchable: true }),
    reference('CHANNEL', lookups.channels),
    reference('CHANNEL_TYPE', lookups.channelTypes),
    reference('LOCATION', lookups.locations),
    reference('FULFILLMENT_MODE', lookups.fulfillmentModes),
    reference('PAYMENT_METHOD', lookups.paymentMethods),
    reference('DELIVERY_ZONE', lookups.zones),
    reference('CUSTOMER_SEGMENT', lookups.segments),
    {
      type: 'DAY_OF_WEEK',
      labelKey: labelKey('DAY_OF_WEEK'),
      valueKind: 'DAY_OF_WEEK_SET',
      operators: ['IN'],
    },
    {
      type: 'TIME_OF_DAY',
      labelKey: labelKey('TIME_OF_DAY'),
      valueKind: 'TIME_RANGE',
      operators: ['BETWEEN'],
    },
    { type: 'FIRST_ORDER', labelKey: labelKey('FIRST_ORDER'), valueKind: 'FLAG' },
    { type: 'ORDER_FIRST_CHANNEL', labelKey: labelKey('ORDER_FIRST_CHANNEL'), valueKind: 'FLAG' },
    {
      type: 'ORDER_NTH',
      labelKey: labelKey('ORDER_NTH'),
      valueKind: 'NUMERIC',
      operators: ['EQUALS'],
    },
    {
      type: 'ORDER_EVERY_NTH',
      labelKey: labelKey('ORDER_EVERY_NTH'),
      valueKind: 'NUMERIC',
      operators: ['EQUALS'],
    },
  ];
}

// ------------------------------------------------- conditions: rows <-> wire

function ids(row: ConditionRow): string[] {
  return row.textValues
    .split(',')
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

function whole(text: string): number {
  return Math.trunc(Number(text));
}

/**
 * The backend conditions a builder's rows stand for, in order, numbered from 1.
 * A row the operator has not finished (no value) is not a rule yet and is left out
 * rather than sent as a mistyped operand.
 */
export function conditionsFromGroups(groups: readonly ConditionGroup[]): readonly PromotionRule[] {
  const rules: PromotionRule[] = [];
  for (const group of groups) {
    for (const row of group.rows) {
      const rule = ruleFromRow(row);
      if (rule) {
        rules.push({ ...rule, sequence: rules.length + 1 });
      }
    }
  }
  return rules;
}

function ruleFromRow(row: ConditionRow): Omit<PromotionRule, 'sequence'> | null {
  const exclude = row.operator === 'NOT_IN';
  switch (row.type as PromotionConditionType) {
    case 'SUBTOTAL_AT_LEAST':
      return row.numericLow.trim() === ''
        ? null
        : { type: 'SUBTOTAL_AT_LEAST', operands: { amountMinor: whole(row.numericLow) } };
    case 'QUANTITY_AT_LEAST':
      return row.numericLow.trim() === ''
        ? null
        : {
            type: 'QUANTITY_AT_LEAST',
            operands:
              row.operator === 'EQUALS'
                ? { quantity: whole(row.numericLow), exact: true }
                : { quantity: whole(row.numericLow) },
          };
    case 'PRODUCT':
      return setRule('PRODUCT', 'productIds', row, exclude);
    case 'CATEGORY':
      return setRule('CATEGORY', 'categoryIds', row, exclude);
    case 'VARIANT':
      return setRule('VARIANT', 'variantIds', row, exclude);
    case 'CHANNEL':
      return setRule('CHANNEL', 'channels', row, false);
    case 'CHANNEL_TYPE':
      return setRule('CHANNEL_TYPE', 'channelTypes', row, false);
    case 'LOCATION':
      return setRule('LOCATION', 'locationIds', row, false);
    case 'FULFILLMENT_MODE':
      return setRule('FULFILLMENT_MODE', 'fulfillmentModes', row, false);
    case 'PAYMENT_METHOD':
      return setRule('PAYMENT_METHOD', 'paymentMethodCodes', row, false);
    case 'DELIVERY_ZONE':
      return setRule('DELIVERY_ZONE', 'zoneIds', row, false);
    case 'CUSTOMER_SEGMENT':
      return setRule('CUSTOMER_SEGMENT', 'segments', row, false);
    case 'DAY_OF_WEEK':
      return row.dayOfWeekValues.length === 0
        ? null
        : {
            type: 'DAY_OF_WEEK',
            operands: { daysOfWeek: [...row.dayOfWeekValues].sort((a, b) => a - b) },
          };
    case 'TIME_OF_DAY':
      return row.numericLow.trim() === '' || row.numericHigh.trim() === ''
        ? null
        : {
            type: 'TIME_OF_DAY',
            operands: {
              fromMinuteOfDay: whole(row.numericLow),
              toMinuteOfDay: whole(row.numericHigh),
            },
          };
    case 'FIRST_ORDER':
      return { type: 'FIRST_ORDER', operands: {} };
    case 'ORDER_FIRST_CHANNEL':
      return { type: 'ORDER_SEQUENCE', operands: { mode: 'FIRST', basis: 'CHANNEL' } };
    case 'ORDER_NTH':
      return row.numericLow.trim() === ''
        ? null
        : {
            type: 'ORDER_SEQUENCE',
            operands: { mode: 'NTH', n: whole(row.numericLow), basis: 'BRAND' },
          };
    case 'ORDER_EVERY_NTH':
      return row.numericLow.trim() === ''
        ? null
        : {
            type: 'ORDER_SEQUENCE',
            operands: { mode: 'EVERY_NTH', n: whole(row.numericLow), basis: 'BRAND' },
          };
    default:
      return null;
  }
}

function setRule(
  type: string,
  key: string,
  row: ConditionRow,
  exclude: boolean,
): Omit<PromotionRule, 'sequence'> | null {
  const values = ids(row);
  if (values.length === 0) {
    return null;
  }
  return { type, operands: exclude ? { [key]: values, exclude: true } : { [key]: values } };
}

/** The rows and the unsupported rules a stored promotion's conditions stand for. */
export interface ConditionRows {
  readonly groups: readonly ConditionGroup[];
  /** Condition types this console cannot represent. Non-empty means the editor must not save over them. */
  readonly unsupported: readonly string[];
}

export function groupsFromConditions(
  conditions: readonly PromotionRule[],
  catalogue: readonly ConditionTypeDescriptor[],
): ConditionRows {
  const rows: ConditionRow[] = [];
  const unsupported: string[] = [];
  for (const rule of [...conditions].sort((a, b) => a.sequence - b.sequence)) {
    const row = rowFromRule(rule, catalogue);
    if (row) {
      rows.push(row);
    } else {
      unsupported.push(rule.type);
    }
  }
  return { groups: [andGroup(rows)], unsupported };
}

function stringList(value: unknown): string[] | null {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string')
    ? (value as string[])
    : null;
}

function intOperand(value: unknown): string | null {
  return typeof value === 'number' && Number.isInteger(value) ? String(value) : null;
}

function rowFromRule(
  rule: PromotionRule,
  catalogue: readonly ConditionTypeDescriptor[],
): ConditionRow | null {
  const base = (type: PromotionConditionType): ConditionRow => emptyConditionRow(catalogue, type);
  const set = (
    type: PromotionConditionType,
    key: string,
    allowExclude: boolean,
  ): ConditionRow | null => {
    const values = stringList(rule.operands[key]);
    if (values === null || values.length === 0) {
      return null;
    }
    const excluded = allowExclude && rule.operands['exclude'] === true;
    return { ...base(type), operator: excluded ? 'NOT_IN' : 'IN', textValues: values.join(',') };
  };
  switch (rule.type) {
    case 'SUBTOTAL_AT_LEAST': {
      const amount = intOperand(rule.operands['amountMinor']);
      return amount === null
        ? null
        : { ...base('SUBTOTAL_AT_LEAST'), operator: 'AT_LEAST', numericLow: amount };
    }
    case 'QUANTITY_AT_LEAST': {
      const quantity = intOperand(rule.operands['quantity']);
      return quantity === null
        ? null
        : {
            ...base('QUANTITY_AT_LEAST'),
            operator: rule.operands['exact'] === true ? 'EQUALS' : 'AT_LEAST',
            numericLow: quantity,
          };
    }
    case 'PRODUCT':
      return set('PRODUCT', 'productIds', true);
    case 'CATEGORY':
      return set('CATEGORY', 'categoryIds', true);
    case 'VARIANT':
      return set('VARIANT', 'variantIds', true);
    case 'CHANNEL':
      return set('CHANNEL', 'channels', false);
    case 'CHANNEL_TYPE':
      return set('CHANNEL_TYPE', 'channelTypes', false);
    case 'LOCATION':
      return set('LOCATION', 'locationIds', false);
    case 'FULFILLMENT_MODE':
      return set('FULFILLMENT_MODE', 'fulfillmentModes', false);
    case 'PAYMENT_METHOD':
      return set('PAYMENT_METHOD', 'paymentMethodCodes', false);
    case 'DELIVERY_ZONE':
      return set('DELIVERY_ZONE', 'zoneIds', false);
    case 'CUSTOMER_SEGMENT':
      return set('CUSTOMER_SEGMENT', 'segments', false);
    case 'DAY_OF_WEEK': {
      const days = rule.operands['daysOfWeek'];
      return Array.isArray(days) && days.length > 0 && days.every((day) => Number.isInteger(day))
        ? { ...base('DAY_OF_WEEK'), operator: 'IN', dayOfWeekValues: days as number[] }
        : null;
    }
    case 'TIME_OF_DAY': {
      const from = intOperand(rule.operands['fromMinuteOfDay']);
      const to = intOperand(rule.operands['toMinuteOfDay']);
      return from === null || to === null
        ? null
        : { ...base('TIME_OF_DAY'), operator: 'BETWEEN', numericLow: from, numericHigh: to };
    }
    case 'FIRST_ORDER':
      return base('FIRST_ORDER');
    case 'ORDER_SEQUENCE':
      return sequenceRow(rule, base);
    default:
      return null;
  }
}

function sequenceRow(
  rule: PromotionRule,
  base: (type: PromotionConditionType) => ConditionRow,
): ConditionRow | null {
  const mode = rule.operands['mode'];
  const basis = rule.operands['basis'] ?? 'BRAND';
  if (mode === 'FIRST' && basis === 'CHANNEL') {
    return base('ORDER_FIRST_CHANNEL');
  }
  const n = intOperand(rule.operands['n']);
  if (n !== null && basis === 'BRAND' && mode === 'NTH') {
    return { ...base('ORDER_NTH'), operator: 'EQUALS', numericLow: n };
  }
  if (n !== null && basis === 'BRAND' && mode === 'EVERY_NTH') {
    return { ...base('ORDER_EVERY_NTH'), operator: 'EQUALS', numericLow: n };
  }
  return null;
}

// -------------------------------------------------- actions: drafts <-> wire

/** The wire action an editor row stands for, numbered by the caller. */
export function ruleFromAction(action: ActionDraft): Omit<PromotionRule, 'sequence'> {
  switch (action.type) {
    case 'ITEM_PERCENTAGE_DISCOUNT':
    case 'ORDER_PERCENTAGE_DISCOUNT':
    case 'ITEM_PERCENTAGE_MARKUP':
      return { type: action.type, operands: { basisPoints: action.basisPoints } };
    case 'ITEM_FIXED_DISCOUNT':
    case 'ORDER_FIXED_DISCOUNT':
    case 'ITEM_FIXED_PRICE':
    case 'ITEM_FIXED_MARKUP':
      return { type: action.type, operands: { amountMinor: action.amountMinor } };
    case 'FREE_DELIVERY':
      return { type: 'FREE_DELIVERY', operands: {} };
    case 'REDUCED_DELIVERY':
      return {
        type: 'REDUCED_DELIVERY',
        operands:
          action.reduceBy === 'AMOUNT'
            ? { amountMinor: action.amountMinor }
            : { basisPoints: action.basisPoints },
      };
    case 'FREE_ITEM':
      return {
        type: 'FREE_ITEM',
        operands:
          action.mode === 'PER_MULTIPLE'
            ? {
                variantIds: [...action.variantIds],
                quantity: action.quantity,
                mode: 'PER_MULTIPLE',
                triggerQuantity: action.triggerQuantity,
              }
            : { variantIds: [...action.variantIds], quantity: action.quantity, mode: 'ONCE' },
      };
  }
}

function isActionType(type: string): type is PromotionActionType {
  return (PROMOTION_ACTION_TYPES as readonly string[]).includes(type);
}

function positiveInt(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null;
}

/** The editor row a stored action stands for, or `null` when its operands are not a shape this console writes. */
export function actionFromRule(rule: PromotionRule): ActionDraft | null {
  if (!isActionType(rule.type)) {
    return null;
  }
  const base = emptyAction(rule.type);
  const operands = rule.operands;
  switch (rule.type) {
    case 'ITEM_PERCENTAGE_DISCOUNT':
    case 'ORDER_PERCENTAGE_DISCOUNT':
    case 'ITEM_PERCENTAGE_MARKUP': {
      const basisPoints = positiveInt(operands['basisPoints']);
      return basisPoints === null ? null : { ...base, basisPoints };
    }
    case 'ITEM_FIXED_DISCOUNT':
    case 'ORDER_FIXED_DISCOUNT':
    case 'ITEM_FIXED_PRICE':
    case 'ITEM_FIXED_MARKUP': {
      const amountMinor = positiveInt(operands['amountMinor']);
      return amountMinor === null ? null : { ...base, amountMinor };
    }
    case 'FREE_DELIVERY':
      return base;
    case 'REDUCED_DELIVERY': {
      const amountMinor = positiveInt(operands['amountMinor']);
      const basisPoints = positiveInt(operands['basisPoints']);
      if ((amountMinor === null) === (basisPoints === null)) {
        return null;
      }
      return amountMinor !== null
        ? { ...base, reduceBy: 'AMOUNT', amountMinor }
        : { ...base, reduceBy: 'PERCENT', basisPoints: basisPoints as number };
    }
    case 'FREE_ITEM': {
      const variantIds = stringList(operands['variantIds']);
      const quantity = positiveInt(operands['quantity']);
      const mode = operands['mode'] === 'PER_MULTIPLE' ? 'PER_MULTIPLE' : 'ONCE';
      const trigger = operands['triggerQuantity'];
      const triggerQuantity = trigger === undefined ? 1 : positiveInt(trigger);
      if (variantIds === null || quantity === null || triggerQuantity === null) {
        return null;
      }
      return { ...base, variantIds, quantity, mode, triggerQuantity };
    }
  }
}

// ------------------------------------------------------------ dates and body

const LOCAL_INPUT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

/** An instant as a `datetime-local` value in the device's time zone, or empty. */
export function isoToLocalInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The inverse of {@link isoToLocalInput}: the instant a `datetime-local` value names, or `null` for empty or unreadable text. */
export function localInputToIso(local: string): string | null {
  const match = LOCAL_INPUT.exec(local.trim());
  if (!match) {
    return null;
  }
  const [, year, month, day, hour, minute] = match.map(Number);
  const date = new Date(year, month - 1, day, hour, minute);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** Builds the draft an existing promotion stands for, and reports what the console cannot represent in it. */
export function draftFromView(
  view: PromotionView,
  catalogue: readonly ConditionTypeDescriptor[],
): { readonly draft: PromotionDraft; readonly unsupported: readonly string[] } {
  const conditions = groupsFromConditions(view.conditions, catalogue);
  const unsupported = [...conditions.unsupported];
  const actions: ActionDraft[] = [];
  for (const rule of [...view.actions].sort((a, b) => a.sequence - b.sequence)) {
    const action = actionFromRule(rule);
    if (action) {
      actions.push(action);
    } else {
      unsupported.push(rule.type);
    }
  }
  return {
    draft: {
      code: view.code,
      name: view.name,
      kind: view.kind,
      scope: view.scope,
      stackingGroup: view.stackingGroup,
      exclusive: view.exclusive,
      priority: view.priority,
      hasCap: view.maximumDiscountMinor !== null,
      maximumDiscountMinor: view.maximumDiscountMinor ?? 50_000,
      currency: view.currency,
      validFrom: isoToLocalInput(view.validFrom),
      validUntil: isoToLocalInput(view.validUntil),
      hasTotalLimit: view.maximumRedemptions !== null,
      maximumRedemptions: view.maximumRedemptions ?? 100,
      hasCustomerLimit: view.maximumPerCustomer !== null,
      maximumPerCustomer: view.maximumPerCustomer ?? 1,
      loyaltyAccrual: view.loyaltyAccrual,
      loyaltyRedemption: view.loyaltyRedemption,
      conditions: conditions.groups,
      actions,
    },
    unsupported,
  };
}

/** The request body a draft stands for. Optional fields are `null`, never absent, and never a missing primitive. */
export function bodyFromDraft(draft: PromotionDraft): PromotionBody {
  return {
    code: draft.code.trim(),
    name: draft.name.trim(),
    kind: draft.kind,
    scope: draft.scope,
    stackingGroup: draft.stackingGroup.trim(),
    exclusive: draft.kind === 'MARKUP' ? false : draft.exclusive,
    priority: draft.priority,
    maximumDiscountMinor:
      draft.kind === 'DISCOUNT' && draft.hasCap ? draft.maximumDiscountMinor : null,
    currency: draft.currency.trim().toUpperCase(),
    validFrom: localInputToIso(draft.validFrom),
    validUntil: localInputToIso(draft.validUntil),
    maximumRedemptions: draft.hasTotalLimit ? draft.maximumRedemptions : null,
    maximumPerCustomer: draft.hasCustomerLimit ? draft.maximumPerCustomer : null,
    loyaltyAccrual: draft.loyaltyAccrual,
    loyaltyRedemption: draft.loyaltyRedemption,
    conditions: conditionsFromGroups(draft.conditions),
    actions: draft.actions.map((action, index) => ({
      ...ruleFromAction(action),
      sequence: index + 1,
    })),
  };
}

/** The reasons a draft cannot be saved yet, as promotion text keys: only what the form itself can know. The server's validator owns the rest. */
export function draftProblems(draft: PromotionDraft): readonly PromotionTextKey[] {
  const problems: PromotionTextKey[] = [];
  if (draft.name.trim().length === 0) {
    problems.push('problem.name');
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$/.test(draft.code.trim())) {
    problems.push('problem.code');
  }
  if (draft.stackingGroup.trim().length === 0) {
    problems.push('problem.group');
  }
  if (draft.actions.length === 0) {
    problems.push('problem.action');
  }
  if (
    draft.actions.some((action) => action.type === 'FREE_ITEM' && action.variantIds.length === 0)
  ) {
    problems.push('problem.gift');
  }
  const unfinished = draft.conditions.some((group) =>
    group.rows.some((row) => ruleFromRow(row) === null),
  );
  if (unfinished) {
    problems.push('problem.condition');
  }
  return problems;
}
