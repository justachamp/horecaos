import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

export interface ComboboxOption {
  readonly id: string;
  /** Already translated / already the tenant's own data — never a key. */
  readonly label: string;
  readonly sublabel?: string | null;
}

const DEBOUNCE_MS = 250;

/**
 * Async search with create-on-miss and multi-select chips (ADR 0101, row
 * `X.9`).
 *
 * The gap this closes: nothing in this console lets an operator type a
 * phone number and get the matching customer back, offering "create" only
 * when nothing matches — the call-centre screen's unknown-caller card is the
 * only create-from-a-phone-number path that exists, and it is not reusable.
 * A product search over a catalogue of thousands has the identical shape.
 *
 * Fully controlled, like every primitive here: {@link options} is supplied
 * by the caller (this component never calls an API), {@link search} fires
 * {@link DEBOUNCE_MS} after the query stops changing so the caller can debounce
 * its own request, and {@link query} is a plain input/output pair rather than
 * `model()` so a caller can hold the in-flight text separately from the
 * still-loading result set.
 *
 * Full listbox ARIA (`combobox`/`listbox`/`option`, `aria-activedescendant`)
 * over roving tabindex — a combobox's individual options are not
 * independently focusable the way `q-action-menu`'s are, so the roving
 * pattern this directory otherwise uses does not fit here.
 */
@Component({
  selector: 'q-combobox',
  imports: [TPipe],
  templateUrl: './combobox.html',
  styleUrl: './combobox.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Combobox {
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly query = input<string>('');
  readonly options = input<readonly ComboboxOption[]>([]);
  readonly loading = input(false, { transform: booleanAttribute });
  readonly placeholder = input<string>('');
  readonly ariaLabel = input<string | null>(null);
  readonly multiple = input(false, { transform: booleanAttribute });
  readonly selected = input<readonly ComboboxOption[]>([]);
  readonly allowCreate = input(false, { transform: booleanAttribute });
  /** Overrides the generic `ui.combobox.createOption` row text with an already-translated, domain-specific one. */
  readonly createLabel = input<string | null>(null);

  readonly queryChange = output<string>();
  /** Fires `DEBOUNCE_MS` after typing settles — the signal to run an async search. */
  readonly search = output<string>();
  readonly optionSelected = output<ComboboxOption>();
  readonly selectedChange = output<readonly ComboboxOption[]>();
  /** The raw typed text, when the create-on-miss row is activated. */
  readonly create = output<string>();

  protected readonly open = signal(false);
  protected readonly activeIndex = signal(-1);

  private debounceHandle: ReturnType<typeof setTimeout> | null = null;

  /** Options plus, when offered, the trailing create-on-miss row — one flat list for keyboard nav. */
  protected readonly rowCount = computed(
    () => this.options().length + (this.showCreateRow() ? 1 : 0),
  );

  protected readonly showCreateRow = computed(
    () => this.allowCreate() && this.query().trim().length > 0,
  );

  protected onInput(text: string): void {
    this.queryChange.emit(text);
    this.open.set(true);
    this.activeIndex.set(-1);
    if (this.debounceHandle !== null) {
      clearTimeout(this.debounceHandle);
    }
    this.debounceHandle = setTimeout(() => this.search.emit(text), DEBOUNCE_MS);
  }

  protected onFocus(): void {
    this.open.set(true);
  }

  protected onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.open.set(true);
        this.activeIndex.update((i) => (i + 1 >= this.rowCount() ? 0 : i + 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.open.set(true);
        this.activeIndex.update((i) => (i - 1 < 0 ? this.rowCount() - 1 : i - 1));
        break;
      case 'Enter':
        if (this.open() && this.activeIndex() >= 0) {
          event.preventDefault();
          this.activateRow(this.activeIndex());
        }
        break;
      case 'Escape':
        this.open.set(false);
        this.activeIndex.set(-1);
        break;
      case 'Backspace':
        if (this.multiple() && this.query() === '' && this.selected().length > 0) {
          this.removeChip(this.selected()[this.selected().length - 1]);
        }
        break;
      default:
        break;
    }
  }

  protected activateRow(index: number): void {
    const options = this.options();
    if (index < options.length) {
      this.choose(options[index]);
      return;
    }
    if (this.showCreateRow()) {
      this.create.emit(this.query().trim());
      this.open.set(false);
      this.activeIndex.set(-1);
    }
  }

  protected choose(option: ComboboxOption): void {
    if (this.multiple()) {
      if (!this.selected().some((existing) => existing.id === option.id)) {
        this.selectedChange.emit([...this.selected(), option]);
      }
      this.queryChange.emit('');
    } else {
      this.optionSelected.emit(option);
    }
    this.open.set(false);
    this.activeIndex.set(-1);
  }

  protected removeChip(option: ComboboxOption): void {
    this.selectedChange.emit(this.selected().filter((existing) => existing.id !== option.id));
  }

  protected activateCreateRow(): void {
    this.create.emit(this.query().trim());
    this.open.set(false);
  }

  protected onBlur(event: FocusEvent): void {
    // A click on an option or the create row fires its own handler before
    // blur closes the list; only close when focus actually left the host.
    const next = event.relatedTarget as Node | null;
    if (!next || !this.host.nativeElement.contains(next)) {
      this.open.set(false);
      this.activeIndex.set(-1);
    }
  }
}
