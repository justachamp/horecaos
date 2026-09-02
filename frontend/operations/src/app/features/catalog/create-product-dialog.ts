import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { toCatalogLocale } from './catalog-domain';

export interface CreateProductSubmission {
  readonly code: string;
  readonly name: string;
  readonly locale: string;
}

/**
 * `Создать товар` — catalog.md §4.1. `CreateProductRequest` needs only
 * `code`/`name`/`locale` at the edge (everything else — description, SKU,
 * fiscal — is filled in afterwards on the full editor), so this dialog asks
 * for exactly those three and hands off to `ProductEditorPage` once the
 * product exists.
 */
@Component({
  selector: 'q-create-product-dialog',
  imports: [TPipe],
  templateUrl: './create-product-dialog.html',
  styleUrl: './create-product-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateProductDialog {
  private readonly i18n = inject(I18n);

  readonly busy = input(false);
  readonly error = input<string | null>(null);

  readonly confirm = output<CreateProductSubmission>();
  readonly dismiss = output<void>();

  protected readonly code = signal('');
  protected readonly name = signal('');
  private readonly touched = signal(false);

  protected readonly codeMissing = computed(() => this.touched() && this.code().trim() === '');
  protected readonly nameMissing = computed(() => this.touched() && this.name().trim() === '');

  protected setCode(value: string): void {
    this.code.set(value);
  }

  protected setName(value: string): void {
    this.name.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const code = this.code().trim();
    const name = this.name().trim();
    if (!code || !name) {
      return;
    }
    // `locale` is the brand's default locale in the general case; until a
    // brand-configuration read exists on this console (`current-brand.ts`'s
    // own scope note), the operator's own console locale is the
    // least-wrong choice available — an author can add the other locales
    // immediately afterwards on Tab 1's locale switcher.
    this.confirm.emit({ code, name, locale: toCatalogLocale(this.i18n.locale()) });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
