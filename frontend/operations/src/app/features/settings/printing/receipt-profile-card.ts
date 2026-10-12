import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { formatDateTime } from '../../../core/format/datetime';
import { PrintingApi, ReceiptProfileView } from './printing-api';
import { describePrintError } from './printing-errors';

/** The languages a footer is written in (ADR 0154 decision 4: a footer of up to three lines per locale). */
export const FOOTER_LOCALES: readonly {
  readonly code: string;
  readonly labelKey:
    | 'settings.printing.profile.locale.ru'
    | 'settings.printing.profile.locale.uzLatn'
    | 'settings.printing.profile.locale.en';
}[] = [
  { code: 'ru', labelKey: 'settings.printing.profile.locale.ru' },
  { code: 'uz-Latn', labelKey: 'settings.printing.profile.locale.uzLatn' },
  { code: 'en', labelKey: 'settings.printing.profile.locale.en' },
];

const LINES = 3;
const LINE_LENGTH = 48;

/**
 * Settings · Printing and receipts · Tape width and presentation (ADR 0154 decision 4).
 *
 * What a brand can choose about its paper is two things: whether its logo prints, and up to three footer lines
 * per language. That is all. The seller's name and tax number, the order number, the totals, the QR code and the
 * fiscal block are not fields, so there is nothing here to move, resize or remove them, and the card says so
 * instead of offering a designer. Tape width is a property of each printer, set on the printer.
 *
 * Brand scope: a footer and a logo are the brand's face on every branch's paper, so a branch manager reads this
 * and an administrator writes it.
 */
@Component({
  selector: 'q-print-receipt-profile-card',
  imports: [TPipe],
  templateUrl: './receipt-profile-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptProfileCard {
  private readonly api = inject(PrintingApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();

  protected readonly locales = FOOTER_LOCALES;
  protected readonly lineIndexes: readonly number[] = [0, 1, 2];
  protected readonly lineLength = LINE_LENGTH;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly profile = signal<ReceiptProfileView | null>(null);
  protected readonly printLogo = signal(false);
  protected readonly footers = signal<Readonly<Record<string, readonly string[]>>>({});
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly dirty = computed(() => {
    const stored = this.profile();
    if (stored === null) {
      return false;
    }
    return (
      stored.printLogo !== this.printLogo() ||
      JSON.stringify(this.normalised(stored.footerTexts)) !==
        JSON.stringify(this.normalised(this.footers()))
    );
  });
  protected readonly canSave = computed(
    () => !this.busy() && this.profile() !== null && this.dirty(),
  );

  /** A ticket for the latest read: a reply under an older one belongs to a brand already left. */
  private seq = 0;

  constructor() {
    effect(() => {
      const scope = this.scope();
      untracked(() => void this.load(scope));
    });
  }

  protected line(code: string, index: number): string {
    return this.footers()[code]?.[index] ?? '';
  }

  protected setLine(code: string, index: number, value: string): void {
    const lines = [...(this.footers()[code] ?? [])];
    while (lines.length < LINES) {
      lines.push('');
    }
    lines[index] = value;
    this.footers.update((all) => ({ ...all, [code]: lines }));
    this.notice.set(null);
  }

  protected setLogo(on: boolean): void {
    this.printLogo.set(on);
    this.notice.set(null);
  }

  protected savedAt(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected retry(): void {
    void this.load(this.scope());
  }

  protected async save(): Promise<void> {
    const current = this.profile();
    if (current === null || !this.canSave()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const saved = await this.api.saveReceiptProfile(
        this.scope(),
        { printLogo: this.printLogo(), footerTexts: this.normalised(this.footers()) },
        current.version,
      );
      this.adopt(saved);
      this.notice.set(this.i18n.t('settings.printing.profile.saved'));
    } catch (error) {
      this.error.set(this.describe(error));
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        // Someone else saved first: show their version, not ours, so the next save is against what is stored.
        await this.load(this.scope());
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(scope: BrandScope): Promise<void> {
    const ticket = ++this.seq;
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const view = await this.api.receiptProfile(scope);
      if (ticket === this.seq) {
        this.adopt(view);
      }
    } catch (error) {
      if (ticket === this.seq) {
        this.loadError.set(this.describe(error));
      }
    } finally {
      if (ticket === this.seq) {
        this.loading.set(false);
      }
    }
  }

  private adopt(view: ReceiptProfileView): void {
    this.profile.set(view);
    this.printLogo.set(view.printLogo);
    this.footers.set(view.footerTexts);
  }

  /** Only non-empty lines, trimmed, at most three per language; a language with none is left out. */
  private normalised(
    all: Readonly<Record<string, readonly string[]>>,
  ): Record<string, readonly string[]> {
    const result: Record<string, readonly string[]> = {};
    for (const { code } of FOOTER_LOCALES) {
      const lines = (all[code] ?? []).map((line) => line.trim()).filter((line) => line.length > 0);
      if (lines.length > 0) {
        result[code] = lines.slice(0, LINES);
      }
    }
    return result;
  }

  private describe(error: unknown): string {
    return describePrintError(error, this.i18n, {
      off: 'settings.printing.error.off',
      noRoute: 'settings.printing.error.noRoute',
    });
  }
}
