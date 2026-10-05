import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import type { MenuItemModifierGroup } from '../../types/home.types';
import type { ModifierChoices, NestedChoices } from '../../utils/modifier-selection';
import { NestedChoicesComponent } from './nested-choices.component';

class FakeTranslateService {
  current = (): Record<string, unknown> => ({});
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
}

const HEAT: MenuItemModifierGroup = {
  id: 'heat',
  name: 'Heat',
  required: true,
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameOptionMultipleTimes: false,
  options: [
    { id: 'hot', label: 'Hot', amountMinor: 500, maximumQuantity: 1 },
    { id: 'mild', label: 'Mild', amountMinor: null, maximumQuantity: 1 },
  ],
};

const SAUCES: MenuItemModifierGroup = {
  id: 'sauces',
  name: 'Sauces',
  required: false,
  minimumSelections: 0,
  maximumSelections: 2,
  allowSameOptionMultipleTimes: false,
  options: [
    { id: 'chili', label: 'Chili', amountMinor: 1000, maximumQuantity: 1, nestedGroups: [HEAT] },
    { id: 'garlic', label: 'Garlic', amountMinor: null, maximumQuantity: 1 },
  ],
};

function render(
  choices: ModifierChoices,
  nested: NestedChoices = {},
  showUnmet = false,
  groups: readonly MenuItemModifierGroup[] = [SAUCES],
) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(NestedChoicesComponent);
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('choices', choices);
  fixture.componentRef.setInput('nested', nested);
  fixture.componentRef.setInput('currency', 'UZS');
  fixture.componentRef.setInput('showUnmet', showUnmet);
  const emitted: NestedChoices[] = [];
  fixture.componentInstance.nestedChange.subscribe((next) => emitted.push(next));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, emitted };
}

describe('NestedChoicesComponent', () => {
  it('draws nothing while no chosen option opens choices', () => {
    expect(render({}).host.querySelector('[data-testid="nested-group"]')).toBeNull();
    expect(
      render({ sauces: ['garlic'] }).host.querySelector('[data-testid="nested-group"]'),
    ).toBeNull();
  });

  it('draws the choices of a chosen option under its name, with the rule and what each costs', () => {
    const { host } = render({ sauces: ['chili'] });

    expect(host.querySelector('[data-testid="nested-parent"]')?.textContent).toContain(
      'nested.for',
    );
    expect(host.querySelector('[data-testid="nested-parent"]')?.textContent).toContain('Chili');
    expect(host.querySelector('[data-testid="nested-group"]')?.textContent).toContain('Heat');
    expect(host.querySelector('[data-testid="nested-group-required"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="nested-group-rule"]')?.textContent).toContain(
      'dineIn.pickerExactly',
    );
    const options = [...host.querySelectorAll('[data-testid="nested-option"]')];
    expect(options.map((o) => o.textContent)).toEqual([
      expect.stringContaining('Hot'),
      expect.stringContaining('Mild'),
    ]);
    expect(options[0].textContent).toMatch(/\+\s?500/);
  });

  it('raises the answers with the tap under the parent, and marks the chosen one', () => {
    const { host, emitted, fixture } = render({ sauces: ['chili'] });

    (host.querySelectorAll('[data-testid="nested-option"]')[0] as HTMLButtonElement).click();
    expect(emitted).toEqual([{ chili: { heat: ['hot'] } }]);

    fixture.componentRef.setInput('nested', { chili: { heat: ['hot'] } });
    fixture.detectChanges();
    expect(
      host.querySelectorAll('[data-testid="nested-option"]')[0].getAttribute('aria-pressed'),
    ).toBe('true');
  });

  it('says which required group is still to be answered, but only once the guest has started', () => {
    expect(render({ sauces: ['chili'] }).host.querySelector('.group.is-missing')).toBeNull();
    expect(
      render({ sauces: ['chili'] }, {}, true).host.querySelector('.group.is-missing'),
    ).not.toBeNull();
    expect(
      render({ sauces: ['chili'] }, { chili: { heat: ['mild'] } }, true).host.querySelector(
        '.group.is-missing',
      ),
    ).toBeNull();
  });
});
