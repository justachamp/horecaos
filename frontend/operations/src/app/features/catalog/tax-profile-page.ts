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
 * **Built this wave.** `PUT .../tax-profiles/{jurisdictionCode}` was real
 * (`PriceAuthoringService.setTaxProfile`, ADR 0018) and reachable from no
 * screen: `PricingApi` had no wrapper for it at all, and no route existed.
 * Without a profile every cart in the brand refuses with `NO_TAX_PROFILE` —
 * this is the one place an operator can set one.
 *
 * **Write-only, honestly.** There is no `GET` for a tax profile yet, so this
 * screen cannot list what is already in force; it can only author a new one
 * and show what the write itself returned. A jurisdiction code an operator
 * is unsure about is best confirmed against a fiscal receipt or Kassa
 * settings rather than guessed here.
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

  protected readonly jurisdictionCode = signal('UZ');
  protected readonly mode = signal<TaxMode>('INCLUSIVE');
  protected readonly rateBasisPoints = signal(1200);

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    this.denied.set(!this.brand.scope());
  }

  protected setMode(mode: TaxMode): void {
    this.mode.set(mode);
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
