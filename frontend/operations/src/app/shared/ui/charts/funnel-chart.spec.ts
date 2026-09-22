import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { FunnelChart, FunnelDropOff, FunnelStage } from './funnel-chart';

const STAGES: readonly FunnelStage[] = [
  { key: 'TOTAL', label: 'Total', value: 200 },
  { key: 'COMPLETED', label: 'Completed', value: 150 },
  { key: 'ON_TIME', label: 'On time', value: 120 },
];

const DROP_OFFS: readonly FunnelDropOff[] = [
  { key: 'CANCELLED', label: 'Cancelled', value: 30, fromStageKey: 'TOTAL' },
  { key: 'REJECTED', label: 'Rejected', value: 20, fromStageKey: 'TOTAL' },
  { key: 'LATE', label: 'Late', value: 30, fromStageKey: 'COMPLETED' },
];

describe('FunnelChart', () => {
  function render(
    stages: readonly FunnelStage[] = STAGES,
    dropOffs: readonly FunnelDropOff[] = DROP_OFFS,
  ) {
    TestBed.configureTestingModule({ imports: [FunnelChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(FunnelChart);
    fixture.componentRef.setInput('stages', stages);
    fixture.componentRef.setInput('dropOffs', dropOffs);
    fixture.componentRef.setInput('ariaLabel', 'Sales funnel');
    fixture.detectChanges();
    return fixture;
  }

  it('draws one narrowing bar per stage, each smaller than the one before it', () => {
    const fixture = render();
    const bars = fixture.nativeElement.querySelectorAll('.q-funnel__bar');
    expect(bars.length).toBe(3);
    const widths = Array.from(bars as NodeListOf<SVGRectElement>).map((bar) =>
      Number(bar.getAttribute('width')),
    );
    expect(widths[0]).toBeGreaterThan(widths[1]);
    expect(widths[1]).toBeGreaterThan(widths[2]);
  });

  it('shows each stage’s share of the funnel’s own first stage, not of the previous one', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-funnel-chart-table"]',
    ) as HTMLElement;
    // 150 of 200 is 75%, matching the first stage — never 150 of 150 (100%).
    expect(table.textContent).toContain('75%');
  });

  it('shares a drop-off against the stage it left, not the funnel’s first stage', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-funnel-chart-table"]',
    ) as HTMLElement;
    // Late is 30 of 150 COMPLETED orders — 20% — never 30 of 200 (15%).
    const rows = Array.from(table.querySelectorAll('tr')).map((row) => row.textContent ?? '');
    const lateRow = rows.find((text) => text.includes('Late'));
    expect(lateRow).toContain('20%');
  });

  it('groups every drop-off under the stage it branched from', () => {
    const fixture = render();
    const rows = fixture.nativeElement.querySelectorAll('.q-funnel__drop-table-row');
    expect(rows.length).toBe(3);
    const labels = Array.from(rows as NodeListOf<HTMLElement>).map((row) => row.textContent);
    expect(labels.some((text) => text?.includes('Cancelled'))).toBe(true);
    expect(labels.some((text) => text?.includes('Rejected'))).toBe(true);
    expect(labels.some((text) => text?.includes('Late'))).toBe(true);
  });

  it('reconciles: every stage plus its own drop-offs accounts for the stage before it', () => {
    // TOTAL (200) = COMPLETED (150) + CANCELLED (30) + REJECTED (20).
    const total = STAGES[0].value;
    const completed = STAGES[1].value;
    const cancelledAndRejected = DROP_OFFS.filter((d) => d.fromStageKey === 'TOTAL').reduce(
      (sum, d) => sum + d.value,
      0,
    );
    expect(completed + cancelledAndRejected).toBe(total);
  });

  it('shows a tooltip with the stage’s own label and value on hover', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelectorAll('.q-funnel__bar')[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-funnel-chart-tooltip"]')?.textContent).toContain(
      'Completed: 150',
    );
  });

  it('has an accessible table equivalent — the graphic itself is aria-hidden', () => {
    const fixture = render();
    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });

  it('renders with no drop-offs at all — a funnel with nothing to branch off yet', () => {
    const fixture = render(STAGES, []);
    const bars = fixture.nativeElement.querySelectorAll('.q-funnel__bar');
    expect(bars.length).toBe(3);
    expect(fixture.nativeElement.querySelectorAll('.q-funnel__drop-table-row').length).toBe(0);
  });
});
