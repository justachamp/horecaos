import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { RefreshIndicator } from './refresh-indicator';

function render(): ReturnType<typeof TestBed.createComponent<RefreshIndicator>> {
  return TestBed.createComponent(RefreshIndicator);
}

describe('RefreshIndicator', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('shows no stamp when nothing has been fetched yet', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', null);
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-refresh-indicator"]')
        ?.textContent,
    ).not.toContain('Updated');
  });

  it('shows the HH:mm:ss stamp once a fetch has landed', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', new Date('2026-09-14T09:05:30Z'));
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-refresh-indicator"]')
        ?.textContent,
    ).toContain('Updated');
  });

  it('emits refresh when the manual control is clicked', () => {
    const fixture = render();
    fixture.componentRef.setInput('updatedAt', new Date());
    fixture.detectChanges();
    let refreshed = 0;
    fixture.componentInstance.refresh.subscribe(() => (refreshed += 1));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-refresh-indicator-button"]',
      ) as HTMLButtonElement
    ).click();

    expect(refreshed).toBe(1);
  });
});
