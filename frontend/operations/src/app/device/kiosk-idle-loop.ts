import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { environment } from '../../environments/environment';
import { TPipe } from '../core/i18n/t.pipe';
import { DeviceIdleMedia } from './device-profile';

/**
 * One picture of a kiosk's idle loop, full screen, with the channel and the branch it sells at kept in a corner (ADR 0187,
 * D25). The shell decides which picture is on screen and when it changes; this draws it. Each picture is a fresh element, so
 * the cross-fade runs again on every change, and a user who asks for reduced motion gets none.
 *
 * Decorative: the picture has no text alternative, because a lobby screen's pictures are the tenant's advertising and carry
 * no information a person at the kiosk needs read to them.
 */
@Component({
  selector: 'app-kiosk-idle-loop',
  imports: [TPipe],
  templateUrl: './kiosk-idle-loop.html',
  styleUrl: './kiosk-idle-loop.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KioskIdleLoop {
  readonly picture = input.required<DeviceIdleMedia>();
  readonly channelName = input('');
  readonly branchName = input('');

  /** An idle picture's address on the API's own origin. */
  protected src(picture: DeviceIdleMedia): string {
    return `${environment.apiBaseUrl}${picture.url}`;
  }
}
