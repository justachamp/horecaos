import { ChangeDetectionStrategy, Component, OnInit, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/** One of the product's own photos, offered as a choice for the previewed channel. */
export interface ChannelImageChoice {
  readonly assetId: string;
  /** Null until the thumbnail has resolved, or when it cannot — the tile then draws a placeholder. */
  readonly url: string | null;
}

/**
 * Picks which of a product's own photos one channel shows instead (ADR 0138's
 * channel-scoped media override) — the first picked is the main one, the rest the
 * gallery, in the order picked.
 *
 * Presentation only: loading the product's photos, saving, and reloading the
 * preview belong to `ChannelPreviewPage`; this raises what the operator asked for.
 * Price is not on this panel and cannot be — an image override and a price are
 * independent axes of a channel.
 */
@Component({
  selector: 'q-channel-image-override-panel',
  imports: [TPipe],
  templateUrl: './channel-image-override-panel.html',
  styleUrl: './channel-image-override-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelImageOverridePanel implements OnInit {
  readonly choices = input.required<readonly ChannelImageChoice[]>();
  /** The asset ids the channel shows now, when it has an override; empty otherwise. */
  readonly current = input.required<readonly string[]>();
  readonly saving = input(false);
  readonly error = input<string | null>(null);

  readonly saved = output<readonly string[]>();
  readonly cleared = output<void>();
  readonly cancelled = output<void>();

  /** Asset ids in the order the operator picked them. */
  protected readonly picked = signal<readonly string[]>([]);

  ngOnInit(): void {
    this.picked.set([...this.current()]);
  }

  /** 1-based position among the picks, or 0 when not picked. */
  protected position(assetId: string): number {
    return this.picked().indexOf(assetId) + 1;
  }

  protected toggle(assetId: string): void {
    this.picked.update((ids) =>
      ids.includes(assetId) ? ids.filter((id) => id !== assetId) : [...ids, assetId],
    );
  }
}
