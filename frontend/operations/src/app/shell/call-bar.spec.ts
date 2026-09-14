import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { ScreenPopCard } from '../features/orders/call-centre-api';
import { CallBar } from './call-bar';
import { VoicePresence } from './voice-presence';

function ringingCard(overrides: Partial<ScreenPopCard> = {}): ScreenPopCard {
  return {
    ringing: true,
    callEventId: 'call-1',
    lineDid: null,
    maskedCallerNumber: '•••••••••4567',
    occurredAt: new Date().toISOString(),
    unknownCaller: true,
    customerAccountId: null,
    customerDisplayName: null,
    recentOrders: [],
    acknowledgedBy: null,
    ...overrides,
  };
}

/**
 * A fake at the `VoicePresence` boundary, not the `CallCentreApi` one below
 * it — this component reads the service's own signals directly and never
 * touches the API, so faking the service is what actually isolates it (and
 * sidesteps constructing the real service's own `CurrentLocation`/
 * `CallCentreApi` chain, which this spec has no business standing up).
 */
class FakeVoicePresence {
  readonly currentCall = signal<ScreenPopCard | null>(null);
  readonly claim = vi.fn().mockResolvedValue(undefined);
}

describe('CallBar', () => {
  let fixture: ComponentFixture<CallBar>;
  let voicePresence: FakeVoicePresence;
  let router: Router;

  async function render(): Promise<void> {
    voicePresence = new FakeVoicePresence();
    await TestBed.configureTestingModule({
      imports: [CallBar],
      providers: [provideRouter([]), { provide: VoicePresence, useValue: voicePresence }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(CallBar);
    fixture.detectChanges();
  }

  it('renders nothing while no call is ringing', async () => {
    await render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="call-bar"]'),
    ).toBeNull();
  });

  it('shows the masked number for an unclaimed, unknown caller with a claim action', async () => {
    await render();
    voicePresence.currentCall.set(ringingCard());
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const bar = host.querySelector('[data-testid="call-bar"]');
    expect(bar?.textContent).toContain('•••••••••4567');
    expect(host.querySelector('[data-testid="call-bar-claim"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="call-bar-start-order"]')).toBeNull();
  });

  it('claims a ringing call', async () => {
    await render();
    voicePresence.currentCall.set(ringingCard());
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="call-bar-claim"]') as HTMLButtonElement
    ).click();

    expect(voicePresence.claim).toHaveBeenCalledWith('call-1');
  });

  it('offers "start order" only for a claimed, known caller — never an unknown one', async () => {
    await render();
    voicePresence.currentCall.set(
      ringingCard({ unknownCaller: false, customerDisplayName: 'Alisher', acknowledgedBy: 'op-1' }),
    );
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="call-bar"]')?.textContent).toContain('Alisher');
    const startOrder = host.querySelector(
      '[data-testid="call-bar-start-order"]',
    ) as HTMLButtonElement;
    expect(startOrder).not.toBeNull();

    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    startOrder.click();

    expect(navigate).toHaveBeenCalledWith(['/orders/new'], {
      queryParams: { callEventId: 'call-1' },
    });
  });

  it('never offers "start order" for a claimed but unknown caller', async () => {
    await render();
    voicePresence.currentCall.set(ringingCard({ unknownCaller: true, acknowledgedBy: 'op-1' }));
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="call-bar-start-order"]'),
    ).toBeNull();
  });
});
