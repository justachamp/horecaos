import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { AddToCategoryDialog, AddToCategorySubmission } from './add-to-category-dialog';
import { CategorySummary } from './catalog-domain';

const CATEGORIES: readonly CategorySummary[] = [
  {
    categoryId: 'cat-1',
    code: 'HOT',
    name: 'Горячее',
    sortOrder: 0,
    status: 'ACTIVE',
    productCount: 2,
  },
  {
    categoryId: 'cat-2',
    code: 'COLD',
    name: 'Холодное',
    sortOrder: 1,
    status: 'ACTIVE',
    productCount: 0,
  },
];

@Component({
  selector: 'q-host',
  imports: [AddToCategoryDialog],
  template: `
    <q-add-to-category-dialog
      [categories]="categories"
      [busy]="busy"
      [error]="error"
      (confirm)="onConfirm($event)"
      (dismiss)="dismissed = true"
    />
  `,
})
class HostComponent {
  categories: readonly CategorySummary[] = CATEGORIES;
  busy = false;
  error: string | null = null;
  dismissed = false;
  confirmed: AddToCategorySubmission | null = null;

  onConfirm(submission: AddToCategorySubmission): void {
    this.confirmed = submission;
  }
}

function render(): {
  fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  host: HTMLElement;
} {
  TestBed.configureTestingModule({ imports: [HostComponent] });
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('AddToCategoryDialog', () => {
  it('lists every category by name', () => {
    const { host } = render();
    expect(host.textContent).toContain('Горячее');
    expect(host.textContent).toContain('Холодное');
  });

  it('does not confirm while nothing is selected', () => {
    const { fixture, host } = render();
    (
      host.querySelector('[data-testid="add-to-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.confirmed).toBeNull();
    expect(host.querySelector('.dialog__error')).toBeTruthy();
  });

  it('confirms with the chosen category id', () => {
    const { fixture, host } = render();
    const select = host.querySelector(
      '[data-testid="add-to-category-dialog-select"]',
    ) as HTMLSelectElement;
    select.value = 'cat-2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (
      host.querySelector('[data-testid="add-to-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.confirmed).toEqual({ categoryId: 'cat-2' });
  });

  it('emits dismiss on cancel', () => {
    const { fixture, host } = render();
    (host.querySelector('.dialog__dismiss') as HTMLButtonElement).click();
    expect(fixture.componentInstance.dismissed).toBe(true);
  });

  it('shows the empty state and disables confirm when the catalog has no categories', () => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.categories = [];
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="add-to-category-dialog-select"]')).toBeFalsy();
    expect(
      (host.querySelector('[data-testid="add-to-category-dialog-confirm"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
});
