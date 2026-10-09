import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { DAY_OF_WEEK_KEYS } from '../../shared/ui/condition-types';
import {
  DispatchConditions,
  DispatchOptions,
  IntRange,
  SOURCE_TYPES,
  ScopeLevel,
  TimeWindow,
  WEEKDAYS,
  Weekday,
} from './dispatch-rules-api';
import {
  defaultWindow,
  fromInputEnd,
  parseOptionalInt,
  rangeOf,
  toInputTime,
  toggled,
} from './dispatch-rules-model';
import { localisedName } from './localised-name';

/**
 * The "when an order…" half of a dispatch rule (ADR 0142): a conjunction over a closed vocabulary --
 * source, sales channel, delivery zone, branch, preparation time, distance, the day and hours of
 * confirmation on the branch's clock, and prepayment. Presentational: it holds no draft of its own and
 * emits a whole new {@link DispatchConditions} for the host to keep.
 *
 * Every box that is left empty is "any", so a condition not set costs nothing and a rule never has to
 * say what it does not care about. The server refuses a name that is not this company's; the pickers
 * below are drawn from `GET .../dispatch-rules/options`, which lists only what is.
 */
@Component({
  selector: 'q-dispatch-conditions-form',
  imports: [TPipe],
  templateUrl: './dispatch-conditions-form.html',
  styleUrl: './dispatch-conditions-form.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchConditionsForm {
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  readonly conditions = input.required<DispatchConditions>();
  readonly options = input<DispatchOptions | null>(null);
  /** At LOCATION scope the branch is implied, so the branch picker is not offered. */
  readonly scopeLevel = input<ScopeLevel>('LOCATION');
  readonly idPrefix = input('rule');

  readonly conditionsChange = output<DispatchConditions>();

  protected readonly sourceTypes = SOURCE_TYPES;
  protected readonly weekdays = WEEKDAYS;
  protected readonly dayKeys = DAY_OF_WEEK_KEYS;

  protected readonly showBranches = computed(() => this.scopeLevel() !== 'LOCATION');

  protected zoneLabel(zone: DispatchOptions['zones'][number]): string {
    return localisedName(
      this.i18n.locale(),
      {
        displayNameRu: zone.nameRu,
        displayNameUz: zone.nameUz,
        displayNameEn: zone.nameEn,
      },
      this.registry.fallbackOrder(),
    );
  }

  protected toggleSource(source: string): void {
    this.emit({ sources: toggled(this.conditions().sources, source) });
  }

  protected toggleChannel(id: string): void {
    this.emit({ channelIds: toggled(this.conditions().channelIds, id) });
  }

  protected toggleZone(id: string): void {
    this.emit({ zoneIds: toggled(this.conditions().zoneIds, id) });
  }

  protected toggleLocation(id: string): void {
    this.emit({ locationIds: toggled(this.conditions().locationIds, id) });
  }

  protected setRange(
    which: 'prepMinutes' | 'distanceMeters',
    end: 'min' | 'max',
    raw: string,
  ): void {
    const current: IntRange = this.conditions()[which] ?? { min: null, max: null };
    const value = parseOptionalInt(raw);
    const next = end === 'min' ? rangeOf(value, current.max) : rangeOf(current.min, value);
    this.emit({ [which]: next });
  }

  protected rangeEnd(which: 'prepMinutes' | 'distanceMeters', end: 'min' | 'max'): string {
    const value = this.conditions()[which]?.[end];
    return value === null || value === undefined ? '' : String(value);
  }

  protected limitHours(): void {
    this.emit({ localTime: defaultWindow() });
  }

  protected anyTime(): void {
    this.emit({ localTime: null });
  }

  protected toggleDay(day: Weekday): void {
    const window = this.conditions().localTime;
    if (window) {
      this.emit({ localTime: { ...window, days: toggled(window.days, day) } });
    }
  }

  protected setFrom(value: string): void {
    const window = this.conditions().localTime;
    if (window && value) {
      this.emit({ localTime: { ...window, from: value } });
    }
  }

  protected setTo(value: string): void {
    const window = this.conditions().localTime;
    if (window && value) {
      this.emit({ localTime: { ...window, to: fromInputEnd(value, window.from) } });
    }
  }

  protected inputTime(stored: string): string {
    return toInputTime(stored);
  }

  protected setPrepaid(value: string): void {
    this.emit({ prepaid: value === 'true' ? true : value === 'false' ? false : null });
  }

  protected prepaidValue(): string {
    const prepaid = this.conditions().prepaid;
    return prepaid === null ? 'any' : String(prepaid);
  }

  protected has(list: readonly string[], value: string): boolean {
    return list.includes(value);
  }

  protected window(): TimeWindow | null {
    return this.conditions().localTime;
  }

  private emit(patch: Partial<DispatchConditions>): void {
    this.conditionsChange.emit({ ...this.conditions(), ...patch });
  }
}
