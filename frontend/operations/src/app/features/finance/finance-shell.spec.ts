import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { FinanceShell } from './finance-shell';

/** A stand-in for whatever screen the outlet renders — the shell's own tabs are what this file tests. */
@Component({ selector: 'q-stub', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class StubScreen {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FinanceShell', () => {
  it('renders a sub-nav link for every Finance screen this app builds — 8.1/8.2 (tier P) plus 8.3-8.6 (tier 2, wave 39)', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'finance',
            component: FinanceShell,
            children: [{ path: 'payments', component: StubScreen }],
          },
        ]),
      ],
    });
    TestBed.inject(I18n).setLocale('ru');

    const harness = await RouterTestingHarness.create('/finance/payments');
    await flushMicrotasks();

    const labels = [...harness.routeNativeElement!.querySelectorAll('.finance-shell__tab')].map(
      (el) => el.textContent?.trim(),
    );
    expect(labels).toEqual([
      'Платежи и расчёты',
      'Фискализация',
      'Инкассация',
      'Стоимость доставки',
      'Выплаты курьерам',
      'Подписка',
    ]);
  });
});
