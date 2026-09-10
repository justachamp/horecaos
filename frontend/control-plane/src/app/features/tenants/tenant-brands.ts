import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { BrandView, LocationView, TenantsApi } from './tenants-api';

interface BrandRow {
  readonly brand: BrandView;
  readonly locations: readonly LocationView[];
  readonly locationsLoaded: boolean;
}

type Editing =
  | { readonly kind: 'brand'; readonly brand: BrandView }
  | { readonly kind: 'location'; readonly location: LocationView };

/**
 * The reasons the platform gives for refusing a delete, each with its own
 * sentence. A map rather than a key built from the reason, so a reason with no
 * translation is a compile error, not a raw key on screen.
 */
const DELETE_REASON_MESSAGES = {
  NOT_DRAFT: 'tenantBrands.delete.reason.NOT_DRAFT',
  HAS_LOCATIONS: 'tenantBrands.delete.reason.HAS_LOCATIONS',
  HAS_ACCESS_GRANTS: 'tenantBrands.delete.reason.HAS_ACCESS_GRANTS',
  STILL_REFERENCED: 'tenantBrands.delete.reason.STILL_REFERENCED',
} as const satisfies Record<string, MessageKey>;
type DeleteReason = keyof typeof DELETE_REASON_MESSAGES;

/**
 * IA 2.3 Brands & locations -- the ownership tree, and provisioning on the
 * tenant's behalf.
 *
 * Owns exactly what the backend owns and no more: brand/location hierarchy
 * and activation. Per-brand currency/locale/fiscal-regime and business-type
 * assignment (both named in the IA row) are not modeled anywhere in the
 * tenancy schema -- currency and timezone are set once, tenant-wide, at
 * creation, and there is no business-type column on either `Tenant` or
 * `Brand`. This screen does not invent fields the API cannot save.
 *
 * Correcting and deleting follow the platform's rules rather than restating
 * them: the name can always change, the code, slug and a location's timezone
 * only while the unit is DRAFT, and only a DRAFT that nothing refers to can be
 * deleted. The screen greys out what the server would refuse, and when it
 * refuses anyway -- someone else's change, a record elsewhere still pointing
 * at the unit -- says why in the operator's language, from the refusal's
 * `reason` rather than its English `detail`.
 */
@Component({
  selector: 'app-tenant-brands',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-brands.html',
  styleUrl: './tenant-brands.css',
})
export class TenantBrands {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly rows = signal<readonly BrandRow[]>([]);
  protected readonly actionError = signal<string | null>(null);

  protected readonly creatingBrand = signal(false);
  protected readonly brandCode = signal('');
  protected readonly brandSlug = signal('');
  protected readonly brandName = signal('');
  protected readonly brandSubmitting = signal(false);

  protected readonly creatingLocationFor = signal<string | null>(null);
  protected readonly locationCode = signal('');
  protected readonly locationSlug = signal('');
  protected readonly locationName = signal('');
  protected readonly locationTimezone = signal('Asia/Tashkent');
  protected readonly locationSubmitting = signal(false);

  protected readonly editing = signal<Editing | null>(null);
  protected readonly editCode = signal('');
  protected readonly editSlug = signal('');
  protected readonly editName = signal('');
  protected readonly editTimezone = signal('');
  protected readonly editSubmitting = signal(false);

  /** The location whose address and pin are being edited. */
  protected readonly placeFor = signal<LocationView | null>(null);
  protected readonly placeAddress = signal('');
  protected readonly placeDistrict = signal('');
  protected readonly placeCity = signal('');
  protected readonly placeLandmark = signal('');
  protected readonly placePhone = signal('');
  protected readonly placeLatitude = signal('');
  protected readonly placeLongitude = signal('');
  protected readonly placeSubmitting = signal(false);
  protected readonly placeError = signal<string | null>(null);

  /** The brand or location whose Delete has been pressed once and awaits confirmation. */
  protected readonly confirmingDelete = signal<string | null>(null);
  protected readonly deleting = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const brands = await this.tenantsApi.getBrands(this.tenantId);
      this.rows.set(brands.map((brand) => ({ brand, locations: [], locationsLoaded: false })));
      await Promise.all(brands.map((brand) => this.loadLocations(brand.id)));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadLocations(brandId: string): Promise<void> {
    try {
      const locations = await this.tenantsApi.getLocations(this.tenantId, brandId);
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, locations, locationsLoaded: true } : row)),
      );
    } catch {
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, locationsLoaded: true } : row)),
      );
    }
  }

  protected openCreateBrand(): void {
    this.creatingBrand.set(true);
    this.actionError.set(null);
  }

  protected closeCreateBrand(): void {
    this.creatingBrand.set(false);
  }

  protected canSubmitBrand(): boolean {
    return (
      !this.brandSubmitting() &&
      this.brandCode().trim().length > 0 &&
      this.brandSlug().trim().length > 0 &&
      this.brandName().trim().length > 0
    );
  }

  protected async submitBrand(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitBrand()) {
      return;
    }
    this.brandSubmitting.set(true);
    this.actionError.set(null);
    try {
      const brand = await this.tenantsApi.createBrand(this.tenantId, {
        code: this.brandCode().trim(),
        slug: this.brandSlug().trim(),
        displayName: this.brandName().trim(),
      });
      this.rows.update((rows) => [...rows, { brand, locations: [], locationsLoaded: true }]);
      this.creatingBrand.set(false);
      this.brandCode.set('');
      this.brandSlug.set('');
      this.brandName.set('');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.brandSubmitting.set(false);
    }
  }

  protected async activateBrand(brandId: string): Promise<void> {
    this.actionError.set(null);
    try {
      const activated = await this.tenantsApi.activateBrand(this.tenantId, brandId);
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, brand: activated } : row)),
      );
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected openCreateLocation(brandId: string): void {
    this.creatingLocationFor.set(brandId);
    this.actionError.set(null);
  }

  protected closeCreateLocation(): void {
    this.creatingLocationFor.set(null);
  }

  protected canSubmitLocation(): boolean {
    return (
      !this.locationSubmitting() &&
      this.locationCode().trim().length > 0 &&
      this.locationSlug().trim().length > 0 &&
      this.locationName().trim().length > 0 &&
      this.locationTimezone().trim().length > 0
    );
  }

  protected async submitLocation(event: Event): Promise<void> {
    event.preventDefault();
    const brandId = this.creatingLocationFor();
    if (brandId === null || !this.canSubmitLocation()) {
      return;
    }
    this.locationSubmitting.set(true);
    this.actionError.set(null);
    try {
      const location = await this.tenantsApi.createLocation(this.tenantId, brandId, {
        code: this.locationCode().trim(),
        slug: this.locationSlug().trim(),
        displayName: this.locationName().trim(),
        timezone: this.locationTimezone().trim(),
      });
      this.rows.update((rows) =>
        rows.map((row) =>
          row.brand.id === brandId ? { ...row, locations: [...row.locations, location] } : row,
        ),
      );
      this.creatingLocationFor.set(null);
      this.locationCode.set('');
      this.locationSlug.set('');
      this.locationName.set('');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.locationSubmitting.set(false);
    }
  }

  protected async activateLocation(brandId: string, locationId: string): Promise<void> {
    this.actionError.set(null);
    try {
      const activated = await this.tenantsApi.activateLocation(this.tenantId, brandId, locationId);
      this.rows.update((rows) =>
        rows.map((row) =>
          row.brand.id === brandId
            ? {
                ...row,
                locations: row.locations.map((location) =>
                  location.id === locationId ? activated : location,
                ),
              }
            : row,
        ),
      );
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected openEditBrand(brand: BrandView): void {
    this.editing.set({ kind: 'brand', brand });
    this.editCode.set(brand.code);
    this.editSlug.set(brand.slug);
    this.editName.set(brand.displayName);
    this.editTimezone.set('');
    this.actionError.set(null);
    this.confirmingDelete.set(null);
  }

  protected openEditLocation(location: LocationView): void {
    this.editing.set({ kind: 'location', location });
    this.editCode.set(location.code);
    this.editSlug.set(location.slug);
    this.editName.set(location.displayName);
    this.editTimezone.set(location.timezone);
    this.actionError.set(null);
    this.confirmingDelete.set(null);
  }

  protected closeEdit(): void {
    this.editing.set(null);
  }

  /** Code, slug and timezone are fixed once the unit has left DRAFT; the name never is. */
  protected identityLocked(): boolean {
    const editing = this.editing();
    if (editing === null) {
      return false;
    }
    const status = editing.kind === 'brand' ? editing.brand.status : editing.location.status;
    return status !== 'DRAFT';
  }

  protected canSubmitEdit(): boolean {
    const editing = this.editing();
    return (
      editing !== null &&
      !this.editSubmitting() &&
      this.editCode().trim().length > 0 &&
      this.editSlug().trim().length > 0 &&
      this.editName().trim().length > 0 &&
      (editing.kind === 'brand' || this.editTimezone().trim().length > 0)
    );
  }

  protected async submitEdit(event: Event): Promise<void> {
    event.preventDefault();
    const editing = this.editing();
    if (editing === null || !this.canSubmitEdit()) {
      return;
    }
    this.editSubmitting.set(true);
    this.actionError.set(null);
    try {
      if (editing.kind === 'brand') {
        const revised = await this.tenantsApi.reviseBrand(this.tenantId, editing.brand, {
          code: this.editCode().trim(),
          slug: this.editSlug().trim(),
          displayName: this.editName().trim(),
        });
        this.rows.update((rows) =>
          rows.map((row) => (row.brand.id === revised.id ? { ...row, brand: revised } : row)),
        );
      } else {
        const revised = await this.tenantsApi.reviseLocation(this.tenantId, editing.location, {
          code: this.editCode().trim(),
          slug: this.editSlug().trim(),
          displayName: this.editName().trim(),
          timezone: this.editTimezone().trim(),
        });
        this.replaceLocation(revised.brandId, revised.id, revised);
      }
      this.editing.set(null);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  protected openPlace(location: LocationView): void {
    this.placeFor.set(location);
    this.placeAddress.set(location.addressLine ?? '');
    this.placeDistrict.set(location.district ?? '');
    this.placeCity.set(location.city ?? '');
    this.placeLandmark.set(location.landmark ?? '');
    this.placePhone.set(location.contactPhone ?? '');
    this.placeLatitude.set(location.latitude === null ? '' : String(location.latitude));
    this.placeLongitude.set(location.longitude === null ? '' : String(location.longitude));
    this.placeError.set(null);
    this.actionError.set(null);
    this.confirmingDelete.set(null);
  }

  protected closePlace(): void {
    this.placeFor.set(null);
  }

  /**
   * The same checks the server makes, so the common mistakes are named here in
   * the operator's language instead of coming back as a validation failure.
   */
  private placeProblem(): string | null {
    const latitude = this.placeLatitude().trim();
    const longitude = this.placeLongitude().trim();
    if ((latitude === '') !== (longitude === '')) {
      return this.i18n.t('tenantBrands.place.pairError');
    }
    if (latitude !== '') {
      const lat = Number(latitude);
      const lng = Number(longitude);
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
        return this.i18n.t('tenantBrands.place.rangeError');
      }
    }
    const phone = this.placePhone().trim();
    if (phone !== '' && !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
      return this.i18n.t('tenantBrands.place.phoneError');
    }
    return null;
  }

  protected async submitPlace(event: Event): Promise<void> {
    event.preventDefault();
    const location = this.placeFor();
    if (location === null || this.placeSubmitting()) {
      return;
    }
    const problem = this.placeProblem();
    if (problem !== null) {
      this.placeError.set(problem);
      return;
    }
    const orNull = (value: string): string | null => (value.trim() === '' ? null : value.trim());
    const latitude = orNull(this.placeLatitude());
    const longitude = orNull(this.placeLongitude());
    this.placeSubmitting.set(true);
    this.placeError.set(null);
    try {
      const described = await this.tenantsApi.describeLocation(this.tenantId, location, {
        addressLine: orNull(this.placeAddress()),
        district: orNull(this.placeDistrict()),
        city: orNull(this.placeCity()),
        landmark: orNull(this.placeLandmark()),
        contactPhone: orNull(this.placePhone()),
        latitude: latitude === null ? null : Number(latitude),
        longitude: longitude === null ? null : Number(longitude),
        // Entered here by platform staff, so a point is an operator's pin.
        ...(latitude === null ? {} : { coordinateSource: 'OPERATOR_PIN' as const }),
      });
      this.replaceLocation(described.brandId, described.id, described);
      this.placeFor.set(null);
    } catch (error) {
      this.placeError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.placeSubmitting.set(false);
    }
  }

  /** The address as one line for the table, or null when nothing is recorded. */
  protected addressSummary(location: LocationView): string | null {
    const parts = [location.addressLine, location.district, location.city].filter(
      (part): part is string => part !== null && part.trim() !== '',
    );
    return parts.length === 0 ? null : parts.join(', ');
  }

  protected askDelete(id: string): void {
    this.confirmingDelete.set(id);
    this.actionError.set(null);
  }

  protected cancelDelete(): void {
    this.confirmingDelete.set(null);
  }

  protected async deleteBrand(brand: BrandView): Promise<void> {
    this.deleting.set(true);
    this.actionError.set(null);
    try {
      await this.tenantsApi.deleteBrand(this.tenantId, brand);
      this.rows.update((rows) => rows.filter((row) => row.brand.id !== brand.id));
    } catch (error) {
      this.actionError.set(this.describeDeleteRefusal(error as ApiError));
    } finally {
      this.confirmingDelete.set(null);
      this.deleting.set(false);
    }
  }

  protected async deleteLocation(location: LocationView): Promise<void> {
    this.deleting.set(true);
    this.actionError.set(null);
    try {
      await this.tenantsApi.deleteLocation(this.tenantId, location);
      this.rows.update((rows) =>
        rows.map((row) =>
          row.brand.id === location.brandId
            ? { ...row, locations: row.locations.filter((candidate) => candidate.id !== location.id) }
            : row,
        ),
      );
    } catch (error) {
      this.actionError.set(this.describeDeleteRefusal(error as ApiError));
    } finally {
      this.confirmingDelete.set(null);
      this.deleting.set(false);
    }
  }

  /**
   * A refused delete names its reason as a problem property. Anything else --
   * a stale version, a lost connection -- gets the ordinary sentence for its code.
   */
  private describeDeleteRefusal(error: ApiError): string {
    const reason = error.problem['reason'];
    if (error.code !== 'RESOURCE_CONFLICT' || typeof reason !== 'string' || !(reason in DELETE_REASON_MESSAGES)) {
      return this.i18n.describe(error);
    }
    const sentence = this.i18n.t(DELETE_REASON_MESSAGES[reason as DeleteReason]);
    const referencedBy = error.problem['referencedBy'];
    return typeof referencedBy === 'string'
      ? `${sentence} ${this.i18n.t('tenantBrands.delete.referencedBy', { table: referencedBy })}`
      : sentence;
  }

  private replaceLocation(brandId: string, locationId: string, replacement: LocationView): void {
    this.rows.update((rows) =>
      rows.map((row) =>
        row.brand.id === brandId
          ? {
              ...row,
              locations: row.locations.map((location) => (location.id === locationId ? replacement : location)),
            }
          : row,
      ),
    );
  }
}
