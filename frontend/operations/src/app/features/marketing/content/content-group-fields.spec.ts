import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { Combobox } from '../../../shared/ui/combobox';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { RuleList } from '../../../shared/ui/rule-list';
import { ContentDocument } from './content-api';
import { ContentGroupFields, MAX_GROUP_MEMBERS } from './content-group-fields';
import { emptyDocument } from './content-document';
import { ContentReferences } from './content-references';

const DISHES = [
  { id: 'd1', label: 'Margherita' },
  { id: 'd2', label: 'Pepperoni' },
  { id: 'd3', label: 'Cola' },
];
const CATEGORIES = [{ id: 'c1', label: 'Pizza' }];

function setup(document: ContentDocument = emptyDocument('PRODUCT_GROUP')): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentGroupFields>>;
  emitted: ContentDocument[];
  root: HTMLElement;
} {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: ContentReferences,
        useValue: {
          products: (q: string) =>
            DISHES.filter((d) => d.label.toLowerCase().includes(q.toLowerCase())),
          categories: () => CATEGORIES,
          productLabel: (id: string) => DISHES.find((d) => d.id === id)?.label ?? id,
          categoryLabel: (id: string) => CATEGORIES.find((c) => c.id === id)?.label ?? id,
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentGroupFields);
  fixture.componentRef.setInput('document', document);
  fixture.componentRef.setInput('locale', 'ru');
  const emitted: ContentDocument[] = [];
  fixture.componentInstance.documentChange.subscribe((d) => emitted.push(d));
  fixture.detectChanges();
  return { fixture, emitted, root: fixture.nativeElement as HTMLElement };
}

const comboboxOf = (
  fixture: ReturnType<typeof TestBed.createComponent<ContentGroupFields>>,
): Combobox => fixture.debugElement.query(By.directive(Combobox)).componentInstance as Combobox;

describe('ContentGroupFields (ADR 0157, Decision 5: home groups)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('titles the group in the language being edited, up to 60 characters', () => {
    const { root, emitted } = setup();
    const input = root.querySelector<HTMLInputElement>('[data-testid="content-title"]')!;
    expect(input.getAttribute('maxlength')).toBe('60');

    input.value = 'Популярное';
    input.dispatchEvent(new Event('input'));

    expect(emitted.at(-1)?.['texts']).toEqual({ ru: { title: 'Популярное' } });
  });

  it('starts as a manual group of hand-picked dishes', () => {
    const { root } = setup();

    expect(root.querySelector('[data-testid="content-group-members"]')).toBeTruthy();
    expect(root.querySelector('[data-testid="content-group-category"]')).toBeNull();
  });

  it('adds a dish the combobox reports, keeping the dishes already chosen', () => {
    const { fixture, emitted } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [{ type: 'PRODUCT', id: 'd1' }],
    });

    comboboxOf(fixture).selectedChange.emit([DISHES[0], DISHES[1]]);

    expect(emitted.at(-1)?.['members']).toEqual([
      { type: 'PRODUCT', id: 'd1' },
      { type: 'PRODUCT', id: 'd2' },
    ]);
  });

  it('does not offer a dish that is already in the group', () => {
    const { fixture } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [{ type: 'PRODUCT', id: 'd1' }],
    });

    expect(
      comboboxOf(fixture)
        .options()
        .map((o) => o.id),
    ).toEqual(['d2', 'd3']);
  });

  it('removes a dish when its chip is removed', () => {
    const { fixture, emitted } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [
        { type: 'PRODUCT', id: 'd1' },
        { type: 'PRODUCT', id: 'd2' },
      ],
    });

    comboboxOf(fixture).selectedChange.emit([DISHES[1]]);

    expect(emitted.at(-1)?.['members']).toEqual([{ type: 'PRODUCT', id: 'd2' }]);
  });

  it('never holds more than the 40 dishes the grammar accepts', () => {
    const { fixture, emitted } = setup();
    const many = Array.from({ length: MAX_GROUP_MEMBERS + 5 }, (_, i) => ({
      id: `d${i}`,
      label: `Dish ${i}`,
    }));

    comboboxOf(fixture).selectedChange.emit(many);

    expect((emitted.at(-1)?.['members'] as unknown[]).length).toBe(MAX_GROUP_MEMBERS);
  });

  it('orders the dishes on the flat sortable list, with no enable switch', () => {
    const { fixture, emitted, root } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [
        { type: 'PRODUCT', id: 'd1' },
        { type: 'PRODUCT', id: 'd2' },
        { type: 'PRODUCT', id: 'd3' },
      ],
    });
    const list = fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;

    expect(list.showToggle()).toBe(false);
    expect(root.querySelectorAll('.rule-list__toggle')).toHaveLength(0);
    list.reorder.emit(['d3', 'd1', 'd2']);

    expect(emitted.at(-1)?.['members']).toEqual([
      { type: 'PRODUCT', id: 'd3' },
      { type: 'PRODUCT', id: 'd1' },
      { type: 'PRODUCT', id: 'd2' },
    ]);
  });

  it('offers no ordering list for a single dish', () => {
    const { fixture } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [{ type: 'PRODUCT', id: 'd1' }],
    });

    expect(fixture.debugElement.query(By.directive(RuleList))).toBeNull();
  });

  it('switches to a category and drops the hand-picked dishes from the document', () => {
    const { root, emitted } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      members: [{ type: 'PRODUCT', id: 'd1' }],
    });

    (root.querySelector('[data-testid="content-group-by-category"]') as HTMLInputElement).click();

    expect(emitted.at(-1)).toMatchObject({
      mode: 'BY_CATEGORY',
      members: [],
      order: 'MENU_ORDER',
      limit: 8,
    });
  });

  it('picks the category, its order and its limit', () => {
    const { fixture, emitted, root } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      mode: 'BY_CATEGORY',
      members: [],
      order: 'MENU_ORDER',
      limit: 8,
    });

    comboboxOf(fixture).optionSelected.emit(CATEGORIES[0]);
    expect(emitted.at(-1)?.['categoryId']).toBe('c1');

    const select = root.querySelector<HTMLSelectElement>('[data-testid="content-group-order"]')!;
    select.value = 'NEWEST';
    select.dispatchEvent(new Event('change'));
    expect(emitted.at(-1)?.['order']).toBe('NEWEST');

    (
      fixture.debugElement.query(By.directive(NumberStepper)).componentInstance as NumberStepper
    ).valueChange.emit(12);
    expect(emitted.at(-1)?.['limit']).toBe(12);
  });

  it('names the chosen category and lets the author change it', () => {
    const { root, emitted } = setup({
      ...emptyDocument('PRODUCT_GROUP'),
      mode: 'BY_CATEGORY',
      categoryId: 'c1',
      order: 'MENU_ORDER',
      limit: 8,
    });

    expect(
      root.querySelector('[data-testid="content-group-category-chosen"]')?.textContent,
    ).toContain('Pizza');
    (
      root.querySelector('[data-testid="content-group-category-clear"]') as HTMLButtonElement
    ).click();

    expect('categoryId' in emitted.at(-1)!).toBe(false);
  });
});
