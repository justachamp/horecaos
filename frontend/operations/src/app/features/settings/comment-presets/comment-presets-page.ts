import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import {
  LabelsByLocale,
  PLATFORM_LOCALE_SET,
  labelDrafts,
  labelsToSend,
  localeDisplayName,
  platformColumns,
} from '../../../core/i18n/locale-labels';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { CommentPresetsApi, NewPreset, PresetEdit, PresetResponse } from './comment-presets-api';

const STATUSES = ['ACTIVE', 'ARCHIVED'] as const;

/**
 * Row 2.1b — preset product comments. The tenant-wide coded
 * kitchen-instruction vocabulary itself (create, list, edit, archive); which
 * presets a product offers on a line is the product editor's own concern
 * (`ProductCommentPresetController`), not this screen's.
 *
 * `TENANT`-only, like `CatalogSettingsPage`'s own switches: a preset is
 * shared by every brand a tenant runs, so this page reads and writes at
 * `CurrentTenant`, ignoring the shell's brand/location scope bar entirely.
 *
 * **Row 10.12 — the wording is per locale, not a fixed ru/uz/en triple.** A
 * preset belongs to the tenant, not to a brand, so it is edited in the
 * *union of the tenant's brands' supported languages*, default first — the
 * default being the tenant's first brand's (`TenantLocaleSet`, read from
 * `GET .../comment-presets/locale-set`). One field per offered language; the
 * default language is the one wording a preset must have. **A language the
 * tenant does not offer is never touched by an edit**: `labels` carries only
 * the offered, filled-in languages ({@link labelsToSend}) so the server keeps
 * every other one beyond the platform triple, and the three platform fields —
 * which the OpenAPI contract keeps required — go back for a language the
 * tenant does not offer with the wording the preset already has, unchanged
 * ({@link platformColumns}). Narrowing the brands' languages later therefore
 * cannot silently delete a translation.
 *
 * **Not built here, honestly**: no screen yet selects a preset on an order
 * line, no KDS renders one, and no POS export maps `posModifierCode` to a
 * modifier — this screen and `CommentPresetController`/
 * `ProductCommentPresetController` are the vocabulary and its product
 * attachment; the line-level round-trip is a follow-up wave's own.
 */
@Component({
  selector: 'q-comment-presets-page',
  imports: [TPipe],
  templateUrl: './comment-presets-page.html',
  styleUrl: './comment-presets-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommentPresetsPage {
  private readonly api = inject(CommentPresetsApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly i18n = inject(I18n);

  protected readonly statuses = STATUSES;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);

  protected readonly presets = signal<readonly PresetResponse[]>([]);
  protected readonly sortedPresets = computed(() =>
    [...this.presets()].sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code)),
  );

  /** The languages this editor offers (row 10.12): the platform triple until the tenant's set loads. */
  protected readonly localeSet = signal(PLATFORM_LOCALE_SET);
  protected readonly locales = computed(() => this.localeSet().locales);
  protected readonly defaultLocale = computed(() => this.localeSet().defaultLocale);

  protected readonly formCode = signal('');
  protected readonly formLabels = signal<LabelsByLocale>({});
  protected readonly formPosModifierCode = signal('');
  protected readonly formSortOrder = signal(0);
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  /** A code and the wording in the tenant's default language — every other language is optional. */
  protected readonly formValid = computed(
    () =>
      /^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(this.formCode()) &&
      (this.formLabels()[this.defaultLocale()] ?? '').trim().length > 0,
  );

  /** The one preset, if any, being corrected in place. */
  protected readonly editingPresetId = signal<string | null>(null);
  protected readonly editLabels = signal<LabelsByLocale>({});
  protected readonly editPosModifierCode = signal('');
  protected readonly editSortOrder = signal(0);
  protected readonly editStatus = signal<string>('ACTIVE');
  protected readonly editSubmitting = signal(false);
  protected readonly editError = signal<string | null>(null);

  protected readonly editValid = computed(
    () => (this.editLabels()[this.defaultLocale()] ?? '').trim().length > 0,
  );

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.denied.set(true);
      this.loading.set(false);
      return;
    }
    this.tenantId = tenantId;
    try {
      const [presets, localeSet] = await Promise.all([
        this.api.list(tenantId),
        // The set only decides which languages the form offers; a tenant whose
        // set cannot be read still gets a working editor on the platform triple.
        this.api.localeSet(tenantId).catch(() => PLATFORM_LOCALE_SET),
      ]);
      this.presets.set(presets);
      this.localeSet.set(localeSet);
      this.denied.set(false);
      this.lastError.set(null);
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

  protected localeName(locale: string): string {
    return localeDisplayName(this.i18n, locale);
  }

  protected isDefault(locale: string): boolean {
    return locale === this.defaultLocale();
  }

  protected setFormLabel(locale: string, value: string): void {
    this.formLabels.update((labels) => ({ ...labels, [locale]: value }));
  }

  protected setEditLabel(locale: string, value: string): void {
    this.editLabels.update((labels) => ({ ...labels, [locale]: value }));
  }

  /** Languages this preset has wording in that the editor does not offer — kept, not shown. */
  protected hiddenLocales(preset: PresetResponse): readonly string[] {
    const offered = new Set(this.locales());
    return Object.keys(preset.labels ?? {}).filter((locale) => !offered.has(locale));
  }

  protected statusLabel(status: string): string {
    return status === 'ARCHIVED'
      ? this.i18n.t('settings.commentPresets.status.archived')
      : this.i18n.t('settings.commentPresets.status.active');
  }

  protected async submit(): Promise<void> {
    this.formTouched.set(true);
    const tenantId = this.tenantId;
    if (!tenantId || !this.formValid()) {
      return;
    }
    const columns = platformColumns(this.locales(), this.formLabels(), this.defaultLocale());
    const body: NewPreset = {
      code: this.formCode(),
      labelRu: columns.ru,
      labelUz: columns['uz-Latn'],
      labelEn: columns.en,
      labels: labelsToSend(this.locales(), this.formLabels()),
      posModifierCode: this.formPosModifierCode().trim() || null,
      sortOrder: this.formSortOrder(),
    };
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const created = await firstValueFrom(this.api.create(tenantId, body));
      this.presets.update((current) => [...current, created]);
      this.formCode.set('');
      this.formLabels.set({});
      this.formPosModifierCode.set('');
      this.formSortOrder.set(0);
      this.formTouched.set(false);
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  protected isEditing(preset: PresetResponse): boolean {
    return this.editingPresetId() === preset.presetId;
  }

  protected startEdit(preset: PresetResponse): void {
    this.editingPresetId.set(preset.presetId);
    this.editLabels.set(labelDrafts(this.locales(), preset.labels));
    this.editPosModifierCode.set(preset.posModifierCode ?? '');
    this.editSortOrder.set(preset.sortOrder);
    this.editStatus.set(preset.status);
    this.editError.set(null);
  }

  protected cancelEdit(): void {
    this.editingPresetId.set(null);
    this.editError.set(null);
  }

  protected async submitEdit(preset: PresetResponse): Promise<void> {
    if (!this.editValid()) {
      return;
    }
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    // The contract keeps the platform triple required, so a platform language the
    // tenant does not offer goes back exactly as the preset has it; a language
    // beyond the triple that is not offered is simply not in `labels`.
    const columns = platformColumns(this.locales(), this.editLabels(), this.defaultLocale(), {
      ru: preset.labelRu,
      'uz-Latn': preset.labelUz,
      en: preset.labelEn,
    });
    const body: PresetEdit = {
      labelRu: columns.ru,
      labelUz: columns['uz-Latn'],
      labelEn: columns.en,
      labels: labelsToSend(this.locales(), this.editLabels()),
      posModifierCode: this.editPosModifierCode().trim() || null,
      sortOrder: this.editSortOrder(),
      status: this.editStatus(),
      expectedVersion: preset.version,
    };
    this.editSubmitting.set(true);
    this.editError.set(null);
    try {
      const updated = await firstValueFrom(this.api.update(tenantId, preset.presetId, body));
      this.presets.update((current) =>
        current.map((row) => (row.presetId === updated.presetId ? updated : row)),
      );
      this.editingPresetId.set(null);
    } catch (error) {
      this.editError.set(this.describe(error));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
