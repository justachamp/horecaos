import { describe, expect, it } from 'vitest';

import {
  ConditionRow,
  ConditionTypeDescriptor,
  clockToMinutes,
  conditionRowIsWorkable,
  emptyConditionRow,
  evaluateConditionRow,
  minutesToClock,
  operatorsForDescriptor,
  operatorsForValueKind,
} from './condition-types';

function row(overrides: Partial<ConditionRow>): ConditionRow {
  return {
    id: 'r',
    type: 'T',
    operator: 'BETWEEN',
    numericLow: '',
    numericHigh: '',
    dateLow: '',
    dateHigh: '',
    textValues: '',
    dayOfWeekValues: [],
    ...overrides,
  };
}

describe('clock helpers', () => {
  it('round-trips a minute of the day through HH:mm', () => {
    expect(minutesToClock('0')).toBe('00:00');
    expect(minutesToClock('720')).toBe('12:00');
    expect(minutesToClock('1439')).toBe('23:59');
    expect(minutesToClock('1440')).toBe('24:00');
    expect(clockToMinutes('12:00')).toBe('720');
    expect(clockToMinutes('9:05')).toBe('545');
    expect(clockToMinutes('24:00')).toBe('1440');
  });

  it('treats an unreadable value as empty rather than guessing', () => {
    expect(minutesToClock('')).toBe('');
    expect(minutesToClock('abc')).toBe('');
    expect(clockToMinutes('')).toBe('');
    expect(clockToMinutes('noon')).toBe('');
    expect(clockToMinutes('25:00')).toBe('');
  });
});

describe('TIME_RANGE', () => {
  const lunch = row({ numericLow: '720', numericHigh: '900' });

  it('includes its start minute and excludes its end minute, as the engine does', () => {
    expect(evaluateConditionRow(lunch, 'TIME_RANGE', 720)).toBe(true);
    expect(evaluateConditionRow(lunch, 'TIME_RANGE', 899)).toBe(true);
    expect(evaluateConditionRow(lunch, 'TIME_RANGE', 900)).toBe(false);
    expect(evaluateConditionRow(lunch, 'TIME_RANGE', 719)).toBe(false);
  });

  it('runs a window that ends before it starts through midnight', () => {
    const night = row({ numericLow: '1320', numericHigh: '120' });
    expect(evaluateConditionRow(night, 'TIME_RANGE', 1380)).toBe(true);
    expect(evaluateConditionRow(night, 'TIME_RANGE', 60)).toBe(true);
    expect(evaluateConditionRow(night, 'TIME_RANGE', 600)).toBe(false);
  });

  it('is workable only with both ends and never matches without a candidate', () => {
    expect(conditionRowIsWorkable(row({ numericLow: '720' }), 'TIME_RANGE')).toBe(false);
    expect(conditionRowIsWorkable(lunch, 'TIME_RANGE')).toBe(true);
    expect(evaluateConditionRow(lunch, 'TIME_RANGE', null)).toBe(false);
  });
});

describe('FLAG', () => {
  it('holds only for a candidate that says yes', () => {
    const flag = row({ operator: 'EQUALS' });
    expect(evaluateConditionRow(flag, 'FLAG', '1')).toBe(true);
    expect(evaluateConditionRow(flag, 'FLAG', 'true')).toBe(true);
    expect(evaluateConditionRow(flag, 'FLAG', '')).toBe(false);
    expect(evaluateConditionRow(flag, 'FLAG', '0')).toBe(false);
    expect(evaluateConditionRow(flag, 'FLAG', undefined)).toBe(false);
  });
});

describe('operatorsForDescriptor', () => {
  const base: ConditionTypeDescriptor = {
    type: 'SUBTOTAL',
    labelKey: 'ui.conditionBuilder.addCondition',
    valueKind: 'MONEY_MINOR',
  };

  it('falls back to the value kind, and honours a narrower list', () => {
    expect(operatorsForDescriptor(base)).toEqual(operatorsForValueKind('MONEY_MINOR'));
    expect(operatorsForDescriptor({ ...base, operators: ['AT_LEAST'] })).toEqual(['AT_LEAST']);
  });

  it('seeds a new row with the first operator of the restricted list', () => {
    const catalogue: readonly ConditionTypeDescriptor[] = [
      { ...base, valueKind: 'REFERENCE', operators: ['NOT_IN', 'IN'] },
    ];
    expect(emptyConditionRow(catalogue).operator).toBe('NOT_IN');
  });
});
