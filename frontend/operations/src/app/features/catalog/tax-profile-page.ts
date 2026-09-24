import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { PercentInput } from '../../shared/ui/percent-input';
import { describeApiError } from '../orders/order-errors';
import { TaxProfile } from './catalog-domain';
import { PricingApi } from './pricing-api';

type TaxMode = 'INCLUSIVE' | 'EXCLUSIVE';

/**
 * IA 4.8a — the VAT / tax-profile screen.
 *
 * `PUT .../tax-profiles/{jurisdictionCode}` was real
 * (`PriceAuthoringService.setTaxProfile`, ADR 0018) but reachable from no
 * screen until this wave. Without a profile every cart in the brand refuses
 * with `NO_TAX_PROFILE`.
 *
 * **No longer write-only.** `GET /tax-profiles` (every jurisdiction in
 * force) and `GET /tax-profiles/{jurisdictionCode}` now back this screen: the
 * jurisdiction list renders every profile the brand already has, and
 * choosing one — or typing a jurisdiction code by hand — loads its current
 * mode and rate into the form before the operator changes anything, so a
 * write can no longer overwrite a rate nobody on this screen has seen.
 */
@Component({
  selector: 'q-tax-profile-page',
  imports: [TPipe, PercentInput],
  templateUrl: './tax-profile-page.html',
  styleUrl: './tax-profile-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaxProfilePage implements OnInit {
  private readonly api = inject(PricingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly denied = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal<TaxProfile | null>(null);

  protected readonly existingProfiles = signal<readonly TaxProfile[]>([]);
  protected readonly listLoading = signal(false);
  protected readonly listError = signal<string | null>(null);

  protected readonly jurisdictionCode = signal('UZ');
  protected readonly mode = signal<TaxMode>('INCLUSIVE');
  protected readonly rateBasisPoints = signal(1200);

  /** The in-force profile for the jurisdiction currently typed in, or `undefined` before the lookup resolves. */
  protected readonly inForce = signal<TaxProfile | null | undefined>(undefined);
  protected readonly inForceLoading = signal(false);

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    this.denied.set(!scope);
    if (!scope) {
      return;
    }
    this.listLoading.set(true);
    try {
      this.existingProfiles.set(await firstValueFrom(this.api.taxProfiles(scope)));
    } catch (error) {
      this.listError.set(this.describe(error));
    } finally {
      this.listLoading.set(false);
    }
    await this.loadInForce();
  }

  protected setMode(mode: TaxMode): void {
    this.mode.set(mode);
  }

  /** Loads what is currently in force for the typed jurisdiction, shown above the edit form. */
  protected async loadInForce(): Promise<void> {
    const scope = this.brand.scope();
    const code = this.jurisdictionCode().trim().toUpperCase();
    if (!scope || code.length === 0) {
      this.inForce.set(undefined);
      return;
    }
    this.inForceLoading.set(true);
    try {
      const profile = await firstValueFrom(this.api.taxProfile(scope, code));
      this.inForce.set(profile);
      if (profile) {
        this.mode.set(profile.mode);
        this.rateBasisPoints.set(profile.rateBasisPoints);
      }
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.inForceLoading.set(false);
    }
  }

  protected selectJurisdiction(code: string): void {
    this.jurisdictionCode.set(code);
    void this.loadInForce();
  }

  protected canSubmit(): boolean {
    return (
      !this.submitting() &&
      this.jurisdictionCode().trim().length > 0 &&
      this.jurisdictionCode().trim().length <= 16 &&
      this.rateBasisPoints() >= 0 &&
      this.rateBasisPoints() < 10_000
    );
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    try {
      const profile = await firstValueFrom(
        this.api.setTaxProfile(scope, this.jurisdictionCode().trim().toUpperCase(), {
          mode: this.mode(),
          rateBasisPoints: this.rateBasisPoints(),
        }),
      );
      this.saved.set(profile);
      this.inForce.set(profile);
      this.existingProfiles.set(
        [...this.existingProfiles().filter((p) => p.jurisdictionCode !== profile.jurisdictionCode), profile].sort(
          (a, b) => a.jurisdictionCode.localeCompare(b.jurisdictionCode),
        ),
      );
    } catch (error) {
      this.error.set(this.describe(error));
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
