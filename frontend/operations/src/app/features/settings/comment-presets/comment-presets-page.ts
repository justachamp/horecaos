import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
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

  protected readonly formCode = signal('');
  protected readonly formLabelRu = signal('');
  protected readonly formLabelUz = signal('');
  protected readonly formLabelEn = signal('');
  protected readonly formPosModifierCode = signal('');
  protected readonly formSortOrder = signal(0);
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly formValid = computed(
    () =>
      /^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(this.formCode()) &&
      this.formLabelRu().trim().length > 0 &&
      this.formLabelUz().trim().length > 0 &&
      this.formLabelEn().trim().length > 0,
  );

  /** The one preset, if any, being corrected in place. */
  protected readonly editingPresetId = signal<string | null>(null);
  protected readonly editLabelRu = signal('');
  protected readonly editLabelUz = signal('');
  protected readonly editLabelEn = signal('');
  protected readonly editPosModifierCode = signal('');
  protected readonly editSortOrder = signal(0);
  protected readonly editStatus = signal<string>('ACTIVE');
  protected readonly editSubmitting = signal(false);
  protected readonly editError = signal<string | null>(null);

  protected readonly editValid = computed(
    () =>
      this.editLabelRu().trim().length > 0 &&
      this.editLabelUz().trim().length > 0 &&
      this.editLabelEn().trim().length > 0,
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
      this.presets.set(await this.api.list(tenantId));
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
    const body: NewPreset = {
      code: this.formCode(),
      labelRu: this.formLabelRu().trim(),
      labelUz: this.formLabelUz().trim(),
      labelEn: this.formLabelEn().trim(),
      posModifierCode: this.formPosModifierCode().trim() || null,
      sortOrder: this.formSortOrder(),
    };
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const created = await firstValueFrom(this.api.create(tenantId, body));
      this.presets.update((current) => [...current, created]);
      this.formCode.set('');
      this.formLabelRu.set('');
      this.formLabelUz.set('');
      this.formLabelEn.set('');
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
    this.editLabelRu.set(preset.labelRu);
    this.editLabelUz.set(preset.labelUz);
    this.editLabelEn.set(preset.labelEn);
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
    const body: PresetEdit = {
      labelRu: this.editLabelRu().trim(),
      labelUz: this.editLabelUz().trim(),
      labelEn: this.editLabelEn().trim(),
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
