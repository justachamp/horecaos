import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CatalogShell } from './catalog-shell';

/** A stand-in for whatever screen the outlet renders — the shell's own tabs are what this file tests. */
@Component({ selector: 'q-stub', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class StubScreen {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CatalogShell', () => {
  it('renders a sub-nav link for each built Catalog screen', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'catalog',
            component: CatalogShell,
            children: [{ path: 'products', component: StubScreen }],
          },
        ]),
      ],
    });
    TestBed.inject(I18n).setLocale('ru');

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();

    const labels = [...harness.routeNativeElement!.querySelectorAll('.catalog-shell__tab')].map(
      (el) => el.textContent?.trim(),
    );
    expect(labels).toEqual(['Товары', 'Категории', 'Меню', 'Импорт']);
  });
});
