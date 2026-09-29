import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

export interface CreateProductSubmission {
  readonly code: string;
  readonly name: string;
}

/**
 * `Создать товар` — catalog.md §4.1. `CreateProductRequest` needs only
 * `code`/`name`/`locale` at the edge (everything else — description, SKU,
 * fiscal — is filled in afterwards on the full editor), so this dialog asks
 * for the code and the name and hands off to `ProductEditorPage` once the
 * product exists.
 *
 * **Row 10.12 — the name's language is the brand's, not the operator's.** The
 * dialog does not choose a `locale`: {@link ProductsPage} writes the name in
 * the catalog locale its own list reads resolve in (the brand's default
 * language, or the server's when the brand has chosen none), because a name
 * authored in whatever the operator's console happened to be set to shows the
 * product's bare code in that list. {@link languageName} tells the operator
 * which language that is, so typing a Russian name into a brand whose default
 * is Uzbek is a visible choice rather than a silent one.
 */
@Component({
  selector: 'q-create-product-dialog',
  imports: [TPipe],
  templateUrl: './create-product-dialog.html',
  styleUrl: './create-product-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateProductDialog {
  readonly busy = input(false);
  /** The name of the language the product is written in ("Uzbek (Latin)"), shown under the name field. */
  readonly languageName = input<string | null>(null);
  /**
   * Whether {@link languageName} is a language the brand itself chose. False for a
   * brand that has chosen none: the name is then written in the catalog's own
   * default, and the hint must not credit the brand with a choice it never made.
   */
  readonly languageChosenByBrand = input(true);
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
    this.confirm.emit({ code, name });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
