import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { VduTicketResponse } from '../features/kitchen/kitchen-api';
import { VduWall } from './vdu-wall';

function ticket(overrides: Partial<VduTicketResponse> = {}): VduTicketResponse {
  return {
    ticketId: 'ticket-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    status: 'FIRED',
    targetReadyAt: null,
    createdAt: new Date().toISOString(),
    items: [],
    ...overrides,
  };
}

describe('VduWall', () => {
  let fixture: ComponentFixture<VduWall>;

  function render(inputs: {
    tickets?: readonly VduTicketResponse[];
    policy?: LatenessPolicy;
    timeZone?: string;
    phase?: 'loading' | 'ready' | 'denied';
    freshness?: 'loading' | 'fresh' | 'aging' | 'stale';
    stationLabel?: string | null;
  }): HTMLElement {
    TestBed.configureTestingModule({ imports: [VduWall] });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VduWall);
    fixture.componentRef.setInput('tickets', inputs.tickets ?? []);
    fixture.componentRef.setInput('policy', inputs.policy ?? PLATFORM_DEFAULT_LATENESS_POLICY);
    fixture.componentRef.setInput('timeZone', inputs.timeZone ?? 'Asia/Tashkent');
    fixture.componentRef.setInput('phase', inputs.phase ?? 'ready');
    fixture.componentRef.setInput('freshness', inputs.freshness ?? 'fresh');
    fixture.componentRef.setInput('freshnessLabel', 'Updated 3s ago');
    fixture.componentRef.setInput('now', Date.now());
    fixture.componentRef.setInput('stationLabel', inputs.stationLabel ?? null);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('has no button, link, form field or select of its own: a wall display has no controls', () => {
    const host = render({ tickets: [ticket({ externalReference: 'YE-1' })] });

    expect(
      host.querySelectorAll('button, a, input, select, textarea, [role="button"]'),
    ).toHaveLength(0);
  });

  it('names the station it is configured to show, in the language of the room', () => {
    const host = render({ stationLabel: 'Grill' });

    expect(host.querySelector('[data-testid="wallboard-vdu-station"]')?.textContent?.trim()).toBe(
      'Grill',
    );

    fixture.destroy();
    TestBed.resetTestingModule();
    expect(
      render({ stationLabel: null }).querySelector('[data-testid="wallboard-vdu-station"]'),
    ).toBeNull();
  });

  it('reads the clock in the zone it is given, not in a placeholder', () => {
    // 12:00 UTC is 17:00 in Tashkent and 08:00 in New York.
    const at = '2026-10-07T12:00:00Z';

    const tashkent = render({
      tickets: [ticket({ targetReadyAt: at })],
      timeZone: 'Asia/Tashkent',
    });
    expect(tashkent.textContent).toContain('17:00');

    fixture.destroy();
    TestBed.resetTestingModule();
    const newYork = render({
      tickets: [ticket({ targetReadyAt: at })],
      timeZone: 'America/New_York',
    });
    expect(newYork.textContent).toContain('08:00');
  });

  it('paints a ticket by the policy it is handed: late in the tenant’s own colour once past that policy’s grace', () => {
    // Ready 30 seconds ago. The platform default (no grace) calls it late; a tenant that allows ten
    // minutes of grace does not. Seen failing first against a wall that ignores the policy it was sent.
    const overdue = ticket({ targetReadyAt: new Date(Date.now() - 30_000).toISOString() });
    const lenient: LatenessPolicy = {
      delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 600, noPromiseFallbackSeconds: 2700 },
      pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 600, noPromiseFallbackSeconds: 2700 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 600, noPromiseFallbackSeconds: 2700 },
      lateColour: '#c0392b',
    };

    const byDefault = render({ tickets: [overdue] });
    expect(
      byDefault
        .querySelector('[data-testid="wallboard-vdu-card"]')
        ?.classList.contains('wv__card--danger'),
    ).toBe(true);

    fixture.destroy();
    TestBed.resetTestingModule();
    const byTenant = render({ tickets: [overdue], policy: lenient });
    expect(
      byTenant
        .querySelector('[data-testid="wallboard-vdu-card"]')
        ?.classList.contains('wv__card--danger'),
    ).toBe(false);
  });

  it('draws a late ticket in the tenant’s colour and an at-risk one in the platform’s amber', () => {
    const policy: LatenessPolicy = {
      ...PLATFORM_DEFAULT_LATENESS_POLICY,
      lateColour: '#c0392b',
    };
    const host = render({
      tickets: [
        ticket({
          ticketId: 'late',
          sequenceLabel: 'L',
          targetReadyAt: new Date(Date.now() - 60_000).toISOString(),
        }),
        ticket({
          ticketId: 'risk',
          sequenceLabel: 'R',
          targetReadyAt: new Date(Date.now() + 120_000).toISOString(),
        }),
      ],
      policy,
    });

    const [late, risk] = Array.from(
      host.querySelectorAll<HTMLElement>('[data-testid="wallboard-vdu-card"]'),
    );
    expect(late.classList.contains('wv__card--danger')).toBe(true);
    expect(late.style.getPropertyValue('--q-sla-late')).toBe('#c0392b');
    expect(risk.classList.contains('wv__card--warning')).toBe(true);
    expect(risk.style.getPropertyValue('--q-sla-late')).toBe('');
  });

  it('says what the board area is doing: loading, denied, empty', () => {
    expect(render({ phase: 'loading' }).textContent).toContain('Loading the display board');
    fixture.destroy();
    TestBed.resetTestingModule();
    expect(
      render({ phase: 'denied' }).querySelector('[data-testid="wallboard-vdu-denied"]'),
    ).not.toBeNull();
    fixture.destroy();
    TestBed.resetTestingModule();
    expect(
      render({ phase: 'ready', tickets: [] }).querySelector('[data-testid="wallboard-vdu-empty"]'),
    ).not.toBeNull();
  });

  it('shows how old the board is once it stops being fresh', () => {
    const aging = render({ freshness: 'aging' });
    expect(aging.querySelector('[data-testid="wallboard-vdu-offline-banner"]')).not.toBeNull();
    expect(
      aging.querySelector('[data-testid="wallboard-vdu-last-updated"]')?.textContent,
    ).toContain('Updated 3s ago');

    fixture.destroy();
    TestBed.resetTestingModule();
    expect(
      render({ freshness: 'fresh' }).querySelector('[data-testid="wallboard-vdu-offline-banner"]'),
    ).toBeNull();
  });
});
