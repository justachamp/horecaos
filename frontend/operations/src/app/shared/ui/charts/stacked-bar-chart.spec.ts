import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { StackedBarChart } from './stacked-bar-chart';
import { ChartCategory } from './chart-model';

const SEGMENTS: readonly ChartCategory[] = [
  { key: 'DELIVERY', label: 'Delivery', value: 60 },
  { key: 'PICKUP', label: 'Pickup', value: 30 },
  { key: 'DINE_IN', label: 'Dine-in', value: 10 },
];

describe('StackedBarChart', () => {
  function render(segments: readonly ChartCategory[] = SEGMENTS) {
    TestBed.configureTestingModule({ imports: [StackedBarChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(StackedBarChart);
    fixture.componentRef.setInput('segments', segments);
    fixture.componentRef.setInput('ariaLabel', 'Fulfilment mix');
    fixture.detectChanges();
    return fixture;
  }

  it('draws a 0%/50%/100% axis under the bar', () => {
    const fixture = render();
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('.q-chart__axis-label') as NodeListOf<Element>,
    ).map((el) => el.textContent?.trim());
    expect(labels).toEqual(['0%', '50%', '100%']);
  });

  it('gives every segment its own categorical colour, never a reused status colour', () => {
    const fixture = render();
    const segments = fixture.nativeElement.querySelectorAll('.q-chart__segment');
    expect(segments.length).toBe(3);
    const fills = Array.from(segments).map((s) => (s as SVGRectElement).style.fill);
    expect(new Set(fills).size).toBe(3);
    for (const fill of fills) {
      expect(fill).toMatch(/--q-viz-cat-/);
    }
  });

  it('separates every segment with a visible gap, per the mark spec', () => {
    const fixture = render();
    const rects = fixture.nativeElement.querySelectorAll('.q-chart__segment');
    const first = rects[0] as SVGRectElement;
    const second = rects[1] as SVGRectElement;
    const firstEnd = Number(first.getAttribute('x')) + Number(first.getAttribute('width'));
    const secondStart = Number(second.getAttribute('x'));
    expect(secondStart - firstEnd).toBeGreaterThan(0);
  });

  it('shows a legend with each segment’s share', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector(
      '[data-testid="q-stacked-bar-chart-legend"]',
    );
    expect(legend?.textContent).toContain('Delivery');
    expect(legend?.textContent).toContain('60%');
  });

  it('shows a tooltip with the label, value and share on hover', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelectorAll('.q-chart__segment')[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    const tooltip = host.querySelector('[data-testid="q-stacked-bar-chart-tooltip"]');
    expect(tooltip?.textContent).toContain('Pickup: 30 (30%)');
  });

  it('has an accessible table equivalent with count and share for every segment', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-stacked-bar-chart-table"]',
    ) as HTMLElement;
    expect(table.textContent).toContain('Dine-in');
    expect(table.textContent).toContain('10%');
  });
});
