import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { CategorySummary } from './catalog-domain';

export interface AddToCategorySubmission {
  readonly categoryId: string;
}

/**
 * catalog.md §4.1's add-to-category row action. `PUT
 * .../categories/{categoryId}/products/{productId}` (`CatalogAuthoringController.placeInCategory`)
 * has existed with no caller anywhere; this dialog is that caller — pick one
 * of the active catalog's categories, place the product at the end of it.
 */
@Component({
  selector: 'q-add-to-category-dialog',
  imports: [TPipe],
  templateUrl: './add-to-category-dialog.html',
  styleUrl: './add-to-category-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddToCategoryDialog {
  readonly categories = input.required<readonly CategorySummary[]>();
  readonly busy = input(false);
  readonly error = input<string | null>(null);

  readonly confirm = output<AddToCategorySubmission>();
  readonly dismiss = output<void>();

  protected readonly categoryId = signal<string | null>(null);
  private readonly touched = signal(false);

  protected readonly missing = computed(() => this.touched() && !this.categoryId());

  protected setCategoryId(value: string): void {
    this.categoryId.set(value === '' ? null : value);
  }

  protected submit(): void {
    this.touched.set(true);
    const categoryId = this.categoryId();
    if (!categoryId) {
      return;
    }
    this.confirm.emit({ categoryId });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
