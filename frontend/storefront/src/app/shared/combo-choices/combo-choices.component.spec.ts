import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import type { MenuItemComboComponent, MenuItemComboGroup } from '../../types/home.types';
import type { ComboPicks } from '../../utils/combo-selection';
import { ComboChoicesComponent } from './combo-choices.component';

class FakeTranslateService {
  current = vi.fn(() => ({}));
  get = vi.fn((key: string) => key);
  getWithParams = vi.fn(
    (key: string, params: Record<string, unknown>) => `${key}:${JSON.stringify(params)}`,
  );
}

const component = (
  id: string,
  overrides: Partial<MenuItemComboComponent> = {},
): MenuItemComboComponent => ({
  id,
  name: id,
  variantName: null,
  defaultQuantity: 1,
  active: true,
  amountMinor: 0,
  ...overrides,
});

const MAIN: MenuItemComboGroup = {
  id: 'main',
  name: 'Main',
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameComponentMultipleTimes: false,
  components: [
    component('burger', { amountMinor: 25_000 }),
    component('wrap', { amountMinor: 22_000, variantName: 'large' }),
    component('soup', { active: false, amountMinor: 18_000 }),
  ],
};

const DRINK: MenuItemComboGroup = {
  id: 'drink',
  name: 'Drink',
  minimumSelections: 0,
  maximumSelections: 2,
  allowSameComponentMultipleTimes: true,
  components: [
    component('cola', { amountMinor: 3_000 }),
    component('water', { amountMinor: null }),
  ],
};

function render(groups: readonly MenuItemComboGroup[], picks: ComboPicks = {}, showUnmet = false) {
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useValue: new FakeTranslateService() }],
  });
  const fixture = TestBed.createComponent(ComboChoicesComponent);
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('picks', picks);
  fixture.componentRef.setInput('showUnmet', showUnmet);
  const emitted: ComboPicks[] = [];
  fixture.componentInstance.picksChange.subscribe((next) => emitted.push(next));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const all = (testId: string) => [
    ...host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`),
  ];
  return { fixture, host, emitted, all };
}

describe('ComboChoicesComponent (ADR 0136)', () => {
  it('draws each group with its range and each component by its own name and price inside this combo', () => {
    const { all, host } = render([MAIN, DRINK]);

    expect(all('combo-group')).toHaveLength(2);
    const text = host.textContent ?? '';
    expect(text).toContain('Main');
    expect(text).toContain('product.comboPickExactly:{"min":1}');
    expect(text).toContain('product.comboPickUpTo:{"max":2}');
    expect(text).toContain('wrap large');
    expect(text).toMatch(/25.000/);
    expect(text).toContain('product.comboNotPriced');
  });

  it('draws a one-pick group as radios and a repeating group as steppers', () => {
    const { all } = render([MAIN, DRINK]);

    expect(all('combo-radio')).toHaveLength(3);
    expect(all('combo-increase')).toHaveLength(2);
  });

  it('shows a sold-out component as sold out and does not let it be picked', () => {
    const { all, emitted } = render([MAIN]);

    expect(all('combo-sold-out')).toHaveLength(1);
    const radios = all('combo-radio') as HTMLInputElement[];
    expect(radios[2].disabled).toBe(true);
    radios[2].click();
    expect(emitted).toEqual([]);
  });

  it('a tap on a radio replaces the group’s pick', () => {
    const { all, emitted } = render([MAIN], { burger: 1 });

    (all('combo-radio')[1] as HTMLInputElement).click();

    expect(emitted).toEqual([{ wrap: 1 }]);
  });

  it('a stepper raises a component up to what the group has room for, and no further', () => {
    const { all, emitted, fixture } = render([DRINK], { cola: 2 });

    // The group already holds its two: the plus is disabled and a click raises nothing.
    expect((all('combo-increase')[0] as HTMLButtonElement).disabled).toBe(true);
    (all('combo-increase')[0] as HTMLButtonElement).click();
    expect(emitted).toEqual([]);

    (all('combo-decrease')[0] as HTMLButtonElement).click();
    expect(emitted).toEqual([{ cola: 1 }]);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('names the group still short once the customer has started choosing, and not before', () => {
    const quiet = render([MAIN], {}, false);
    expect(quiet.all('combo-group-unmet')).toHaveLength(0);
    TestBed.resetTestingModule();

    const touched = render([MAIN], {}, true);
    expect(touched.all('combo-group-unmet')).toHaveLength(1);
  });
});
