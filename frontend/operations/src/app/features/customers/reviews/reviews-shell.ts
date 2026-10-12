import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { TPipe } from '../../../core/i18n/t.pipe';

/**
 * The Reviews section's own small tab strip (IA 5.4, ADR 0159): the brand's review list that ADR
 * 0071 built, the service-recovery worklist beside it, and the queue of comments customers agreed
 * to show publicly.
 *
 * A tab is shown only to an operator who holds its capability somewhere, so a line cook who can
 * read reviews is not offered a worklist they cannot work. The route behind a tab still answers for
 * itself: a direct URL is refused by the server, not by this tab's absence (ADR 0025).
 */
@Component({
  selector: 'q-reviews-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './reviews-shell.html',
  styleUrl: './reviews-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReviewsShell {
  protected readonly capabilities = inject(SessionCapabilities);
}
