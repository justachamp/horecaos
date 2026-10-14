import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { GLYPH_NAMES, GLYPH_PATHS, Glyph, GlyphName, isGlyphName } from './glyph';

@Component({
  imports: [Glyph],
  template: `<q-glyph [name]="name" [label]="label" />`,
})
class Host {
  name: GlyphName = 'cash';
  label: string | null = null;
}

function render(name: GlyphName, label: string | null) {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.name = name;
  fixture.componentInstance.label = label;
  fixture.detectChanges();
  return fixture.nativeElement.querySelector('svg') as SVGElement;
}

describe('q-glyph, the console’s closed icon set', () => {
  it('draws every mark of the set from its own path, and nothing from outside it', () => {
    for (const name of GLYPH_NAMES) {
      const svg = render(name, name);
      expect(svg.querySelector('path')!.getAttribute('d'), name).toBe(GLYPH_PATHS[name]);
    }
  });

  it('is an image with an accessible name and a tooltip when it has a label', () => {
    const svg = render('telegram', 'Telegram bot');

    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Telegram bot');
    expect(svg.querySelector('title')!.textContent).toBe('Telegram bot');
    expect(svg.getAttribute('aria-hidden')).toBeNull();
  });

  it('is hidden from assistive technology when it has no label, because it is then only decoration', () => {
    const svg = render('card', null);

    expect(svg.getAttribute('role')).toBeNull();
    expect(svg.getAttribute('aria-label')).toBeNull();
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelector('title')).toBeNull();
  });

  it('lets a tenant’s free-text icon choose a mark and never supply one', () => {
    expect(isGlyphName('telegram')).toBe(true);
    expect(isGlyphName('<script>alert(1)</script>')).toBe(false);
    expect(isGlyphName('constructor')).toBe(false);
    expect(isGlyphName('toString')).toBe(false);
    expect(isGlyphName(null)).toBe(false);
    expect(isGlyphName('')).toBe(false);
  });
});
