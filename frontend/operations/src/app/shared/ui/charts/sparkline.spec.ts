import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Sparkline } from './sparkline';

describe('Sparkline', () => {
  function render(points: readonly (number | null)[]) {
    TestBed.configureTestingModule({ imports: [Sparkline] });
    const fixture = TestBed.createComponent(Sparkline);
    fixture.componentRef.setInput('points', points);
    fixture.detectChanges();
    return fixture;
  }

  it('draws a single path through the points', () => {
    const fixture = render([10, 12, 8, 15]);
    const path = fixture.nativeElement.querySelector('path');
    expect(path?.getAttribute('d')).toMatch(/^M.*L.*L.*L/);
  });

  it('is aria-hidden — the tile’s own text already states the number', () => {
    const fixture = render([10, 12, 8, 15]);
    const svg = fixture.nativeElement.querySelector('[data-testid="q-sparkline"]');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
  });

  it('skips null points rather than drawing a false zero', () => {
    const fixture = render([10, null, 8]);
    const path = fixture.nativeElement.querySelector('path')?.getAttribute('d') as string;
    // Two plottable points (10, 8) draw a two-command path: one M, one L.
    expect((path.match(/M/g) ?? []).length).toBe(1);
    expect((path.match(/L/g) ?? []).length).toBe(1);
  });

  it('renders nothing for fewer than two plottable points — a shape needs two ends', () => {
    const fixture = render([10]);
    expect(fixture.nativeElement.querySelector('[data-testid="q-sparkline"]')).toBeNull();
  });

  it('renders nothing for an all-null series', () => {
    const fixture = render([null, null]);
    expect(fixture.nativeElement.querySelector('[data-testid="q-sparkline"]')).toBeNull();
  });
});
