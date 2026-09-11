import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DEFAULT_TOAST_TIMEOUT_MS, Toasts } from './toast';
import { ToastHost } from './toast-host';

describe('Toasts and ToastHost', () => {
  let toasts: Toasts;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    toasts = TestBed.inject(Toasts);
  });

  afterEach(() => {
    vi.useRealTimers();
    toasts.clear();
  });

  function render(): ReturnType<typeof TestBed.createComponent<ToastHost>> {
    const fixture = TestBed.createComponent(ToastHost);
    fixture.detectChanges();
    return fixture;
  }

  it('renders both live regions before anything is announced', () => {
    // The assertion that matters: a live region created at the moment it gains
    // content is frequently never announced, so both must exist from first
    // paint, empty.
    const host: HTMLElement = render().nativeElement;

    expect(host.querySelector('[data-testid="q-toast-alerts"]')?.getAttribute('role')).toBe(
      'alert',
    );
    const statuses = host.querySelector('[data-testid="q-toast-statuses"]')!;
    expect(statuses.getAttribute('role')).toBe('status');
    expect(statuses.getAttribute('aria-live')).toBe('polite');
    expect(host.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(0);
  });

  it('puts a success announcement in the polite region and a failure in the assertive one', () => {
    const fixture = render();
    toasts.show({ message: 'Customer created', tone: 'success' });
    toasts.show({ message: 'Could not save', tone: 'error' });
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="q-toast-statuses"]')?.textContent).toContain(
      'Customer created',
    );
    expect(host.querySelector('[data-testid="q-toast-statuses"]')?.textContent).not.toContain(
      'Could not save',
    );
    expect(host.querySelector('[data-testid="q-toast-alerts"]')?.textContent).toContain(
      'Could not save',
    );
  });

  it('dismisses itself after its timeout', () => {
    vi.useFakeTimers();
    const fixture = render();
    toasts.show({ message: 'Order updated', tone: 'success' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(1);

    vi.advanceTimersByTime(DEFAULT_TOAST_TIMEOUT_MS);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(0);
  });

  it('pins a toast asked to stay, rather than treating zero as "immediately"', () => {
    vi.useFakeTimers();
    const fixture = render();
    toasts.show({ message: 'Import running', timeoutMs: 0 });
    fixture.detectChanges();

    vi.advanceTimersByTime(60_000);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(1);
  });

  it('dismisses on the operator’s own control', () => {
    const fixture = render();
    toasts.show({ message: 'Order updated', tone: 'success' });
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="q-toast-dismiss"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(0);
  });

  it('keeps two announcements apart rather than replacing the first', () => {
    const fixture = render();
    toasts.show({ message: 'First', tone: 'success' });
    toasts.show({ message: 'Second', tone: 'success' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]')).toHaveLength(2);
  });

  it('ignores a dismissal for a toast that has already gone', () => {
    const id = toasts.show({ message: 'Gone', timeoutMs: 0 });
    toasts.dismiss(id);
    toasts.dismiss(id);

    expect(toasts.visible()).toHaveLength(0);
  });
});
