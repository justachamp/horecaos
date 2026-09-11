import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
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
 * Regions and their geocoder bounding boxes — operations §3.6b (ADR 0101).
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

  protected readonly code = signal('');
  protected readonly nameRu = signal('');
  protected readonly nameUz = signal('');
  protected readonly nameEn = signal('');
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
      this.regions.set(await this.api.list(scope.tenantId));
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

  protected statusKey(status: string): MessageKey {
    return STATUS_KEYS[status] ?? 'delivery.regions.status.ACTIVE';
  }

  protected openCreateForm(): void {
    this.editing.set(null);
    this.code.set('');
    this.nameRu.set('');
    this.nameUz.set('');
    this.nameEn.set('');
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
    this.nameRu.set(region.displayNameRu);
    this.nameUz.set(region.displayNameUz);
    this.nameEn.set(region.displayNameEn);
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
      this.nameRu().trim().length > 0 &&
      this.nameUz().trim().length > 0 &&
      this.nameEn().trim().length > 0
    );
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    const request: RegionGeographyRequest = {
      code: this.code().trim().toUpperCase(),
      displayNameRu: this.nameRu().trim(),
      displayNameUz: this.nameUz().trim(),
      displayNameEn: this.nameEn().trim(),
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
        await this.api.update(scope.tenantId, existing.regionId, request);
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
