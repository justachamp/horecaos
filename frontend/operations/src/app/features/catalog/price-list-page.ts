import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { PriceBookAssignmentRequest, PriceBookSummary } from './catalog-domain';
import { describeApiError } from '../orders/order-errors';
import { PricingApi } from './pricing-api';

type AssignmentScope = 'BRAND' | 'LOCATION' | 'CHANNEL';

/**
 * IA 4.8 — Price list (Прейскурант), the price-book half (4.8a).
 *
 * **Built this wave.** Price books could only be applied brand-wide from the
 * console: `assignToLocation` and `assignToChannel` existed on `PricingApi`
 * as client methods with zero callers, even though the endpoints behind them
 * (`PUT .../assignments/locations/{id}` and `.../channels/{id}`) were real —
 * so the hall-versus-base plane and aggregator price propagation ADR 0018
 * describes could not be expressed from this screen at all. The assign
 * dialog now offers all three scopes and lets `priority`, `validFrom` and
 * `validUntil` be authored, instead of always sending `{}`. The template
 * also no longer hides assign behind `book.status === 'DRAFT'`:
 * `PriceAuthoringService.assign` only refuses an `ARCHIVED` book (see its own
 * Javadoc — an `ACTIVE` book is deliberately still assignable, because
 * changing where a live book applies is an ordinary operation, not a
 * lifecycle transition). Activate stays gated on `DRAFT`, because the
 * backend itself refuses activating anything else.
 *
 * **Not built: 4.8b, the bulk change tool.** See `bulk-price-change-page.ts`
 * — a separate screen, reachable from the same shell tab bar.
 */
@Component({
  selector: 'q-price-list-page',
  imports: [TPipe, RouterLink],
  templateUrl: './price-list-page.html',
  styleUrl: './price-list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PriceListPage implements OnInit {
  private readonly api = inject(PricingApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly books = signal<readonly PriceBookSummary[]>([]);
  protected readonly actionError = signal<string | null>(null);

  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);

  protected readonly showCreateForm = signal(false);
  protected readonly creating = signal(false);
  protected readonly newBookName = signal('');
  protected readonly newBookCurrency = signal('UZS');

  protected readonly activatingBookId = signal<string | null>(null);

  // -------------------------------------------------------------- assignment dialog
  protected readonly assigningBook = signal<PriceBookSummary | null>(null);
  protected readonly assignSubmitting = signal(false);
  protected readonly assignScope = signal<AssignmentScope>('BRAND');
  protected readonly assignTargetId = signal('');
  protected readonly assignPriority = signal(0);
  protected readonly assignValidFrom = signal('');
  protected readonly assignValidUntil = signal('');

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

    // The location and channel lists back the assign dialog's target picker.
    // Neither failure is fatal to the page the way the price-book load is —
    // an operator can still assign to the brand, and create/activate, with
    // neither list loaded.
    try {
      this.locations.set(
        await this.locationsApi.list({
          tenantId: scope.tenantId,
          brandId: scope.brandId,
          locationId: '',
        }),
      );
    } catch {
      // See above.
    }
    try {
      this.channels.set(
        await this.channelsApi.list({
          tenantId: scope.tenantId,
          brandId: scope.brandId,
          locationId: '',
        }),
      );
    } catch {
      // See above.
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

  /** `ARCHIVED` is the only status `PriceAuthoringService.assign` refuses (see its own Javadoc). */
  protected canAssign(book: PriceBookSummary): boolean {
    return book.status !== 'ARCHIVED' && this.assigningBook() === null;
  }

  protected canActivate(book: PriceBookSummary): boolean {
    return book.status === 'DRAFT' && this.activatingBookId() === null;
  }

  protected openAssignDialog(book: PriceBookSummary): void {
    this.assigningBook.set(book);
    this.assignScope.set('BRAND');
    this.assignTargetId.set('');
    this.assignPriority.set(book.priority);
    this.assignValidFrom.set('');
    this.assignValidUntil.set('');
    this.actionError.set(null);
  }

  protected closeAssignDialog(): void {
    if (this.assignSubmitting()) {
      return;
    }
    this.assigningBook.set(null);
  }

  protected setAssignScope(scope: AssignmentScope): void {
    this.assignScope.set(scope);
    this.assignTargetId.set('');
  }

  protected canSubmitAssign(): boolean {
    if (this.assignSubmitting()) {
      return false;
    }
    return this.assignScope() === 'BRAND' || this.assignTargetId().trim().length > 0;
  }

  protected async submitAssign(): Promise<void> {
    const scope = this.brand.scope();
    const book = this.assigningBook();
    if (!scope || !book || !this.canSubmitAssign()) {
      return;
    }
    this.assignSubmitting.set(true);
    this.actionError.set(null);
    const request: PriceBookAssignmentRequest = {
      priority: this.assignPriority(),
      validFrom: this.assignValidFrom() ? `${this.assignValidFrom()}T00:00:00Z` : null,
      validUntil: this.assignValidUntil() ? `${this.assignValidUntil()}T00:00:00Z` : null,
    };
    try {
      switch (this.assignScope()) {
        case 'BRAND':
          await firstValueFrom(this.api.assignToBrand(scope, book.priceBookId, request));
          break;
        case 'LOCATION':
          await firstValueFrom(
            this.api.assignToLocation(scope, book.priceBookId, this.assignTargetId(), request),
          );
          break;
        case 'CHANNEL':
          await firstValueFrom(
            this.api.assignToChannel(scope, book.priceBookId, this.assignTargetId(), request),
          );
          break;
      }
      this.assigningBook.set(null);
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.assignSubmitting.set(false);
    }
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
