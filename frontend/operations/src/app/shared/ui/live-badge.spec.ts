import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { LiveBadge } from './live-badge';

function render(): ReturnType<typeof TestBed.createComponent<LiveBadge>> {
  return TestBed.createComponent(LiveBadge);
}

function badge(fixture: ReturnType<typeof TestBed.createComponent<LiveBadge>>): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    '[data-testid="q-live-badge"]',
  );
}

describe('LiveBadge', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders when the accelerator is connected', () => {
    const fixture = render();
    fixture.componentRef.setInput('state', 'open');
    fixture.detectChanges();

    expect(badge(fixture)).not.toBeNull();
    expect(badge(fixture)?.textContent).toContain('Live');
  });

  it('renders nothing for connecting, reconnecting, or unavailable — a quiet screen never claims to be live', () => {
    for (const state of ['connecting', 'reconnecting', 'unavailable'] as const) {
      const fixture = render();
      fixture.componentRef.setInput('state', state);
      fixture.detectChanges();

      expect(badge(fixture)).toBeNull();
    }
  });
});
