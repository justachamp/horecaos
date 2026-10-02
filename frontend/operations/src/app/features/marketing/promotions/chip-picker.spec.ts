import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { PromotionChipPicker } from './chip-picker';

const OPTIONS = [
  { value: 'v-cola', label: 'Cola' },
  { value: 'v-fanta', label: 'Fanta' },
  { value: 'v-colada', label: 'Pina colada' },
];

@Component({
  selector: 'q-chip-picker-host',
  imports: [PromotionChipPicker],
  template: `<q-promotion-chip-picker
    [options]="options"
    [selected]="selected()"
    [limit]="limit()"
    (selectedChange)="selected.set($event)"
  />`,
})
class Host {
  readonly options = OPTIONS;
  readonly selected = signal<readonly string[]>([]);
  readonly limit = signal(30);
}

describe('PromotionChipPicker', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  function type(text: string): void {
    const input = el.querySelector<HTMLInputElement>('.picker__filter')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function chips(): string[] {
    return [...el.querySelectorAll('.chip')].map((chip) => chip.textContent!.trim());
  }

  it('shows nothing to pick until the operator types, so a long menu is not a wall of buttons', () => {
    expect(chips()).toEqual([]);
  });

  it('finds dishes by what is typed, case-insensitively, and picking one selects it', () => {
    type('col');
    expect(chips()).toEqual(['Cola', 'Pina colada']);
    el.querySelectorAll<HTMLButtonElement>('.chip')[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.selected()).toEqual(['v-cola']);
    // The selected one stays on show and is no longer offered again as a match.
    expect(chips()).toEqual(['Cola', 'Pina colada']);
    expect(el.querySelectorAll('.chip--active')).toHaveLength(1);
  });

  it('removes a selected dish when its chip is pressed', () => {
    fixture.componentInstance.selected.set(['v-fanta']);
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('.chip--active')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.selected()).toEqual([]);
  });

  it('still shows a selected id the list no longer holds, under its raw value', () => {
    fixture.componentInstance.selected.set(['v-withdrawn']);
    fixture.detectChanges();
    expect(chips()).toEqual(['v-withdrawn']);
  });

  it('caps the matches it renders and says how many it left out', () => {
    fixture.componentInstance.limit.set(1);
    fixture.detectChanges();
    type('col');
    expect(chips()).toEqual(['Cola']);
    expect(el.textContent).toContain('1 more not shown');
  });
});
