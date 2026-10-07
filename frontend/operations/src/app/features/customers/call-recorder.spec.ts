import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CallRecorder } from './call-recorder';
import { RecordContactAttemptRequest } from './leads-api';

describe('CallRecorder', () => {
  let fixture: ComponentFixture<CallRecorder>;
  let host: HTMLElement;
  let submitted: RecordContactAttemptRequest[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CallRecorder] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CallRecorder);
    submitted = [];
    fixture.componentInstance.submitted.subscribe((request) => submitted.push(request));
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function choose(testId: string, value: string): void {
    const select = host.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function submit(): void {
    (host.querySelector('[data-testid="recorder-submit"]') as HTMLButtonElement).click();
  }

  it('records an outgoing call that connected, with no reason and no next step', () => {
    submit();

    expect(submitted).toEqual([
      {
        direction: 'OUTBOUND',
        outcome: 'CONNECTED',
        attemptId: expect.any(String),
        blockingReason: undefined,
        nextAction: undefined,
        nextActionAt: undefined,
      },
    ]);
  });

  it('names one attempt for one form, so a submit retried after a lost answer is not a second call', () => {
    submit();
    submit();

    expect(submitted).toHaveLength(2);
    const [first, second] = submitted;
    expect(first.attemptId).toMatch(/^[0-9a-f-]{36}$/);
    expect(second.attemptId).toBe(first.attemptId);
  });

  it('names a different attempt for the next form', async () => {
    submit();
    const other = TestBed.createComponent(CallRecorder);
    const otherSubmitted: RecordContactAttemptRequest[] = [];
    other.componentInstance.submitted.subscribe((request) => otherSubmitted.push(request));
    other.detectChanges();
    (
      (other.nativeElement as HTMLElement).querySelector(
        '[data-testid="recorder-submit"]',
      ) as HTMLButtonElement
    ).click();

    expect(otherSubmitted).toHaveLength(1);
    expect(otherSubmitted[0].attemptId).not.toBe(submitted[0].attemptId);
  });

  it('asks why only for an attempt that was refused, and sends the reason with it', () => {
    expect(host.querySelector('[data-testid="recorder-blocking-reason"]')).toBeNull();

    choose('recorder-outcome', 'BLOCKED');
    expect(host.querySelector('[data-testid="recorder-blocking-reason"]')).not.toBeNull();
    choose('recorder-blocking-reason', 'WRONG_NUMBER');
    submit();

    expect(submitted[0]).toMatchObject({ outcome: 'BLOCKED', blockingReason: 'WRONG_NUMBER' });
  });

  it('sends no reason for a call that was made, even after one was picked for a refused one', () => {
    choose('recorder-outcome', 'BLOCKED');
    choose('recorder-blocking-reason', 'NO_CONSENT');
    choose('recorder-outcome', 'NO_ANSWER');
    submit();

    expect(submitted[0].blockingReason).toBeUndefined();
    expect(submitted[0].outcome).toBe('NO_ANSWER');
  });

  it('asks when the next step is to happen only once there is a next step, and sends it as an instant', () => {
    expect(host.querySelector('[data-testid="recorder-next-action-at"]')).toBeNull();

    choose('recorder-next-action', 'CALL_AGAIN');
    const at = host.querySelector('[data-testid="recorder-next-action-at"]') as HTMLInputElement;
    at.value = '2026-10-08T15:00';
    at.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    submit();

    expect(submitted[0].nextAction).toBe('CALL_AGAIN');
    expect(submitted[0].nextActionAt).toBe(new Date('2026-10-08T15:00').toISOString());
  });

  it('records an incoming call', () => {
    choose('recorder-direction', 'INBOUND');
    submit();

    expect(submitted[0].direction).toBe('INBOUND');
  });
});
