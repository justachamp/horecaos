import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { KpiTile, deltaOf } from './kpi-tile';

describe('deltaOf', () => {
  it('computes a signed percentage change', () => {
    expect(deltaOf(120, 100).deltaText).toBe('+20%');
    expect(deltaOf(80, 100).deltaText).toBe('−20%');
  });

  it('is up only for a positive change', () => {
    expect(deltaOf(120, 100).deltaUp).toBe(true);
    expect(deltaOf(80, 100).deltaUp).toBe(false);
    expect(deltaOf(100, 100).deltaUp).toBe(false);
  });

  it('is null against a zero comparison — nothing to divide by', () => {
    expect(deltaOf(50, 0).deltaText).toBeNull();
  });

  it('is null when either side is not yet known', () => {
    expect(deltaOf(null, 100).deltaText).toBeNull();
    expect(deltaOf(100, null).deltaText).toBeNull();
  });
});

describe('KpiTile', () => {
  function render(inputs: {
    label?: string;
    value?: string;
    deltaText?: string | null;
    deltaUp?: boolean;
    deltaSuffix?: string | null;
    subtitle?: string | null;
    provisional?: boolean;
    provisionalNote?: string | null;
    sparklinePoints?: readonly (number | null)[] | null;
  }) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [KpiTile] });
    const fixture = TestBed.createComponent(KpiTile);
    fixture.componentRef.setInput('label', inputs.label ?? 'Revenue');
    fixture.componentRef.setInput('value', inputs.value ?? '1 200 000 сум');
    if (inputs.deltaText !== undefined)
      fixture.componentRef.setInput('deltaText', inputs.deltaText);
    if (inputs.deltaUp !== undefined) fixture.componentRef.setInput('deltaUp', inputs.deltaUp);
    if (inputs.deltaSuffix !== undefined)
      fixture.componentRef.setInput('deltaSuffix', inputs.deltaSuffix);
    if (inputs.subtitle !== undefined) fixture.componentRef.setInput('subtitle', inputs.subtitle);
    if (inputs.provisional !== undefined)
      fixture.componentRef.setInput('provisional', inputs.provisional);
    if (inputs.provisionalNote !== undefined) {
      fixture.componentRef.setInput('provisionalNote', inputs.provisionalNote);
    }
    if (inputs.sparklinePoints !== undefined) {
      fixture.componentRef.setInput('sparklinePoints', inputs.sparklinePoints);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('renders the label and the already-formatted value verbatim', () => {
    const fixture = render({ label: 'Orders', value: '42' });
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Orders');
    expect(host.textContent).toContain('42');
  });

  it('renders no delta row at all when there is nothing to compare against', () => {
    const fixture = render({});
    expect(fixture.nativeElement.querySelector('[data-testid="q-kpi-tile-delta"]')).toBeNull();
  });

  it('styles an upward delta differently from a downward one', () => {
    const up = render({ deltaText: '+12%', deltaUp: true, deltaSuffix: 'vs last week' });
    const upDelta = up.nativeElement.querySelector('[data-testid="q-kpi-tile-delta"]');
    expect(upDelta.classList.contains('q-kpi-tile__delta--up')).toBe(true);
    expect(upDelta.textContent).toContain('+12%');
    expect(upDelta.textContent).toContain('vs last week');

    const down = render({ deltaText: '−4%', deltaUp: false });
    const downDelta = down.nativeElement.querySelector('[data-testid="q-kpi-tile-delta"]');
    expect(downDelta.classList.contains('q-kpi-tile__delta--up')).toBe(false);
  });

  it('shows the provisional note only when marked provisional', () => {
    const fixture = render({ provisional: true, provisionalNote: 'Provisional' });
    expect(fixture.nativeElement.textContent).toContain('Provisional');

    const notProvisional = render({ provisional: false, provisionalNote: 'Provisional' });
    expect(notProvisional.nativeElement.textContent).not.toContain('Provisional');
  });

  it('embeds a sparkline when the caller has a series, and none when it does not', () => {
    const withSeries = render({ sparklinePoints: [10, 12, 9, 15] });
    expect(withSeries.nativeElement.querySelector('[data-testid="q-sparkline"]')).not.toBeNull();

    const withoutSeries = render({});
    expect(withoutSeries.nativeElement.querySelector('[data-testid="q-sparkline"]')).toBeNull();
  });
});
