import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { TranslatePipe } from '../translate/translate.pipe';
import { Session } from '../../core/auth/session';
import { HorecaOSApiError, messageKeyFor } from '../../core/api/problem-details';
import { NotificationService } from '../../services/notification.service';
import { PushSubscriptionService } from '../../services/push-subscription.service';
import { StorageService } from '../../services/storage.service';
import { TranslateService } from '../../services/translate.service';

/** How long «not now» is respected before the prompt may be shown again. */
export const PUSH_DISMISS_DAYS = 30;

/** Remembered per browser: it is a convenience, never a decision about consent. */
export const PUSH_DISMISSED_KEY = 'push_prompt_dismissed_at';

/**
 * "Promotional notifications" -- the storefront's explicit opt-in to Web Push (ADR 0182, D14).
 *
 * A card with one honest question. It says what the customer would get (offers from this restaurant, on
 * this device), asks once, and only when they tap does the browser ask its own permission question; the
 * platform records the consent in the same step as the subscription. Turning it off is one tap, and it
 * withdraws the consent. Nothing here is asked on page load.
 *
 * It renders nothing, not an empty card, when push cannot be offered: a browser without Web Push (a
 * Telegram mini app webview, an in-app browser), a guest, a brand the platform cannot send push for. The
 * page that mounts it does not have to know why.
 *
 * `dismissible` is for a place where the card is an invitation (a banner on the home screen): «not now»
 * hides it for {@link PUSH_DISMISS_DAYS} days. On a settings page it is not dismissible, because it is
 * the only place to change the answer.
 */
@Component({
  selector: 'app-push-subscribe',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './push-subscribe.html',
  styleUrl: './push-subscribe.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PushSubscribeComponent implements OnInit {
  private readonly session = inject(Session);
  private readonly push = inject(PushSubscriptionService);
  private readonly storage = inject(StorageService);
  private readonly translate = inject(TranslateService);
  private readonly notification = inject(NotificationService);

  readonly dismissible = input(false);

  protected readonly state = this.push.state;
  protected readonly busy = this.push.busy;
  protected readonly error = signal<string | null>(null);
  private readonly dismissed = signal(false);

  /** Whether anything is shown at all. */
  protected readonly visible = computed(() => {
    if (!this.session.isAuthenticated()) {
      return false;
    }
    const state = this.state();
    if (state === 'unknown' || state === 'unsupported' || state === 'unavailable') {
      return false;
    }
    return !(state === 'off' && this.dismissible() && this.dismissed());
  });

  ngOnInit(): void {
    this.dismissed.set(this.dismissedRecently());
    if (!this.session.isAuthenticated()) {
      return;
    }
    this.push.refresh().catch((failure) => this.fail(failure));
  }

  protected async turnOn(): Promise<void> {
    this.error.set(null);
    try {
      const state = await this.push.enable();
      if (state === 'on') {
        this.notification.show(this.translate.get('push.enabled'));
      }
    } catch (failure) {
      this.fail(failure);
    }
  }

  protected async turnOff(): Promise<void> {
    this.error.set(null);
    try {
      await this.push.disable();
      this.notification.show(this.translate.get('push.disabled'));
    } catch (failure) {
      this.fail(failure);
    }
  }

  protected notNow(): void {
    this.storage.setItemSync(PUSH_DISMISSED_KEY, String(Date.now()));
    this.dismissed.set(true);
  }

  private dismissedRecently(): boolean {
    const at = Number(this.storage.getItemSync(PUSH_DISMISSED_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < PUSH_DISMISS_DAYS * 86_400_000;
  }

  private fail(failure: unknown): void {
    this.error.set(
      this.translate.get(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      ),
    );
  }
}
