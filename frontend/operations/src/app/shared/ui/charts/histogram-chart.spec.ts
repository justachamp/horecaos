import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { HistogramChart } from './histogram-chart';
import { ChartCategory } from './chart-model';

/** `sla_bucket_set.v1`'s six fixed buckets, `UNDER_30` fastest .. `OVER_60` slowest. */
const BUCKETS: readonly ChartCategory[] = [
  { key: 'UNDER_30', label: '< 30', value: 40 },
  { key: 'M30_35', label: '30–35', value: 20 },
  { key: 'M35_40', label: '35–40', value: 10 },
  { key: 'M40_50', label: '40–50', value: 8 },
  { key: 'M50_60', label: '50–60', value: 4 },
  { key: 'OVER_60', label: '> 60', value: 2 },
];

describe('HistogramChart', () => {
  function render(buckets: readonly ChartCategory[] = BUCKETS) {
    TestBed.configureTestingModule({ imports: [HistogramChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(HistogramChart);
    fixture.componentRef.setInput('buckets', buckets);
    fixture.componentRef.setInput('ariaLabel', 'Handover time');
    fixture.detectChanges();
    return fixture;
  }

  it('draws one bar per bucket, in order, with a value axis', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.q-chart__bar').length).toBe(6);
    expect(host.querySelectorAll('.q-chart__gridline').length).toBeGreaterThan(0);
    const labels = Array.from(
      host.querySelectorAll('.q-chart__axis-label') as NodeListOf<Element>,
    ).map((el) => el.textContent?.trim());
    expect(labels).toContain('< 30');
    expect(labels).toContain('> 60');
  });

  it('darkens the sequential ramp as the bucket gets slower — an ordinal cue, not identity', () => {
    const fixture = render();
    const bars = fixture.nativeElement.querySelectorAll('.q-chart__bar');
    const fills = Array.from(bars as NodeListOf<SVGRectElement>).map((b) => b.style.fill);
    // Six ordered buckets over a five-step ramp: the fill sequence is monotone by slot number.
    const slots = fills.map((fill) => Number(/--q-viz-seq-(\d)/.exec(fill)?.[1]));
    for (let i = 1; i < slots.length; i += 1) {
      expect(slots[i]).toBeGreaterThanOrEqual(slots[i - 1]);
    }
  });

  it('shows no legend — one series names itself in the chart’s own title', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-legend"]');
    expect(legend?.textContent?.trim()).toBe('');
  });

  it('shows a tooltip with the bucket label and count on hover', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelectorAll('.q-chart__bar')[0].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-histogram-chart-tooltip"]')?.textContent).toContain(
      '< 30: 40',
    );
  });

  it('has an accessible table equivalent with every bucket and its count', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-histogram-chart-table"]',
    ) as HTMLElement;
    expect(table.textContent).toContain('> 60');
    expect(table.textContent).toContain('2');
  });
});
