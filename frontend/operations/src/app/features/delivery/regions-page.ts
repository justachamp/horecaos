import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import {
  LabelsByLocale,
  PLATFORM_LOCALE_SET,
  labelDrafts,
  labelsToSend,
  localeDisplayName,
  platformColumns,
} from '../../core/i18n/locale-labels';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { localisedName } from './localised-name';
import { RegionGeographyRequest, RegionResponse, RegionsApi } from './regions-api';

/** Spelled out rather than concatenated, so a missing translation stays a compile error. */
const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  ACTIVE: 'delivery.regions.status.ACTIVE',
  ARCHIVED: 'delivery.regions.status.ARCHIVED',
};

/**
 * Regions and their geocoder bounding boxes — operations §3.6b (ADR 0104).
 *
 * **A row that existed at no layer.** `fulfillment.regions` has been in the
 * schema since V0025 and had no store, no service, no controller and no
 * screen; the only writer in the repository was test SQL. So a fresh
 * production database had no region at all, which is worse than it sounds: the
 * box is what `ServiceZoneService.activate` checks a polygon against, and with
 * nothing to check against the guard that catches a transposed latitude passed
 * for every zone anyone drew. Onboarding a merchant in a second city needed a
 * developer.
 *
 * **Typed numbers, not a dragged rectangle.** No `BoundingBoxEditor` exists —
 * ADR 0015 still owes the map/geocoder provider decision that ADR 0037
 * inherited — so the four corners are four inputs. The database's own
 * `ck_region_bbox_oriented` and `ck_region_centre_within_bbox` are the whole
 * of the defence against a mis-typed corner, and `RegionService` returns them
 * as sentences an operator can act on, listed here rather than collapsed into
 * one line.
 *
 * **Row 10.12 — the name is per locale, not a fixed ru/uz/en triple.** A
 * region belongs to the tenant, not to a brand, so its name is edited in the
 * *union of the tenant's brands' supported languages*, default first (the
 * tenant's first brand's), read from `GET .../regions/locale-set`. The
 * default language is the one name a region must have; every other offered
 * language is optional. **A language the tenant does not offer is never
 * touched by a rewrite**: `displayNames` carries only the offered, filled-in
 * names ({@link labelsToSend}) so the server keeps every other one beyond the
 * platform triple, and the three platform fields — which the OpenAPI contract
 * keeps required — go back for a language the tenant does not offer with the
 * name the region already has, unchanged ({@link platformColumns}).
 *
 * **Platform regions are visible and read-only.** V0025's nullable tenant —
 * "Tashkent is not one tenant's fact" — means a tenant may reference the
 * platform's regions and may not edit them. The list says which is which
 * rather than offering an edit button that always fails.
 */
@Component({
  selector: 'q-regions-page',
  imports: [TPipe],
  templateUrl: './regions-page.html',
  styleUrl: './regions-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionsPage implements OnInit {
  private readonly api = inject(RegionsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly regions = signal<readonly RegionResponse[]>([]);

  protected readonly editing = signal<RegionResponse | null>(null);
  protected readonly showForm = signal(false);
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly problems = signal<readonly string[]>([]);

  /** The languages this editor offers (row 10.12): the platform triple until the tenant's set loads. */
  protected readonly localeSet = signal(PLATFORM_LOCALE_SET);
  protected readonly locales = computed(() => this.localeSet().locales);
  protected readonly defaultLocale = computed(() => this.localeSet().defaultLocale);

  protected readonly code = signal('');
  protected readonly names = signal<LabelsByLocale>({});
  protected readonly centreLat = signal(41.311081);
  protected readonly centreLon = signal(69.240562);
  protected readonly swLat = signal(40.5);
  protected readonly swLon = signal(68.5);
  protected readonly neLat = signal(42.0);
  protected readonly neLon = signal(70.0);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

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
      const [regions, localeSet] = await Promise.all([
        this.api.list(scope.tenantId),
        // The set only decides which languages the form offers; a tenant whose
        // set cannot be read still gets a working editor on the platform triple.
        this.api.localeSet(scope.tenantId).catch(() => PLATFORM_LOCALE_SET),
      ]);
      this.regions.set(regions);
      this.localeSet.set(localeSet);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** The region's name in the operator's own locale. */
  protected regionName(region: RegionResponse): string {
    return localisedName(this.i18n.locale(), region);
  }

  protected localeName(locale: string): string {
    return localeDisplayName(this.i18n, locale);
  }

  protected isDefault(locale: string): boolean {
    return locale === this.defaultLocale();
  }

  protected setName(locale: string, value: string): void {
    this.names.update((names) => ({ ...names, [locale]: value }));
  }

  /** Languages the region is named in that this editor does not offer — kept, not shown. */
  protected hiddenLocales(region: RegionResponse | null): readonly string[] {
    const offered = new Set(this.locales());
    return Object.keys(region?.displayNames ?? {}).filter((locale) => !offered.has(locale));
  }

  protected statusKey(status: string): MessageKey {
    return STATUS_KEYS[status] ?? 'delivery.regions.status.ACTIVE';
  }

  protected openCreateForm(): void {
    this.editing.set(null);
    this.code.set('');
    this.names.set({});
    this.centreLat.set(41.311081);
    this.centreLon.set(69.240562);
    this.swLat.set(40.5);
    this.swLon.set(68.5);
    this.neLat.set(42.0);
    this.neLon.set(70.0);
    this.formError.set(null);
    this.problems.set([]);
    this.showForm.set(true);
  }

  protected openEditForm(region: RegionResponse): void {
    this.editing.set(region);
    this.code.set(region.code);
    this.names.set(labelDrafts(this.locales(), region.displayNames ?? triple(region)));
    this.centreLat.set(region.centreLat);
    this.centreLon.set(region.centreLon);
    this.swLat.set(region.bboxSwLat);
    this.swLon.set(region.bboxSwLon);
    this.neLat.set(region.bboxNeLat);
    this.neLon.set(region.bboxNeLon);
    this.formError.set(null);
    this.problems.set([]);
    this.showForm.set(true);
  }

  protected closeForm(): void {
    this.showForm.set(false);
  }

  protected canSubmit(): boolean {
    return (
      !this.submitting() &&
      this.code().trim().length > 0 &&
      // The tenant's default language is the one name a region must have.
      (this.names()[this.defaultLocale()] ?? '').trim().length > 0
    );
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    const existingRegion = this.editing();
    const columns = platformColumns(
      this.locales(),
      this.names(),
      this.defaultLocale(),
      existingRegion
        ? {
            ru: existingRegion.displayNameRu,
            'uz-Latn': existingRegion.displayNameUz,
            en: existingRegion.displayNameEn,
          }
        : null,
    );
    const request: RegionGeographyRequest = {
      code: this.code().trim().toUpperCase(),
      // The platform triple stays required by the contract: a platform language
      // the tenant does not offer goes back as the region already has it
      // (unchanged), and `displayNames` names only the offered, filled-in
      // languages, so one beyond the triple that is not offered keeps its name.
      displayNameRu: columns.ru,
      displayNameUz: columns['uz-Latn'],
      displayNameEn: columns.en,
      displayNames: labelsToSend(this.locales(), this.names()),
      centreLat: this.centreLat(),
      centreLon: this.centreLon(),
      bboxSwLat: this.swLat(),
      bboxSwLon: this.swLon(),
      bboxNeLat: this.neLat(),
      bboxNeLon: this.neLon(),
    };
    this.submitting.set(true);
    this.formError.set(null);
    this.problems.set([]);
    try {
      const existing = this.editing();
      if (existing) {
        // The version this form was opened with (openEditForm); a rewrite
        // refused as STALE_VERSION means somebody else's edit landed first.
        await this.api.update(scope.tenantId, existing.regionId, {
          ...request,
          expectedVersion: existing.version,
        });
      } else {
        await this.api.create(scope.tenantId, request);
      }
      this.showForm.set(false);
      await this.load();
    } catch (error) {
      this.formError.set(this.describe(error));
      this.problems.set(problemsOf(error));
    } finally {
      this.submitting.set(false);
    }
  }

  protected async archive(region: RegionResponse): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.loadError.set(null);
    try {
      await this.api.archive(scope.tenantId, region.regionId);
      await this.load();
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** The `problems` array the refusal carries, so every mis-typed corner is named at once. */
function problemsOf(error: unknown): readonly string[] {
  if (!(error instanceof ApiError)) {
    return [];
  }
  const problems = error.problem?.['problems'];
  return Array.isArray(problems) ? problems.map((entry) => String(entry)) : [];
}

/** A region's platform-triple name columns keyed by locale — for a response that predates `displayNames`. */
function triple(region: RegionResponse): LabelsByLocale {
  return { ru: region.displayNameRu, 'uz-Latn': region.displayNameUz, en: region.displayNameEn };
}
