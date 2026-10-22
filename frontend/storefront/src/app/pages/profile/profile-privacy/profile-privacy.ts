import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  WritableSignal,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { TranslateService } from '../../../services/translate.service';
import { Session } from '../../../core/auth/session';
import {
  CustomerApi,
  type ExportBundleLink,
  type ExportRequestStatus,
} from '../../../core/api/customer-api';
import {
  HorecaOSApiError,
  isNotFound,
  isUnauthenticated,
  messageKeyFor,
} from '../../../core/api/problem-details';

/**
 * "My data" -- a customer's own request for a copy of the data the business holds about her (ADR 0029), beside the profile
 * she already edits.
 *
 * She can ask, see the day the business owes her an answer by, and withdraw while it waits. Producing the file is the
 * business's act (a person on its side looks at who is asking), so after asking this screen says "being prepared" and
 * nothing else; once the file exists she is given a link that works for a few minutes. Asking for the link is recorded
 * against her as the reader, so the screen asks only when she presses the button and never on load, and it keeps the link
 * in a signal and nowhere else: not in a log, not in an analytics event, not in a URL of this app.
 *
 * The file itself, and what is in it, is not rendered here. It is a JSON document she opens or saves; this screen is the
 * door to it.
 */
@Component({
  selector: 'app-profile-privacy',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe, BackDirective],
  templateUrl: './profile-privacy.html',
  styleUrl: './profile-privacy.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfilePrivacyComponent implements OnInit {
  private readonly session = inject(Session);
  private readonly customers = inject(CustomerApi);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly needsSignIn = signal(false);
  readonly error = signal<string | null>(null);

  readonly request = signal<ExportRequestStatus | null>(null);
  readonly asking = signal(false);
  readonly askError = signal<string | null>(null);
  readonly withdrawing = signal(false);
  readonly link = signal<ExportBundleLink | null>(null);
  readonly gettingLink = signal(false);
  readonly linkError = signal<string | null>(null);

  ngOnInit(): void {
    if (!this.session.isAuthenticated()) {
      this.needsSignIn.set(true);
      this.loading.set(false);
      return;
    }
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.request.set(await this.customers.exportRequest());
    } catch (failure) {
      if (isNotFound(failure)) {
        // Signed in, but no account at this brand yet: nothing has been asked and there is nothing to ask about.
        this.request.set(null);
      } else {
        this.handleFailure(failure, this.error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** Whether a request is waiting for the business to produce the file. */
  isWaiting(): boolean {
    return this.request()?.status === 'PENDING';
  }

  /** Whether the file has been produced and can still be opened. */
  hasFile(): boolean {
    const current = this.request();
    return current?.status === 'COMPLETED' && current.bundleAvailable;
  }

  /** Whether the last request ended without a file she can still open (withdrawn, or the file has expired). */
  mayAskAgain(): boolean {
    const current = this.request();
    return current === null || current.status === 'CANCELLED' || (current.status === 'COMPLETED' && !current.bundleAvailable);
  }

  /** The due day in her own locale; never a time, which would promise a precision nobody offered. */
  dueDay(): string {
    const current = this.request();
    return current ? new Date(current.responseDueAt).toLocaleDateString() : '';
  }

  async ask(): Promise<void> {
    if (this.asking()) return;
    this.askError.set(null);
    this.asking.set(true);
    try {
      this.link.set(null);
      this.request.set(await this.customers.requestExport());
    } catch (failure) {
      this.handleFailure(failure, this.askError);
    } finally {
      this.asking.set(false);
    }
  }

  async withdraw(): Promise<void> {
    if (this.withdrawing()) return;
    this.askError.set(null);
    this.withdrawing.set(true);
    try {
      this.request.set(await this.customers.cancelExport());
    } catch (failure) {
      this.handleFailure(failure, this.askError);
    } finally {
      this.withdrawing.set(false);
    }
  }

  async openFile(): Promise<void> {
    if (this.gettingLink()) return;
    this.linkError.set(null);
    this.gettingLink.set(true);
    try {
      this.link.set(await this.customers.exportBundleLink());
    } catch (failure) {
      if (isNotFound(failure)) {
        // Expired between the screen loading and the press: say what is true now.
        await this.load();
      } else {
        this.handleFailure(failure, this.linkError);
      }
    } finally {
      this.gettingLink.set(false);
    }
  }

  private handleFailure(failure: unknown, target: WritableSignal<string | null>): void {
    if (isUnauthenticated(failure)) {
      this.needsSignIn.set(true);
      return;
    }
    target.set(this.messageFor(failure));
  }

  private messageFor(failure: unknown): string {
    if (failure instanceof HorecaOSApiError) {
      return this.translate.get(messageKeyFor(failure));
    }
    return this.translate.get('errors.generic');
  }
}
