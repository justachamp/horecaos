import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { PropagationBinding, StopsApi } from './stop-scope-api';
import { StopPropagationBanner } from './stop-propagation-banner';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function binding(overrides: Partial<PropagationBinding>): PropagationBinding {
  return {
    bindingId: 'b-1',
    providerType: 'YANDEX_EDA',
    displayName: 'Yandex Eda',
    mode: 'AUTOMATIC',
    inSync: 0,
    pending: 0,
    uncertain: 0,
    rejectedUnmapped: 0,
    unconfirmed: 0,
    unconfirmedItems: [],
    ...overrides,
  };
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  imports: [StopPropagationBanner],
  template: `<q-stop-propagation-banner [refreshKey]="key()" />`,
})
class Host {
  readonly key = signal(0);
}

describe('StopPropagationBanner (ADR 0141)', () => {
  let fixture: ComponentFixture<Host>;

  async function render(propagation: ReturnType<typeof vi.fn>): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: StopsApi, useValue: { propagation } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function lines(host: HTMLElement): string[] {
    return [...host.querySelectorAll('[data-testid="stop-propagation-line"]')].map((line) =>
      (line.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
  }

  it('says a provider with no stop API is not propagated automatically — never a quiet "in sync"', async () => {
    const host = await render(
      vi.fn().mockResolvedValue([binding({ mode: 'MANUAL', displayName: 'Wolt' })]),
    );

    expect(lines(host)).toEqual([
      'Wolt: not propagated automatically — update the partner portal by hand',
    ]);
    expect(host.querySelector('[data-mode="MANUAL"]')?.className).toContain(
      'propagation__line--attention',
    );
  });

  it('says how many items a live marketplace has not confirmed, and since when', async () => {
    const host = await render(
      vi.fn().mockResolvedValue([
        binding({
          pending: 5,
          uncertain: 2,
          unconfirmed: 7,
          oldestUnconfirmedSince: '2026-10-01T09:32:00Z',
        }),
      ]),
    );

    const [line] = lines(host);
    expect(line).toContain('Yandex Eda: 7 items not confirmed since');
    expect(line).toContain('update in the partner portal');
    expect(host.querySelector('[data-mode="AUTOMATIC"]')?.className).toContain(
      'propagation__line--attention',
    );
  });

  it('reads "in sync" only for a live binding with nothing unconfirmed, and says paused when suspended', async () => {
    const host = await render(
      vi
        .fn()
        .mockResolvedValue([
          binding({ displayName: 'Uzum', inSync: 12 }),
          binding({ bindingId: 'b-2', displayName: 'Express24', mode: 'SUSPENDED' }),
        ]),
    );

    expect(lines(host)).toEqual([
      'Uzum: in sync',
      'Express24: updates to the partner are paused — update the partner portal by hand',
    ]);
  });

  it('renders nothing for a branch with no marketplace, or when the read fails', async () => {
    const none = await render(vi.fn().mockResolvedValue([]));
    expect(none.querySelector('[data-testid="stop-propagation"]')).toBeNull();
    TestBed.resetTestingModule();

    const failed = await render(vi.fn().mockRejectedValue(new Error('network')));
    expect(failed.querySelector('[data-testid="stop-propagation"]')).toBeNull();
  });

  it('re-reads when the page bumps its refresh key', async () => {
    const propagation = vi.fn().mockResolvedValue([binding({ inSync: 1 })]);
    await render(propagation);
    expect(propagation).toHaveBeenCalledTimes(1);

    fixture.componentInstance.key.set(1);
    fixture.detectChanges();
    await settle();

    expect(propagation).toHaveBeenCalledTimes(2);
  });
});
