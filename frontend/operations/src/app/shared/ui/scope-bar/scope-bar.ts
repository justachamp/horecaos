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
 *
 * Row 10.3b adds the TENANT toggle: a pill pair beside the level readout, so
 * any settings screen can offer a tenant-wide default the same `q-scope-bar`
 * the brand/location pickers already live on — whether a given screen's own
 * keys are actually settable at TENANT is `q-inherited-field`'s own
 * `NOT_SETTABLE` state to render, not this bar's concern.
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

  /**
   * Row 10.3b — whether the bar is currently at TENANT level, the third
   * level above BRAND/LOCATION for a tenant-wide default. Orthogonal to
   * {@link selectedBrandId}/{@link selectedLocationId}, which keep their own
   * values as display context while this wins.
   */
  readonly tenantWide = input(false);

  readonly brandChange = output<string>();
  /** Emits `null` for "Все филиалы". */
  readonly locationChange = output<string | null>();
  /** Emits `true` to switch to TENANT level, `false` to return to BRAND/LOCATION. */
  readonly tenantWideChange = output<boolean>();

  protected readonly level = computed(() => {
    if (this.tenantWide()) {
      return 'TENANT';
    }
    return this.selectedLocationId() ? 'LOCATION' : 'BRAND';
  });

  /** A concatenated key (`'scopeBar.level.' + level()`) would not type-check against MessageKey's literal union. */
  protected readonly levelKey = computed<MessageKey>(() => {
    switch (this.level()) {
      case 'TENANT':
        return 'scopeBar.level.TENANT';
      case 'LOCATION':
        return 'scopeBar.level.LOCATION';
      default:
        return 'scopeBar.level.BRAND';
    }
  });

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
