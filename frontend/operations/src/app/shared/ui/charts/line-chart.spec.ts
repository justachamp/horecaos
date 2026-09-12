import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { LineChart } from './line-chart';
import { ChartSeries } from './chart-model';

const SERIES: readonly ChartSeries[] = [
  {
    key: 'revenue',
    label: 'Revenue',
    points: [
      { x: '01.09', y: 100 },
      { x: '02.09', y: 150 },
      { x: '03.09', y: null },
      { x: '04.09', y: 80 },
    ],
  },
  {
    key: 'orders',
    label: 'Orders',
    points: [
      { x: '01.09', y: 10 },
      { x: '02.09', y: 14 },
      { x: '03.09', y: 6 },
      { x: '04.09', y: 9 },
    ],
  },
];

describe('LineChart', () => {
  function render(series: readonly ChartSeries[] = SERIES) {
    TestBed.configureTestingModule({ imports: [LineChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(LineChart);
    fixture.componentRef.setInput('series', series);
    fixture.componentRef.setInput('ariaLabel', 'Revenue and orders by day');
    fixture.detectChanges();
    return fixture;
  }

  it('draws an axis: gridlines with numeric labels and a category label per day', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;

    const gridlines = host.querySelectorAll('.q-chart__gridline');
    expect(gridlines.length).toBeGreaterThan(0);
    const axisLabels = Array.from(host.querySelectorAll('.q-chart__axis-label')).map((el) =>
      el.textContent?.trim(),
    );
    expect(axisLabels).toContain('01.09');
    expect(axisLabels).toContain('04.09');
  });

  it('shows a legend when there is more than one series, naming each by its own label', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-line-chart-legend"]');
    expect(legend?.textContent).toContain('Revenue');
    expect(legend?.textContent).toContain('Orders');
  });

  it('hides the legend for a single series — the title already names it', () => {
    const fixture = render([SERIES[0]]);
    expect(fixture.nativeElement.querySelector('[data-testid="q-line-chart-legend"]')).toBeNull();
  });

  it('draws one line per series and skips the null point rather than drawing a false zero', () => {
    const fixture = render();
    const paths = fixture.nativeElement.querySelectorAll('.q-chart__line');
    expect(paths.length).toBe(2);
    // The revenue path has a null at index 2 — its `d` breaks into two subpaths (two "M" moves).
    const revenuePathD = paths[0].getAttribute('d') as string;
    expect((revenuePathD.match(/M/g) ?? []).length).toBe(2);
  });

  it('shows a crosshair tooltip with every series’ value at the hovered day', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-line-chart-tooltip"]')).toBeNull();

    const hits = host.querySelectorAll('.q-chart__hit');
    hits[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();

    const tooltip = host.querySelector('[data-testid="q-line-chart-tooltip"]');
    expect(tooltip?.textContent).toContain('02.09');
    expect(tooltip?.textContent).toContain('Revenue: 150');
    expect(tooltip?.textContent).toContain('Orders: 14');

    hits[1].dispatchEvent(new Event('mouseleave'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-line-chart-tooltip"]')).toBeNull();
  });

  it('omits a series from the tooltip on a day it has no data, rather than showing a false zero', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    const hits = host.querySelectorAll('.q-chart__hit');
    hits[2].dispatchEvent(new Event('mouseenter')); // 03.09 — revenue is null there
    fixture.detectChanges();

    const tooltip = host.querySelector('[data-testid="q-line-chart-tooltip"]');
    expect(tooltip?.textContent).not.toContain('Revenue');
    expect(tooltip?.textContent).toContain('Orders: 6');
  });

  it('has an accessible table equivalent — not a title attribute — with every value the graphic drew', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-line-chart-table"]',
    ) as HTMLElement;
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('150');
    expect(table.textContent).toContain('—'); // the null point, honestly rendered
    expect(table.querySelector('caption')).not.toBeNull();

    // The graphic itself carries no accessible semantics of its own — the table is the real path.
    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });
});
