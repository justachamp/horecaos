import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/** One column the picker lists. Already translated: the picker never looks a label up. */
export interface ColumnPickerItem {
  readonly key: string;
  readonly label: string;
  readonly shown: boolean;
}

/**
 * «Столбцы»: a button that opens the list of a table's optional columns, each a checkbox (ADR 0101, 0160).
 *
 * The shared library's answer to orders.md §2.5's «column picker, persisted per user». It owns only the
 * menu: which columns exist, which are shown and where a choice is kept all belong to the table that hosts
 * it, so the order board can keep it in the person's account while the data table's own chooser stays in
 * the browser (the two are different on purpose -- ADR 0160, decision 8).
 *
 * `persistent` and `writeFailed` only change one line of small print: whether the choice follows the person
 * to another machine, lives for this session, or could not be saved to the account just now. A choice is
 * applied at once either way; saving it is the host's job.
 */
@Component({
  selector: 'q-column-picker',
  imports: [TPipe],
  templateUrl: './column-picker.html',
  styleUrl: './column-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:pointerdown)': 'onDocumentPointerDown($event)',
    '(keydown.escape)': 'close()',
  },
})
export class ColumnPicker {
  readonly columns = input.required<readonly ColumnPickerItem[]>();
  /** True when the host keeps the choice in the person's account; false when it lives for this session only. */
  readonly persistent = input(true, { transform: booleanAttribute });
  /** True after the host's last attempt to save the choice to the account failed: the small print must not say it is saved. */
  readonly writeFailed = input(false, { transform: booleanAttribute });
  /** Whether there is a choice to give up: the reset is offered only then. */
  readonly canReset = input(false, { transform: booleanAttribute });

  readonly toggled = output<{ readonly key: string; readonly shown: boolean }>();
  readonly resetRequested = output<void>();

  protected readonly open = signal(false);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected toggleMenu(): void {
    this.open.update((value) => !value);
  }

  protected close(): void {
    this.open.set(false);
  }

  protected onDocumentPointerDown(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  protected toggle(item: ColumnPickerItem): void {
    this.toggled.emit({ key: item.key, shown: !item.shown });
  }
}
