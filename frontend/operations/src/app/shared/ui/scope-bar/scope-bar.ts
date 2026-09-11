import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';

/** The minimum a picker option needs, whichever view model supplies it. */
export interface ScopeBarOption {
  readonly id: string;
  readonly displayName: string;
  readonly status?: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
}

/**
 * settings.md §1.1's scope bar: a brand picker, a location picker, and a
 * plain-language "level being edited" readout — the pattern every settings
 * screen renders through (wave P31, gap map row `10/X.1`).
 *
 * Presentational by design: state (which brand/location is selected, the
 * `?brand=&location=` query sync, the API calls) lives in
 * `features/settings/settings-scope.ts`; this component only renders the
 * pickers it is handed and emits a selection. Kept in `shared/ui` because
 * every settings wave after this one (P32 onward) mounts one.
 */
@Component({
  selector: 'q-scope-bar',
  imports: [TPipe],
  templateUrl: './scope-bar.html',
  styleUrl: './scope-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScopeBar {
  /** Hidden entirely when there is nothing to switch between — "a picker with one option is noise". */
  readonly showBrandPicker = input(true);
  readonly brands = input.required<readonly ScopeBarOption[]>();
  readonly selectedBrandId = input<string | null>(null);

  readonly locations = input.required<readonly ScopeBarOption[]>();
  /** `null` selects "Все филиалы" — editing at BRAND level. */
  readonly selectedLocationId = input<string | null>(null);
  /** Set to disable the location picker with an explanatory chip — a key not settable at LOCATION. */
  readonly locationDisabledReason = input<string | null>(null);

  readonly brandChange = output<string>();
  /** Emits `null` for "Все филиалы". */
  readonly locationChange = output<string | null>();

  protected readonly level = computed(() => (this.selectedLocationId() ? 'LOCATION' : 'BRAND'));

  /** A concatenated key (`'scopeBar.level.' + level()`) would not type-check against MessageKey's literal union. */
  protected readonly levelKey = computed<MessageKey>(() =>
    this.level() === 'LOCATION' ? 'scopeBar.level.LOCATION' : 'scopeBar.level.BRAND',
  );

  protected onBrandChange(value: string): void {
    if (value) {
      this.brandChange.emit(value);
    }
  }

  protected onLocationChange(value: string): void {
    this.locationChange.emit(value === '' ? null : value);
  }

  /** Same reason {@link levelKey} exists rather than a template-side concatenation. */
  protected statusKey(status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'): MessageKey {
    switch (status) {
      case 'DRAFT':
        return 'scopeBar.status.DRAFT';
      case 'SUSPENDED':
        return 'scopeBar.status.SUSPENDED';
      case 'ARCHIVED':
        return 'scopeBar.status.ARCHIVED';
      case 'ACTIVE':
        return 'scopeBar.status.ACTIVE';
    }
  }
}
