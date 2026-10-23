import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { BrowserPrinter } from '../settings/printing/browser-print';
import {
  PRINT_REASONS,
  PrintDocumentType,
  PrintReason,
  PrinterView,
  PrintingApi,
} from '../settings/printing/printing-api';
import { describePrintError } from '../settings/printing/printing-errors';

/** The documents an order can put on paper from here: a test page is a printer's, not an order's. */
const ORDER_DOCUMENTS: readonly PrintDocumentType[] = [
  'ORDER_SLIP',
  'KITCHEN_TICKET',
  'FISCAL_RECEIPT',
];

const DOCUMENT_KEYS: Readonly<Record<string, MessageKey>> = {
  ORDER_SLIP: 'orders.detail.print.doc.orderSlip',
  KITCHEN_TICKET: 'orders.detail.print.doc.kitchenTicket',
  FISCAL_RECEIPT: 'orders.detail.print.doc.fiscalReceipt',
};

const REASON_KEYS: Readonly<Record<PrintReason, MessageKey>> = {
  CUSTOMER_REQUEST: 'orders.detail.print.reason.customerRequest',
  PAPER_JAM: 'orders.detail.print.reason.paperJam',
  PRINTER_FAULT: 'orders.detail.print.reason.printerFault',
  KITCHEN_REQUEST: 'orders.detail.print.reason.kitchenRequest',
  AMENDED_ORDER: 'orders.detail.print.reason.amendedOrder',
  TEST: 'orders.detail.print.reason.test',
  OTHER: 'orders.detail.print.reason.other',
};

/**
 * The order detail's «Печать» action (ADR 0154 decision 6 and 12).
 *
 * It puts one of an order's documents on paper at this branch: the order summary, the kitchen ticket, or the
 * fiscal receipt. With no printer chosen the document goes where the branch's route says; a document with no
 * route is refused, and the refusal says what to do about it (a manager adds a route; or the person here picks a
 * printer), because the alternative is a button that looks like it worked.
 *
 * It is deliberately not the «Экспорт в кассу» section beside it. That one sends the order to the till, which
 * prints because it receives an order; this one prints on a printer HorecaOS knows. The section says so in its
 * first line, so that nobody presses one believing it is the other (ADR 0154 decision 11).
 *
 * Every print here is a manual print: it asks why, writes a `printing.job.reprinted` fact naming the person, and
 * is marked as a copy on paper when it is not the first. Hidden while `feature.printing` is off for the tenant.
 * A browser printer's job comes back handed off, and the picture is printed through this workstation's dialog.
 */
@Component({
  selector: 'q-order-print-panel',
  imports: [TPipe],
  templateUrl: './order-print-panel.html',
  styleUrl: './order-print-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPrintPanel {
  private readonly api = inject(PrintingApi);
  private readonly flags = inject(FeatureFlags);
  private readonly browser = inject(BrowserPrinter);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();

  protected readonly documents = ORDER_DOCUMENTS;
  protected readonly documentKeys = DOCUMENT_KEYS;
  protected readonly reasons = PRINT_REASONS;
  protected readonly reasonKeys = REASON_KEYS;

  protected readonly documentType = signal<PrintDocumentType>('ORDER_SLIP');
  protected readonly reason = signal<PrintReason>('CUSTOMER_REQUEST');
  protected readonly printerId = signal('');
  /** Empty for a person who may print but not read the printer list (a cashier); then the route decides. */
  protected readonly printers = signal<readonly PrinterView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly flagsLoaded = signal(false);

  protected readonly visible = computed(
    () => this.flagsLoaded() && this.flags.isOn('feature.printing'),
  );
  protected readonly activePrinters = computed(() =>
    this.printers().filter((printer) => printer.status === 'ACTIVE'),
  );

  constructor() {
    void this.flags.ensureLoaded().then(() => this.flagsLoaded.set(true));
    effect(() => {
      const scope = this.scope();
      this.orderId();
      untracked(() => {
        this.error.set(null);
        this.notice.set(null);
        if (this.flagsLoaded()) {
          void this.loadPrinters(scope);
        }
      });
    });
    effect(() => {
      const scope = this.scope();
      if (this.visible()) {
        untracked(() => void this.loadPrinters(scope));
      }
    });
  }

  /**
   * Brings the panel into view and puts the cursor on its first control -- what the row's «Печать» (orders.md
   * §2.12's `p`) and the pane's header action both ask for. Does nothing while the panel is hidden (printing
   * off): the action that calls this is absent then, and a stale click must not scroll to nothing.
   */
  reveal(): void {
    if (!this.visible()) {
      return;
    }
    const host = this.host.nativeElement;
    host.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
    host.querySelector<HTMLElement>('[data-testid="order-print-document"]')?.focus();
  }

  protected async print(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    const scope = this.scope();
    try {
      const jobs = await this.api.printOrderDocument(
        scope,
        this.orderId(),
        this.documentType(),
        this.reason(),
        this.printerId() === '' ? null : this.printerId(),
      );
      const handedOff = jobs.filter((job) => job.status === 'HANDED_OFF');
      if (handedOff.length === 0) {
        this.notice.set(this.i18n.t('orders.detail.print.queued', { count: jobs.length }));
        return;
      }
      try {
        for (const job of handedOff) {
          // The cashier who made the job fetches its picture: the preview is print.read, a manager's.
          const picture = await this.api.handedOffPicture(scope, job.id);
          await this.browser.print(picture, job.tapeWidthMm);
        }
        this.notice.set(this.i18n.t('orders.detail.print.browser'));
      } catch {
        this.error.set(this.i18n.t('orders.detail.print.error.picture'));
      }
    } catch (error) {
      this.error.set(
        describePrintError(error, this.i18n, {
          off: 'orders.detail.print.error.off',
          noRoute: 'orders.detail.print.error.noRoute',
        }),
      );
    } finally {
      this.busy.set(false);
    }
  }

  private async loadPrinters(scope: LocationScope): Promise<void> {
    try {
      this.printers.set(await this.api.printers(scope));
    } catch {
      // A cashier may print but not read the printer list; the route then decides where it goes.
      this.printers.set([]);
    }
  }
}
