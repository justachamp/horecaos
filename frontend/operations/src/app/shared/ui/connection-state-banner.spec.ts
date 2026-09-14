import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ConnectionStateBanner } from './connection-state-banner';

function render(): ReturnType<typeof TestBed.createComponent<ConnectionStateBanner>> {
  return TestBed.createComponent(ConnectionStateBanner);
}

function bannerEl(
  fixture: ReturnType<typeof TestBed.createComponent<ConnectionStateBanner>>,
): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    '[data-testid="q-connection-state-banner"]',
  );
}

describe('ConnectionStateBanner', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders nothing for connecting or open — the ordinary states', () => {
    for (const state of ['connecting', 'open'] as const) {
      const fixture = render();
      fixture.componentRef.setInput('state', state);
      fixture.detectChanges();

      expect(bannerEl(fixture)).toBeNull();
    }
  });

  it('shows a quiet, non-interrupting notice while reconnecting', () => {
    const fixture = render();
    fixture.componentRef.setInput('state', 'reconnecting');
    fixture.detectChanges();

    const el = bannerEl(fixture);
    expect(el).not.toBeNull();
    expect(el?.getAttribute('role')).toBe('status');
    expect(el?.textContent).toContain('Reconnecting');
  });

  it('names the poll fallback once the accelerator is unavailable', () => {
    const fixture = render();
    fixture.componentRef.setInput('state', 'unavailable');
    fixture.detectChanges();

    expect(bannerEl(fixture)?.textContent).toContain('10 seconds');
  });
});
