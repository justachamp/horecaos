import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { errorMessage } from '../../core/i18n/error-message';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { NoticeStore } from '../../core/notice/notice.store';
import { DutyStore } from '../../core/telemetry/duty.store';

/**
 * Going on duty: the one place a courier chooses to share their location, and the place that says
 * what that means (ADR 0045, ADR 0179 D7).
 *
 * The limitation is on the card, in words, before the button: a PWA reads a position only while
 * it is open with the screen on, so a locked phone leaves a gap. The button is not offered until
 * the transparency notice has been read and recorded; what the card shows in its place is the
 * way to the notice.
 */
@Component({
  selector: 'app-duty-card',
  imports: [TPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './duty-card.html',
  styleUrl: './duty-card.css',
})
export class DutyCard {
  protected readonly duty = inject(DutyStore);
  protected readonly notice = inject(NoticeStore);
  protected readonly profile = inject(CourierProfileStore);
  private readonly i18n = inject(I18n);

  protected readonly errorText = computed(() =>
    errorMessage(this.i18n, this.duty.error(), 'duty.error'),
  );
}
