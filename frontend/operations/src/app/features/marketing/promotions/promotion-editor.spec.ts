import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { emptyConditionRow } from '../../../shared/ui/condition-types';
import {
  NO_LOOKUPS,
  PromotionDraft,
  PromotionLookups,
  andGroup,
  buildConditionCatalogue,
  emptyAction,
  emptyDraft,
} from './promotion-draft';
import { PromotionEditor } from './promotion-editor';

const LOOKUPS: PromotionLookups = {
  ...NO_LOOKUPS,
  variants: [
    { value: 'v-cola', label: 'Cola' },
    { value: 'v-fanta', label: 'Fanta' },
  ],
  locations: [
    { value: 'loc-1', label: 'Chilonzor' },
    { value: 'loc-2', label: 'Yunusobod' },
  ],
};

@Component({
  selector: 'q-editor-host',
  imports: [PromotionEditor],
  template: `
    <q-promotion-editor
      [(draft)]="draft"
      [catalogue]="catalogue"
      [lookups]="lookups"
      [stackingGroups]="['menu', 'order']"
      [locations]="lookups.locations"
      [menuLocationId]="'loc-1'"
      [unsupported]="unsupported()"
      [locked]="locked()"
      [lockedHint]="'Suspend it first'"
      (menuLocationChange)="menuChanged = $event"
      (save)="saved = saved + 1"
      (cancel)="cancelled = cancelled + 1"
    />
  `,
})
class Host {
  readonly catalogue = buildConditionCatalogue(LOOKUPS);
  readonly lookups = LOOKUPS;
  readonly draft = signal<PromotionDraft>({ ...emptyDraft(), name: 'Lunch', code: 'LUNCH' });
  readonly unsupported = signal<readonly string[]>([]);
  readonly locked = signal(false);
  menuChanged: string | null = null;
  saved = 0;
  cancelled = 0;
}

describe('PromotionEditor', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  let el: HTMLElement;
  let host: Host;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  function byId<T extends HTMLElement>(id: string): T {
    return el.querySelector(`[data-testid="${id}"]`) as T;
  }

  function choose(id: string, value: string): void {
    const select = byId<HTMLSelectElement>(id);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function typeInto(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function optionValues(select: HTMLSelectElement): string[] {
    return [...select.querySelectorAll('option')].map((option) => option.value);
  }

  it('offers a markup only item scope, forces it non-exclusive, and swaps its actions and group', () => {
    host.draft.update((d) => ({ ...d, exclusive: true }));
    fixture.detectChanges();
    choose('promotion-kind', 'MARKUP');
    const draft = host.draft();
    expect(draft.kind).toBe('MARKUP');
    expect(draft.scope).toBe('ITEM');
    expect(draft.exclusive).toBe(false);
    expect(draft.stackingGroup).toBe('markup');
    expect(draft.actions.map((a) => a.type)).toEqual(['ITEM_PERCENTAGE_MARKUP']);
    expect(optionValues(byId<HTMLSelectElement>('promotion-scope'))).toEqual(['ITEM']);
    // A markup is never exclusive, so the box is not offered at all.
    expect(byId('promotion-exclusive')).toBeNull();
    expect(el.textContent).toContain('second person');
  });

  it('moves the actions with the scope: an order percentage becomes a product one, and the group follows only its own default', () => {
    choose('promotion-scope', 'ITEM');
    expect(host.draft().scope).toBe('ITEM');
    expect(host.draft().actions.map((a) => a.type)).toEqual(['ITEM_PERCENTAGE_DISCOUNT']);
    expect(host.draft().stackingGroup).toBe('menu');

    host.draft.update((d) => ({ ...d, stackingGroup: 'my-own-group' }));
    fixture.detectChanges();
    choose('promotion-scope', 'DELIVERY');
    expect(host.draft().stackingGroup).toBe('my-own-group');
    expect(host.draft().actions.map((a) => a.type)).toEqual(['FREE_DELIVERY']);
  });

  it('keeps an action that is still legal for the new scope', () => {
    host.draft.update((d) => ({
      ...d,
      scope: 'ORDER',
      actions: [
        { ...emptyAction('ORDER_FIXED_DISCOUNT'), amountMinor: 7_000 },
        emptyAction('ORDER_PERCENTAGE_DISCOUNT'),
      ],
    }));
    fixture.detectChanges();
    choose('promotion-kind', 'DISCOUNT');
    expect(host.draft().actions).toHaveLength(2);
  });

  it('adds actions and never removes the last one', () => {
    const add = [...el.querySelectorAll<HTMLButtonElement>('button.secondary')].find((b) =>
      b.textContent?.includes('Add an action'),
    )!;
    add.click();
    fixture.detectChanges();
    expect(host.draft().actions).toHaveLength(2);
    const removers = el.querySelectorAll<HTMLButtonElement>('.action-row__remove');
    expect([...removers].every((r) => !r.disabled)).toBe(true);
    removers[0].click();
    fixture.detectChanges();
    expect(host.draft().actions).toHaveLength(1);
    expect(el.querySelector<HTMLButtonElement>('.action-row__remove')!.disabled).toBe(true);
  });

  it('edits a percentage in basis points', () => {
    const input = el.querySelector<HTMLInputElement>('q-percent-input input')!;
    typeInto(input, '12.5');
    expect(host.draft().actions[0].basisPoints).toBe(1_250);
  });

  it('edits a fixed amount in whole minor units', () => {
    choose('promotion-action-type', 'ORDER_FIXED_DISCOUNT');
    const input = el.querySelector<HTMLInputElement>('q-money-input input')!;
    typeInto(input, '45 000');
    expect(host.draft().actions[0]).toMatchObject({
      type: 'ORDER_FIXED_DISCOUNT',
      amountMinor: 45_000,
    });
  });

  it('builds a gift: the dishes are searched for, and "for every n units" reveals the trigger', () => {
    choose('promotion-scope', 'ITEM');
    choose('promotion-action-type', 'FREE_ITEM');
    typeInto(el.querySelector<HTMLInputElement>('q-promotion-chip-picker input')!, 'col');
    el.querySelector<HTMLButtonElement>('q-promotion-chip-picker .chip')!.click();
    fixture.detectChanges();
    expect(host.draft().actions[0].variantIds).toEqual(['v-cola']);

    expect(el.querySelector('input[min="1"][type="number"]:not([data-testid])')).toBeNull();
    choose('promotion-gift-mode', 'PER_MULTIPLE');
    expect(host.draft().actions[0].mode).toBe('PER_MULTIPLE');
    expect(el.textContent).toContain('Matched units per gift unit');
    typeInto(byId<HTMLInputElement>('promotion-gift-quantity'), '3');
    expect(host.draft().actions[0].quantity).toBe(3);
  });

  it('reveals a limit’s number only once the limit is switched on', () => {
    expect(el.textContent).not.toContain('Redemptions allowed in total');
    const box = byId<HTMLInputElement>('promotion-has-total-limit');
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.draft().hasTotalLimit).toBe(true);
    expect(el.textContent).toContain('Redemptions allowed in total');
  });

  it('lets the last condition go: no condition means every order', () => {
    host.draft.update((d) => ({
      ...d,
      conditions: [andGroup([emptyConditionRow(host.catalogue, 'FIRST_ORDER')])],
    }));
    fixture.detectChanges();
    const remove = el.querySelector<HTMLButtonElement>('.predicate-row__remove')!;
    expect(remove.disabled).toBe(false);
    remove.click();
    fixture.detectChanges();
    expect(host.draft().conditions[0].rows).toHaveLength(0);
  });

  it('asks which branch’s menu the dish lists come from', () => {
    choose('promotion-menu-branch', 'loc-2');
    expect(host.menuChanged).toBe('loc-2');
  });

  it('refuses to save until the form is complete, and says what is missing', () => {
    host.draft.set({ ...emptyDraft(), name: '', code: '' });
    fixture.detectChanges();
    expect(byId<HTMLButtonElement>('promotion-save').disabled).toBe(true);
    expect(byId('promotion-problems').textContent).toContain('Give the promotion a name');
    byId<HTMLFormElement>('promotion-name').closest('form')!.dispatchEvent(new Event('submit'));
    expect(host.saved).toBe(0);

    host.draft.set({ ...emptyDraft(), name: 'Lunch', code: 'LUNCH' });
    fixture.detectChanges();
    expect(byId<HTMLButtonElement>('promotion-save').disabled).toBe(false);
    byId<HTMLButtonElement>('promotion-save').click();
    expect(host.saved).toBe(1);
  });

  it('is read-only, with no save, when the promotion holds a rule it cannot draw', () => {
    host.unsupported.set(['SERVICE_CHARGE']);
    fixture.detectChanges();
    expect(byId('promotion-unsupported').textContent).toContain('SERVICE_CHARGE');
    expect(byId('promotion-save')).toBeNull();
    expect([...el.querySelectorAll('fieldset')].every((f) => f.disabled)).toBe(true);
  });

  it('is read-only with its reason when locked (a live promotion)', () => {
    host.locked.set(true);
    fixture.detectChanges();
    expect(byId('promotion-locked').textContent).toContain('Suspend it first');
    expect(byId('promotion-save')).toBeNull();
    expect([...el.querySelectorAll('fieldset')].every((f) => f.disabled)).toBe(true);
  });

  it('cancels', () => {
    [...el.querySelectorAll<HTMLButtonElement>('.editor__actions button')]
      .find((b) => b.textContent?.includes('Cancel'))!
      .click();
    expect(host.cancelled).toBe(1);
  });
});
