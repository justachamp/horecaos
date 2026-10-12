import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MAX_SAVED_VIEWS, SavedView } from '../../core/preferences/preference-documents';
import { OrderSavedViews } from './order-saved-views';

function view(index: number, label = `View ${index}`): SavedView {
  return {
    id: `0198a3c0-0000-7000-8000-${String(index).padStart(12, '0')}`,
    label,
    tab: index % 2 === 0 ? 'attention' : 'delivering',
    filters: { lateOnly: true },
  };
}

describe('OrderSavedViews (ADR 0160)', () => {
  let fixture: ComponentFixture<OrderSavedViews>;
  let applied: SavedView[];
  let saved: string[];
  let removed: string[];

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function query<T extends HTMLElement>(testId: string): T | null {
    return host().querySelector<T>(`[data-testid="${testId}"]`);
  }

  async function render(
    views: readonly SavedView[],
    inputs: { persistent?: boolean; leftOut?: boolean } = {},
  ): Promise<void> {
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(OrderSavedViews);
    fixture.componentRef.setInput('views', views);
    fixture.componentRef.setInput('persistent', inputs.persistent ?? true);
    fixture.componentRef.setInput('leftOut', inputs.leftOut ?? false);
    fixture.componentInstance.applied.subscribe((v) => applied.push(v));
    fixture.componentInstance.saved.subscribe((label) => saved.push(label));
    fixture.componentInstance.removed.subscribe((id) => removed.push(id));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function open(): void {
    query<HTMLButtonElement>('saved-views-toggle')!.click();
    fixture.detectChanges();
  }

  function startNaming(): void {
    open();
    query<HTMLButtonElement>('saved-view-new')!.click();
    fixture.detectChanges();
  }

  function type(text: string): void {
    const input = query<HTMLInputElement>('saved-view-name')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    applied = [];
    saved = [];
    removed = [];
  });

  it('shows how many views there are on the button and lists them, each with its tab', async () => {
    await render([view(1), view(2)]);

    expect(query('saved-views-toggle')?.textContent).toContain('My views');
    expect(query('saved-views-toggle')?.textContent).toContain('2');
    open();
    const applyButtons = host().querySelectorAll('[data-testid="saved-view-apply"]');
    expect(applyButtons).toHaveLength(2);
    expect(applyButtons[0].textContent).toContain('View 1');
    expect(applyButtons[0].textContent).toContain('Delivering');
    expect(applyButtons[1].textContent).toContain('Attention');
  });

  it('says there are none yet, and offers to save the current one', async () => {
    await render([]);
    open();

    expect(query('saved-views-empty')?.textContent).toContain('No saved views yet');
    expect(query('saved-view-new')).not.toBeNull();
  });

  it('opens a view when it is picked, and closes', async () => {
    await render([view(1), view(2)]);
    open();

    host().querySelectorAll<HTMLButtonElement>('[data-testid="saved-view-apply"]')[1].click();
    fixture.detectChanges();

    expect(applied).toEqual([view(2)]);
    expect(query('saved-views-menu')).toBeNull();
  });

  it('deletes a view without opening it', async () => {
    await render([view(1), view(2)]);
    open();

    host().querySelectorAll<HTMLButtonElement>('[data-testid="saved-view-remove"]')[0].click();

    expect(removed).toEqual([view(1).id]);
    expect(applied).toEqual([]);
  });

  it('saves the current board under the name typed, stripped of the spaces around it', async () => {
    await render([]);
    startNaming();

    type('  Late deliveries  ');
    query<HTMLButtonElement>('saved-view-save')!.click();
    fixture.detectChanges();

    expect(saved).toEqual(['Late deliveries']);
    expect(query('saved-view-name')).toBeNull();
  });

  it('refuses, in words and before the platform does, a name that would be refused', async () => {
    await render([]);
    startNaming();

    const cases: Array<[string, string]> = [
      ['   ', 'Enter a name'],
      ['x'.repeat(41), 'At most 40 characters'],
      ['bell\u0007', 'control characters'],
      ['+998 90 123 45 67', 'cannot be a phone number'],
      ['998901234567', 'cannot be a phone number'],
    ];
    for (const [name, words] of cases) {
      type(name);
      query<HTMLButtonElement>('saved-view-save')!.click();
      fixture.detectChanges();
      expect(query('saved-view-problem')?.textContent, name).toContain(words);
    }
    expect(saved).toEqual([]);
  });

  it('does not scold an empty box before anybody has typed, and accepts a date and a name that looks like a person’s', async () => {
    await render([]);
    startNaming();
    expect(query('saved-view-problem')).toBeNull();

    type('Since 2026-10-08');
    expect(query('saved-view-problem')).toBeNull();
    query<HTMLButtonElement>('saved-view-save')!.click();
    fixture.detectChanges();

    // The platform cannot tell a name from a label, which is why the whole document is protected.
    query<HTMLButtonElement>('saved-view-new')!.click();
    fixture.detectChanges();
    type('Jasur Karimov');
    query<HTMLButtonElement>('saved-view-save')!.click();
    fixture.detectChanges();

    expect(saved).toEqual(['Since 2026-10-08', 'Jasur Karimov']);
  });

  it('stops offering a twenty-first view and says why', async () => {
    const full = Array.from({ length: MAX_SAVED_VIEWS }, (_, index) => view(index + 1));
    await render(full);
    open();

    expect(query('saved-view-new')).toBeNull();
    expect(query('saved-views-limit')?.textContent).toContain('At most 20 views');
  });

  it('tells a person when the board has a filter a view cannot keep', async () => {
    await render([], { leftOut: true });
    startNaming();

    expect(query('saved-view-left-out')?.textContent).toContain('leaves out the search');
  });

  it('says whether views follow the person or last only for the session', async () => {
    await render([], { persistent: true });
    open();
    expect(query('saved-views-note')?.textContent).toContain('kept in your account');

    fixture.componentRef.setInput('persistent', false);
    fixture.detectChanges();
    expect(query('saved-views-note')?.textContent).toContain('only until this session ends');
  });

  it('stops saying views are kept in the account once the host says the last save failed', async () => {
    await render([], { persistent: true });
    open();

    fixture.componentRef.setInput('writeFailed', true);
    fixture.detectChanges();
    expect(query('saved-views-note')?.textContent).toContain('could not be saved to your account');
    expect(query('saved-views-note')?.textContent).not.toContain('kept in your account');
  });
});
