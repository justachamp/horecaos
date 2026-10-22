import { TestBed } from '@angular/core/testing';

import { delivery } from '../../../testing/work-fixtures';
import { ApiError } from '../api/problem-details';
import { I18n } from '../i18n/i18n';
import type { OfferOutcome } from '../offers/offers.store';
import { GATE_REFUSAL_REASONS, type StepOutcome } from './step-outcome';
import { stepMessage } from './step-message';

describe('saying what became of a step', () => {
  let i18n: I18n;

  beforeEach(async () => {
    i18n = TestBed.inject(I18n);
    await i18n.require(['work', 'offers', 'deliveries']);
    await i18n.use('en');
  });

  const done = (
    checks: { kind: 'GEO' | 'PHOTO' | 'PAYMENT'; outcome: 'PASSED' | 'FAILED' | 'SKIPPED' }[] = [],
  ): StepOutcome => ({
    kind: 'done',
    delivery: delivery(),
    checks,
    unconfirmed: checks.filter((c) => c.outcome !== 'PASSED').map((c) => c.kind),
  });

  it('says nothing for no outcome, or for one that needs no words', () => {
    expect(stepMessage(i18n, null)).toBeNull();
    expect(stepMessage(i18n, { kind: 'revealed' })).toBeNull();
  });

  describe('a rule that refused', () => {
    it('has a sentence for every reason the platform can give, none of them the unknown one', () => {
      const unknown = stepMessage(i18n, { kind: 'refused', reason: null })!.text;
      for (const reason of GATE_REFUSAL_REASONS) {
        const message = stepMessage(i18n, { kind: 'refused', reason })!;
        expect(message.tone, reason).toBe('error');
        expect(message.text, reason).not.toBe(unknown);
        expect(message.text, reason).not.toContain('work.refusal');
      }
    });

    it('says plainly what to do about the position', () => {
      expect(
        stepMessage(i18n, { kind: 'refused', reason: 'GPS_POSITION_REQUIRED' })!.text,
      ).toContain('Turn on location');
      expect(stepMessage(i18n, { kind: 'refused', reason: 'TOO_FAR_FROM_PICKUP' })!.text).toContain(
        'Go closer',
      );
    });

    it('has a sentence even for a reason this build has never heard of', () => {
      expect(stepMessage(i18n, { kind: 'refused', reason: null })!.text).toContain(
        'did not accept this step',
      );
    });
  });

  describe('a hard gate that refused (409 GATE_FAILED)', () => {
    it('names the checks in the courier’s words', () => {
      const message = stepMessage(i18n, { kind: 'gate-failed', failed: ['GEO', 'PHOTO'] })!;

      expect(message.tone).toBe('error');
      expect(message.text).toBe(
        'This step was refused. These checks could not be confirmed: location, photo.',
      );
    });

    it('still refuses in words when the platform named no check', () => {
      const message = stepMessage(i18n, { kind: 'gate-failed', failed: [] })!;

      expect(message.text).toContain('a check could not be confirmed');
    });
  });

  describe('a soft gate that let the step through', () => {
    it('tells the courier what the platform could not confirm, as a warning not an error', () => {
      const message = stepMessage(
        i18n,
        done([
          { kind: 'GEO', outcome: 'FAILED' },
          { kind: 'PAYMENT', outcome: 'PASSED' },
        ]),
      )!;

      expect(message.tone).toBe('warning');
      expect(message.text).toBe(
        'Done. The platform could not confirm: location. Your dispatcher can see this.',
      );
    });

    it('says every check passed when they did', () => {
      const message = stepMessage(i18n, done([{ kind: 'GEO', outcome: 'PASSED' }]))!;

      expect(message.tone).toBe('info');
      expect(message.text).toBe('Every check passed.');
    });

    it('has nothing to add when the platform reports no checks', () => {
      expect(stepMessage(i18n, done())).toBeNull();
    });
  });

  it('says a stale step changed under the courier, as a warning', () => {
    expect(stepMessage(i18n, { kind: 'stale' })).toEqual({
      tone: 'warning',
      text: 'This changed while you were looking. The latest is shown.',
    });
  });

  it('says the connection’s sentence for a request that never arrived, and the generic one otherwise', () => {
    const offline: StepOutcome = {
      kind: 'failed',
      error: new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }),
    };
    const broken: StepOutcome = {
      kind: 'failed',
      error: new ApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'stack trace' }),
    };

    expect(stepMessage(i18n, offline)!.text).toContain('Cannot reach the server');
    expect(stepMessage(i18n, broken)!.text).toContain('Something went wrong');
    expect(stepMessage(i18n, broken)!.text).not.toContain('stack trace');
  });

  it('says an offer that is gone or declined in the offers’ own words', () => {
    const gone: OfferOutcome = { kind: 'gone' };
    const declined: OfferOutcome = { kind: 'declined' };

    expect(stepMessage(i18n, gone)).toEqual({
      tone: 'warning',
      text: 'That offer is no longer available.',
    });
    expect(stepMessage(i18n, declined)).toEqual({ tone: 'info', text: 'Offer declined.' });
  });

  it('speaks Russian and Uzbek too, with the same checks named', async () => {
    await i18n.use('ru');
    expect(stepMessage(i18n, { kind: 'gate-failed', failed: ['GEO'] })!.text).toBe(
      'Шаг отклонён. Не удалось подтвердить: местоположение.',
    );
    await i18n.use('uz-Latn');
    expect(stepMessage(i18n, { kind: 'gate-failed', failed: ['PHOTO'] })!.text).toBe(
      'Qadam rad etildi. Tasdiqlab boʻlmadi: surat.',
    );
  });
});
