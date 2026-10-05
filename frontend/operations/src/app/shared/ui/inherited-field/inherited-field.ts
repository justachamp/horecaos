import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
  EditableScopeType,
  ResolutionTraceLevel,
} from '../../../core/api/configuration';
import { I18n } from '../../../core/i18n/i18n';
import { formatDateTime } from '../../../core/format/datetime';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { traceMessages } from './trace-messages';

/** Same stand-in every other screen uses until the tenant's own zone reaches the console. */
const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

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
 * **What the trace popover shows.** The ladder settings.md asks for (most
 * specific first, the winning row marked), and for every level that stores
 * something, which version is in force there, who changed it and when
 * (row `X.1`, ADR 0030's `ResolutionTrace.Provenance`). The trace carries the
 * principal's id; the server resolves the name through `StaffDirectory` when it
 * answers, so a person with no member record in this tenant arrives without a
 * name and the popover says so rather than printing an id. The reason is not
 * shown: a free-text reason is not part of the trace (ADR 0029).
 */
@Component({
  selector: 'q-inherited-field',
  imports: [TPipe],
  templateUrl: './inherited-field.html',
  styleUrl: './inherited-field.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InheritedField {
  private readonly i18n = inject(I18n);

  readonly label = input.required<string>();
  readonly resolution = input<ConfigurationResolutionView | null>(null);
  readonly loading = input(false);
  readonly currentScopeType = input.required<EditableScopeType>();
  /** Empty means "not yet known" — rendered as NOT_SETTABLE is skipped until the key list has loaded. */
  readonly settableScopes = input<readonly ConfigurationScopeType[]>([]);
  /** Renders the resolved value; defaults to a plain string coercion. */
  readonly formatValue = input<(value: unknown) => string>(defaultFormat);
  /**
   * Whether "revert to inherit" is offered. A scalar can be un-set with an explicit null; a
   * versioned policy document (the `ordering.lateness` editor, rows `X.39`/`10.3b`) cannot — a
   * published version is never withdrawn — so its rows turn the action off rather than render a
   * button that does nothing.
   */
  readonly revertable = input(true);

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

  /** «Version 3 · A. Karimov · 30.09 14:15» -- the version, who changed it, and when. */
  protected traceWho(level: ResolutionTraceLevel): string {
    const locale = this.i18n.locale();
    return traceMessages.text(locale, 'changed', {
      version: level.version ?? 0,
      who: level.changedByName || traceMessages.text(locale, 'unknownActor'),
      when: level.changedAt
        ? formatDateTime(new Date(level.changedAt), PLACEHOLDER_TIME_ZONE)
        : '—',
    });
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
