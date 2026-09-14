import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { VoicePresence } from './voice-presence';

/**
 * IA X.37 — the one thing a call centre screen alone cannot do: follow an
 * operator who is on Kitchen, or Delivery, or already taking an order,
 * when a call rings. Reads {@link VoicePresence}, the same service and the
 * same poll `call-centre-page.ts` itself now reads from — mounted once in
 * the shell (`shell.html`), outside the routed outlet, so it survives
 * navigation the way `q-toast-host` does (ADR 0101).
 *
 * Renders nothing when no call is ringing — an empty bar on every screen,
 * always, would be exactly the "0 late" always-visible-and-therefore-never-
 * read failure `service-status.ts`'s own late indicator was built to avoid.
 *
 * Not a softphone. `claim` calls the same acknowledgement `call-centre-
 * page.ts` does; the only thing this bar adds beyond that is "start order",
 * which is a plain navigation carrying the claimed call's id — see
 * `new-order-page.ts`'s own doc for what happens to it there.
 */
@Component({
  selector: 'q-call-bar',
  imports: [TPipe],
  templateUrl: './call-bar.html',
  styleUrl: './call-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CallBar {
  private readonly voicePresence = inject(VoicePresence);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly call = this.voicePresence.currentCall;
  protected readonly claiming = signal(false);

  protected async claim(): Promise<void> {
    const call = this.call();
    if (!call?.callEventId || this.claiming()) {
      return;
    }
    this.claiming.set(true);
    try {
      await this.voicePresence.claim(call.callEventId);
    } catch {
      // Same stance as `call-centre-page.ts`'s own claim handler: a lost
      // race is not an error worth interrupting an operator over.
    } finally {
      this.claiming.set(false);
    }
  }

  protected startOrder(): void {
    const call = this.call();
    if (!call?.callEventId) {
      return;
    }
    void this.router.navigate(['/orders/new'], { queryParams: { callEventId: call.callEventId } });
  }

  protected openCallCentre(): void {
    void this.router.navigateByUrl('/orders/call-centre');
  }
}
