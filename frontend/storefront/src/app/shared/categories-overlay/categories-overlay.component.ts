import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { FocusTrapDirective } from '../focus-trap/focus-trap.directive';
import { TranslatePipe } from '../translate/translate.pipe';

/** One category as the menu strip names it. */
export interface CategoryChoice {
  readonly id: string;
  readonly label: string;
}

/**
 * The marketplace layout's «all categories» overlay (ADR 0183 D17): every category of the menu in
 * one list, opened from the compact strip on the home screen.
 *
 * A modal dialog: focus moves in and comes back to the control that opened it, Escape and the
 * backdrop close it, and choosing a category reports the choice and closes. Choosing «All» reports
 * `null`, the same value the strip uses for «no filter».
 */
@Component({
  selector: 'app-categories-overlay',
  standalone: true,
  imports: [FocusTrapDirective, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'closed.emit()' },
  template: `
    <div
      class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60"
      data-testid="categories-overlay-backdrop"
      (click)="onBackdrop($event)"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="categories-overlay-title"
        appFocusTrap
        tabindex="-1"
        class="flex max-h-[85vh] w-full max-w-[430px] flex-col rounded-t-2xl bg-surface-raised text-text-primary shadow-elevated"
      >
        <div class="flex items-center justify-between px-4 py-3">
          <h2 id="categories-overlay-title" class="text-lg font-semibold">
            {{ 'home.allCategories' | t }}
          </h2>
          <button
            type="button"
            data-autofocus
            class="flex h-9 w-9 items-center justify-center rounded-full bg-surface-raised-2"
            [attr.aria-label]="'home.categoriesClose' | t"
            (click)="closed.emit()"
          >
            <svg
              class="h-4 w-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <ul class="overflow-y-auto px-2 pb-4">
          <li>
            <button
              type="button"
              class="w-full rounded-xl px-3 py-3 text-left text-sm"
              [class.font-semibold]="selectedId() === null"
              [class.text-brand-accent]="selectedId() === null"
              [attr.aria-current]="selectedId() === null ? 'true' : null"
              data-testid="categories-overlay-all"
              (click)="chosen.emit(null)"
            >
              {{ 'home.all' | t }}
            </button>
          </li>
          @for (category of categories(); track category.id) {
            <li>
              <button
                type="button"
                class="w-full rounded-xl px-3 py-3 text-left text-sm"
                [class.font-semibold]="selectedId() === category.id"
                [class.text-brand-accent]="selectedId() === category.id"
                [attr.aria-current]="selectedId() === category.id ? 'true' : null"
                [attr.data-testid]="'categories-overlay-' + category.id"
                (click)="chosen.emit(category.id)"
              >
                {{ category.label }}
              </button>
            </li>
          }
        </ul>
      </div>
    </div>
  `,
})
export class CategoriesOverlayComponent {
  readonly categories = input<readonly CategoryChoice[]>([]);
  readonly selectedId = input<string | null>(null);

  readonly chosen = output<string | null>();
  readonly closed = output<void>();

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closed.emit();
    }
  }
}
