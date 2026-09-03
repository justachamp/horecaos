import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MarketingShell } from './marketing-shell';

/** A stand-in for whatever screen the outlet renders — the shell's own tabs are what this file tests. */
@Component({ selector: 'q-stub', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class StubScreen {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('MarketingShell', () => {
  it('renders a sub-nav link for every tier-2 Marketing screen, tier-3 excluded', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'marketing',
            component: MarketingShell,
            children: [{ path: 'campaigns', component: StubScreen }],
          },
        ]),
      ],
    });
    TestBed.inject(I18n).setLocale('ru');

    const harness = await RouterTestingHarness.create('/marketing/campaigns');
    await flushMicrotasks();

    const labels = [...harness.routeNativeElement!.querySelectorAll('.marketing-shell__tab')].map(
      (el) => el.textContent?.trim(),
    );
    // §6.3 Loyalty and §6.6 Referrals are tier 3 and carry no tab at all — the
    // same "tier 3 stays out" rule Finance's own shell test proves for its
    // Wave-2 rows.
    expect(labels).toEqual([
      'Промоакции',
      'Промокоды',
      'Кампании',
      'Автоматизации',
      'Контент',
      'Витрина',
    ]);
  });
});
