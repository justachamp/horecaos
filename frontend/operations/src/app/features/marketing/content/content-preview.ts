import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { environment } from '../../../../environments/environment';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { PhoneFrame } from '../../../shared/ui/phone-frame';
import { describeApiError } from '../../orders/order-errors';
import { ContentApi, PreviewCard, PreviewDocument, PreviewMedia } from './content-api';
import { ContentReferences } from './content-references';

/**
 * What a customer would see, built from drafts (ADR 0157, Staff API: `GET /preview`).
 *
 * The endpoint runs **the same assembler** as the public read with every draft overlaid, so this
 * cannot drift from production: if a card is missing here it is missing there for the same reason
 * (the channel it is not on, a window it is outside of, a promotion that is not live, an image still
 * being checked). The item being edited is outlined. `asOf` previews a schedule -- the document as
 * it will read at a chosen day.
 *
 * Drawn roughly, in a phone frame: the point is which items appear, in what order and with which
 * words, not pixel fidelity with an app the platform does not own.
 */
@Component({
  selector: 'q-content-preview',
  imports: [PhoneFrame, TPipe],
  templateUrl: './content-preview.html',
  styleUrl: './content-preview.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentPreview implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);
  protected readonly refs = inject(ContentReferences);

  /** The item being edited, outlined in the preview. */
  readonly highlightId = input<string | null>(null);
  /** The languages the brand writes in. */
  readonly locales = input.required<readonly string[]>();
  /** The language the author is editing: where the preview starts. */
  readonly startLocale = input<string | null>(null);

  protected readonly channel = signal<string | null>(null);
  protected readonly locale = signal<string | null>(null);
  protected readonly day = signal('');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly document = signal<PreviewDocument | null>(null);

  protected readonly channels = computed(() => this.refs.contentChannels());
  protected readonly empty = computed(() => {
    const doc = this.document();
    return (
      doc !== null &&
      doc.carousel.length === 0 &&
      doc.groups.length === 0 &&
      (doc.stories ?? []).length === 0 &&
      (doc.promoGrid ?? []).length === 0 &&
      !doc.popup
    );
  });
  /** Whether the item being edited is in the document at all, at this channel, language and day. */
  protected readonly highlighted = computed(() => {
    const id = this.highlightId();
    const doc = this.document();
    if (id === null || doc === null) {
      return null;
    }
    return (
      doc.carousel.some((c) => c.id === id) ||
      (doc.promoGrid ?? []).some((c) => c.id === id) ||
      doc.groups.some((g) => g.id === id) ||
      (doc.stories ?? []).some((s) => s.id === id) ||
      doc.popup?.id === id
    );
  });

  ngOnInit(): void {
    this.locale.set(this.startLocale() ?? this.locales()[0] ?? null);
    void this.refresh();
  }

  protected chooseChannel(code: string): void {
    this.channel.set(code);
    void this.refresh();
  }

  protected chooseLocale(locale: string): void {
    this.locale.set(locale);
    void this.refresh();
  }

  protected chooseDay(value: string): void {
    this.day.set(value);
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    if (this.channel() === null) {
      const first = this.channels()[0];
      this.channel.set(first ? first.code : null);
    }
    const channel = this.channel();
    if (channel === null) {
      this.document.set(null);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      // The start of the chosen day in the operator's timezone.
      const asOf = this.day() === '' ? null : new Date(`${this.day()}T00:00:00`).toISOString();
      this.document.set(await this.api.preview(scope, channel, this.locale(), asOf));
    } catch (failure) {
      this.document.set(null);
      this.error.set(
        failure instanceof ApiError
          ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('marketing.content.preview.failed'),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** The smallest rendition that is at least 400 px wide, else the largest there is; the original is never offered. */
  protected imageUrl(media: PreviewMedia | null | undefined): string | null {
    if (!media) {
      return null;
    }
    const relative =
      media.kind === 'VIDEO'
        ? (media.posterUrl ?? null)
        : (media.renditions.find((r) => r.widthPx >= 400)?.url ??
          media.renditions.at(-1)?.url ??
          null);
    return relative === null ? null : `${environment.apiBaseUrl}${relative}`;
  }

  protected isVideo(card: PreviewCard): boolean {
    return card.media.kind === 'VIDEO';
  }
}
