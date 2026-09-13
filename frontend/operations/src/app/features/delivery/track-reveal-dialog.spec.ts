import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { TrackRevealDialog, TrackRevealSubmission } from './track-reveal-dialog';

/** Same `setInput` rationale as `order-reason-dialog.spec.ts` — this app has no `NgZone`. */
function render(): { fixture: ReturnType<typeof TestBed.createComponent<TrackRevealDialog>> } {
  const fixture = TestBed.createComponent(TrackRevealDialog);
  fixture.componentRef.setInput('courierId', 'courier-1');
  fixture.detectChanges();
  return { fixture };
}

function setField(host: HTMLElement, testId: string, value: string): void {
  const el = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement | HTMLTextAreaElement;
  el.value = value;
  el.dispatchEvent(new Event('input'));
}

describe('TrackRevealDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('refuses to submit a purpose shorter than the backend’s own minimum', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: TrackRevealSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    setField(host, 'track-reveal-dialog-purpose', 'too short');
    (host.querySelector('[data-testid="track-reveal-dialog-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="track-reveal-dialog-purpose-error"]')).not.toBeNull();
    expect(submissions).toEqual([]);
  });

  it('refuses a window whose end is not after its start', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: TrackRevealSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    setField(host, 'track-reveal-dialog-purpose', 'Customer says the order never arrived');
    setField(host, 'track-reveal-dialog-from', '2026-09-12T12:00');
    setField(host, 'track-reveal-dialog-to', '2026-09-12T11:00');
    (host.querySelector('[data-testid="track-reveal-dialog-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="track-reveal-dialog-window-error"]')).not.toBeNull();
    expect(submissions).toEqual([]);
  });

  it('emits the trimmed purpose and an ISO window on confirm', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: TrackRevealSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    setField(host, 'track-reveal-dialog-purpose', '  Customer says the order never arrived  ');
    setField(host, 'track-reveal-dialog-from', '2026-09-12T10:00');
    setField(host, 'track-reveal-dialog-to', '2026-09-12T11:00');
    (host.querySelector('[data-testid="track-reveal-dialog-confirm"]') as HTMLButtonElement).click();

    expect(submissions).toHaveLength(1);
    expect(submissions[0].purpose).toBe('Customer says the order never arrived');
    expect(new Date(submissions[0].to).getTime()).toBeGreaterThan(
      new Date(submissions[0].from).getTime(),
    );
  });

  it('shows a passed-in error and stays open rather than closing on failure', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('errorText', 'Something went wrong. Reference ABC123.');
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="track-reveal-dialog-error"]')?.textContent,
    ).toContain('ABC123');
  });

  it('disables the form and blocks Escape while busy', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.every((b) => b.disabled)).toBe(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(dismissed).toBe(false);
  });
});
