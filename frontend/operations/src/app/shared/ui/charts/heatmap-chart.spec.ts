import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { HeatmapChart } from './heatmap-chart';
import { ChartHeatRow } from './chart-model';

const ROWS: readonly ChartHeatRow[] = [
  {
    key: '1',
    label: 'Mon',
    cells: [
      { key: '0', label: '00:00', value: 2 },
      { key: '1', label: '01:00', value: null },
      { key: '2', label: '02:00', value: 8 },
    ],
  },
  {
    key: '2',
    label: 'Tue',
    cells: [
      { key: '0', label: '00:00', value: 1 },
      { key: '1', label: '01:00', value: 4 },
      { key: '2', label: '02:00', value: 5 },
    ],
  },
];

describe('HeatmapChart', () => {
  function render(rows: readonly ChartHeatRow[] = ROWS) {
    TestBed.configureTestingModule({ imports: [HeatmapChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(HeatmapChart);
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('ariaLabel', 'Demand by weekday and hour');
    fixture.detectChanges();
    return fixture;
  }

  it('draws a row label per weekday and a coloured cell per hour', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const rowLabels = Array.from(
      host.querySelectorAll('.q-chart__row-label') as NodeListOf<Element>,
    ).map((el) => el.textContent?.trim());
    expect(rowLabels).toEqual(['Mon', 'Tue']);
    expect(host.querySelectorAll('.q-chart__cell').length).toBe(6);
  });

  it('renders a below-minimum-sample cell as an uncoloured, hatched cell — never a fabricated shade', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const emptyCells = host.querySelectorAll('.q-chart__cell--empty');
    expect(emptyCells.length).toBe(1);
    expect((emptyCells[0] as SVGRectElement).style.fill).toBe('');
  });

  it('shades the coloured cells from a shared sequential ramp — a bigger value gets a darker step', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const coloured = Array.from(
      host.querySelectorAll(
        '.q-chart__cell:not(.q-chart__cell--empty)',
      ) as NodeListOf<SVGRectElement>,
    );
    // Cell values are 2, 8, 1, 4, 5 (max 8): the max cell's slot must outrank the min cell's slot —
    // proof the ramp is scaled against the whole grid's domain, not each cell in isolation.
    const slotOf = (fill: string) => Number(/--q-viz-seq-(\d)/.exec(fill)?.[1]);
    const slots = coloured.map((c) => slotOf(c.style.fill));
    expect(Math.max(...slots)).toBeGreaterThan(Math.min(...slots));
  });

  it('shows a legend explaining the ramp direction', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-heatmap-chart-legend"]');
    expect(legend?.textContent).toContain('Fewer');
    expect(legend?.textContent).toContain('More');
  });

  it('shows a tooltip naming the row, column and value on hover', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const cells = host.querySelectorAll('.q-chart__cell:not(.q-chart__cell--empty)');
    cells[0].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-heatmap-chart-tooltip"]')?.textContent).toContain(
      'Mon, 00:00: 2',
    );
  });

  it('names the insufficient-sample cell honestly in the tooltip and the table, not as a zero', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const empty = host.querySelector('.q-chart__cell--empty')!;
    empty.dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-heatmap-chart-tooltip"]')?.textContent).toContain(
      'Not enough history',
    );

    const table = host.querySelector('[data-testid="q-heatmap-chart-table"]') as HTMLElement;
    expect(table.textContent).toContain('Not enough history');
  });

  it('has an accessible table equivalent with every weekday, hour and value', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-heatmap-chart-table"]',
    ) as HTMLElement;
    expect(table.textContent).toContain('Mon');
    expect(table.textContent).toContain('02:00');
    expect(table.textContent).toContain('8');
  });
});
