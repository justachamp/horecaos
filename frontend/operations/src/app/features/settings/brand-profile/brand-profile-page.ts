import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  BrandLocaleCode,
  BrandProfileApi,
  BrandView,
  UpdateBrandProfileRequest,
} from './brand-profile-api';

/** One row of the supported-language editor, over the fixed set the console can author content in today. */
interface LocaleDraft {
  readonly locale: BrandLocaleCode;
  included: boolean;
  description: string;
  isDefault: boolean;
}

/** `uz.horecaos.platform.tenancy.domain.BrandProfile.KNOWN_LOCALES`, mirrored. */
const KNOWN_LOCALES: readonly BrandLocaleCode[] = ['ru', 'uz-Latn', 'en'];

/**
 * 10.1 Brand profile, 10.12 languages and regional formats —
 * `docs/operations-spec/settings.md` §10.1.
 *
 * Two independent writes, edited and saved separately because they are two
 * different acts on two different schedules (`BrandProfileApi`'s own doc):
 * **renaming** (trade name only — code and slug stay whatever they already
 * are) goes through `TenantControlPlaneController.reviseBrand`, requires
 * `If-Match` against the brand's version, and is the identity correction two
 * people could race on. **The profile** — contact phone, Telegram handle,
 * logo, banner (as already-uploaded media asset ids; this screen has no
 * uploader yet, named below rather than pretended away) and the 10.12
 * supported-locale set — goes through the new `.../profile` endpoint and
 * carries no version of its own.
 *
 * The language editor is a fixed three-row grid over `KNOWN_LOCALES` rather
 * than a free-text list: the console has an editor for exactly ru/uz-Latn/en
 * today, so offering a fourth would record a choice nothing can render.
 */
@Component({
  selector: 'q-brand-profile-page',
  imports: [TPipe],
  templateUrl: './brand-profile-page.html',
  styleUrl: './brand-profile-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandProfilePage {
  private readonly api = inject(BrandProfileApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly knownLocales = KNOWN_LOCALES;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly brand = signal<BrandView | null>(null);

  protected readonly editingName = signal(false);
  protected readonly draftDisplayName = signal('');
  protected readonly nameSaving = signal(false);
  protected readonly nameError = signal<string | null>(null);

  protected readonly editingProfile = signal(false);
  protected readonly draftContactPhone = signal('');
  protected readonly draftTelegramHandle = signal('');
  protected readonly draftLogoAssetId = signal('');
  protected readonly draftBannerAssetId = signal('');
  protected readonly draftLocales = signal<readonly LocaleDraft[]>([]);
  protected readonly profileSaving = signal(false);
  protected readonly profileError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected localeLabel(locale: BrandLocaleCode): string {
    switch (locale) {
      case 'ru':
        return this.i18n.t('settings.brandProfile.locale.ru');
      case 'uz-Latn':
        return this.i18n.t('settings.brandProfile.locale.uzLatn');
      case 'en':
        return this.i18n.t('settings.brandProfile.locale.en');
    }
  }

  protected statusLabel(status: BrandView['status']): string {
    switch (status) {
      case 'DRAFT':
        return this.i18n.t('settings.brandProfile.status.DRAFT');
      case 'ACTIVE':
        return this.i18n.t('settings.brandProfile.status.ACTIVE');
      case 'SUSPENDED':
        return this.i18n.t('settings.brandProfile.status.SUSPENDED');
      case 'ARCHIVED':
        return this.i18n.t('settings.brandProfile.status.ARCHIVED');
      default:
        return status;
    }
  }

  // ------------------------------------------------------------- rename

  protected startEditingName(): void {
    const current = this.brand();
    this.draftDisplayName.set(current?.displayName ?? '');
    this.nameError.set(null);
    this.editingName.set(true);
  }

  protected async saveName(): Promise<void> {
    const scope = this.location.scope();
    const current = this.brand();
    if (!scope || !current || this.nameSaving()) {
      return;
    }
    const displayName = this.draftDisplayName().trim();
    if (!displayName) {
      this.nameError.set(this.i18n.t('settings.brandProfile.error.nameRequired'));
      return;
    }
    this.nameSaving.set(true);
    this.nameError.set(null);
    try {
      const updated = await this.api.reviseBrand(
        scope,
        { code: current.code, slug: current.slug, displayName },
        current.version,
      );
      this.brand.set(updated);
      this.editingName.set(false);
    } catch (error) {
      this.nameError.set(this.describe(error));
    } finally {
      this.nameSaving.set(false);
    }
  }

  // ------------------------------------------------------------- profile

  protected startEditingProfile(): void {
    const current = this.brand();
    this.draftContactPhone.set(current?.contactPhone ?? '');
    this.draftTelegramHandle.set(current?.telegramHandle ?? '');
    this.draftLogoAssetId.set(current?.logoAssetId ?? '');
    this.draftBannerAssetId.set(current?.bannerAssetId ?? '');
    this.draftLocales.set(
      KNOWN_LOCALES.map((locale) => {
        const existing = current?.locales.find((entry) => entry.locale === locale);
        return {
          locale,
          included: existing !== undefined,
          description: existing?.description ?? '',
          isDefault: existing?.isDefault ?? false,
        };
      }),
    );
    this.profileError.set(null);
    this.editingProfile.set(true);
  }

  protected toggleLocaleIncluded(locale: BrandLocaleCode): void {
    this.draftLocales.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, included: !row.included } : row)),
    );
  }

  protected setLocaleDefault(locale: BrandLocaleCode): void {
    this.draftLocales.update((rows) =>
      rows.map((row) => ({ ...row, isDefault: row.locale === locale })),
    );
  }

  protected setLocaleDescription(locale: BrandLocaleCode, description: string): void {
    this.draftLocales.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, description } : row)),
    );
  }

  protected async saveProfile(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.profileSaving()) {
      return;
    }
    this.profileSaving.set(true);
    this.profileError.set(null);
    try {
      const included = this.draftLocales().filter((row) => row.included);
      // A supported set with no chosen default cannot render a storefront's
      // first paint (BrandProfile's own invariant refuses it) — the first
      // included language is the default rather than surfacing that as an
      // error the operator has no obvious next step for.
      const hasDefault = included.some((row) => row.isDefault);
      const locales = included.map((row, index) => ({
        locale: row.locale,
        description: row.description.trim() || undefined,
        isDefault: hasDefault ? row.isDefault : index === 0,
      }));

      const request: UpdateBrandProfileRequest = {
        contactPhone: this.draftContactPhone().trim() || undefined,
        telegramHandle: this.draftTelegramHandle().trim() || undefined,
        logoAssetId: this.draftLogoAssetId().trim() || undefined,
        bannerAssetId: this.draftBannerAssetId().trim() || undefined,
        locales,
      };
      const updated = await this.api.updateProfile(scope, request);
      this.brand.set(updated);
      this.editingProfile.set(false);
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      this.profileSaving.set(false);
    }
  }

  // --------------------------------------------------------------- load

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.brand.set(await this.api.getBrand(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
