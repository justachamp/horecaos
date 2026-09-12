import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

/** `CourierTrackRevealService.MINIMUM_PURPOSE_LENGTH` — checked here too so the refusal is immediate, not a round trip. */
const MINIMUM_PURPOSE_LENGTH = 12;

export interface TrackRevealSubmission {
  /** ISO instant. */
  readonly from: string;
  /** ISO instant. */
  readonly to: string;
  readonly purpose: string;
}

/**
 * The one dialog `courier.track.reveal` needs: a bounded window and a stated
 * purpose, both mandatory (`OperationsCourierPositionController.RevealRequest`).
 *
 * A free-text purpose, not a picker — the same reasoning `order-reason-dialog`
 * gives for its own free-text reason: the class of reason a track is opened for
 * ("a customer says it never arrived", "a courier disputes a distance", "a
 * scooter was stolen") is an open set, and the backend agrees — it validates a
 * sentence, not an enum member.
 */
@Component({
  selector: 'q-track-reveal-dialog',
  imports: [TPipe, Modal],
  templateUrl: './track-reveal-dialog.html',
  styleUrl: './track-reveal-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TrackRevealDialog {
  readonly courierId = input.required<string>();
  /** True while the reveal request is in flight — disables the form and blocks dismissal. */
  readonly busy = input(false);
  /** Already-translated, from `describeApiError` — a failed attempt stays on this dialog rather than closing it. */
  readonly errorText = input<string | null>(null);

  readonly confirm = output<TrackRevealSubmission>();
  readonly dismiss = output<void>();

  protected readonly from = signal(defaultFromLocal());
  protected readonly to = signal(defaultToLocal());
  protected readonly purpose = signal('');
  private readonly touched = signal(false);

  protected readonly purposeTooShort = computed(
    () => this.touched() && this.purpose().trim().length < MINIMUM_PURPOSE_LENGTH,
  );

  protected readonly windowInvalid = computed(() => {
    if (!this.touched()) {
      return false;
    }
    const from = this.from();
    const to = this.to();
    if (!from || !to) {
      return true;
    }
    return !(new Date(to).getTime() > new Date(from).getTime());
  });

  protected setFrom(value: string): void {
    this.from.set(value);
  }

  protected setTo(value: string): void {
    this.to.set(value);
  }

  protected setPurpose(value: string): void {
    this.purpose.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const purpose = this.purpose().trim();
    const from = this.from();
    const to = this.to();
    if (purpose.length < MINIMUM_PURPOSE_LENGTH || !from || !to) {
      return;
    }
    const fromDate = new Date(from);
    const toDate = new Date(to);
    if (!(toDate.getTime() > fromDate.getTime())) {
      return;
    }
    this.confirm.emit({ from: fromDate.toISOString(), to: toDate.toISOString(), purpose });
  }

  protected close(): void {
    if (this.busy()) {
      return;
    }
    this.dismiss.emit();
  }
}

/** `datetime-local` value, an hour ago — a dispute is usually about a recent delivery. */
function defaultFromLocal(): string {
  return toLocalInputValue(new Date(Date.now() - 60 * 60 * 1000));
}

function defaultToLocal(): string {
  return toLocalInputValue(new Date());
}

function toLocalInputValue(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}
