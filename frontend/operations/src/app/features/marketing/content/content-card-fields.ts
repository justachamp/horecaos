import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  input,
  output,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { MessageKey } from '../../../core/i18n/messages.en';
import { DayOfWeekToggle } from '../../../shared/ui/day-of-week-toggle';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { TimeInput } from '../../../shared/ui/time-input';
import { ContentDocument } from './content-api';
import {
  CapMode,
  LinkValue,
  linkOf,
  localeImageId,
  popupOf,
  posterAssetId,
  primaryAssetId,
  textOf,
  withLink,
  withLocaleImage,
  withPopup,
  withPrimaryAsset,
  withText,
} from './content-document';
import { ContentLinkPicker } from './content-link-picker';
import { ContentMediaField, MediaChange } from './content-media-field';

export type CardKind = 'BANNER' | 'PROMO_CARD' | 'POPUP';

/** The crop each kind is held to: the ratio, the export edge and the least width the publication gate accepts. */
export const CARD_MEDIA: Readonly<
  Record<CardKind, { ratio: string; exportEdgePx: number; minWidthPx: number }>
> = {
  BANNER: { ratio: '3:1', exportEdgePx: 1600, minWidthPx: 960 },
  PROMO_CARD: { ratio: '3:2', exportEdgePx: 1200, minWidthPx: 600 },
  POPUP: { ratio: '3:2', exportEdgePx: 1200, minWidthPx: 600 },
};

const CAP_KEYS: Readonly<Record<CapMode, MessageKey>> = {
  ONCE_EVER: 'marketing.content.popup.cap.ONCE_EVER',
  ONCE_PER_DAY: 'marketing.content.popup.cap.ONCE_PER_DAY',
  EVERY_N_DAYS: 'marketing.content.popup.cap.EVERY_N_DAYS',
};

/**
 * The fields of a banner, a promo card and an entry pop-up (ADR 0157, "The typed documents"): the
 * texts of the language being edited, the picture (with its own ratio and export edge), a picture
 * for one language when it carries its own words, the link, and -- for a pop-up -- when it shows and
 * how often.
 *
 * Edits the document in its wire shape through the pure accessors and reports the whole new
 * document; it never saves anything itself.
 */
@Component({
  selector: 'q-content-card-fields',
  imports: [ContentLinkPicker, ContentMediaField, DayOfWeekToggle, NumberStepper, TimeInput, TPipe],
  templateUrl: './content-card-fields.html',
  styleUrl: './content-card-fields.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentCardFields {
  readonly kind = input.required<CardKind>();
  readonly document = input.required<ContentDocument>();
  readonly locale = input.required<string>();
  readonly allowVideo = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly documentChange = output<ContentDocument>();

  protected readonly media = computed(() => CARD_MEDIA[this.kind()]);
  protected readonly isPopup = computed(() => this.kind() === 'POPUP');
  protected readonly link = computed(() => linkOf(this.document()));
  protected readonly popup = computed(() => popupOf(this.document()));
  protected readonly primary = computed(() => primaryAssetId(this.document()));
  protected readonly poster = computed(() => posterAssetId(this.document()));
  protected readonly localeImage = computed(() => localeImageId(this.document(), this.locale()));
  protected readonly dailyDays = computed(() => new Set(this.popup().daily?.daysOfWeek ?? []));
  protected readonly capModes: readonly CapMode[] = ['ONCE_EVER', 'ONCE_PER_DAY', 'EVERY_N_DAYS'];

  protected text(field: string): string {
    return textOf(this.document(), this.locale(), field);
  }

  protected setText(field: string, value: string): void {
    this.documentChange.emit(withText(this.document(), this.locale(), field, value));
  }

  protected setPrimary(change: MediaChange): void {
    this.documentChange.emit(
      withPrimaryAsset(this.document(), change.assetId, change.posterAssetId),
    );
  }

  protected setLocaleImage(change: MediaChange): void {
    this.documentChange.emit(withLocaleImage(this.document(), this.locale(), change.assetId));
  }

  protected setLink(link: LinkValue): void {
    this.documentChange.emit(withLink(this.document(), link));
  }

  protected capKey(mode: CapMode): MessageKey {
    return CAP_KEYS[mode];
  }

  // ---------------------------------------------------------------------------- pop-up

  protected setDelay(seconds: number): void {
    this.documentChange.emit(
      withPopup(this.document(), { ...this.popup(), showAfterSeconds: seconds }),
    );
  }

  protected setDailyWindow(on: boolean): void {
    this.documentChange.emit(
      withPopup(this.document(), {
        ...this.popup(),
        daily: on ? { from: '09:00', until: '21:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] } : null,
      }),
    );
  }

  protected setDaily(change: { from?: string; until?: string; days?: ReadonlySet<number> }): void {
    const daily = this.popup().daily;
    if (daily === null) {
      return;
    }
    this.documentChange.emit(
      withPopup(this.document(), {
        ...this.popup(),
        daily: {
          from: change.from ?? daily.from,
          until: change.until ?? daily.until,
          daysOfWeek: change.days ? [...change.days].sort() : daily.daysOfWeek,
        },
      }),
    );
  }

  protected setCapMode(value: string): void {
    const capMode = this.capModes.find((mode) => mode === value) ?? 'ONCE_PER_DAY';
    this.documentChange.emit(
      withPopup(this.document(), {
        ...this.popup(),
        capMode,
        capN: capMode === 'EVERY_N_DAYS' ? 7 : 1,
      }),
    );
  }

  protected setCapDays(days: number): void {
    this.documentChange.emit(withPopup(this.document(), { ...this.popup(), capN: days }));
  }
}
