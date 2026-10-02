import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
  untracked,
  effect,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import {
  EMPTY_PHYSICAL_FORM,
  PhysicalAttributesView,
  PhysicalErrors,
  PhysicalField,
  PhysicalForm,
  PhysicalMeasure,
  PhysicalReason,
  conflictsWithMarking,
  fieldOfReason,
  formFromView,
  isEmptyRequest,
  isPhysicalReason,
  parsePhysicalForm,
} from './physical-attributes';

type LoadState = 'loading' | 'ready' | 'denied' | 'failed';

/** One message per way a figure can be wrong — several server reasons share a sentence. */
const REASON_MESSAGE: Readonly<Record<PhysicalReason, MessageKey>> = {
  NOT_A_NUMBER: 'catalog.editor.physical.error.notANumber',
  NET_WEIGHT_NOT_POSITIVE: 'catalog.editor.physical.error.wholePositive',
  NET_VOLUME_NOT_POSITIVE: 'catalog.editor.physical.error.wholePositive',
  CATCHWEIGHT_QUANTUM_NOT_POSITIVE: 'catalog.editor.physical.error.wholePositive',
  CATCHWEIGHT_NOMINAL_NOT_POSITIVE: 'catalog.editor.physical.error.wholePositive',
  WEIGHT_AND_VOLUME_EXCLUSIVE: 'catalog.editor.physical.error.weightAndVolume',
  CATCHWEIGHT_NEEDS_QUANTUM: 'catalog.editor.physical.error.needsQuantum',
  CATCHWEIGHT_NEEDS_WEIGHT: 'catalog.editor.physical.error.needsWeight',
  CATCHWEIGHT_FIELDS_WITHOUT_CATCHWEIGHT: 'catalog.editor.physical.error.catchweightFields',
  PORTION_SIZE_NOT_POSITIVE: 'catalog.editor.physical.error.portionPositive',
  PORTION_SIZE_TOO_PRECISE: 'catalog.editor.physical.error.portionPrecision',
  PORTION_SIZE_TOO_LARGE: 'catalog.editor.physical.error.portionTooLarge',
  PORTION_SIZE_NEEDS_SPLITTABLE: 'catalog.editor.physical.error.portionNeedsSplittable',
  CALORIES_NEGATIVE: 'catalog.editor.physical.error.caloriesRange',
  CALORIES_TOO_LARGE: 'catalog.editor.physical.error.caloriesRange',
  PROTEIN_OUT_OF_RANGE: 'catalog.editor.physical.error.macroRange',
  FAT_OUT_OF_RANGE: 'catalog.editor.physical.error.macroRange',
  CARBOHYDRATES_OUT_OF_RANGE: 'catalog.editor.physical.error.macroRange',
};

/**
 * ADR 0137, row 4.2c — one variant's physical and nutritional attributes: its
 * weight or volume, whether it is sold by a weight only known at handover
 * (catchweight, and the quantum its price is quoted per), whether it may be
 * ordered by the portion, and its КБЖУ.
 *
 * **One whole set, one save.** The facts are authored, published and read
 * together, so there is one `PUT` of the whole set under the version it was
 * read at (`CatalogAuthoringController.setPhysicalAttributes`); an empty set
 * clears the row, which is the common case and is why this form says so rather
 * than hiding a delete button somewhere. The row is optional: a variant with
 * none opens on an empty form, never on a row that has to exist to be absent.
 *
 * **What the form does for the operator that the server also does.** It mirrors
 * `PhysicalAttributes`' own invariants (`parsePhysicalForm`) so a wrong figure is
 * marked on its field before the request goes out, and a refusal the server
 * still names (`reason`) lands on the same field with the same wording. A figure
 * that does not apply is not sent — unticking "sold by weight" cannot leave a
 * quantum behind for the server to refuse.
 *
 * **The marking exclusion is a warning here, a blocker at publication.** ADR 0038
 * forbids a marked good from being catchweight or splittable; the server checks
 * it when the catalogue publishes, because the fiscal classification lives on
 * another tab authored at another time. The warning appears the moment the two
 * meet on screen. It never blocks the save, which is the server's own rule.
 *
 * The КБЖУ carries no disclaimer: whether the console should word an accuracy
 * warning is a legal question ADR 0137 leaves with legal and product, so nothing
 * is invented here.
 */
@Component({
  selector: 'q-variant-physical-attributes',
  imports: [TPipe, InlineAlert],
  templateUrl: './variant-physical-attributes.html',
  styleUrl: './variant-physical-attributes.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VariantPhysicalAttributes {
  private readonly api = inject(CatalogApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly variantId = input.required<string>();
  /** The variant's name in the editing locale — the card's legend. */
  readonly label = input.required<string>();
  /** Whether the variant's fiscal classification requires marking (ADR 0038). */
  readonly markingRequired = input(false);

  /** A set was written: the page's readiness rail may now say something different. */
  readonly saved = output<void>();

  protected readonly state = signal<LoadState>('loading');
  private readonly stored = signal<PhysicalAttributesView | null>(null);
  protected readonly form = signal<PhysicalForm>(EMPTY_PHYSICAL_FORM);
  protected readonly saving = signal(false);
  protected readonly notice = signal<{
    readonly kind: 'success' | 'warning' | 'error';
    readonly text: string;
  } | null>(null);
  /** A refusal the server named, until the operator changes anything. */
  private readonly refusal = signal<{
    readonly field: PhysicalField;
    readonly reason: PhysicalReason;
  } | null>(null);

  private readonly parsed = computed(() => parsePhysicalForm(this.form()));

  /** What is wrong with the form: its own checks first, then whatever the server last refused. */
  protected readonly errors = computed<PhysicalErrors>(() => {
    const own = this.parsed().errors;
    const refused = this.refusal();
    return refused && !own[refused.field] ? { ...own, [refused.field]: refused.reason } : own;
  });

  private readonly dirty = computed(() => {
    const stored = this.stored();
    if (stored === null) {
      return false;
    }
    return (
      JSON.stringify(this.parsed().request) !==
      JSON.stringify(parsePhysicalForm(formFromView(stored)).request)
    );
  });

  protected readonly canSave = computed(
    () => this.dirty() && Object.keys(this.errors()).length === 0 && !this.saving(),
  );

  /** The stored row would be removed: everything on the form is empty and there is a row to remove. */
  protected readonly clearing = computed(
    () => (this.stored()?.version ?? 0) > 0 && isEmptyRequest(this.parsed().request),
  );

  protected readonly markingConflict = computed(() =>
    conflictsWithMarking(this.parsed().request, this.markingRequired()),
  );

  protected readonly perUnitKey = computed<MessageKey>(() =>
    this.form().measure === 'VOLUME'
      ? 'catalog.editor.physical.per100ml'
      : 'catalog.editor.physical.per100g',
  );

  constructor() {
    effect(() => {
      const scope = this.scope();
      const variantId = this.variantId();
      untracked(() => void this.load(scope, variantId));
    });
  }

  protected retry(): void {
    void this.load(this.scope(), this.variantId());
  }

  private async load(scope: BrandScope, variantId: string): Promise<void> {
    this.state.set('loading');
    try {
      const view = await firstValueFrom(this.api.physicalAttributes(scope, variantId));
      this.adopt(view);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else if (error instanceof ApiError) {
        this.state.set('failed');
      } else {
        throw error;
      }
    }
  }

  private adopt(view: PhysicalAttributesView): void {
    this.stored.set(view);
    this.form.set(formFromView(view));
    this.refusal.set(null);
  }

  // ------------------------------------------------------------ the form

  private edit(change: Partial<PhysicalForm>): void {
    this.form.update((current) => ({ ...current, ...change }));
    this.refusal.set(null);
    this.notice.set(null);
  }

  protected setMeasure(measure: string): void {
    const next = measure as PhysicalMeasure;
    this.edit({ measure: next, measureValue: next === 'NONE' ? '' : this.form().measureValue });
  }

  protected setMeasureValue(value: string): void {
    this.edit({ measureValue: value });
  }

  protected setCatchweight(on: boolean): void {
    this.edit(on ? { catchweight: true } : { catchweight: false, quantum: '', nominal: '' });
  }

  protected setQuantum(value: string): void {
    this.edit({ quantum: value });
  }

  protected setNominal(value: string): void {
    this.edit({ nominal: value });
  }

  protected setSplittable(on: boolean): void {
    this.edit(on ? { splittable: true } : { splittable: false, portion: '' });
  }

  protected setPortion(value: string): void {
    this.edit({ portion: value });
  }

  protected setNutrient(field: 'calories' | 'protein' | 'fat' | 'carbs', value: string): void {
    this.edit({ [field]: value });
  }

  protected errorText(field: PhysicalField): string | null {
    const reason = this.errors()[field];
    return reason ? this.i18n.t(REASON_MESSAGE[reason]) : null;
  }

  // ------------------------------------------------------------ save

  protected async save(): Promise<void> {
    const stored = this.stored();
    if (!this.canSave() || stored === null) {
      return;
    }
    this.saving.set(true);
    this.notice.set(null);
    try {
      const view = await firstValueFrom(
        this.api.setPhysicalAttributes(
          this.scope(),
          this.variantId(),
          this.parsed().request,
          stored.version,
        ),
      );
      this.adopt(view);
      this.notice.set({ kind: 'success', text: this.i18n.t('catalog.editor.saved') });
      this.saved.emit();
    } catch (error) {
      await this.handleSaveError(error);
    } finally {
      this.saving.set(false);
    }
  }

  private async handleSaveError(error: unknown): Promise<void> {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (error.code === ApiErrorCode.STALE_VERSION) {
      // Someone saved first: show what is there now rather than overwrite it.
      await this.load(this.scope(), this.variantId());
      this.notice.set({ kind: 'warning', text: this.i18n.t('catalog.editor.physical.stale') });
      return;
    }
    const reason = error.problem?.['reason'];
    if (error.code === ApiErrorCode.VALIDATION_FAILED && isPhysicalReason(reason)) {
      const field = fieldOfReason(reason);
      if (field !== null) {
        this.refusal.set({ field, reason });
        return;
      }
    }
    this.notice.set({
      kind: 'error',
      text: describeApiError(error, (key, values) => this.i18n.t(key, values)),
    });
  }
}
