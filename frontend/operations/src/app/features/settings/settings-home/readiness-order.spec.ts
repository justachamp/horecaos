import { describe, expect, it } from 'vitest';

import { ValidationResult } from './readiness-api';
import { countByTier, orderFindings, tierOf } from './readiness-order';

function finding(
  errorCode: string,
  detail: string,
  extra: Partial<ValidationResult> = {},
): ValidationResult {
  return {
    stepKey: `${errorCode}_VALIDATE`,
    passed: false,
    errorCode,
    detail,
    locationId: null,
    ...extra,
  };
}

describe('tierOf', () => {
  it('reads the severity the server names', () => {
    expect(tierOf(finding('A', 'a', { severity: 'EXPIRING', advisory: true }))).toBe('EXPIRING');
    expect(tierOf(finding('A', 'a', { severity: 'ADVISORY', advisory: true }))).toBe('ADVISORY');
    expect(tierOf(finding('A', 'a', { severity: 'BLOCKING', advisory: false }))).toBe('BLOCKING');
  });

  it('falls back to the two tiers an older server knew when it sends no severity', () => {
    expect(tierOf(finding('A', 'a', { advisory: true }))).toBe('ADVISORY');
    expect(tierOf(finding('A', 'a', { advisory: false }))).toBe('BLOCKING');
    expect(tierOf(finding('A', 'a'))).toBe('BLOCKING');
  });
});

describe('orderFindings', () => {
  it('puts blocking before expiring before advisory whatever order the server named them in', () => {
    const advisory = finding('ADV', 'a', { severity: 'ADVISORY', advisory: true });
    const expiring = finding('EXP', 'e', { severity: 'EXPIRING', advisory: true });
    const blocking = finding('BLK', 'b', { severity: 'BLOCKING' });

    expect(orderFindings([advisory, expiring, blocking])).toEqual([blocking, expiring, advisory]);
  });

  it('puts the condition with the most offending items first inside a tier', () => {
    const one = finding('ONE', 'one');
    const twoA = finding('TWO', 'two-a');
    const threeA = finding('THREE', 'three-a');
    const twoB = finding('TWO', 'two-b');
    const threeB = finding('THREE', 'three-b');
    const threeC = finding('THREE', 'three-c');

    const ordered = orderFindings([one, twoA, threeA, twoB, threeB, threeC]);

    expect(ordered.map((f) => f.detail)).toEqual([
      'three-a',
      'three-b',
      'three-c',
      'two-a',
      'two-b',
      'one',
    ]);
  });

  it('never lets a big advisory group outrank a small blocking one', () => {
    const blocking = finding('BLK', 'b');
    const advisory = ['x', 'y', 'z'].map((d) =>
      finding('ADV', d, { severity: 'ADVISORY', advisory: true }),
    );

    expect(orderFindings([...advisory, blocking])[0]).toBe(blocking);
  });

  it('keeps the server order for conditions with the same count, and for items inside one condition', () => {
    const first = finding('FIRST', '1');
    const second = finding('SECOND', '2');
    const third = finding('THIRD', '3');

    expect(orderFindings([second, first, third]).map((f) => f.errorCode)).toEqual([
      'SECOND',
      'FIRST',
      'THIRD',
    ]);
  });

  it('counts a finding with no error code under its step, and does not mutate its input', () => {
    const a = { ...finding('X', 'a'), errorCode: null, stepKey: 'STEP_A' };
    const b = { ...finding('X', 'b'), errorCode: null, stepKey: 'STEP_B' };
    const c = { ...finding('X', 'c'), errorCode: null, stepKey: 'STEP_B' };
    const input = [a, b, c];

    expect(orderFindings(input)).toEqual([b, c, a]);
    expect(input).toEqual([a, b, c]);
  });
});

describe('countByTier', () => {
  it('counts each tier', () => {
    expect(
      countByTier([
        finding('A', 'a'),
        finding('B', 'b', { severity: 'EXPIRING', advisory: true }),
        finding('C', 'c', { advisory: true }),
        finding('D', 'd', { advisory: true }),
      ]),
    ).toEqual({ blocking: 1, expiring: 1, advisory: 2 });
  });
});
