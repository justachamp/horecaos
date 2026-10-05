import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { I18n } from '../../core/i18n/i18n';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { CatalogApi } from './catalog-api';
import { MxikReferenceRow } from './catalog-domain';

/** Spreadsheet cells and pasted codes arrive with spaces, non-breaking spaces and tabs inside them. */
function squash(raw: string): string {
  return raw.replace(/\s+/g, '');
}

function isCodeLike(squashed: string): boolean {
  return squashed === '' || /^\d+$/.test(squashed);
}

/**
 * The ИКПУ/MXIK typeahead over `catalog.mxik_reference` (gap map row `4.2e`), as one field that
 * both the product editor's fiscal tab and the fiscalization settings' classification tab can put
 * where they used to take a typed code.
 *
 * **One text box, two jobs.** What is typed is either a code (digits only: typed, or pasted from a
 * spreadsheet) or a name being looked for. A code is the field's value as it stands, so a code
 * that is not in the reference — and today none is, the official list has never been imported —
 * can still be entered, exactly as it could before there was a lookup. A name is not a value: while
 * the text is a name the field's code is empty, so nothing half-typed can reach a save. Either way
 * the reference is searched (it matches a code or a label in any of its three languages), and
 * choosing a row writes its code and tells the caller which row it was, so the caller can offer
 * the package codes the reference lists for it ({@link MxikReferenceRow.defaultPackageCodes}).
 *
 * The component never decides what a code is worth: the shape check (`MXIK_PATTERN`) stays with
 * the caller, because ADR 0038 keeps it out of the server and only the caller knows whether a
 * half-typed code is an error yet.
 */
@Component({
  selector: 'q-mxik-picker',
  imports: [Combobox],
  templateUrl: './mxik-picker.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MxikPicker {
  private readonly api = inject(CatalogApi);
  private readonly i18n = inject(I18n);

  /** The field's code as its owner holds it; the text box follows it when it changes from outside. */
  readonly code = input<string>('');
  readonly scope = input.required<BrandScope | null>();
  readonly placeholder = input<string>('');
  readonly ariaLabel = input<string | null>(null);
  /** The owner finds the code wrong (format): the text field is marked `aria-invalid`. */
  readonly invalid = input(false);
  /**
   * A value that changes when the owner throws its edits away: whatever was typed here, a name
   * half looked up included, goes with them and the field shows the owner's code again.
   */
  readonly resetKey = input<unknown>(0);

  /** Digits typed or pasted, or `''` while the text is a name being looked for. */
  readonly codeChange = output<string>();
  /** A reference row was chosen from the list. Its code has already been sent through {@link codeChange}. */
  readonly picked = output<MxikReferenceRow>();

  protected readonly text = signal('');
  protected readonly options = signal<readonly ComboboxOption[]>([]);
  protected readonly searching = signal(false);

  /** The rows the last search returned, by code: a chosen option is only an id and a label. */
  private found = new Map<string, MxikReferenceRow>();
  private searchSequence = 0;

  constructor() {
    // The owner changed the code (a pasted column, a copied default): show it, unless the
    // operator is in the middle of looking something up by name and the owner has no code.
    effect(() => {
      const code = this.code();
      untracked(() => {
        const squashed = squash(this.text());
        const lookingUpByName = !isCodeLike(squashed);
        if (lookingUpByName ? code !== '' : code !== squashed) {
          this.text.set(code);
        }
      });
    });
    // The owner threw its edits away: so does this field, whatever it held.
    effect(() => {
      this.resetKey();
      untracked(() => {
        this.text.set(this.code());
        this.found = new Map();
        this.options.set([]);
      });
    });
  }

  protected onQuery(text: string): void {
    const squashed = squash(text);
    if (isCodeLike(squashed)) {
      // A code: shown without the spaces it was pasted with.
      this.text.set(squashed);
      this.codeChange.emit(squashed);
    } else {
      this.text.set(text);
      this.codeChange.emit('');
    }
  }

  protected async onSearch(query: string): Promise<void> {
    const scope = this.scope();
    const wanted = query.trim();
    const sequence = ++this.searchSequence;
    if (!scope || wanted.length < 2) {
      this.found = new Map();
      this.options.set([]);
      this.searching.set(false);
      return;
    }
    this.searching.set(true);
    try {
      const rows = await firstValueFrom(this.api.searchMxikReference(scope, wanted));
      if (sequence !== this.searchSequence) {
        return;
      }
      this.found = new Map(rows.map((row) => [row.code, row] as const));
      this.options.set(rows.map((row) => this.optionOf(row)));
    } catch {
      if (sequence === this.searchSequence) {
        this.found = new Map();
        this.options.set([]);
      }
    } finally {
      if (sequence === this.searchSequence) {
        this.searching.set(false);
      }
    }
  }

  protected onPick(option: ComboboxOption): void {
    const row = this.found.get(option.id);
    if (!row) {
      return;
    }
    this.text.set(row.code);
    this.options.set([]);
    this.codeChange.emit(row.code);
    this.picked.emit(row);
  }

  /** The label in the console's language first, the Uzbek or Russian one beneath it as the product editor shows. */
  private optionOf(row: MxikReferenceRow): ComboboxOption {
    const locale = this.i18n.locale();
    const primary =
      locale === 'uz-Latn'
        ? row.labelUz
        : locale === 'en'
          ? (row.labelEn ?? row.labelRu)
          : row.labelRu;
    return {
      id: row.code,
      label: `${row.code} — ${primary}`,
      sublabel: locale === 'uz-Latn' ? row.labelRu : row.labelUz,
    };
  }
}
