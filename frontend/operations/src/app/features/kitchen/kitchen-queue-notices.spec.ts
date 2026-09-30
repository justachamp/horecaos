import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { KitchenQueueNotices } from './kitchen-queue-notices';

function render(
  overrides: {
    wiringWarning?: boolean;
    actionNotice?: string | null;
    lastError?: { code: string } | null;
  } = {},
) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(KitchenQueueNotices);
  fixture.componentRef.setInput('wiringWarning', overrides.wiringWarning ?? false);
  fixture.componentRef.setInput('actionNotice', overrides.actionNotice ?? null);
  fixture.componentRef.setInput('lastError', overrides.lastError ?? null);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host };
}

describe('KitchenQueueNotices', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('draws nothing when there is nothing to say', () => {
    const { host } = render();

    expect(host.querySelector('[role]')).toBeNull();
  });

  it('warns that the board is not wired, as a status', () => {
    const { host } = render({ wiringWarning: true });

    const band = host.querySelector('.kitchen__warning-band');
    expect(band?.getAttribute('role')).toBe('status');
    expect(band?.textContent?.trim()).not.toBe('');
  });

  it('shows a refused action as an alert that can be dismissed', () => {
    const { fixture, host } = render({ actionNotice: 'Already accepted elsewhere' });
    let dismissed = 0;
    fixture.componentInstance.noticeDismissed.subscribe(() => dismissed++);

    const band = host.querySelector('.kitchen__notice-band');
    expect(band?.getAttribute('role')).toBe('alert');
    expect(band?.textContent).toContain('Already accepted elsewhere');
    host.querySelector<HTMLButtonElement>('.kitchen__notice-dismiss')?.click();

    expect(dismissed).toBe(1);
  });

  it('shows only the stable code of a load failure', () => {
    const { host } = render({ lastError: { code: 'NETWORK_UNREACHABLE' } });

    const band = host.querySelector('.kitchen__error-band');
    expect(band?.getAttribute('role')).toBe('alert');
    expect(band?.textContent?.trim()).toBe('NETWORK_UNREACHABLE');
  });

  it('can show all three at once, in order', () => {
    const { host } = render({
      wiringWarning: true,
      actionNotice: 'x',
      lastError: { code: 'E' },
    });

    expect([...host.querySelectorAll('[role]')].map((band) => band.className)).toEqual([
      'kitchen__warning-band',
      'kitchen__notice-band',
      'kitchen__error-band',
    ]);
  });
});
