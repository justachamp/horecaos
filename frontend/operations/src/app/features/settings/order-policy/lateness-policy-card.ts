import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
  EditableScopeType,
} from '../../../core/api/configuration';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../../orders/order-errors';
import {
  LATENESS_MODES,
  LatenessEditorView,
  LatenessMode,
  LatenessModeInput,
  LatenessModeView,
  LatenessPolicyEditorApi,
} from './lateness-policy-editor-api';

/** One mode's three inputs as the operator types them: minutes for the two windows, seconds for the grace. */
interface ModeDraft {
  readonly atRiskMinutes: string;
  readonly lateAfterSeconds: string;
  readonly fallbackMinutes: string;
}

type DraftField = keyof ModeDraft;

/**
 * What the open form was started against. The scope and the version are captured when the operator
 * clicks Edit and sent back unchanged, whatever the page's inputs or the loaded view have become
 * since: a form opened at the brand's version 2 publishes against version 2 at the brand, never
 * against whatever scope the bar has moved to or whichever read landed last.
 */
interface OpenedForm {
  readonly scopeType: EditableScopeType;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly expectedVersion: number | null;
}

interface ModeErrors {
  readonly atRiskMinutes: boolean;
  readonly lateAfterSeconds: boolean;
  readonly fallbackMinutes: boolean;
}

const MODE_LABEL_KEYS: Readonly<Record<LatenessMode, MessageKey>> = {
  delivery: 'settings.latenessPolicy.mode.delivery',
  pickup: 'settings.latenessPolicy.mode.pickup',
  dineIn: 'settings.latenessPolicy.mode.dineIn',
};

/** The same bounds `OrderLatenessDocument.violations()` enforces — a day, in each unit. */
const MAX_MINUTES = 1440;
const MAX_SECONDS = 86_400;

/**
 * The `ordering.lateness` document's editor on the order-policy card (rows `X.39`/`10.3b`,
 * ADR 0030): when an order counts as late, and how far ahead of the promise it counts as at
 * risk, **per fulfilment mode**.
 *
 * **Why this is not three more `ConfigurationKey` fields like the rows above it.** The
 * boundary of late is a versioned policy *document* — three numbers for each of delivery,
 * pickup and dine-in, replaced as one unit (ADR 0030). It is edited through the same
 * `q-inherited-field` control as the card's scalars, so an operator reads the same
 * set-here / inherited / trace vocabulary, but the row is a mode and an edit opens all three
 * modes at once. There is no "revert to inherit": a published version is never withdrawn,
 * so the control is told not to offer it.
 *
 * **The scalar above is the default, not a rival.** A blank at-risk window means "this kind of
 * order has no window of its own", and it then takes the card's *Warn before the promised
 * time* value when one was set, the platform's five minutes otherwise — the server says which
 * (`atRiskDefault.source`) and the form says it back, so a blank is never a guess.
 *
 * **Concurrency.** The form is opened at the `currentVersionAtScope` the read reported and
 * sends it back as `expectedVersion`; a second operator publishing underneath it gets a
 * `STALE_VERSION` here rather than silently discarding the first one's numbers, and the only
 * way forward is a reload that drops the stale edit. The scope and version are captured when the
 * form opens and sent back as captured; moving the scope bar drops the document and the form at
 * once (the new scope is read before anything is offered), while a re-read for any other reason
 * (the scalar above moved) refreshes the document and leaves an open draft alone.
 *
 * `ordering.late_order_threshold_minutes` (*Order is late after*) is deliberately not read by
 * anything and not touched here: what it should mean is an owner decision.
 */
@Component({
  selector: 'q-lateness-policy-card',
  imports: [TPipe, InheritedField],
  templateUrl: './lateness-policy-card.html',
  styleUrl: './lateness-policy-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LatenessPolicyCard {
  private readonly api = inject(LatenessPolicyEditorApi);
  private readonly i18n = inject(I18n);

  readonly tenantId = input.required<string | null>();
  readonly scopeType = input.required<EditableScopeType>();
  readonly brandId = input.required<string | null>();
  readonly locationId = input.required<string | null>();
  /** Bumped by the page when the *Warn before the promised time* scalar changes, so the default shown here follows it. */
  readonly reloadToken = input(0);

  protected readonly modes = LATENESS_MODES;
  protected readonly editableScopes: readonly ConfigurationScopeType[] = [
    'TENANT',
    'BRAND',
    'LOCATION',
  ];

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly view = signal<LatenessEditorView | null>(null);

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly stale = signal(false);
  protected readonly draft = signal<Readonly<Record<LatenessMode, ModeDraft>>>(emptyDrafts());
  protected readonly draftReason = signal('');

  private loadGeneration = 0;

  /** The scope the signals above describe, so a move to another one can drop them at once. */
  private shownScopeKey: string | null = null;

  private opened: OpenedForm | null = null;

  /** The default the blank at-risk boxes stand for, in whole-or-decimal minutes. */
  protected readonly defaultAtRiskMinutes = computed(() =>
    formatMinutes(this.view()?.atRiskDefault.seconds ?? 300),
  );

  protected readonly defaultSourceKey = computed<MessageKey>(() =>
    this.view()?.atRiskDefault.source === 'SCALAR'
      ? 'settings.latenessPolicy.default.scalar'
      : 'settings.latenessPolicy.default.platform',
  );

  protected readonly errors = computed<Readonly<Record<LatenessMode, ModeErrors>>>(() => {
    const drafts = this.draft();
    return {
      delivery: errorsOf(drafts.delivery),
      pickup: errorsOf(drafts.pickup),
      dineIn: errorsOf(drafts.dineIn),
    };
  });

  protected readonly canSave = computed(
    () =>
      !this.saving() &&
      this.draftReason().trim().length > 0 &&
      this.modes.every((mode) => {
        const modeErrors = this.errors()[mode];
        return (
          !modeErrors.atRiskMinutes && !modeErrors.lateAfterSeconds && !modeErrors.fallbackMinutes
        );
      }),
  );

  /**
   * One stable closure: `InheritedField.displayValue` memoises on it, and a fresh function every
   * change-detection pass would defeat that (the same reason `order-policy-page` caches its formatters).
   */
  protected readonly formatMode = (value: unknown): string => {
    if (!isModeView(value)) {
      return '—';
    }
    const minutes = (seconds: number): string =>
      this.i18n.t('settings.latenessPolicy.unit.minutes', { n: formatMinutes(seconds) });
    const atRisk =
      minutes(value.effectiveAtRiskBeforeSeconds) +
      (value.atRiskBeforeSeconds === null
        ? ` ${this.i18n.t('settings.latenessPolicy.summary.default')}`
        : '');
    return this.i18n.t('settings.latenessPolicy.summary', {
      atRisk,
      lateAfter: value.lateAfterSeconds,
      fallback: minutes(value.noPromiseFallbackSeconds),
    });
  };

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const scopeType = this.scopeType();
      const brandId = this.brandId();
      const locationId = this.locationId();
      this.reloadToken();
      if (!tenantId) {
        return;
      }
      untracked(() => {
        const scopeKey = [tenantId, scopeType, brandId, locationId].join('|');
        if (scopeKey !== this.shownScopeKey) {
          this.shownScopeKey = scopeKey;
          this.forgetShownScope();
        }
        void this.load(tenantId, scopeType, brandId, locationId);
      });
    });
  }

  protected modeLabelKey(mode: LatenessMode): MessageKey {
    return MODE_LABEL_KEYS[mode];
  }

  /** One `ConfigurationResolutionView` per mode, so `q-inherited-field` renders the document's own state. */
  protected resolutionFor(mode: LatenessMode): ConfigurationResolutionView | null {
    const view = this.view();
    if (!view) {
      return null;
    }
    return {
      keyCode: 'ordering.lateness',
      value: view[mode],
      cameFromDefault: view.isPlatformDefault,
      source: view.isPlatformDefault ? 'CODE_DEFAULT' : 'SCOPED_VALUE',
      winningScope: view.winningScope,
      inspectedLevels: view.inspectedLevels,
      describe: `ordering.lateness -> ${view.winningScope ?? 'PLATFORM DEFAULT'}`,
      currentVersionAtScope: view.currentVersionAtScope > 0 ? view.currentVersionAtScope : null,
    };
  }

  protected startEditing(): void {
    const view = this.view();
    if (!view) {
      return;
    }
    this.opened = {
      scopeType: this.scopeType(),
      brandId: this.brandId(),
      locationId: this.locationId(),
      expectedVersion: view.currentVersionAtScope > 0 ? view.currentVersionAtScope : null,
    };
    this.draft.set({
      delivery: draftOf(view.delivery),
      pickup: draftOf(view.pickup),
      dineIn: draftOf(view.dineIn),
    });
    this.draftReason.set('');
    this.saveError.set(null);
    this.stale.set(false);
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
    this.opened = null;
    this.stale.set(false);
  }

  protected setDraft(mode: LatenessMode, field: DraftField, value: string): void {
    this.draft.update((drafts) => ({ ...drafts, [mode]: { ...drafts[mode], [field]: value } }));
  }

  protected fieldId(mode: LatenessMode, field: DraftField): string {
    return `lateness-${mode}-${field}`;
  }

  protected async save(): Promise<void> {
    const tenantId = this.tenantId();
    const opened = this.opened;
    if (!tenantId || !opened || !this.editing() || !this.canSave()) {
      return;
    }
    const drafts = this.draft();
    const scopeKey = this.shownScopeKey;
    this.saving.set(true);
    this.saveError.set(null);
    this.stale.set(false);
    try {
      const updated = await this.api.publish(tenantId, {
        scopeType: opened.scopeType,
        brandId: opened.brandId,
        locationId: opened.locationId,
        delivery: inputOf(drafts.delivery),
        pickup: inputOf(drafts.pickup),
        dineIn: inputOf(drafts.dineIn),
        expectedVersion: opened.expectedVersion,
        reason: this.draftReason().trim(),
      });
      if (scopeKey === this.shownScopeKey) {
        // A read that left before this publish landed describes the document it replaced.
        this.loadGeneration++;
        this.loading.set(false);
        this.view.set(updated);
        this.editing.set(false);
        this.opened = null;
      }
    } catch (error) {
      if (scopeKey === this.shownScopeKey) {
        this.stale.set(error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION);
        this.saveError.set(this.describe(error));
      }
    } finally {
      this.saving.set(false);
    }
  }

  /** After a `STALE_VERSION`: drop the edit and show what is in force now. */
  protected async reloadAfterConflict(): Promise<void> {
    const tenantId = this.tenantId();
    if (!tenantId) {
      return;
    }
    this.editing.set(false);
    this.opened = null;
    this.stale.set(false);
    this.saveError.set(null);
    await this.load(tenantId, this.scopeType(), this.brandId(), this.locationId());
  }

  /**
   * The scope bar moved: what is on screen belongs to the scope it left. Clear it before the read for
   * the new scope returns, or the form (and its Publish) stays up over the new scope until then.
   */
  private forgetShownScope(): void {
    this.view.set(null);
    this.editing.set(false);
    this.opened = null;
    this.stale.set(false);
    this.saveError.set(null);
    this.draftReason.set('');
  }

  private async load(
    tenantId: string,
    scopeType: EditableScopeType,
    brandId: string | null,
    locationId: string | null,
  ): Promise<void> {
    const generation = ++this.loadGeneration;
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const view = await this.api.get(tenantId, scopeType, brandId, locationId);
      if (generation === this.loadGeneration) {
        // The document, not the form: a re-read because the scalar it defaults to moved must not
        // take a draft away (the scope bar moving does, in forgetShownScope).
        this.view.set(view);
      }
    } catch (error) {
      // A form that is open keeps its draft over a failed re-read; the next successful one refreshes the rest.
      if (generation === this.loadGeneration && !this.editing()) {
        this.view.set(null);
        this.loadError.set(this.describe(error));
      }
    } finally {
      if (generation === this.loadGeneration) {
        this.loading.set(false);
      }
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function emptyDrafts(): Readonly<Record<LatenessMode, ModeDraft>> {
  const blank: ModeDraft = { atRiskMinutes: '', lateAfterSeconds: '0', fallbackMinutes: '45' };
  return { delivery: blank, pickup: blank, dineIn: blank };
}

/** Minutes with at most one decimal — a stored 90 seconds reads "1.5", and the form then refuses it until it is whole. */
function formatMinutes(seconds: number): string {
  return String(Number((seconds / 60).toFixed(1)));
}

function draftOf(mode: LatenessModeView): ModeDraft {
  return {
    atRiskMinutes: mode.atRiskBeforeSeconds === null ? '' : formatMinutes(mode.atRiskBeforeSeconds),
    lateAfterSeconds: String(mode.lateAfterSeconds),
    fallbackMinutes: formatMinutes(mode.noPromiseFallbackSeconds),
  };
}

function isWhole(text: string): boolean {
  return /^\d+$/.test(text.trim());
}

function errorsOf(draft: ModeDraft): ModeErrors {
  const atRisk = draft.atRiskMinutes.trim();
  const fallback = draft.fallbackMinutes.trim();
  const lateAfter = draft.lateAfterSeconds.trim();
  return {
    // Blank is a real answer ("take the default"), not an omission.
    atRiskMinutes: atRisk !== '' && !(isWhole(atRisk) && Number(atRisk) <= MAX_MINUTES),
    lateAfterSeconds: !(isWhole(lateAfter) && Number(lateAfter) <= MAX_SECONDS),
    fallbackMinutes: !(
      isWhole(fallback) &&
      Number(fallback) >= 1 &&
      Number(fallback) <= MAX_MINUTES
    ),
  };
}

function inputOf(draft: ModeDraft): LatenessModeInput {
  const atRisk = draft.atRiskMinutes.trim();
  return {
    atRiskBeforeSeconds: atRisk === '' ? null : Number(atRisk) * 60,
    lateAfterSeconds: Number(draft.lateAfterSeconds.trim()),
    noPromiseFallbackSeconds: Number(draft.fallbackMinutes.trim()) * 60,
  };
}

function isModeView(value: unknown): value is LatenessModeView {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Partial<LatenessModeView>).effectiveAtRiskBeforeSeconds === 'number'
  );
}
