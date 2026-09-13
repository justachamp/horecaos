import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { BarChart } from './bar-chart';
import { ChartCategory } from './chart-model';

const ITEMS: readonly ChartCategory[] = [
  { key: 'TELEGRAM_BOT', label: 'TELEGRAM_BOT', value: 6 },
  { key: 'YANDEX', label: 'Yandex', value: 3 },
  { key: 'PHONE', label: 'Phone', value: 1 },
];

describe('BarChart', () => {
  function render(
    overrides: Partial<{ items: readonly ChartCategory[]; scale: 'max' | 'total' }> = {},
  ) {
    TestBed.configureTestingModule({ imports: [BarChart] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('items', overrides.items ?? ITEMS);
    fixture.componentRef.setInput('ariaLabel', 'Source mix');
    if (overrides.scale) {
      fixture.componentRef.setInput('scale', overrides.scale);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('draws an axis: value gridlines with numeric tick labels', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.q-chart__gridline').length).toBeGreaterThan(0);
    const axisLabels = Array.from(host.querySelectorAll('.q-chart__axis-label')).map((el) =>
      el.textContent?.trim(),
    );
    expect(axisLabels).toContain('6');
  });

  it('gives every category its own colour — the improvement over the retired single-blue divs', () => {
    const fixture = render();
    const bars = fixture.nativeElement.querySelectorAll('.q-chart__bar');
    const colors = new Set(Array.from(bars).map((bar) => (bar as SVGRectElement).style.fill));
    expect(colors.size).toBe(3);
  });

  it('shows a legend naming every category', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-bar-chart-legend"]');
    expect(legend?.textContent).toContain('TELEGRAM_BOT');
    expect(legend?.textContent).toContain('Yandex');
    expect(legend?.textContent).toContain('Phone');
  });

  it('shows a tooltip for the hovered bar', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-bar-chart-tooltip"]')).toBeNull();

    host.querySelectorAll('.q-chart__bar')[1].dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();

    const tooltip = host.querySelector('[data-testid="q-bar-chart-tooltip"]');
    expect(tooltip?.textContent).toContain('Yandex: 3');
  });

  it('has an accessible table equivalent with every category and value', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector(
      '[data-testid="q-bar-chart-table"]',
    ) as HTMLElement;
    expect(table.textContent).toContain('TELEGRAM_BOT');
    expect(table.textContent).toContain('6');
    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });

  describe('scale="total" — parity with the retired [style.width.%] rows', () => {
    /** `today-page.ts`'s retired `mixShare`: `Math.round((slice.count / total) * 100)`. */
    function retiredMixShare(value: number, items: readonly ChartCategory[]): number {
      const total = items.reduce((sum, item) => sum + item.value, 0);
      return total === 0 ? 0 : Math.round((value / total) * 100);
    }

    it('renders each bar at exactly the width the retired mixShare/channelBarWidth formula computed', () => {
      const fixture = render({ scale: 'total' });
      const bars: readonly { key: string; width: number }[] = (
        fixture.componentInstance as unknown as {
          bars: () => readonly { key: string; width: number }[];
        }
      ).bars();

      // The bar chart's own track width for this fixture (see bar-chart.ts's H_* constants).
      const trackWidth = 640 - 150 - 56 - 8 * 2;

      for (const item of ITEMS) {
        const bar = bars.find((b) => b.key === item.key)!;
        const expectedSharePercent = retiredMixShare(item.value, ITEMS);
        const actualSharePercent = Math.round((bar.width / trackWidth) * 100);
        expect(actualSharePercent).toBe(expectedSharePercent);
      }
    });

    it('is a different scale from the default — max-scaling would size the largest bar at 100%, not its 60% share', () => {
      const fixture = render({ scale: 'total' });
      const bars: readonly { key: string; width: number }[] = (
        fixture.componentInstance as unknown as {
          bars: () => readonly { key: string; width: number }[];
        }
      ).bars();
      const trackWidth = 640 - 150 - 56 - 8 * 2;
      const largest = bars.find((b) => b.key === 'TELEGRAM_BOT')!;
      expect(Math.round((largest.width / trackWidth) * 100)).toBe(60); // 6 of (6+3+1) = 60%, not 100%
    });
  });
});
