import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { DonutChart } from './donut-chart';
import { ChartCategory } from './chart-model';

const SEGMENTS: readonly ChartCategory[] = [
  { key: 'TELEGRAM', label: 'Telegram', value: 70 },
  { key: 'YANDEX', label: 'Yandex', value: 30 },
];

describe('DonutChart', () => {
  function render(segments: readonly ChartCategory[] = SEGMENTS) {
    TestBed.configureTestingModule({ imports: [DonutChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(DonutChart);
    fixture.componentRef.setInput('segments', segments);
    fixture.componentRef.setInput('ariaLabel', 'Channel mix');
    fixture.componentRef.setInput('centerLabel', '100');
    fixture.detectChanges();
    return fixture;
  }

  it('draws one arc per segment, each its own categorical colour', () => {
    const fixture = render();
    const arcs = fixture.nativeElement.querySelectorAll('.q-chart__slice');
    expect(arcs.length).toBe(2);
    const fills = Array.from(arcs as NodeListOf<SVGCircleElement>).map((a) => a.style.stroke);
    expect(new Set(fills).size).toBe(2);
  });

  it('renders the centre label — the one number a share chart is allowed to state directly', () => {
    const fixture = render();
    expect(fixture.nativeElement.querySelector('.q-chart__center-label')?.textContent?.trim()).toBe(
      '100',
    );
  });

  it('shows a legend with each slice’s share', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-donut-chart-legend"]');
    expect(legend?.textContent).toContain('Telegram');
    expect(legend?.textContent).toContain('70%');
    expect(legend?.textContent).toContain('30%');
  });

  it('shows a tooltip with label, value and share on hover', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelectorAll('.q-chart__slice')[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="q-donut-chart-tooltip"]')?.textContent).toContain(
      'Yandex: 30 (30%)',
    );
  });

  it('has an accessible table equivalent — the graphic itself is aria-hidden', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-donut-chart-table"]',
    ) as HTMLElement;
    expect(table.textContent).toContain('Telegram');
    expect(table.textContent).toContain('70%');
    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });
});
