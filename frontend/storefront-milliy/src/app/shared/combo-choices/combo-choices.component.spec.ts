import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import type { MenuItemComboComponent, MenuItemComboGroup } from '../../types/home.types';
import type { ComboPicks } from '../../utils/combo-selection';
import { ComboChoicesComponent } from './combo-choices.component';

class FakeTranslateService {
  current = (): Record<string, unknown> => ({});
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
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
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(ComboChoicesComponent);
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('picks', picks);
  fixture.componentRef.setInput('currency', 'UZS');
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
    expect(text).toContain('combo.pickExactly');
    expect(text).toContain('combo.pickUpTo');
    expect(text).toContain('wrap large');
    expect(text).toMatch(/25.000/);
    expect(text).toContain('combo.notPriced');
  });

  it('draws a one-pick group as buttons and a repeating group as steppers', () => {
    const { all } = render([MAIN, DRINK]);

    expect(all('combo-component').filter((el) => el.tagName === 'BUTTON')).toHaveLength(3);
    expect(all('combo-increase')).toHaveLength(2);
  });

  it('shows a sold-out component as sold out and does not let it be picked', () => {
    const { all, emitted } = render([MAIN]);

    expect(all('combo-sold-out')).toHaveLength(1);
    const buttons = all('combo-component') as HTMLButtonElement[];
    expect(buttons[2].disabled).toBe(true);
    buttons[2].click();
    expect(emitted).toEqual([]);
  });

  it('a tap on a one-pick group replaces its pick', () => {
    const { all, emitted } = render([MAIN], { burger: 1 });

    (all('combo-component')[1] as HTMLButtonElement).click();

    expect(emitted).toEqual([{ wrap: 1 }]);
  });

  it('marks the picked component as pressed', () => {
    const { all } = render([MAIN], { wrap: 1 });

    expect(all('combo-component').map((el) => el.getAttribute('aria-pressed'))).toEqual([
      'false',
      'true',
      'false',
    ]);
  });

  it('a stepper raises a component up to what the group has room for, and no further', () => {
    const { all, emitted } = render([DRINK], { cola: 2 });

    expect((all('combo-increase')[0] as HTMLButtonElement).disabled).toBe(true);
    (all('combo-increase')[0] as HTMLButtonElement).click();
    expect(emitted).toEqual([]);

    (all('combo-decrease')[0] as HTMLButtonElement).click();
    expect(emitted).toEqual([{ cola: 1 }]);
  });

  it('names the group still short once the guest has started choosing, and not before', () => {
    const quiet = render([MAIN], {}, false);
    expect(quiet.host.querySelector('.group.is-missing')).toBeNull();
    TestBed.resetTestingModule();

    const touched = render([MAIN], {}, true);
    expect(touched.host.querySelector('.group.is-missing')).not.toBeNull();
  });
});
