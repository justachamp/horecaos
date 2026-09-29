import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

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
 *
 * **Row 10.12.** The `locale` is not the operator's own console language: the
 * name a person types here is the brand's, so it is written in the locale the
 * catalog lists resolve names in (`listResolutionLocale`), which the page
 * passes in. A create authored in whatever the operator's UI was set to would
 * leave a brand-new product showing its bare code in every list that reads the
 * brand's default.
 */
@Component({
  selector: 'q-create-product-dialog',
  imports: [TPipe],
  templateUrl: './create-product-dialog.html',
  styleUrl: './create-product-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateProductDialog {
  /** The catalog locale the brand's list screens resolve names in; the name typed here is written under it. */
  readonly locale = input.required<string>();
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
    // The brand's list locale, never the operator's console language; the other
    // locales are added afterwards on the editor's locale switcher.
    this.confirm.emit({ code, name, locale: this.locale() });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
