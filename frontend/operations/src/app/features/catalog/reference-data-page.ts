import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import {
  LabelsByLocale,
  labelDrafts,
  labelsToSend,
  localeDisplayName,
  platformLocaleSet,
} from '../../core/i18n/locale-labels';
import type { MessageKey } from '../../core/i18n/messages.en';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CommentPresetsApi } from '../settings/comment-presets/comment-presets-api';
import { TAG_ICONS, VocabularyApi, VocabularyEntry, VocabularyKind } from './vocabulary-api';

/** The server's stable `findingCode`s a vocabulary write is refused with, and the sentence for each. */
const REFUSALS: Readonly<Record<string, MessageKey>> = {
  VOCABULARY_CODE_INVALID: 'catalog.referenceData.refusal.VOCABULARY_CODE_INVALID',
  VOCABULARY_CODE_TAKEN: 'catalog.referenceData.refusal.VOCABULARY_CODE_TAKEN',
  TAG_ICON_UNKNOWN: 'catalog.referenceData.refusal.TAG_ICON_UNKNOWN',
  TAG_NOT_VISIBLE: 'catalog.referenceData.refusal.TAG_NOT_VISIBLE',
  VOCABULARY_DEFAULT_NAME_MISSING: 'catalog.referenceData.refusal.VOCABULARY_DEFAULT_NAME_MISSING',
  VOCABULARY_TENANT_LIMIT_REACHED: 'catalog.referenceData.refusal.VOCABULARY_TENANT_LIMIT_REACHED',
};

/**
 * Catalog, Reference data (ADR 0174, gap-map row 4.7): where the two vocabularies the row names that had no
 * table -- tags and ingredients -- are authored, and where the four that live elsewhere are pointed to.
 *
 * A left rail picks the vocabulary; the right is its list and form. Both registries are tenant-wide (a chain's
 * "spicy" means the same at every brand), so the page reads and writes at `CurrentTenant` and ignores the
 * brand scope bar. A code is chosen once and never changes; a rename changes the wording; an archived entry
 * keeps resolving on the products that carry it and leaves the next publication. The wording is per locale, in
 * the union of the tenant's brands' languages, the default language required.
 *
 * An ingredient is a composition label and nothing else: the page says so, and offers no price, stock or
 * allergen field because none exists.
 */
@Component({
  selector: 'q-reference-data-page',
  imports: [TPipe, RouterLink],
  templateUrl: './reference-data-page.html',
  styleUrl: './reference-data-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReferenceDataPage {
  private readonly api = inject(VocabularyApi);
  private readonly presets = inject(CommentPresetsApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  protected readonly icons = TAG_ICONS;

  protected readonly kind = signal<VocabularyKind>('TAG');
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);
  protected readonly entries = signal<Readonly<Record<VocabularyKind, readonly VocabularyEntry[]>>>(
    {
      TAG: [],
      INGREDIENT: [],
    },
  );
  protected readonly localeSet = signal(platformLocaleSet(this.registry));
  protected readonly locales = computed(() => this.localeSet().locales);
  protected readonly defaultLocale = computed(() => this.localeSet().defaultLocale);
  protected readonly isTag = computed(() => this.kind() === 'TAG');

  protected readonly rows = computed(() =>
    [...this.entries()[this.kind()]].sort(
      (a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code),
    ),
  );

  // create form
  protected readonly formCode = signal('');
  protected readonly formIcon = signal('');
  protected readonly formBadge = signal(true);
  protected readonly formFilter = signal(false);
  protected readonly formSortOrder = signal(0);
  protected readonly formNames = signal<LabelsByLocale>({});
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  /** A code in the registry's shape, the wording in the default language, and (a tag) a badge or a filter. */
  protected readonly formValid = computed(
    () =>
      /^[a-z0-9][a-z0-9_-]{0,31}$/.test(this.formCode()) &&
      (this.formNames()[this.defaultLocale()] ?? '').trim().length > 0 &&
      (!this.isTag() || this.formBadge() || this.formFilter()),
  );

  // in-place edit
  protected readonly editingId = signal<string | null>(null);
  protected readonly editIcon = signal('');
  protected readonly editBadge = signal(true);
  protected readonly editFilter = signal(false);
  protected readonly editSortOrder = signal(0);
  protected readonly editNames = signal<LabelsByLocale>({});
  protected readonly editSubmitting = signal(false);
  protected readonly editError = signal<string | null>(null);
  protected readonly editValid = computed(
    () =>
      (this.editNames()[this.defaultLocale()] ?? '').trim().length > 0 &&
      (!this.isTag() || this.editBadge() || this.editFilter()),
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
      const [tags, ingredients, localeSet] = await Promise.all([
        this.api.list(tenantId, 'TAG'),
        this.api.list(tenantId, 'INGREDIENT'),
        // The tenant's language set decides which wording fields the form offers; a tenant whose set cannot
        // be read still gets a working editor on the platform languages.
        this.presets.localeSet(tenantId).catch(() => platformLocaleSet(this.registry)),
      ]);
      this.entries.set({ TAG: tags, INGREDIENT: ingredients });
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

  protected choose(kind: VocabularyKind): void {
    this.kind.set(kind);
    this.editingId.set(null);
    this.formError.set(null);
    this.formTouched.set(false);
  }

  protected localeName(locale: string): string {
    return localeDisplayName(this.i18n, locale);
  }

  protected isDefault(locale: string): boolean {
    return locale === this.defaultLocale();
  }

  protected iconKey(icon: string): MessageKey {
    return `catalog.referenceData.icon.${icon}` as MessageKey;
  }

  /** The entry's name in the tenant's default language, else any it has: what a list row shows. */
  protected nameOf(entry: VocabularyEntry): string {
    return entry.names[this.defaultLocale()] ?? Object.values(entry.names)[0] ?? entry.code;
  }

  protected setFormName(locale: string, value: string): void {
    this.formNames.update((names) => ({ ...names, [locale]: value }));
  }

  protected setEditName(locale: string, value: string): void {
    this.editNames.update((names) => ({ ...names, [locale]: value }));
  }

  protected async create(): Promise<void> {
    this.formTouched.set(true);
    const tenantId = this.tenantId;
    if (!tenantId || !this.formValid()) {
      return;
    }
    const kind = this.kind();
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const created = await this.api.create(tenantId, kind, {
        code: this.formCode(),
        ...(kind === 'TAG'
          ? {
              iconKey: this.formIcon() || null,
              showAsBadge: this.formBadge(),
              showAsFilter: this.formFilter(),
            }
          : {}),
        sortOrder: this.formSortOrder(),
        names: labelsToSend(this.locales(), this.formNames()),
      });
      this.entries.update((all) => ({ ...all, [kind]: [...all[kind], created] }));
      this.formCode.set('');
      this.formIcon.set('');
      this.formBadge.set(true);
      this.formFilter.set(false);
      this.formSortOrder.set(0);
      this.formNames.set({});
      this.formTouched.set(false);
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  protected startEdit(entry: VocabularyEntry): void {
    this.editingId.set(entry.id);
    this.editIcon.set(entry.iconKey ?? '');
    this.editBadge.set(entry.showAsBadge);
    this.editFilter.set(entry.showAsFilter);
    this.editSortOrder.set(entry.sortOrder);
    this.editNames.set(labelDrafts(this.locales(), entry.names));
    this.editError.set(null);
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
    this.editError.set(null);
  }

  protected async saveEdit(entry: VocabularyEntry): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId || !this.editValid()) {
      return;
    }
    const kind = this.kind();
    this.editSubmitting.set(true);
    this.editError.set(null);
    try {
      // Only the offered, filled-in languages go back: a language the editor does not show keeps its wording.
      const updated = await this.api.update(tenantId, kind, entry, {
        ...(kind === 'TAG'
          ? {
              iconKey: this.editIcon() || null,
              showAsBadge: this.editBadge(),
              showAsFilter: this.editFilter(),
            }
          : {}),
        sortOrder: this.editSortOrder(),
        names: labelsToSend(this.locales(), this.editNames()),
      });
      this.replace(kind, updated);
      this.editingId.set(null);
    } catch (error) {
      this.editError.set(this.describe(error));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  protected async archive(entry: VocabularyEntry): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    const kind = this.kind();
    this.lastError.set(null);
    try {
      this.replace(kind, await this.api.archive(tenantId, kind, entry));
    } catch (error) {
      this.lastError.set(this.describe(error));
    }
  }

  private replace(kind: VocabularyKind, updated: VocabularyEntry): void {
    this.entries.update((all) => ({
      ...all,
      [kind]: all[kind].map((row) => (row.id === updated.id ? updated : row)),
    }));
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
