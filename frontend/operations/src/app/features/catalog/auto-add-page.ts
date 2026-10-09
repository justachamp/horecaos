import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { describeApiError } from '../orders/order-errors';
import {
  AUTO_ADD_CHANNEL_TYPES,
  AUTO_ADD_FULFILLMENT_MODES,
  AutoAddApi,
  AutoAddBand,
  AutoAddKind,
  AutoAddMode,
  AutoAddRule,
  AutoAddRuleBody,
  SimulationResult,
} from './auto-add-api';
import { CatalogApi, fetchAllVariantsAtLocation } from './catalog-api';

/** The stable `findingCode`s a rule is refused with, and the sentence for each. */
const REFUSALS: Readonly<Record<string, MessageKey>> = {
  AUTO_ADD_RULE_INVALID: 'catalog.autoAdd.refusal.AUTO_ADD_RULE_INVALID',
  AUTO_ADD_CHANNEL_NOT_ALLOWED: 'catalog.autoAdd.refusal.AUTO_ADD_CHANNEL_NOT_ALLOWED',
  AUTO_ADD_BANDS_OVERLAP: 'catalog.autoAdd.refusal.AUTO_ADD_BANDS_OVERLAP',
};

/** One band row as the author edits it: numbers as text until they are sent. */
interface BandDraft {
  readonly from: string;
  readonly to: string;
  readonly variantId: string | null;
  readonly quantity: string;
}

interface Draft {
  readonly code: string;
  readonly kind: AutoAddKind;
  readonly mode: AutoAddMode;
  readonly status: 'DRAFT' | 'ACTIVE';
  readonly addVariantId: string | null;
  readonly quantity: string;
  readonly triggerThreshold: string;
  readonly repeatPerMultiple: boolean;
  readonly minimumQuantity: string;
  readonly maximumQuantity: string;
  readonly mandatory: boolean;
  readonly fulfillmentModes: readonly string[];
  readonly channelSystemTypes: readonly string[];
  readonly sortOrder: string;
  readonly triggerVariantIds: readonly string[];
  readonly bands: readonly BandDraft[];
  readonly textRu: string;
  readonly textUz: string;
  readonly textEn: string;
}

const EMPTY_DRAFT: Draft = {
  code: '',
  kind: 'PLAIN',
  mode: 'INJECT',
  status: 'DRAFT',
  addVariantId: null,
  quantity: '1',
  triggerThreshold: '1',
  repeatPerMultiple: false,
  minimumQuantity: '1',
  maximumQuantity: '20',
  mandatory: false,
  fulfillmentModes: [...AUTO_ADD_FULFILLMENT_MODES],
  channelSystemTypes: [...AUTO_ADD_CHANNEL_TYPES],
  sortOrder: '0',
  triggerVariantIds: [],
  bands: [],
  textRu: '',
  textUz: '',
  textEn: '',
};

function number(text: string): number | null {
  const parsed = Number(text.replace(',', '.'));
  return text.trim() !== '' && Number.isFinite(parsed) ? parsed : null;
}

/**
 * Catalog, Auto-add rules (ADR 0158, gap-map row 4.9): the packaging, cutlery and gift lines a rule puts in a
 * cart, and a simulator that previews them on a hypothetical basket before a rule goes live.
 *
 * Three kinds: **plain** adds a fixed quantity of one variant whenever the order's context matches;
 * **product-triggered** adds a quantity per step of trigger units in the basket; **portion band** totals the
 * basket's portions and adds the variant and quantity of the band that holds the total. A rule conditions on the
 * fulfilment mode, the channel's system type (cart-built channels only: an aggregator or POS order cannot carry a
 * rule), optionally the location, and the basket's contents, and on nothing else. A rule may **inject** (it
 * writes the line, and a mandatory one cannot be declined) or **offer** (it suggests the line and the customer
 * takes it or not). A rule is saved as a draft and goes live with an explicit activation; an active rule reaches
 * customers with the next publication. Archiving keeps the row: any published snapshot can be traced to it.
 *
 * Read and written at the brand; the simulator writes nothing.
 */
@Component({
  selector: 'q-auto-add-page',
  imports: [TPipe, Combobox],
  templateUrl: './auto-add-page.html',
  styleUrl: './auto-add-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AutoAddPage {
  private readonly api = inject(AutoAddApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly fulfillmentModes = AUTO_ADD_FULFILLMENT_MODES;
  protected readonly channelTypes = AUTO_ADD_CHANNEL_TYPES;
  protected readonly kinds: readonly AutoAddKind[] = ['PLAIN', 'PRODUCT_TRIGGERED', 'PORTION_BAND'];

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);
  protected readonly rules = signal<readonly AutoAddRule[]>([]);

  /** Variant id to the name a person reads, from the location's whole variant list. */
  protected readonly variantNames = signal<ReadonlyMap<string, string>>(new Map());

  // -------------------------------------------------------------------------------------- the form

  /** Null while closed; `create` or the rule being replaced while open. */
  protected readonly editing = signal<'create' | AutoAddRule | null>(null);
  protected readonly draft = signal<Draft>(EMPTY_DRAFT);
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly pickerQuery = signal('');
  protected readonly pickerOptions = signal<readonly ComboboxOption[]>([]);
  protected readonly pickerLoading = signal(false);
  /** Which control the picker feeds: the added variant, a trigger, or a band row (by index). */
  private pickerTarget: 'add' | 'trigger' | { readonly band: number } = 'add';

  protected readonly isCreate = computed(() => this.editing() === 'create');

  protected readonly canSubmit = computed(() => {
    const draft = this.draft();
    if (this.submitting() || this.editing() === null) {
      return false;
    }
    if (this.isCreate() && draft.code.trim().length === 0) {
      return false;
    }
    if (draft.fulfillmentModes.length === 0 || draft.channelSystemTypes.length === 0) {
      return false;
    }
    if (draft.kind === 'PORTION_BAND') {
      return (
        draft.bands.length > 0 &&
        draft.bands.every(
          (band) =>
            band.variantId !== null && number(band.from) !== null && number(band.quantity) !== null,
        )
      );
    }
    if (draft.addVariantId === null || number(draft.quantity) === null) {
      return false;
    }
    return draft.kind !== 'PRODUCT_TRIGGERED' || draft.triggerVariantIds.length > 0;
  });

  // ------------------------------------------------------------------------------------ simulator

  protected readonly simMode = signal<string>('DELIVERY');
  protected readonly simChannel = signal<string>('WEB');
  protected readonly simLines = signal<readonly { variantId: string; quantity: string }[]>([]);
  protected readonly simRunning = signal(false);
  protected readonly simResult = signal<SimulationResult | null>(null);
  protected readonly simError = signal<string | null>(null);
  protected readonly simQuery = signal('');
  protected readonly simOptions = signal<readonly ComboboxOption[]>([]);
  protected readonly simLoading = signal(false);

  protected readonly canSimulate = computed(
    () =>
      !this.simRunning() &&
      this.simLines().length > 0 &&
      this.simLines().every((line) => number(line.quantity) !== null),
  );

  constructor() {
    void this.load();
  }

  // ------------------------------------------------------------------------------------ rules list

  protected nameOf(variantId: string | null | undefined): string {
    if (!variantId) {
      return '—';
    }
    return this.variantNames().get(variantId) ?? variantId.slice(0, 8);
  }

  /** One line saying what the rule does, from its own fields. */
  protected summary(rule: AutoAddRule): string {
    switch (rule.kind) {
      case 'PLAIN':
        return this.i18n.t('catalog.autoAdd.summary.plain', {
          quantity: rule.quantity ?? 0,
          variant: this.nameOf(rule.addVariantId),
        });
      case 'PRODUCT_TRIGGERED':
        return this.i18n.t('catalog.autoAdd.summary.triggered', {
          quantity: rule.quantity ?? 0,
          variant: this.nameOf(rule.addVariantId),
          step: rule.triggerThreshold ?? 0,
        });
      case 'PORTION_BAND':
        return this.i18n.t('catalog.autoAdd.summary.bands', { count: rule.bands.length });
    }
  }

  protected kindKey(kind: AutoAddKind): MessageKey {
    return `catalog.autoAdd.kind.${kind}` as MessageKey;
  }

  protected modeKey(mode: AutoAddMode): MessageKey {
    return `catalog.autoAdd.mode.${mode}` as MessageKey;
  }

  protected toggleKey(rule: AutoAddRule): MessageKey {
    return rule.status === 'ACTIVE' ? 'catalog.autoAdd.deactivate' : 'catalog.autoAdd.activate';
  }

  protected statusKey(rule: AutoAddRule): MessageKey {
    return `catalog.autoAdd.status.${rule.status}` as MessageKey;
  }

  protected async archive(rule: AutoAddRule): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    if (!confirm(this.i18n.t('catalog.autoAdd.archive.confirm', { code: rule.code }))) {
      return;
    }
    try {
      await this.api.archive(scope, rule);
      await this.reload();
    } catch (error) {
      this.lastError.set(this.describe(error));
    }
  }

  /** A draft goes live, or an active rule goes back to draft, with the same replace that edits it. */
  protected async toggleActive(rule: AutoAddRule): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || rule.status === 'ARCHIVED') {
      return;
    }
    try {
      await this.api.replace(scope, rule, {
        ...bodyOf(rule),
        status: rule.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE',
      });
      await this.reload();
    } catch (error) {
      this.lastError.set(this.describe(error));
    }
  }

  // ------------------------------------------------------------------------------------ the form

  protected openCreate(): void {
    this.draft.set(EMPTY_DRAFT);
    this.formError.set(null);
    this.resetPicker();
    this.editing.set('create');
  }

  protected openEdit(rule: AutoAddRule): void {
    this.draft.set({
      code: rule.code,
      kind: rule.kind,
      mode: rule.mode,
      status: rule.status === 'ACTIVE' ? 'ACTIVE' : 'DRAFT',
      addVariantId: rule.addVariantId,
      quantity: String(rule.quantity ?? 1),
      triggerThreshold: String(rule.triggerThreshold ?? 1),
      repeatPerMultiple: rule.repeatPerMultiple,
      minimumQuantity: String(rule.minimumQuantity),
      maximumQuantity: String(rule.maximumQuantity),
      mandatory: rule.mandatory,
      fulfillmentModes: rule.fulfillmentModes,
      channelSystemTypes: rule.channelSystemTypes,
      sortOrder: String(rule.sortOrder),
      triggerVariantIds: rule.triggers.flatMap((trigger) =>
        trigger.variantId ? [trigger.variantId] : [],
      ),
      bands: rule.bands.map((band) => ({
        from: String(band.portionsFrom),
        to: band.portionsTo == null ? '' : String(band.portionsTo),
        variantId: band.addVariantId,
        quantity: String(band.quantity),
      })),
      textRu: rule.customerText['ru'] ?? '',
      textUz: rule.customerText['uz-Latn'] ?? rule.customerText['uz'] ?? '',
      textEn: rule.customerText['en'] ?? '',
    });
    this.formError.set(null);
    this.resetPicker();
    this.editing.set(rule);
  }

  protected close(): void {
    this.editing.set(null);
  }

  protected patch(change: Partial<Draft>): void {
    this.draft.update((current) => ({ ...current, ...change }));
  }

  protected toggleIn(field: 'fulfillmentModes' | 'channelSystemTypes', value: string): void {
    const current = this.draft()[field];
    this.patch({
      [field]: current.includes(value) ? current.filter((v) => v !== value) : [...current, value],
    });
  }

  protected addBand(): void {
    this.patch({
      bands: [...this.draft().bands, { from: '', to: '', variantId: null, quantity: '1' }],
    });
  }

  protected patchBand(index: number, change: Partial<BandDraft>): void {
    this.patch({
      bands: this.draft().bands.map((band, i) => (i === index ? { ...band, ...change } : band)),
    });
  }

  protected removeBand(index: number): void {
    this.patch({ bands: this.draft().bands.filter((_, i) => i !== index) });
  }

  protected removeTrigger(variantId: string): void {
    this.patch({
      triggerVariantIds: this.draft().triggerVariantIds.filter((v) => v !== variantId),
    });
  }

  /** Points the shared picker at one control; the next pick lands there. */
  protected aim(target: 'add' | 'trigger' | { readonly band: number }): void {
    this.pickerTarget = target;
    this.pickerQuery.set('');
    this.pickerOptions.set([]);
  }

  protected async searchVariants(text: string): Promise<void> {
    this.pickerOptions.set(await this.search(text, this.pickerLoading));
  }

  protected pick(option: ComboboxOption): void {
    this.rememberName(option);
    const target = this.pickerTarget;
    if (target === 'add') {
      this.patch({ addVariantId: option.id });
    } else if (target === 'trigger') {
      const held = this.draft().triggerVariantIds;
      this.patch({
        triggerVariantIds: held.includes(option.id) ? held : [...held, option.id],
      });
    } else {
      this.patchBand(target.band, { variantId: option.id });
    }
    this.pickerQuery.set('');
    this.pickerOptions.set([]);
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    const target = this.editing();
    if (!scope || target === null || !this.canSubmit()) {
      return;
    }
    const draft = this.draft();
    const body = this.bodyFrom(draft);
    this.submitting.set(true);
    this.formError.set(null);
    try {
      if (target === 'create') {
        await this.api.create(scope, { ...body, code: draft.code.trim(), kind: draft.kind });
      } else {
        await this.api.replace(scope, target, { ...body, status: draft.status });
      }
      this.editing.set(null);
      await this.reload();
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  private bodyFrom(draft: Draft): AutoAddRuleBody {
    const text: Record<string, string> = {};
    if (draft.textRu.trim()) {
      text['ru'] = draft.textRu.trim();
    }
    if (draft.textUz.trim()) {
      text['uz-Latn'] = draft.textUz.trim();
    }
    if (draft.textEn.trim()) {
      text['en'] = draft.textEn.trim();
    }
    const banded = draft.kind === 'PORTION_BAND';
    return {
      mode: draft.mode,
      ...(banded
        ? {}
        : { addVariantId: draft.addVariantId, quantity: number(draft.quantity) ?? undefined }),
      ...(draft.kind === 'PRODUCT_TRIGGERED'
        ? {
            triggerThreshold: number(draft.triggerThreshold) ?? undefined,
            repeatPerMultiple: draft.repeatPerMultiple,
            triggers: draft.triggerVariantIds.map((variantId) => ({ variantId })),
          }
        : {}),
      ...(banded
        ? {
            bands: draft.bands.map((band): AutoAddBand => ({
              portionsFrom: number(band.from) ?? 0,
              portionsTo: number(band.to),
              addVariantId: band.variantId ?? '',
              quantity: number(band.quantity) ?? 1,
            })),
          }
        : {}),
      minimumQuantity: number(draft.minimumQuantity) ?? 1,
      maximumQuantity: number(draft.maximumQuantity) ?? 20,
      mandatory: draft.mandatory,
      fulfillmentModes: draft.fulfillmentModes,
      channelSystemTypes: draft.channelSystemTypes,
      sortOrder: number(draft.sortOrder) ?? 0,
      customerText: text,
    };
  }

  // ------------------------------------------------------------------------------------ simulator

  protected async searchSimVariants(text: string): Promise<void> {
    this.simOptions.set(await this.search(text, this.simLoading));
  }

  protected addSimLine(option: ComboboxOption): void {
    this.rememberName(option);
    this.simLines.update((lines) => [...lines, { variantId: option.id, quantity: '1' }]);
    this.simQuery.set('');
    this.simOptions.set([]);
    this.simResult.set(null);
  }

  protected setSimQuantity(index: number, quantity: string): void {
    this.simLines.update((lines) =>
      lines.map((line, i) => (i === index ? { ...line, quantity } : line)),
    );
    this.simResult.set(null);
  }

  protected removeSimLine(index: number): void {
    this.simLines.update((lines) => lines.filter((_, i) => i !== index));
    this.simResult.set(null);
  }

  protected async simulate(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSimulate()) {
      return;
    }
    this.simRunning.set(true);
    this.simError.set(null);
    try {
      const location = this.location.scope();
      this.simResult.set(
        await this.api.simulate(scope, {
          fulfillmentMode: this.simMode(),
          channelSystemType: this.simChannel(),
          locationId: location?.locationId ?? null,
          lines: this.simLines().map((line) => ({
            variantId: line.variantId,
            quantity: number(line.quantity) ?? 1,
          })),
        }),
      );
    } catch (error) {
      this.simResult.set(null);
      this.simError.set(this.describe(error));
    } finally {
      this.simRunning.set(false);
    }
  }

  protected outcomeKey(outcome: string): MessageKey {
    return `catalog.autoAdd.outcome.${outcome}` as MessageKey;
  }

  // ------------------------------------------------------------------------------------ internals

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.rules.set(await this.api.list(scope));
      await this.loadNames();
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.lastError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reload(): Promise<void> {
    const scope = this.brand.scope();
    if (scope) {
      this.rules.set(await this.api.list(scope));
    }
  }

  /** Names for the ids a rule holds, from the location's variant list; an id with no name shows short. */
  private async loadNames(): Promise<void> {
    const scope = this.brand.scope();
    await this.location.ensureLoaded();
    const location = this.location.scope();
    if (!scope || !location) {
      return;
    }
    try {
      const rows = await fetchAllVariantsAtLocation(this.catalogApi, scope, location.locationId);
      this.variantNames.set(new Map(rows.map((row) => [row.variantId, row.productName])));
    } catch {
      // Names are a courtesy: the rules list works without them.
    }
  }

  private async search(
    text: string,
    loading: { set(value: boolean): void },
  ): Promise<readonly ComboboxOption[]> {
    const scope = this.brand.scope();
    const query = text.trim();
    await this.location.ensureLoaded();
    const location = this.location.scope();
    if (!scope || !location || query.length < 2) {
      return [];
    }
    loading.set(true);
    try {
      const rows = await fetchAllVariantsAtLocation(this.catalogApi, scope, location.locationId, {
        search: query,
      });
      return rows.slice(0, 20).map((row) => ({
        id: row.variantId,
        label: row.productName,
        sublabel: row.category ?? null,
      }));
    } catch {
      return [];
    } finally {
      loading.set(false);
    }
  }

  private rememberName(option: ComboboxOption): void {
    this.variantNames.update((names) => new Map(names).set(option.id, option.label));
  }

  private resetPicker(): void {
    this.pickerTarget = 'add';
    this.pickerQuery.set('');
    this.pickerOptions.set([]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      const finding = error.problem?.['findingCode'];
      const key = typeof finding === 'string' ? REFUSALS[finding] : undefined;
      return key !== undefined
        ? this.i18n.t(key)
        : describeApiError(error, (k, values) => this.i18n.t(k, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

/** A rule's own fields as a replace body, for a change that touches only its status. */
function bodyOf(rule: AutoAddRule): AutoAddRuleBody {
  return {
    mode: rule.mode,
    addVariantId: rule.addVariantId,
    quantity: rule.quantity,
    triggerThreshold: rule.triggerThreshold,
    repeatPerMultiple: rule.repeatPerMultiple,
    minimumQuantity: rule.minimumQuantity,
    maximumQuantity: rule.maximumQuantity,
    mandatory: rule.mandatory,
    fulfillmentModes: rule.fulfillmentModes,
    channelSystemTypes: rule.channelSystemTypes,
    sortOrder: rule.sortOrder,
    triggers: rule.triggers,
    bands: rule.bands,
    locationIds: rule.locationIds,
    customerText: rule.customerText,
  };
}
