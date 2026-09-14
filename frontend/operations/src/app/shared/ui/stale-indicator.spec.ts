import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { StaleIndicator } from './stale-indicator';

function render(): ReturnType<typeof TestBed.createComponent<StaleIndicator>> {
  return TestBed.createComponent(StaleIndicator);
}

function marker(
  fixture: ReturnType<typeof TestBed.createComponent<StaleIndicator>>,
): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    '[data-testid="q-stale-indicator"]',
  );
}

describe('StaleIndicator', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-14T09:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders nothing when there is no updatedAt at all', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', null);
    fixture.detectChanges();

    expect(marker(fixture)).toBeNull();
  });

  it('renders nothing just after a fetch, before the age threshold', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', new Date('2026-09-14T09:00:00Z'));
    fixture.detectChanges();

    expect(marker(fixture)).toBeNull();
  });

  it('marks itself stale once the board has stopped updating past the threshold — a `computed()` alone would never notice', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', new Date('2026-09-14T09:00:00Z'));
    fixture.componentRef.setInput('ageThresholdMs', 30_000);
    fixture.detectChanges();
    expect(marker(fixture)).toBeNull();

    // Nothing re-fetches, nothing sets a new input — wall-clock time alone
    // is what crosses the threshold, which only the internal timer can see.
    vi.advanceTimersByTime(30_000);
    fixture.detectChanges();

    expect(marker(fixture)).not.toBeNull();
    expect(marker(fixture)?.textContent).toContain('out of date');
  });
});
