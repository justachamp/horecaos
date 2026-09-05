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
  it('renders a sub-nav link for every tier-2 Marketing screen, plus wave 44’s Loyalty and wave 47’s Referrals tabs', async () => {
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
    // §6.3 Loyalty was tier 3 and stayed out through wave 39; wave 44 built it
    // (ADR 0046) alongside Customers §5.5, so it now has a tab like every
    // tier-2 row. §6.6 Referrals was the other tier-3 row still out; wave 47
    // built its reward half (a new ADR), so it now has a tab too.
    expect(labels).toEqual([
      'Промоакции',
      'Промокоды',
      'Лояльность',
      'Рефералы',
      'Кампании',
      'Автоматизации',
      'Контент',
      'Витрина',
    ]);
  });
});
