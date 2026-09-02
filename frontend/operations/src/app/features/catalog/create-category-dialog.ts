import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { CategorySummary } from './catalog-domain';
import { TPipe } from '../../core/i18n/t.pipe';

export interface CreateCategorySubmission {
  readonly parentCategoryId: string | null;
  readonly code: string;
  readonly name: string;
}

/** `Создать категорию` — catalog.md §4.3. */
@Component({
  selector: 'q-create-category-dialog',
  imports: [TPipe],
  templateUrl: './create-category-dialog.html',
  styleUrl: './create-product-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateCategoryDialog {
  readonly categories = input<readonly CategorySummary[]>([]);
  readonly busy = input(false);
  readonly error = input<string | null>(null);

  readonly confirm = output<CreateCategorySubmission>();
  readonly dismiss = output<void>();

  protected readonly code = signal('');
  protected readonly name = signal('');
  protected readonly parentCategoryId = signal<string>('');
  private readonly touched = signal(false);

  protected readonly codeMissing = computed(() => this.touched() && this.code().trim() === '');
  protected readonly nameMissing = computed(() => this.touched() && this.name().trim() === '');

  protected setCode(value: string): void {
    this.code.set(value);
  }

  protected setName(value: string): void {
    this.name.set(value);
  }

  protected setParent(value: string): void {
    this.parentCategoryId.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const code = this.code().trim();
    const name = this.name().trim();
    if (!code || !name) {
      return;
    }
    this.confirm.emit({
      parentCategoryId: this.parentCategoryId() || null,
      code,
      name,
    });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
