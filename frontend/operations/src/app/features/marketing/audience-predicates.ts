import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The closed predicate catalogue an audience may be built from (ADR 0044,
 * `uz.horecaos.platform.marketing.domain.PredicateType`/`PredicateOperator`).
 *
 * Mirrored here rather than fetched, because it is exactly as closed on this
 * side as it is on the server's: ADR 0044 is explicit that extending it is a
 * schema and code change, never configuration, so there is nothing for an
 * endpoint to serve that would not immediately need a matching frontend
 * release anyway. Getting a type/operator pairing wrong here fails at
 * `AudiencePredicate`'s own constructor on submit — this catalogue exists so
 * an operator does not have to discover the pairing rules that way.
 */
export type PredicateValueKind = 'NUMERIC' | 'DATE_RANGE' | 'TEXT_SET' | 'AUDIENCE';

export interface PredicateTypeDescriptor {
  readonly type: string;
  readonly labelKey: MessageKey;
  readonly valueKind: PredicateValueKind;
  /** Fixed choices for a TEXT_SET predicate, or null when any short string is accepted. */
  readonly fixedValues: readonly string[] | null;
}

export const PREDICATE_TYPES: readonly PredicateTypeDescriptor[] = [
  {
    type: 'RECENCY_DAYS',
    labelKey: 'marketing.predicate.type.RECENCY_DAYS',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'ORDER_COUNT',
    labelKey: 'marketing.predicate.type.ORDER_COUNT',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'COMPLETED_ORDER_COUNT',
    labelKey: 'marketing.predicate.type.COMPLETED_ORDER_COUNT',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'NET_SPEND_MINOR',
    labelKey: 'marketing.predicate.type.NET_SPEND_MINOR',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'AVERAGE_CHECK_MINOR',
    labelKey: 'marketing.predicate.type.AVERAGE_CHECK_MINOR',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'ACQUISITION_CHANNEL',
    labelKey: 'marketing.predicate.type.ACQUISITION_CHANNEL',
    valueKind: 'TEXT_SET',
    fixedValues: null,
  },
  {
    type: 'REGISTERED_BETWEEN',
    labelKey: 'marketing.predicate.type.REGISTERED_BETWEEN',
    valueKind: 'DATE_RANGE',
    fixedValues: null,
  },
  {
    type: 'BIRTHDAY_WITHIN_DAYS',
    labelKey: 'marketing.predicate.type.BIRTHDAY_WITHIN_DAYS',
    valueKind: 'NUMERIC',
    fixedValues: null,
  },
  {
    type: 'PREFERRED_LOCALE',
    labelKey: 'marketing.predicate.type.PREFERRED_LOCALE',
    valueKind: 'TEXT_SET',
    fixedValues: ['ru', 'uz-Latn', 'en'],
  },
  {
    type: 'AUDIENCE_MEMBERSHIP',
    labelKey: 'marketing.predicate.type.AUDIENCE_MEMBERSHIP',
    valueKind: 'AUDIENCE',
    fixedValues: null,
  },
];

export function descriptorFor(type: string): PredicateTypeDescriptor {
  const found = PREDICATE_TYPES.find((candidate) => candidate.type === type);
  if (!found) {
    throw new RangeError(`${type} is not a predicate type this catalogue knows`);
  }
  return found;
}

/** The operators a value kind accepts — `PredicateType#allowedOperators`. */
export function operatorsFor(valueKind: PredicateValueKind): readonly string[] {
  switch (valueKind) {
    case 'NUMERIC':
      return ['AT_LEAST', 'AT_MOST', 'BETWEEN'];
    case 'DATE_RANGE':
      return ['BETWEEN'];
    case 'TEXT_SET':
    case 'AUDIENCE':
      return ['IN', 'NOT_IN'];
  }
}
