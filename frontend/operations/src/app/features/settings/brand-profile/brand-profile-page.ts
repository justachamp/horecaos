import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { BrandProfileApi, BrandView } from './brand-profile-api';

/**
 * 10.1 Brand profile — `docs/operations-spec/settings.md` §10.1.
 *
 * **Read-only, on purpose.** The spec's field table lists a dozen fields;
 * `OperationsBrandController` (wave 26) reads four of them —
 * `displayName`, `code`, `slug`, `status` — because nothing in the platform
 * can write a brand's profile after it is created (`TenantControlPlaneService`
 * has `createBrand`/`activateBrand` and no `renameBrand`), and the rest —
 * logo, banner, description, phone, Telegram handle, and the tenant-level
 * legal name/currency/timezone the spec shows read-only alongside them — have
 * no backing column at all (ADR 0002, ADR 0010). Rather than render inputs
 * that cannot save, or invent a wider read across `TenantControlPlaneService`
 * this wave did not need, the screen shows what is real and names what is
 * not, the same "omit, do not disable" rule the app's not-built route follows
 * at the section level.
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

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly brand = signal<BrandView | null>(null);

  constructor() {
    void this.load();
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
}
