import { delivery } from '../../../testing/work-fixtures';
import { ApiError } from '../api/problem-details';
import {
  GATE_REFUSAL_REASONS,
  classifyStepFailure,
  describeSuccess,
  failedKinds,
} from './step-outcome';

function failure(init: {
  status: number;
  code: string;
  reason?: string | null;
  detail?: string;
}): ApiError {
  return new ApiError({ detail: 'x', ...init });
}

describe('classifying what the platform answered to a step', () => {
  describe('a refusal of a hard gate (409 GATE_FAILED)', () => {
    it('names the checks that failed, read from the detail the platform wrote', () => {
      const outcome = classifyStepFailure(
        failure({ status: 409, code: 'GATE_FAILED', detail: 'Failed checks: GEO, PHOTO' }),
      );

      expect(outcome).toEqual({ kind: 'gate-failed', failed: ['GEO', 'PHOTO'] });
    });

    it('is told apart from an ordinary conflict, which is the same status', () => {
      const outcome = classifyStepFailure(failure({ status: 409, code: 'RESOURCE_CONFLICT' }));

      expect(outcome.kind).not.toBe('gate-failed');
      expect(outcome.kind).toBe('failed');
    });

    it('is still a gate failure when the detail names no check the app knows', () => {
      expect(
        classifyStepFailure(failure({ status: 409, code: 'GATE_FAILED', detail: 'SOMETHING_NEW' })),
      ).toEqual({ kind: 'gate-failed', failed: [] });
    });

    it('reads the kinds however the platform separates them, once each', () => {
      expect(failedKinds('GEO,PAYMENT')).toEqual(['GEO', 'PAYMENT']);
      expect(failedKinds('checks [GEO; geo; PAYMENT]')).toEqual(['GEO', 'PAYMENT']);
      expect(failedKinds('GEOGRAPHY is not a check')).toEqual([]);
      expect(failedKinds('')).toEqual([]);
    });
  });

  describe('a refusal by a rule (422 with a reason)', () => {
    it('keeps every reason the platform can give, so the screen can say each in words', () => {
      for (const reason of GATE_REFUSAL_REASONS) {
        expect(
          classifyStepFailure(failure({ status: 422, code: 'UNPROCESSABLE_STATE', reason })),
        ).toEqual({ kind: 'refused', reason });
      }
    });

    it('carries a reason nobody here has heard of as an unknown refusal, not a crash', () => {
      expect(
        classifyStepFailure(
          failure({ status: 422, code: 'UNPROCESSABLE_STATE', reason: 'BRAND_NEW_RULE' }),
        ),
      ).toEqual({ kind: 'refused', reason: null });
    });

    it('is a refusal even with no reason at all', () => {
      expect(classifyStepFailure(failure({ status: 422, code: 'UNPROCESSABLE_STATE' }))).toEqual({
        kind: 'refused',
        reason: null,
      });
    });
  });

  it('says a stale version is stale, so the screen reloads rather than reports an error', () => {
    expect(classifyStepFailure(failure({ status: 409, code: 'STALE_VERSION' })).kind).toBe('stale');
    expect(classifyStepFailure(failure({ status: 412, code: 'STALE_VERSION' })).kind).toBe('stale');
  });

  it('treats a key the platform saw with another body as a change under the courier: reload, do not report an error', () => {
    expect(classifyStepFailure(failure({ status: 409, code: 'IDEMPOTENCY_KEY_REUSED' })).kind).toBe(
      'stale',
    );
  });

  it('carries anything else as a failure with the error, including a request that never arrived', () => {
    const offline = failure({ status: 0, code: 'NETWORK' });

    const outcome = classifyStepFailure(offline);

    expect(outcome).toEqual({ kind: 'failed', error: offline });
  });

  it('accepts a thrown value that is not an ApiError', () => {
    expect(classifyStepFailure(new Error('boom')).kind).toBe('failed');
  });
});

describe('describing a step that worked', () => {
  it('is done with no checks when the platform reports none', () => {
    const outcome = describeSuccess(delivery({ status: 'PICKED_UP' }));

    expect(outcome).toEqual({
      kind: 'done',
      delivery: delivery({ status: 'PICKED_UP' }),
      checks: [],
      unconfirmed: [],
    });
  });

  it('carries the checks a soft gate reports, and which of them the platform could not confirm', () => {
    const result = {
      ...delivery({ status: 'DELIVERED' }),
      checks: [
        { kind: 'GEO', outcome: 'FAILED' },
        { kind: 'PHOTO', outcome: 'SKIPPED' },
        { kind: 'PAYMENT', outcome: 'PASSED' },
      ],
    } as const;

    const outcome = describeSuccess(result);

    expect(outcome.kind).toBe('done');
    expect(outcome.checks).toHaveLength(3);
    expect(outcome.unconfirmed).toEqual(['GEO', 'PHOTO']);
  });

  it('has nothing unconfirmed when every check passed', () => {
    const outcome = describeSuccess({
      ...delivery(),
      checks: [{ kind: 'GEO', outcome: 'PASSED' }],
    });

    expect(outcome.unconfirmed).toEqual([]);
  });

  it('ignores a checks field that is not a list, rather than trusting it', () => {
    const outcome = describeSuccess({ ...delivery(), checks: 'GEO' } as never);

    expect(outcome.checks).toEqual([]);
  });
});
