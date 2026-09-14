import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { formatClock } from '../../core/format/datetime';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * "Updated HH:mm:ss" plus the manual refresh control every live surface's
 * own doc promises (row `X.34`, wave P08) — `service-status.ts`'s own doc
 * calls this "not optional: a queue that silently stopped updating looks
 * exactly like a quiet shift".
 *
 * A sibling to the hand-rolled stamp `order-queue.ts` already has, not (yet)
 * a replacement for it: that screen's own markup is left as it is this wave
 * to avoid touching a template several existing specs already assert
 * against, and this component's first real consumer is the shell's own rail
 * (`shell.html`), which had no stamp of any kind before this wave —
 * `service-status.ts`'s `updatedAt` "exists and is displayed nowhere" is
 * exactly the gap this closes there.
 *
 * No tenant timezone reaches this component yet — see `order-queue.ts`'s own
 * `PLACEHOLDER_TIME_ZONE` doc for why `Asia/Tashkent` is the least-wrong
 * constant available today.
 */
@Component({
  selector: 'q-refresh-indicator',
  imports: [TPipe],
  templateUrl: './refresh-indicator.html',
  styleUrl: './refresh-indicator.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RefreshIndicator {
  readonly updatedAt = input.required<Date | null>();
  readonly refreshing = input(false);

  readonly refresh = output<void>();

  protected readonly stamp = computed(() => {
    const at = this.updatedAt();
    return at ? formatClock(at, 'Asia/Tashkent') : null;
  });

  protected onRefreshClick(): void {
    this.refresh.emit();
  }
}
