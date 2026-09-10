import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';

interface Country {
  readonly code: string;
  readonly name: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
}

interface Locale {
  readonly code: string;
  readonly displayName: string;
}

/** A public holiday: a month and day every year, or one date. */
export interface Holiday {
  readonly holidayId: string;
  readonly countryCode: string;
  readonly name: string;
  readonly month: number | null;
  readonly day: number | null;
  readonly date: string | null;
}

interface ReferenceData {
  readonly countries: readonly Country[];
  readonly locales: readonly Locale[];
  readonly holidays: readonly Holiday[];
}

interface SlaBuckets {
  readonly version: number;
  readonly buckets: readonly { code: string; fromMinutes: number; toMinutesExclusive: number | null }[];
}

/**
 * IA 8.3 Reference data -- the countries the platform trades in, the locales
 * the consoles ship, each country's public holidays, and the elapsed-time
 * buckets every order is reported in.
 *
 * Fixed-date holidays recur every year; ones that move with the lunar
 * calendar are entered for the year they fall in. The buckets are fixed per
 * release, so a chart drawn last quarter keeps its meaning.
 */
@Component({
  selector: 'app-reference-data-screen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reference-data.html',
  styleUrl: './reference-data.css',
})
export class ReferenceDataScreen {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(ApiClient);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly data = signal<ReferenceData | null>(null);
  protected readonly sla = signal<SlaBuckets | null>(null);

  protected readonly adding = signal(false);
  protected readonly holidayCountry = signal('UZ');
  protected readonly holidayName = signal('');
  protected readonly recurring = signal(true);
  protected readonly holidayMonthDay = signal('');
  protected readonly holidayDate = signal('');
  protected readonly holidayReason = signal('');
  protected readonly removing = signal<string | null>(null);
  protected readonly removeReason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly holidaysByCountry = computed(() => {
    const groups = new Map<string, Holiday[]>();
    for (const holiday of this.data()?.holidays ?? []) {
      groups.set(holiday.countryCode, [...(groups.get(holiday.countryCode) ?? []), holiday]);
    }
    return [...groups.entries()];
  });

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [data, sla] = await Promise.all([
        firstValueFrom(this.api.get<ReferenceData>('/api/v1/control-plane/reference-data')),
        firstValueFrom(this.api.get<SlaBuckets>('/api/v1/control-plane/reference-data/sla-buckets')),
      ]);
      this.data.set(data);
      this.sla.set(sla);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected countryName(code: string): string {
    return this.data()?.countries.find((country) => country.code === code)?.name ?? code;
  }

  /** `21.03` for a recurring holiday, `10.03.2027` for a dated one. */
  protected when(holiday: Holiday): string {
    if (holiday.date !== null) {
      const [year, month, day] = holiday.date.split('-');
      return `${day}.${month}.${year}`;
    }
    return `${String(holiday.day).padStart(2, '0')}.${String(holiday.month).padStart(2, '0')}`;
  }

  protected bucketKey(code: string): MessageKey {
    return `referenceData.bucket.${code}` as MessageKey;
  }

  /** The month and day typed as `DD.MM`, or null while it is not one. */
  private monthDay(): { month: number; day: number } | null {
    const match = /^(\d{1,2})\.(\d{1,2})$/.exec(this.holidayMonthDay().trim());
    if (match === null) {
      return null;
    }
    const day = Number(match[1]);
    const month = Number(match[2]);
    return month >= 1 && month <= 12 && day >= 1 && day <= 31 ? { month, day } : null;
  }

  protected canAdd(): boolean {
    const when = this.recurring() ? this.monthDay() !== null : /^\d{4}-\d{2}-\d{2}$/.test(this.holidayDate());
    return !this.busy() && when && this.holidayName().trim().length > 0 && this.holidayReason().trim().length > 0;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canAdd()) {
      return;
    }
    const monthDay = this.recurring() ? this.monthDay() : null;
    await this.run(async () => {
      await firstValueFrom(
        this.api.post('/api/v1/control-plane/reference-data/holidays', {
          countryCode: this.holidayCountry(),
          name: this.holidayName().trim(),
          month: monthDay?.month,
          day: monthDay?.day,
          date: this.recurring() ? undefined : this.holidayDate(),
          reason: this.holidayReason().trim(),
        }),
      );
      this.adding.set(false);
      this.holidayName.set('');
      this.holidayMonthDay.set('');
      this.holidayDate.set('');
      this.holidayReason.set('');
    });
  }

  protected async remove(holiday: Holiday): Promise<void> {
    const reason = this.removeReason().trim();
    if (reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await firstValueFrom(
        this.api.delete(`/api/v1/control-plane/reference-data/holidays/${holiday.holidayId}`, { reason }),
      );
      this.removing.set(null);
    });
  }

  private async run(write: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    try {
      await write();
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
