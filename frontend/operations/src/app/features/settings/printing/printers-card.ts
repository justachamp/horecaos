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
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BrowserPrinter } from './browser-print';
import { AgentView, PrintJobView, PrinterView, PrintingApi, TapeWidth } from './printing-api';
import { describePrintError } from './printing-errors';
import { PRINTER_STATUS_KEYS } from './printing-labels';

type Kind = 'BROWSER' | 'AGENT';

/**
 * Settings · Printing and receipts · Printers (ADR 0154 decision 6 and 9).
 *
 * A printer belongs to one branch. It is either this workstation's browser (the operator prints a picture at
 * the roll's width) or a printer an enrolled print agent can reach, bound to the alias the agent advertises. The
 * platform never holds or sends an address: where a printer is on the branch's network is the agent's own
 * configuration, and the screen says so rather than offering a field that would invent one.
 *
 * Tape width is the printer's, not the receipt's: a 58 mm and an 80 mm roll at one branch are two printers.
 * The page owns the lists; this card raises `changed` after a write and the page reads them again.
 */
@Component({
  selector: 'q-print-printers-card',
  imports: [TPipe],
  templateUrl: './printers-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrintersCard {
  private readonly api = inject(PrintingApi);
  private readonly browser = inject(BrowserPrinter);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly printers = input.required<readonly PrinterView[]>();
  readonly agents = input.required<readonly AgentView[]>();
  readonly changed = output<void>();

  protected readonly statusKeys = PRINTER_STATUS_KEYS;
  protected readonly widths: readonly TapeWidth[] = [58, 80];

  /** `null` is closed; `'new'` is the add form; otherwise the printer being edited. */
  protected readonly editing = signal<PrinterView | 'new' | null>(null);
  protected readonly name = signal('');
  protected readonly tape = signal<TapeWidth>(80);
  protected readonly kind = signal<Kind>('BROWSER');
  protected readonly agentId = signal('');
  protected readonly agentKey = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly activeAgents = computed(() =>
    this.agents().filter((agent) => agent.device.status === 'ACTIVE'),
  );
  /** The aliases the chosen agent advertises, and nothing else: an alias nobody advertises cannot be bound. */
  protected readonly aliases = computed(() => {
    const chosen = this.agents().find((agent) => agent.device.deviceId === this.agentId());
    return chosen?.advertised ?? [];
  });
  protected readonly canSave = computed(() => {
    if (this.busy() || this.name().trim().length === 0) {
      return false;
    }
    return this.kind() === 'BROWSER' || (this.agentId() !== '' && this.agentKey() !== '');
  });

  protected agentName(printer: PrinterView): string | null {
    const agent = this.agents().find(
      (candidate) => candidate.device.deviceId === printer.agentDeviceId,
    );
    return agent?.device.displayName ?? null;
  }

  protected seen(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected openNew(): void {
    this.reset();
    this.editing.set('new');
  }

  protected openEdit(printer: PrinterView): void {
    this.reset();
    this.name.set(printer.displayName);
    this.tape.set(printer.tapeWidthMm === 58 ? 58 : 80);
    this.kind.set(printer.connectionKind);
    this.agentId.set(printer.agentDeviceId ?? '');
    this.agentKey.set(printer.agentPrinterKey ?? '');
    this.editing.set(printer);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected setKind(kind: Kind): void {
    this.kind.set(kind);
    if (kind === 'AGENT' && this.agentId() === '' && this.activeAgents().length === 1) {
      this.setAgent(this.activeAgents()[0].device.deviceId);
    }
  }

  protected setAgent(agentId: string): void {
    this.agentId.set(agentId);
    const advertised = this.aliases();
    this.agentKey.set(advertised.length === 1 ? advertised[0].key : '');
  }

  protected async save(): Promise<void> {
    const target = this.editing();
    if (target === null || !this.canSave()) {
      return;
    }
    const draft = {
      displayName: this.name().trim(),
      tapeWidthMm: this.tape(),
      agentDeviceId: this.kind() === 'AGENT' ? this.agentId() : null,
      agentPrinterKey: this.kind() === 'AGENT' ? this.agentKey() : null,
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      if (target === 'new') {
        await this.api.registerPrinter(this.scope(), draft);
      } else {
        await this.api.updatePrinter(this.scope(), target.id, draft, target.status, target.version);
      }
      this.editing.set(null);
      this.notice.set(this.i18n.t('settings.printing.printers.saved'));
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

  protected async setRetired(printer: PrinterView, retired: boolean): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.updatePrinter(
        this.scope(),
        printer.id,
        {
          displayName: printer.displayName,
          tapeWidthMm: printer.tapeWidthMm === 58 ? 58 : 80,
          agentDeviceId: printer.agentDeviceId,
          agentPrinterKey: printer.agentPrinterKey,
        },
        retired ? 'RETIRED' : 'ACTIVE',
        printer.version,
      );
      this.notice.set(
        this.i18n.t(
          retired ? 'settings.printing.printers.retired' : 'settings.printing.printers.restored',
        ),
      );
      this.changed.emit();
    } catch (error) {
      this.error.set(this.describe(error));
      this.changed.emit();
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * A test page: the roll's width, a ruler, every glyph the three languages use, a QR and the printer's name.
   * A browser printer's page is printed from here at once; an agent printer's is queued like any other job.
   */
  protected async testPage(printer: PrinterView): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const job = await this.api.testPage(this.scope(), printer.id);
      await this.deliver(job, printer);
      this.notice.set(
        this.i18n.t(
          printer.connectionKind === 'BROWSER'
            ? 'settings.printing.printers.testBrowser'
            : 'settings.printing.printers.testQueued',
        ),
      );
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private async deliver(job: PrintJobView, printer: PrinterView): Promise<void> {
    if (job.status !== 'HANDED_OFF') {
      return;
    }
    const picture = await this.api.preview(this.scope(), { jobId: job.id });
    await this.browser.print(picture, printer.tapeWidthMm);
  }

  private reset(): void {
    this.name.set('');
    this.tape.set(80);
    this.kind.set('BROWSER');
    this.agentId.set('');
    this.agentKey.set('');
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
