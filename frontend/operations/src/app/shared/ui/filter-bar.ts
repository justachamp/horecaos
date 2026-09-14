import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/** One active-filter chip in the primary row (orders.md §2.4: "a chip appears… for each one that is set"). */
export interface FilterBarChip {
  readonly id: string;
  /** Already translated — a chip names the filter's own chosen value, never a generic key. */
  readonly label: string;
}

/**
 * The filter-bar chrome (orders.md §2.4, wave P07) — lifted out of
 * `features/reports/reports-filter-bar` because §2.4 says the queue and the
 * order reports "share one filter component." `order-queue.ts` builds its own
 * toolbar directly on it; wave P27 re-pointed `reports-shell.ts` at it the
 * same way and deleted the `q-reports-filter-bar` wrapper this doc used to
 * describe — do not extract this a second time.
 *
 * **What this owns, and what it does not.** This renders the shape every
 * filter bar in the console shares: a primary row, an optional secondary row
 * behind an **⋯ ещё** toggle, a chip for every secondary filter that is set,
 * and **Сбросить фильтры**. It does not know what a period pill or a channel
 * picker is — every individual control is the caller's own content,
 * projected into {@link primary}/{@link secondary} through `ng-content`, so a
 * caller can build its filters from whichever shared primitive fits
 * (`q-combobox`, `q-date-range-picker`, a plain segmented control) without
 * this component knowing any of their types. "Typed" is the {@link chips}
 * list and the {@link resetFilters} contract, not a closed enum of control
 * kinds — the individual controls vary too much between a queue and a report
 * for one shared union to describe them honestly.
 */
@Component({
  selector: 'q-filter-bar',
  imports: [TPipe],
  templateUrl: './filter-bar.html',
  styleUrl: './filter-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FilterBar {
  /** Whether a secondary row exists at all — a bar with nothing behind "⋯ ещё" omits the toggle entirely. */
  readonly hasSecondary = input(false);

  /** Chips for every secondary-row filter currently set. Empty renders no chip row. */
  readonly chips = input<readonly FilterBarChip[]>([]);

  /** Whether **Сбросить фильтры** renders at all — hidden while nothing is filtering. */
  readonly canReset = input(false);

  /** One chip's own remove control, or the reset button — both name which filter(s) to clear. */
  readonly chipRemoved = output<string>();
  readonly resetFilters = output<void>();

  protected readonly secondaryOpen = signal(false);

  protected toggleSecondary(): void {
    this.secondaryOpen.update((open) => !open);
  }
}
