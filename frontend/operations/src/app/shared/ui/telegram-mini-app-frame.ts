import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A Telegram Mini App's own header chrome around a screen — `q-telegram-
 * mini-app-frame` (row `X.28`), a `q-phone-frame` sibling for 10.5's bot
 * preview.
 *
 * The one frame in the family with an opinion about its header, because a
 * Mini App's back chevron and close control are Telegram's own chrome, not
 * the tenant's — the projected content starts *below* them, same as it
 * would inside the real client.
 */
@Component({
  selector: 'q-telegram-mini-app-frame',
  templateUrl: './telegram-mini-app-frame.html',
  styleUrl: './telegram-mini-app-frame.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TelegramMiniAppFrame {
  /** The Mini App's own header title — the bot's display name. Tenant data, not a message key. */
  readonly title = input.required<string>();
  /** Shown under the frame, already translated. Optional. */
  readonly caption = input<string | null>(null);
}
