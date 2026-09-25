import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { AbcCurveChart, AbcCurvePoint } from './abc-curve-chart';

const POINTS: readonly AbcCurvePoint[] = [
  { key: 'pizza', label: 'Пицца Маргарита', sharePercent: 80, cumulativeSharePercent: 80, abcClass: 'A' },
  { key: 'salad', label: 'Салат Цезарь', sharePercent: 15, cumulativeSharePercent: 95, abcClass: 'B' },
  { key: 'tea', label: 'Чай', sharePercent: 5, cumulativeSharePercent: 100, abcClass: 'C' },
];

describe('AbcCurveChart', () => {
  function render(points: readonly AbcCurvePoint[] = POINTS) {
    TestBed.configureTestingModule({ imports: [AbcCurveChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(AbcCurveChart);
    fixture.componentRef.setInput('points', points);
    fixture.componentRef.setInput('thresholdAPercent', 80);
    fixture.componentRef.setInput('thresholdBPercent', 95);
    fixture.componentRef.setInput('ariaLabel', 'ABC cumulative revenue share');
    fixture.detectChanges();
    return fixture;
  }

  it('draws one point per product, climbing to 100% at the last rank', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;

    const points = host.querySelectorAll('.q-abc-curve__point');
    expect(points.length).toBe(3);

    const lastCy = Number(points[points.length - 1].getAttribute('cy'));
    const firstCy = Number(points[0].getAttribute('cy'));
    // y grows downward in SVG space, so 100% (the last rank) sits above 80% (the first).
    expect(lastCy).toBeLessThan(firstCy);
  });

  it('draws the two published threshold lines, labelled with their own percent', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;

    const thresholds = host.querySelectorAll('.q-abc-curve__threshold');
    expect(thresholds.length).toBe(2);

    const labels = Array.from(host.querySelectorAll('.q-abc-curve__threshold-label')).map(
      (el) => el.textContent,
    );
    expect(labels.some((label) => label?.includes('80%'))).toBe(true);
    expect(labels.some((label) => label?.includes('95%'))).toBe(true);
  });

  it('shows a legend naming all three classes', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-abc-curve-legend"]');
    expect(legend?.textContent).toContain('A');
    expect(legend?.textContent).toContain('B');
    expect(legend?.textContent).toContain('C');
  });

  it('shows a tooltip naming the hovered product, its cumulative share and its class', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-abc-curve-tooltip"]')).toBeNull();

    const hits = host.querySelectorAll('.q-chart__hit');
    hits[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();

    const tooltip = host.querySelector('[data-testid="q-abc-curve-tooltip"]');
    expect(tooltip?.textContent).toContain('Салат Цезарь');
    expect(tooltip?.textContent).toContain('95.0%');
    expect(tooltip?.textContent).toContain('B');

    hits[1].dispatchEvent(new Event('mouseleave'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-abc-curve-tooltip"]')).toBeNull();
  });

  it('has an accessible table equivalent with every product, its share and its class', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-abc-curve-table"]',
    ) as HTMLElement;
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('Пицца Маргарита');
    expect(table.textContent).toContain('80.0%');
    expect(table.textContent).toContain('100.0%');
    expect(table.querySelector('caption')).not.toBeNull();

    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });
});
