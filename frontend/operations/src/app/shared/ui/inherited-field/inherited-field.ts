import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
  EditableScopeType,
} from '../../../core/api/configuration';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';

/** The five states settings.md §1.2 names — "Locked by plan" is not wired yet; no entitlement data reaches this wave. */
export type InheritedFieldState =
  'LOADING' | 'SET_HERE' | 'INHERITED' | 'EXPLICIT_UNSET' | 'NOT_SETTABLE';

/**
 * settings.md §1.2's `q-inherited-field` — the control every settings row
 * renders a scalar value through, driven by ADR 0030's `ResolutionTrace`
 * (wave P31, gap map row `10/X.1`).
 *
 * Presentational: it renders a {@link ConfigurationResolutionView} (from
 * `ConfigurationApi.resolution`) and emits intent — `override`,
 * `revertToInherit`, `setValueRequested` — for the settings row that owns
 * this key to act on through `ConfigurationApi.setValue`. It does not itself
 * know how to edit a Boolean vs. an Integer vs. a String; the row supplies
 * the actual input control as projected content when the state calls for one
 * (`SET_HERE`, or after `setValueRequested`/`override`).
 *
 * **What the trace popover can show today.** `ResolutionTrace.Level` carries
 * only `(scopeType, outcome)` per level — ADR 0030 does not yet surface
 * which actor set a non-winning level's row, when, or why, only the winning
 * value and `describe()`'s one-line summary. The popover renders the ladder
 * settings.md asks for (most specific first, the winning row marked) with
 * what is real; per-level actor/timestamp/reason is a `ConfigurationResolver`
 * gap for a later wave, not something this component fabricates.
 */
@Component({
  selector: 'q-inherited-field',
  imports: [TPipe],
  templateUrl: './inherited-field.html',
  styleUrl: './inherited-field.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InheritedField {
  readonly label = input.required<string>();
  readonly resolution = input<ConfigurationResolutionView | null>(null);
  readonly loading = input(false);
  readonly currentScopeType = input.required<EditableScopeType>();
  /** Empty means "not yet known" — rendered as NOT_SETTABLE is skipped until the key list has loaded. */
  readonly settableScopes = input<readonly ConfigurationScopeType[]>([]);
  /** Renders the resolved value; defaults to a plain string coercion. */
  readonly formatValue = input<(value: unknown) => string>(defaultFormat);

  readonly override = output<void>();
  readonly revertToInherit = output<void>();
  readonly setValueRequested = output<void>();

  protected readonly popoverOpen = signal(false);

  protected readonly state = computed<InheritedFieldState>(() => {
    if (this.loading() || !this.resolution()) {
      return 'LOADING';
    }
    const scopes = this.settableScopes();
    if (scopes.length > 0 && !scopes.includes(this.currentScopeType())) {
      return 'NOT_SETTABLE';
    }
    const resolution = this.resolution();
    const ownLevel = resolution?.inspectedLevels.find(
      (level) => level.scopeType === this.currentScopeType(),
    );
    if (
      ownLevel &&
      (ownLevel.outcome === 'EXPLICIT_NULL_CONTINUED' ||
        ownLevel.outcome === 'EXPLICIT_NULL_TERMINATED')
    ) {
      return 'EXPLICIT_UNSET';
    }
    if (resolution?.winningScope === this.currentScopeType()) {
      return 'SET_HERE';
    }
    return 'INHERITED';
  });

  protected readonly stateChipKey = computed<MessageKey>(() => {
    switch (this.state()) {
      case 'SET_HERE':
        return 'inheritedField.state.setHere';
      case 'EXPLICIT_UNSET':
        return 'inheritedField.state.explicitUnset';
      case 'NOT_SETTABLE':
        return 'inheritedField.state.notSettable';
      case 'INHERITED':
        return 'inheritedField.state.inherited';
      case 'LOADING':
        return 'inheritedField.state.loading';
    }
  });

  protected readonly inheritedFromKey = computed<MessageKey>(() => {
    const resolution = this.resolution();
    if (!resolution || resolution.source === 'CODE_DEFAULT') {
      return 'inheritedField.source.platformDefault';
    }
    switch (resolution.winningScope) {
      case 'BRAND':
        return 'inheritedField.source.brand';
      case 'TENANT':
        return 'inheritedField.source.tenant';
      case 'PLATFORM':
        return 'inheritedField.source.platformDefault';
      default:
        return 'inheritedField.source.platformDefault';
    }
  });

  /**
   * The value in effect, whatever the state — including EXPLICIT_UNSET: an
   * explicit null that does not terminate resolution still resolves to
   * whatever the next level answers, and {@link ConfigurationResolutionView.value}
   * already carries that continued value (or is genuinely absent for an
   * `explicitNullTerminates()` key, which {@link formatValue}'s default
   * renders as "—").
   */
  protected readonly displayValue = computed(() => {
    const resolution = this.resolution();
    return resolution ? this.formatValue()(resolution.value) : null;
  });

  protected readonly notSettableLevelKey = computed<MessageKey>(() => {
    const scopes = this.settableScopes();
    const narrowest = scopes.includes('BRAND')
      ? 'BRAND'
      : scopes.includes('TENANT')
        ? 'TENANT'
        : 'PLATFORM';
    return narrowest === 'BRAND'
      ? 'inheritedField.notSettable.brand'
      : 'inheritedField.notSettable.tenant';
  });

  protected togglePopover(): void {
    this.popoverOpen.update((open) => !open);
  }

  protected closePopover(): void {
    this.popoverOpen.set(false);
  }

  protected outcomeKey(outcome: string): MessageKey {
    switch (outcome) {
      case 'VALUE':
        return 'inheritedField.trace.outcome.VALUE';
      case 'EXPLICIT_NULL_CONTINUED':
        return 'inheritedField.trace.outcome.EXPLICIT_NULL_CONTINUED';
      case 'EXPLICIT_NULL_TERMINATED':
        return 'inheritedField.trace.outcome.EXPLICIT_NULL_TERMINATED';
      default:
        return 'inheritedField.trace.outcome.NOT_SET';
    }
  }

  /**
   * The trace ladder's own scope labels, mixed case ("Brand") — deliberately
   * not {@link scopeBar}'s `scopeBar.level.*` keys, which are all caps to
   * match settings.md §1.1's "БРЕНД" readout and would read as shouting in
   * a table row.
   */
  protected scopeLabelKey(scopeType: ConfigurationScopeType): MessageKey {
    switch (scopeType) {
      case 'LOCATION':
        return 'inheritedField.trace.scope.LOCATION';
      case 'BRAND':
        return 'inheritedField.trace.scope.BRAND';
      case 'TENANT':
        return 'inheritedField.trace.scope.TENANT';
      case 'PLATFORM':
        return 'inheritedField.trace.scope.PLATFORM';
    }
  }
}

function defaultFormat(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return String(value);
}
