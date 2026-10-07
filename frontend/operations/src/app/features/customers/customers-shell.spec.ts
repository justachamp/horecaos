import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { CustomersShell } from './customers-shell';

/** A stand-in for whatever screen the outlet renders — the shell's own tabs are what this file tests. */
@Component({ selector: 'q-stub', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class StubScreen {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(held: readonly string[]): void {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: SessionCapabilities,
        useValue: { has: (capability: string) => held.includes(capability) },
      },
      provideRouter([
        {
          path: 'customers',
          component: CustomersShell,
          children: [{ path: '', component: StubScreen }],
        },
      ]),
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
}

async function tabLabels(): Promise<(string | undefined)[]> {
  const harness = await RouterTestingHarness.create('/customers');
  await flushMicrotasks();
  return [...harness.routeNativeElement!.querySelectorAll('.shell__tab')].map((el) =>
    el.textContent?.trim(),
  );
}

describe('CustomersShell', () => {
  it('renders a tab for every Customers section screen, including wave 44’s Feedback settings', async () => {
    configure([]);

    // 5.3 Segments and 5.4 Reviews (wave 39), plus 5.5 Feedback settings —
    // the last tier-3 Customers row, built in wave 44 alongside Marketing
    // §6.3 Loyalty.
    expect(await tabLabels()).toEqual(['Клиенты', 'Сегменты', 'Отзывы', 'Настройки отзывов']);
  });

  it('offers the callback queue only to an operator who holds customer.lead.read (ADR 0111)', async () => {
    configure(['CUSTOMER_LEAD_READ']);

    expect(await tabLabels()).toEqual([
      'Клиенты',
      'Обратные звонки',
      'Сегменты',
      'Отзывы',
      'Настройки отзывов',
    ]);
  });
});
