import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ChartFrame } from './chart-frame';

@Component({
  selector: 'q-test-host',
  imports: [ChartFrame],
  template: `
    <q-chart-frame ariaLabel="Revenue by channel">
      <svg chart-graphic viewBox="0 0 10 10"><circle cx="5" cy="5" r="4" /></svg>
      <span chart-legend>Legend text</span>
      <table chart-table>
        <tbody>
          <tr>
            <td>Row</td>
          </tr>
        </tbody>
      </table>
    </q-chart-frame>
  `,
})
class TestHost {}

describe('ChartFrame', () => {
  function render() {
    TestBed.configureTestingModule({ imports: [TestHost] });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
    return fixture;
  }

  it('hides the graphic from assistive tech — the table carries the real semantics', () => {
    const fixture = render();
    const graphic = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-graphic"]');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
  });

  it('names the figure landmark with the caller’s own label, not a generic "chart"', () => {
    const fixture = render();
    const figure = fixture.nativeElement.querySelector('[data-testid="q-chart-frame"]');
    expect(figure.getAttribute('aria-label')).toBe('Revenue by channel');
  });

  it('keeps the legend visible — it is text, not decoration, so it is never aria-hidden', () => {
    const fixture = render();
    const legend = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-legend"]');
    expect(legend.hasAttribute('aria-hidden')).toBe(false);
    expect(legend.textContent).toContain('Legend text');
  });

  it('starts with the table collapsed, and a real button — not hover — reveals it', () => {
    const fixture = render();
    const table = fixture.nativeElement.querySelector('[data-testid="q-chart-frame-table"]');
    expect(table.hidden).toBe(true);

    const toggle = fixture.nativeElement.querySelector(
      '[data-testid="q-chart-frame-table-toggle"]',
    ) as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    fixture.detectChanges();

    expect(table.hidden).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(table.textContent).toContain('Row');
  });
});
