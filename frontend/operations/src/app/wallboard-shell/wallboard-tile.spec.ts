import { TestBed } from '@angular/core/testing';
import { beforeAll, describe, expect, it } from 'vitest';

import { ensureDesignTokensLoaded } from './design-tokens.testing';
import { WallboardTile } from './wallboard-tile';

beforeAll(() => ensureDesignTokensLoaded());

function render(value: number, label: string, tone?: 'default' | 'muted') {
  const fixture = TestBed.createComponent(WallboardTile);
  fixture.componentRef.setInput('value', value);
  fixture.componentRef.setInput('label', label);
  if (tone) {
    fixture.componentRef.setInput('tone', tone);
  }
  fixture.detectChanges();
  return fixture;
}

describe('WallboardTile', () => {
  it('renders the value at the TV type step', () => {
    const fixture = render(42, 'In progress');

    const valueEl = fixture.nativeElement.querySelector('[data-testid="q-wallboard-tile-value"]');
    expect(valueEl?.textContent.trim()).toBe('42');
    expect(getComputedStyle(valueEl).fontSize).toBe('128px');
  });

  it('renders the translated label beneath the value', () => {
    const fixture = render(7, 'Cancelled');

    expect(
      fixture.nativeElement
        .querySelector('[data-testid="q-wallboard-tile-label"]')
        ?.textContent.trim(),
    ).toBe('Cancelled');
  });

  it('carries the muted tone as a class, not a hard-coded colour', () => {
    const fixture = render(3, 'Cancelled', 'muted');

    expect(
      fixture.nativeElement
        .querySelector('[data-testid="q-wallboard-tile"]')
        ?.classList.contains('wallboard-tile--muted'),
    ).toBe(true);
  });
});
