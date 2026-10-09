import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { StationResponse } from '../../kitchen/kitchen-api';
import {
  PrintDocumentType,
  PrinterView,
  PrintingApi,
  ROUTABLE_DOCUMENT_TYPES,
  RouteView,
} from './printing-api';
import { describePrintError } from './printing-errors';
import { DOCUMENT_TYPE_KEYS } from './printing-labels';

/**
 * Settings · Printing and receipts · What prints where (ADR 0154 decision 6).
 *
 * A route maps (document type, optionally a station) to a printer, with a number of copies and an
 * «automatically» switch. A new branch has no routes, and a document with no route prints nowhere; this card
 * says so before anything else, because that is the state every branch starts in and a manager should never
 * have to wonder why nothing came out.
 *
 * Where the branch's point of sale already receives orders (an active `ORDER_EXPORT` binding), the card adds
 * «your point of sale may already print this» beside the kitchen-ticket route. The platform cannot read whether
 * the till prints what it is sent, so it states the possibility and leaves the route to the manager: a
 * duplicate ticket in a kitchen is worse than a missing one only once, and the manager is the one who can look.
 */
@Component({
  selector: 'q-print-routes-card',
  imports: [TPipe],
  templateUrl: './routes-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RoutesCard {
  private readonly api = inject(PrintingApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly printers = input.required<readonly PrinterView[]>();
  readonly routes = input.required<readonly RouteView[]>();
  readonly stations = input.required<readonly StationResponse[]>();
  /** True when the branch's till may already print what it is sent (an active `ORDER_EXPORT` binding). */
  readonly posMayPrint = input.required<boolean>();
  readonly changed = output<void>();

  protected readonly documentTypes = ROUTABLE_DOCUMENT_TYPES;
  protected readonly documentKeys = DOCUMENT_TYPE_KEYS;
  protected readonly copyChoices: readonly number[] = [1, 2, 3];

  protected readonly editing = signal<RouteView | 'new' | null>(null);
  protected readonly documentType = signal<PrintDocumentType>('KITCHEN_TICKET');
  protected readonly stationId = signal('');
  protected readonly printerId = signal('');
  protected readonly copies = signal(1);
  protected readonly autoPrint = signal(false);
  protected readonly active = signal(true);
  protected readonly deleting = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly activePrinters = computed(() =>
    this.printers().filter((printer) => printer.status === 'ACTIVE'),
  );
  protected readonly chosenPrinter = computed(() =>
    this.printers().find((printer) => printer.id === this.printerId()),
  );
  /** Automatic printing needs an agent: a browser prints only when a person is looking at the screen. */
  protected readonly autoPossible = computed(
    () => this.chosenPrinter()?.connectionKind === 'AGENT',
  );
  protected readonly isTicket = computed(() => this.documentType() === 'KITCHEN_TICKET');
  protected readonly hasKitchenRoute = computed(() =>
    this.routes().some((route) => route.documentType === 'KITCHEN_TICKET'),
  );
  protected readonly canSave = computed(() => !this.busy() && this.printerId() !== '');

  protected printerName(id: string): string {
    return this.printers().find((printer) => printer.id === id)?.displayName ?? '—';
  }

  protected stationName(id: string | null): string | null {
    if (id === null) {
      return null;
    }
    const station = this.stations().find((candidate) => candidate.stationId === id);
    if (!station) {
      return id;
    }
    return this.i18n.locale() === 'ru'
      ? station.displayNameRu
      : this.i18n.locale() === 'uz-Latn'
        ? station.displayNameUz
        : station.displayNameEn;
  }

  protected openNew(): void {
    this.reset();
    this.printerId.set(this.activePrinters().length === 1 ? this.activePrinters()[0].id : '');
    this.editing.set('new');
  }

  protected openEdit(route: RouteView): void {
    this.reset();
    this.documentType.set(route.documentType);
    this.stationId.set(route.stationId ?? '');
    this.printerId.set(route.printerId);
    this.copies.set(route.copies);
    this.autoPrint.set(route.autoPrint);
    this.active.set(route.status === 'ACTIVE');
    this.editing.set(route);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected setDocumentType(type: PrintDocumentType): void {
    this.documentType.set(type);
    if (type !== 'KITCHEN_TICKET') {
      this.stationId.set('');
    }
  }

  protected setPrinter(id: string): void {
    this.printerId.set(id);
    if (!this.autoPossible()) {
      this.autoPrint.set(false);
    }
  }

  protected async save(): Promise<void> {
    const target = this.editing();
    if (target === null || !this.canSave()) {
      return;
    }
    const draft = {
      documentType: this.documentType(),
      stationId: this.isTicket() && this.stationId() !== '' ? this.stationId() : null,
      printerId: this.printerId(),
      copies: this.copies(),
      autoPrint: this.autoPossible() && this.autoPrint(),
      status: this.active() ? ('ACTIVE' as const) : ('INACTIVE' as const),
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      if (target === 'new') {
        await this.api.createRoute(this.scope(), draft);
      } else {
        await this.api.updateRoute(this.scope(), target.id, draft, target.version);
      }
      this.editing.set(null);
      this.notice.set(this.i18n.t('settings.printing.routes.saved'));
      this.changed.emit();
    } catch (error) {
      this.error.set(this.describe(error));
      if (target !== 'new') {
        this.changed.emit();
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected askDelete(route: RouteView): void {
    this.deleting.set(this.deleting() === route.id ? null : route.id);
  }

  protected async remove(route: RouteView): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.deleteRoute(this.scope(), route.id, route.version);
      this.deleting.set(null);
      this.notice.set(this.i18n.t('settings.printing.routes.deleted'));
      this.changed.emit();
    } catch (error) {
      this.error.set(this.describe(error));
      this.changed.emit();
    } finally {
      this.busy.set(false);
    }
  }

  private reset(): void {
    this.documentType.set('KITCHEN_TICKET');
    this.stationId.set('');
    this.printerId.set('');
    this.copies.set(1);
    this.autoPrint.set(false);
    this.active.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private describe(error: unknown): string {
    return describePrintError(error, this.i18n, {
      off: 'settings.printing.error.off',
      noRoute: 'settings.printing.error.noRoute',
    });
  }
}
