import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ColumnPicker, ColumnPickerItem } from './column-picker';

const ITEMS: readonly ColumnPickerItem[] = [
  { key: 'courier', label: 'Courier', shown: true },
  { key: 'createdBy', label: 'Created by', shown: false },
  { key: 'discount', label: 'Discount', shown: false },
];

describe('ColumnPicker (ADR 0160)', () => {
  let fixture: ComponentFixture<ColumnPicker>;
  let toggled: Array<{ key: string; shown: boolean }>;
  let resets: number;

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function query<T extends HTMLElement>(testId: string): T | null {
    return host().querySelector<T>(`[data-testid="${testId}"]`);
  }

  async function render(
    inputs: { persistent?: boolean; canReset?: boolean } = {},
    items: readonly ColumnPickerItem[] = ITEMS,
  ): Promise<void> {
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ColumnPicker);
    fixture.componentRef.setInput('columns', items);
    fixture.componentRef.setInput('persistent', inputs.persistent ?? true);
    fixture.componentRef.setInput('canReset', inputs.canReset ?? false);
    fixture.componentInstance.toggled.subscribe((change) => toggled.push(change));
    fixture.componentInstance.resetRequested.subscribe(() => (resets += 1));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    toggled = [];
    resets = 0;
  });

  it('is a closed button until it is asked, and lists nothing', async () => {
    await render();

    expect(query('column-picker-toggle')?.textContent?.trim()).toBe('Columns');
    expect(query('column-picker-toggle')?.getAttribute('aria-expanded')).toBe('false');
    expect(query('column-picker-menu')).toBeNull();
  });

  it('lists every column with a checkbox that says whether it is shown', async () => {
    await render();
    query<HTMLButtonElement>('column-picker-toggle')!.click();
    fixture.detectChanges();

    expect(query('column-picker-menu')).not.toBeNull();
    expect(query<HTMLInputElement>('column-picker-item-courier')!.checked).toBe(true);
    expect(query<HTMLInputElement>('column-picker-item-createdBy')!.checked).toBe(false);
    expect(host().textContent).toContain('Created by');
    expect(query('column-picker-toggle')?.getAttribute('aria-expanded')).toBe('true');
  });

  it('says what a click means: turn the shown column off, turn the hidden one on', async () => {
    await render();
    query<HTMLButtonElement>('column-picker-toggle')!.click();
    fixture.detectChanges();

    query<HTMLInputElement>('column-picker-item-courier')!.click();
    query<HTMLInputElement>('column-picker-item-createdBy')!.click();

    expect(toggled).toEqual([
      { key: 'courier', shown: false },
      { key: 'createdBy', shown: true },
    ]);
  });

  it('offers the reset only when there is a choice to give up', async () => {
    await render({ canReset: false });
    query<HTMLButtonElement>('column-picker-toggle')!.click();
    fixture.detectChanges();
    expect(query('column-picker-reset')).toBeNull();

    fixture.componentRef.setInput('canReset', true);
    fixture.detectChanges();
    query<HTMLButtonElement>('column-picker-reset')!.click();

    expect(resets).toBe(1);
  });

  it('tells a person whose choice follows them from one whose choice dies with the session', async () => {
    await render({ persistent: true });
    query<HTMLButtonElement>('column-picker-toggle')!.click();
    fixture.detectChanges();
    expect(query('column-picker-note')?.textContent).toContain('saved to your account');

    fixture.componentRef.setInput('persistent', false);
    fixture.detectChanges();
    expect(query('column-picker-note')?.textContent).toContain('only until this session ends');
  });

  it('closes on Escape and on a click anywhere else, but not on a click inside', async () => {
    await render();
    const open = (): void => {
      query<HTMLButtonElement>('column-picker-toggle')!.click();
      fixture.detectChanges();
    };

    open();
    query('column-picker-menu')!.dispatchEvent(new Event('pointerdown', { bubbles: true }));
    fixture.detectChanges();
    expect(query('column-picker-menu')).not.toBeNull();

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
    fixture.detectChanges();
    expect(query('column-picker-menu')).toBeNull();

    open();
    host().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(query('column-picker-menu')).toBeNull();
  });
});
