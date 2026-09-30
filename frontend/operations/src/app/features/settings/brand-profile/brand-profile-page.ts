import {
  ChangeDetectionStrategy,
  Component,
  WritableSignal,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { formatMoney } from '../../../core/format/money';
import { PhonePipe } from '../../../core/format/phone.pipe';
import { formatPhone } from '../../../core/format/phone';
import {
  MONEY_GROUPINGS,
  MONEY_SYMBOL_PLACEMENTS,
  MoneyGrouping,
  MoneySymbolPlacement,
  RegionalFormats,
  normalizeRegionalFormats,
} from '../../../core/format/regional-format';
import { RegionalFormatSync } from '../../../core/format/regional-format-sync';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { LocationsApi } from '../locations/locations-api';
import { MediaUploader, mediaUploaderRejectionMessageKey } from '../../../shared/ui/media-uploader';
import { MediaApi } from '../../catalog/media-api';
import { describeApiError } from '../../orders/order-errors';
import {
  BrandLocaleCode,
  BrandProfileApi,
  BrandView,
  TenantMarketView,
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

/** The phone patterns the format picker offers ahead of a custom one; `#` is one digit. */
const PHONE_PATTERN_PRESETS: readonly string[] = [
  '+### ## ### ## ##',
  '+### (##) ###-##-##',
  '+###-##-###-##-##',
];

/** The select's value for "the operator will type a pattern", and for "show a number as it arrives". */
const PHONE_CUSTOM = '__custom__';
const PHONE_AS_RECEIVED = '';

/** What the format card's preview writes: a whole som total, and a Tashkent mobile number. */
const PREVIEW_AMOUNT_MINOR = 146_000;
const PREVIEW_PHONE = '+998901234567';

/** `BrandRegionalFormats`' own rule, mirrored: seven to fifteen `#` slots and only `# + ( ) - .` and spaces. */
function isValidPhonePattern(pattern: string): boolean {
  if (!/^[+#() .-]{1,32}$/.test(pattern)) {
    return false;
  }
  const slots = pattern.split('#').length - 1;
  return slots >= 7 && slots <= 15;
}

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
 * logo, banner and the 10.12 supported-locale set — goes through the new
 * `.../profile` endpoint and carries no version of its own.
 *
 * Row `10.1`/`X.12`: logo and banner go through the real `q-media-uploader`
 * (aspect-ratio crop, size caps) product photos already use, rather than an
 * operator pasting a raw asset UUID. The write contract this screen has
 * always had is unchanged — `updateProfile` still carries `logoAssetId`/
 * `bannerAssetId` as strings — only how that id is obtained moved from a
 * text field to an upload. Both slots crop to `1:1` first (a square brand
 * mark and a wide banner are different shapes, but `q-media-uploader`'s own
 * ratio picker is how a banner reaches `3:1` without a second component).
 *
 * The language editor is a fixed three-row grid over `KNOWN_LOCALES` rather
 * than a free-text list: the console has an editor for exactly ru/uz-Latn/en
 * today, so offering a fourth would record a choice nothing can render.
 *
 * **Formats (row `10.12`).** A third, separate write: where the currency unit
 * sits on a total, how thousands are grouped and how a phone number is
 * written, saved through `.../regional-formats` and applied to every formatter
 * in the console at once (`RegionalFormatSync`). The card's preview writes a
 * sample total and number through the same `formatMoney` and `formatPhone`
 * the rest of the console uses, from the draft, before anything is saved. The
 * timezone is shown beside them but read-only: it is the tenant's and each
 * branch's own, not a brand's.
 *
 * **Country/currency/timezone, read-only** (row `10.1`'s own named gap,
 * closed this wave): `TenantProfileController.tenantProfile`, a third read
 * beside {@link BrandProfileApi.getBrand} — these are tenant facts
 * (`tenant.tenants`), not brand ones, and nothing on this brand-scoped
 * screen writes them; a change of market goes through the ADR 0090
 * residency board, a `PLATFORM_ADMIN` act with its own second-signature
 * flow, not this screen.
 */
@Component({
  selector: 'q-brand-profile-page',
  imports: [TPipe, PhonePipe, MediaUploader],
  templateUrl: './brand-profile-page.html',
  styleUrl: './brand-profile-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandProfilePage {
  private readonly api = inject(BrandProfileApi);
  private readonly mediaApi = inject(MediaApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly regionalSync = inject(RegionalFormatSync);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly knownLocales = KNOWN_LOCALES;
  protected readonly moneySymbolPlacements = MONEY_SYMBOL_PLACEMENTS;
  protected readonly moneyGroupings = MONEY_GROUPINGS;
  protected readonly phonePatternPresets = PHONE_PATTERN_PRESETS;
  protected readonly phoneCustom = PHONE_CUSTOM;
  protected readonly phoneAsReceived = PHONE_AS_RECEIVED;
  protected readonly previewPhone = PREVIEW_PHONE;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly brand = signal<BrandView | null>(null);
  /** Row 10.1 — the tenant's own country/currency/timezone, read-only; null while it has not loaded yet (a best-effort read, see {@link load}). */
  protected readonly tenantMarket = signal<TenantMarketView | null>(null);

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

  // ------------------------------------------- 10.12: regional display formats
  protected readonly editingFormats = signal(false);
  protected readonly draftPlacement = signal<MoneySymbolPlacement>('AFTER');
  protected readonly draftGrouping = signal<MoneyGrouping>('SPACE');
  /** The phone select's value: `''` as it arrives, one of {@link PHONE_PATTERN_PRESETS}, or {@link PHONE_CUSTOM}. */
  protected readonly draftPhoneChoice = signal(PHONE_AS_RECEIVED);
  protected readonly draftCustomPattern = signal('');
  protected readonly formatsSaving = signal(false);
  protected readonly formatsError = signal<string | null>(null);
  /** Every branch's own timezone, read-only; best effort, empty until (or unless) the read lands. */
  protected readonly branchTimezones = signal<readonly { name: string; timezone: string }[]>([]);

  /** The pattern the draft currently holds, `null` for "as it arrives". */
  protected readonly draftPhonePattern = computed<string | null>(() => {
    const choice = this.draftPhoneChoice();
    if (choice === PHONE_AS_RECEIVED) {
      return null;
    }
    const pattern = choice === PHONE_CUSTOM ? this.draftCustomPattern().trim() : choice;
    return pattern === '' ? null : pattern;
  });

  /** The draft as the formatters would read it — what the preview writes. */
  protected readonly draftFormats = computed<RegionalFormats>(() => ({
    moneySymbolPlacement: this.draftPlacement(),
    moneyGrouping: this.draftGrouping(),
    phoneDisplayPattern: this.draftPhonePattern(),
  }));

  /** The brand's stored formats, or the defaults for a platform that sends none. */
  protected readonly storedFormats = computed<RegionalFormats>(() =>
    normalizeRegionalFormats(this.brand()?.regionalFormats),
  );

  /** The tenant's currency, or `UZS` while (or unless) it has not loaded — the preview's only need of it. */
  private readonly previewCurrency = computed(() => this.tenantMarket()?.defaultCurrency ?? 'UZS');

  /** `MediaAssetService.downloadUrl` thumbnails for the current logo/banner, best-effort like `product-editor-page`'s own photo grid. */
  protected readonly logoPreviewUrl = signal<string | null>(null);
  protected readonly bannerPreviewUrl = signal<string | null>(null);
  protected readonly uploadingLogo = signal(false);
  protected readonly uploadingBanner = signal(false);

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
      void this.loadMediaPreviews(updated);
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      this.profileSaving.set(false);
    }
  }

  // ----------------------------------------------- regional display formats

  protected startEditingFormats(): void {
    const stored = this.storedFormats();
    this.draftPlacement.set(stored.moneySymbolPlacement);
    this.draftGrouping.set(stored.moneyGrouping);
    const pattern = stored.phoneDisplayPattern;
    if (pattern === null) {
      this.draftPhoneChoice.set(PHONE_AS_RECEIVED);
      this.draftCustomPattern.set('');
    } else if (PHONE_PATTERN_PRESETS.includes(pattern)) {
      this.draftPhoneChoice.set(pattern);
      this.draftCustomPattern.set('');
    } else {
      this.draftPhoneChoice.set(PHONE_CUSTOM);
      this.draftCustomPattern.set(pattern);
    }
    this.formatsError.set(null);
    this.editingFormats.set(true);
  }

  protected cancelEditingFormats(): void {
    this.editingFormats.set(false);
    this.formatsError.set(null);
  }

  protected setPlacement(value: string): void {
    const placement = MONEY_SYMBOL_PLACEMENTS.find((candidate) => candidate === value);
    if (placement) {
      this.draftPlacement.set(placement);
    }
  }

  protected setGrouping(value: string): void {
    const grouping = MONEY_GROUPINGS.find((candidate) => candidate === value);
    if (grouping) {
      this.draftGrouping.set(grouping);
    }
  }

  protected async saveFormats(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.formatsSaving()) {
      return;
    }
    const pattern = this.draftPhonePattern();
    if (pattern !== null && !isValidPhonePattern(pattern)) {
      this.formatsError.set(this.i18n.t('settings.brandProfile.formats.error.pattern'));
      return;
    }
    this.formatsSaving.set(true);
    this.formatsError.set(null);
    try {
      const updated = await this.api.reviseRegionalFormats(scope, {
        moneySymbolPlacement: this.draftPlacement(),
        moneyGrouping: this.draftGrouping(),
        phoneDisplayPattern: pattern ?? undefined,
      });
      this.brand.set(updated);
      // Every amount and phone in the console reads the new formats from here on, not just this card.
      this.regionalSync.applySaved(scope, updated.regionalFormats);
      this.editingFormats.set(false);
    } catch (error) {
      this.formatsError.set(this.describe(error));
    } finally {
      this.formatsSaving.set(false);
    }
  }

  protected placementLabel(placement: MoneySymbolPlacement): string {
    return placement === 'BEFORE'
      ? this.i18n.t('settings.brandProfile.formats.placement.BEFORE')
      : this.i18n.t('settings.brandProfile.formats.placement.AFTER');
  }

  protected groupingLabel(grouping: MoneyGrouping): string {
    switch (grouping) {
      case 'SPACE':
        return this.i18n.t('settings.brandProfile.formats.grouping.SPACE');
      case 'COMMA':
        return this.i18n.t('settings.brandProfile.formats.grouping.COMMA');
      case 'DOT':
        return this.i18n.t('settings.brandProfile.formats.grouping.DOT');
      case 'NONE':
        return this.i18n.t('settings.brandProfile.formats.grouping.NONE');
    }
  }

  /** A whole-som total written the way `formats` would write it — the card's live preview. */
  protected previewTotal(formats: RegionalFormats): string {
    const money = { amountMinor: PREVIEW_AMOUNT_MINOR, currency: this.previewCurrency() };
    try {
      return formatMoney(money, this.i18n.locale(), { withUnit: true, formats });
    } catch {
      // A currency the console has no exponent for: preview in the platform's own.
      return formatMoney({ ...money, currency: 'UZS' }, this.i18n.locale(), {
        withUnit: true,
        formats,
      });
    }
  }

  /** The sample number written the way `formats` would write it. */
  protected previewPhoneText(formats: RegionalFormats): string {
    return formatPhone(PREVIEW_PHONE, formats.phoneDisplayPattern);
  }

  /** The pattern as the read-only view names it: the pattern itself, or "as stored". */
  protected phonePatternText(formats: RegionalFormats): string {
    return (
      formats.phoneDisplayPattern ?? this.i18n.t('settings.brandProfile.formats.phone.asReceived')
    );
  }

  // --------------------------------------------------------- logo/banner

  /**
   * `q-media-uploader` already cropped the file client-side; this is the
   * same request→PUT-bytes→finalize round trip `product-editor-page`'s
   * `uploadPhoto` drives, just against `BRAND` rather than the product's own
   * media relations — a brand mark has no `catalog.media_relations` row to
   * attach, so the finalized asset id is the whole write.
   */
  protected async uploadLogo(file: File): Promise<void> {
    await this.uploadBrandMedia(
      file,
      this.draftLogoAssetId,
      this.logoPreviewUrl,
      this.uploadingLogo,
    );
  }

  protected async uploadBanner(file: File): Promise<void> {
    await this.uploadBrandMedia(
      file,
      this.draftBannerAssetId,
      this.bannerPreviewUrl,
      this.uploadingBanner,
    );
  }

  private async uploadBrandMedia(
    file: File,
    assetId: WritableSignal<string>,
    previewUrl: WritableSignal<string | null>,
    uploading: WritableSignal<boolean>,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope || uploading()) {
      return;
    }
    uploading.set(true);
    this.profileError.set(null);
    try {
      const asset = await firstValueFrom(
        this.mediaApi.upload(scope.tenantId, 'BRAND', scope.brandId, 'PUBLIC', file),
      );
      assetId.set(asset.assetId);
      // The bytes are already on the client — an instant preview from them
      // beats waiting on a thumbnail derivative that may not have rendered
      // yet (`MediaApi.downloadUrl`'s own trap, product-editor-page's doc).
      const previous = previewUrl();
      if (previous) {
        URL.revokeObjectURL(previous);
      }
      previewUrl.set(URL.createObjectURL(file));
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      uploading.set(false);
    }
  }

  /** `q-media-uploader` rejected the file client-side — before any network call. */
  protected onMediaRejected(reason: string): void {
    this.profileError.set(this.i18n.t(mediaUploaderRejectionMessageKey(reason)));
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
      const brand = await this.api.getBrand(scope);
      this.brand.set(brand);
      void this.loadMediaPreviews(brand);
      void this.loadTenantMarket(scope.tenantId);
      void this.loadBranchTimezones(scope);
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

  /**
   * Thumbnails for the current logo/banner — the same "show the current
   * image" gap row `10.1` names, `MediaAssetService.downloadUrl` for a
   * rendition. Best-effort like `product-editor-page`'s own photo grid: an
   * asset whose thumbnail has not rendered yet, or that this brand no
   * longer owns, leaves that slot on its empty state rather than blocking
   * the rest of the screen.
   */
  private async loadMediaPreviews(brand: BrandView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const [logo, banner] = await Promise.all([
      this.downloadUrlOrNull(scope.tenantId, brand.logoAssetId),
      this.downloadUrlOrNull(scope.tenantId, brand.bannerAssetId),
    ]);
    this.logoPreviewUrl.set(logo);
    this.bannerPreviewUrl.set(banner);
  }

  /**
   * Row 10.1 — the tenant's own country/currency/timezone, read-only.
   * Best-effort like {@link loadMediaPreviews}: an operator who cannot read
   * it (a stale grant edge case, since `BRAND_READ` at `TENANT` scope is
   * ordinarily implied by holding this screen at all) still sees the rest
   * of the brand profile rather than the whole page failing over one
   * secondary read.
   */
  private async loadTenantMarket(tenantId: string): Promise<void> {
    try {
      this.tenantMarket.set(await this.api.tenantProfile(tenantId));
    } catch {
      this.tenantMarket.set(null);
    }
  }

  /**
   * Row 10.12 — each branch's own timezone, read-only beside the tenant's default. Best effort,
   * like {@link loadTenantMarket}: an operator who cannot list the branches still gets the card.
   */
  private async loadBranchTimezones(scope: LocationScope): Promise<void> {
    try {
      const locations = await this.locationsApi.list(scope);
      this.branchTimezones.set(
        locations.map((location) => ({ name: location.displayName, timezone: location.timezone })),
      );
    } catch {
      this.branchTimezones.set([]);
    }
  }

  private async downloadUrlOrNull(
    tenantId: string,
    assetId: string | null,
  ): Promise<string | null> {
    if (!assetId) {
      return null;
    }
    try {
      return await firstValueFrom(this.mediaApi.downloadUrl(tenantId, assetId, 'THUMBNAIL'));
    } catch {
      return null;
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
