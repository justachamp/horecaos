import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CustomersShell } from './customers-shell';

/** A stand-in for whatever screen the outlet renders — the shell's own tabs are what this file tests. */
@Component({ selector: 'q-stub', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class StubScreen {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CustomersShell', () => {
  it('renders a tab for every Customers section screen, including wave 44’s Feedback settings', async () => {
    TestBed.configureTestingModule({
      providers: [
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

    const harness = await RouterTestingHarness.create('/customers');
    await flushMicrotasks();

    const labels = [...harness.routeNativeElement!.querySelectorAll('.shell__tab')].map((el) =>
      el.textContent?.trim(),
    );
    // 5.3 Segments and 5.4 Reviews (wave 39), plus 5.5 Feedback settings —
    // the last tier-3 Customers row, built in wave 44 alongside Marketing
    // §6.3 Loyalty.
    expect(labels).toEqual(['Клиенты', 'Сегменты', 'Отзывы', 'Настройки отзывов']);
  });
});
