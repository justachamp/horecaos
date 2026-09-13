import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { Combobox, ComboboxOption } from './combobox';

const OPTIONS: readonly ComboboxOption[] = [
  { id: 'c1', label: 'Alisher Karimov' },
  { id: 'c2', label: 'Alisher Yusupov' },
];

function render(): ReturnType<typeof TestBed.createComponent<Combobox>> {
  const fixture = TestBed.createComponent(Combobox);
  fixture.detectChanges();
  return fixture;
}

function input(fixture: ReturnType<typeof TestBed.createComponent<Combobox>>): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="q-combobox-input"]',
  )!;
}

describe('Combobox', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  it('emits the typed text immediately', () => {
    const fixture = render();
    let emitted: string | undefined;
    fixture.componentInstance.queryChange.subscribe((q) => (emitted = q));
    const field = input(fixture);

    field.value = 'Alisher';
    field.dispatchEvent(new Event('input'));

    expect(emitted).toBe('Alisher');
  });

  it('debounces the search event until typing settles', () => {
    const fixture = render();
    let searchCount = 0;
    let lastSearch: string | undefined;
    fixture.componentInstance.search.subscribe((q) => {
      searchCount += 1;
      lastSearch = q;
    });
    const field = input(fixture);

    field.value = 'A';
    field.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(100);
    field.value = 'Al';
    field.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(100);
    field.value = 'Ali';
    field.dispatchEvent(new Event('input'));

    expect(searchCount).toBe(0);
    vi.advanceTimersByTime(250);
    expect(searchCount).toBe(1);
    expect(lastSearch).toBe('Ali');
  });

  it('opens the listbox and shows the given options', () => {
    const fixture = render();
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.detectChanges();
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="q-combobox-option"]',
    );
    expect(rows).toHaveLength(2);
  });

  it('selects an option on click in single mode', () => {
    const fixture = render();
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.detectChanges();
    let selected: ComboboxOption | undefined;
    fixture.componentInstance.optionSelected.subscribe((o) => (selected = o));
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="q-combobox-option"]',
      )[0] as HTMLElement
    ).click();

    expect(selected?.id).toBe('c1');
  });

  it('shows the create-on-miss row only when nothing is a perfect fit and allowCreate is set', () => {
    const fixture = render();
    fixture.componentRef.setInput('allowCreate', true);
    fixture.componentRef.setInput('options', []);
    fixture.componentRef.setInput('query', '+998901234567');
    fixture.detectChanges();
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    const createRow = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-combobox-create-row"]',
    );
    expect(createRow).not.toBeNull();
    expect(createRow?.textContent).toContain('+998901234567');
  });

  it('emits create with the raw typed text when the create row is activated', () => {
    const fixture = render();
    fixture.componentRef.setInput('allowCreate', true);
    fixture.componentRef.setInput('query', '+998901234567');
    fixture.detectChanges();
    let created: string | undefined;
    fixture.componentInstance.create.subscribe((q) => (created = q));
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-combobox-create-row"]',
      ) as HTMLElement
    ).click();

    expect(created).toBe('+998901234567');
  });

  it('adds a chip in multi-select mode instead of emitting optionSelected', () => {
    const fixture = render();
    fixture.componentRef.setInput('multiple', true);
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.detectChanges();
    let selectedChips: readonly ComboboxOption[] | undefined;
    let singleSelected = false;
    fixture.componentInstance.selectedChange.subscribe((s) => (selectedChips = s));
    fixture.componentInstance.optionSelected.subscribe(() => (singleSelected = true));
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="q-combobox-option"]',
      )[0] as HTMLElement
    ).click();

    expect(singleSelected).toBe(false);
    expect(selectedChips?.map((o) => o.id)).toEqual(['c1']);
  });

  it('renders selected chips and removes one by its own button', () => {
    const fixture = render();
    fixture.componentRef.setInput('multiple', true);
    fixture.componentRef.setInput('selected', [OPTIONS[0], OPTIONS[1]]);
    fixture.detectChanges();
    let selectedChips: readonly ComboboxOption[] | undefined;
    fixture.componentInstance.selectedChange.subscribe((s) => (selectedChips = s));

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="q-combobox-chip-remove"]',
      )[0] as HTMLElement
    ).click();

    expect(selectedChips?.map((o) => o.id)).toEqual(['c2']);
  });

  it('removes the last chip with Backspace when the query is empty (keyboard removal)', () => {
    const fixture = render();
    fixture.componentRef.setInput('multiple', true);
    fixture.componentRef.setInput('selected', [OPTIONS[0], OPTIONS[1]]);
    fixture.componentRef.setInput('query', '');
    fixture.detectChanges();
    let selectedChips: readonly ComboboxOption[] | undefined;
    fixture.componentInstance.selectedChange.subscribe((s) => (selectedChips = s));

    input(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));

    expect(selectedChips?.map((o) => o.id)).toEqual(['c1']);
  });

  it('does not remove a chip with Backspace while there is still typed text', () => {
    const fixture = render();
    fixture.componentRef.setInput('multiple', true);
    fixture.componentRef.setInput('selected', [OPTIONS[0]]);
    fixture.componentRef.setInput('query', 'still typing');
    fixture.detectChanges();
    let emitted = false;
    fixture.componentInstance.selectedChange.subscribe(() => (emitted = true));

    input(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));

    expect(emitted).toBe(false);
  });

  it('moves the active option with ArrowDown/ArrowUp and selects it on Enter', () => {
    const fixture = render();
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.detectChanges();
    let selected: ComboboxOption | undefined;
    fixture.componentInstance.optionSelected.subscribe((o) => (selected = o));
    const field = input(fixture);
    field.dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(selected?.id).toBe('c2');
  });

  it('closes the list on Escape', () => {
    const fixture = render();
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.detectChanges();
    input(fixture).dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    input(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-combobox-listbox"]'),
    ).toBeNull();
  });
});
