import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/i18n/error-message';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { NoticeStore } from '../../core/notice/notice.store';

/**
 * The courier transparency notice (ADR 0045): what is collected, when, who sees it, for how long.
 *
 * **The wording is a placeholder, and the screen says so.** The notice is the tenant's document,
 * agreed with counsel, and nobody here may write it: the catalogue entries are a clearly marked
 * draft, the version names them as one, and replacing the entries and the version is the whole of
 * the change when the real text arrives (`NoticeStore`). Accepting records the courier's
 * acknowledgement on the platform; only then can the app go on duty.
 */
@Component({
  selector: 'app-notice',
  imports: [TPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './notice.html',
  styleUrl: './notice.css',
})
export class NoticePage {
  protected readonly notice = inject(NoticeStore);
  private readonly i18n = inject(I18n);

  protected readonly errorText = computed(() =>
    errorMessage(this.i18n, this.notice.error(), 'notice.error'),
  );

  protected accept(): void {
    void this.notice.acknowledge();
  }
}
