import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { PriceBookSummary } from './catalog-domain';
import { describeApiError } from '../orders/order-errors';
import { PricingApi } from './pricing-api';

/**
 * IA 4.8 — Price list (Прейскурант), the price-book half (4.8a).
 *
 * **Built.** `PriceAuthoringController`'s list/create/assign-to-brand/
 * activate were all real (ADR 0018), reachable from no screen —
 * `pricing-api.ts` already had `listPriceBooks`/`resolvedVariantPrices`/
 * `setVariantPrice` for the product editor's own price cell, and this wave
 * adds the book-lifecycle calls beside them.
 *
 * **Not built: 4.8b, the bulk change tool.** catalog.md §4.8b's
 * filter → preview → apply flow needs a filtered product/variant selection
 * (4.1's own list) joined against per-variant current prices, a percent/
 * absolute/fixed calculator with rounding rules, and a preview table before
 * committing N `setVariantPrice` calls — a `DataGrid`-shaped tool (IA Part
 * 4's own gap list) this wave's time did not reach. This screen is 4.8a in
 * full: the named price list itself, its brand-wide assignment, and
 * activation — real function, not a mock of the bulk tool.
 */
@Component({
  selector: 'q-price-list-page',
  imports: [TPipe],
  templateUrl: './price-list-page.html',
  styleUrl: './price-list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PriceListPage implements OnInit {
  private readonly api = inject(PricingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly books = signal<readonly PriceBookSummary[]>([]);
  protected readonly actionError = signal<string | null>(null);

  protected readonly showCreateForm = signal(false);
  protected readonly creating = signal(false);
  protected readonly newBookName = signal('');
  protected readonly newBookCurrency = signal('UZS');

  protected readonly assigningBookId = signal<string | null>(null);
  protected readonly activatingBookId = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      const books = await firstValueFrom(this.api.listPriceBooks(scope));
      this.books.set(books);
      this.denied.set(false);
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

  protected openCreateForm(): void {
    this.newBookName.set('');
    this.newBookCurrency.set('UZS');
    this.showCreateForm.set(true);
  }

  protected closeCreateForm(): void {
    this.showCreateForm.set(false);
  }

  protected canCreate(): boolean {
    return (
      !this.creating() &&
      this.newBookName().trim().length > 0 &&
      this.newBookCurrency().trim().length === 3
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.creating.set(true);
    this.actionError.set(null);
    try {
      await firstValueFrom(
        this.api.createPriceBook(scope, {
          name: this.newBookName().trim(),
          currency: this.newBookCurrency().trim().toUpperCase(),
        }),
      );
      this.showCreateForm.set(false);
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.creating.set(false);
    }
  }

  protected canAssignToBrand(book: PriceBookSummary): boolean {
    return this.assigningBookId() === null;
  }

  protected async assignToBrand(book: PriceBookSummary): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canAssignToBrand(book)) {
      return;
    }
    this.assigningBookId.set(book.priceBookId);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.assignToBrand(scope, book.priceBookId, {}));
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.assigningBookId.set(null);
    }
  }

  protected canActivate(book: PriceBookSummary): boolean {
    return book.status === 'DRAFT' && this.activatingBookId() === null;
  }

  protected async activate(book: PriceBookSummary): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canActivate(book)) {
      return;
    }
    this.activatingBookId.set(book.priceBookId);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.activate(scope, book.priceBookId, book.version));
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.activatingBookId.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
