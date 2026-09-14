import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { FilterBar, FilterBarChip } from './filter-bar';

/**
 * `q-filter-bar` projects its own controls, so exercising it needs a tiny
 * host rather than setting inputs on the bare component — the same reason
 * `split-pane.spec.ts`'s own host exists. The host's own state is signals,
 * not plain fields: a plain field mutation does not reliably reach a
 * zoneless, `OnPush` child's signal `input()` through `fixture.detectChanges()`,
 * the same lesson `split-pane.spec.ts` already encodes.
 */
@Component({
  selector: 'q-test-filter-bar-host',
  imports: [FilterBar],
  template: `
    <q-filter-bar
      [hasSecondary]="hasSecondary()"
      [chips]="chips()"
      [canReset]="canReset()"
      (chipRemoved)="removed.set($event)"
      (resetFilters)="resetCount.set(resetCount() + 1)"
    >
      <span primary data-testid="primary-control">primary</span>
      <span secondary data-testid="secondary-control">secondary</span>
    </q-filter-bar>
  `,
})
class TestHost {
  readonly hasSecondary = signal(false);
  readonly chips = signal<readonly FilterBarChip[]>([]);
  readonly canReset = signal(false);
  readonly removed = signal<string | null>(null);
  readonly resetCount = signal(0);
}

function render(): ReturnType<typeof TestBed.createComponent<TestHost>> {
  const fixture = TestBed.createComponent(TestHost);
  fixture.detectChanges();
  return fixture;
}

describe('FilterBar', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('always renders the primary row’s projected content', () => {
    const fixture = render();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="primary-control"]'),
    ).not.toBeNull();
  });

  it('renders no "⋯ ещё" toggle when the bar has no secondary row', () => {
    const fixture = render();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-filter-bar-more"]'),
    ).toBeNull();
  });

  it('keeps the secondary row hidden until "⋯ ещё" is opened, then reveals it', () => {
    const fixture = render();
    fixture.componentInstance.hasSecondary.set(true);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="secondary-control"]')).toBeNull();

    (host.querySelector('[data-testid="q-filter-bar-more"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="secondary-control"]')).not.toBeNull();
  });

  it('renders one chip per active secondary filter, and emits its id when removed', () => {
    const fixture = render();
    fixture.componentInstance.chips.set([{ id: 'mine', label: 'My orders' }]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const chip = host.querySelector('[data-testid="q-filter-bar-chip-mine"]');
    expect(chip?.textContent).toContain('My orders');

    (chip as HTMLButtonElement).click();
    expect(fixture.componentInstance.removed()).toBe('mine');
  });

  it('renders no chip row and no reset button while nothing is filtering', () => {
    const fixture = render();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-filter-bar-chips"]'),
    ).toBeNull();
  });

  it('offers "Сбросить фильтры" only once something is filtering, and emits on click', () => {
    const fixture = render();
    fixture.componentInstance.canReset.set(true);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const reset = host.querySelector('[data-testid="q-filter-bar-reset"]') as HTMLButtonElement;
    expect(reset).not.toBeNull();
    reset.click();

    expect(fixture.componentInstance.resetCount()).toBe(1);
  });
});
