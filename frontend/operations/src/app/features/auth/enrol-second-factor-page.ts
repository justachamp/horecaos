import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Auth } from '../../core/auth/auth';
import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { StaffSessionResponse } from '../../core/auth/staff-session';
import { TPipe } from '../../core/i18n/t.pipe';
import { MfaEnrolmentForm } from './mfa-enrolment';

/**
 * Where a refused sign-in sends an account that must hold a second factor and holds none
 * (ADR 0148, Decision 4): `/enrol-second-factor`.
 *
 * Outside the auth guard, for the reason `/login` is: the person has no session — the platform
 * revoked the one it had just issued — and this is the screen they came for. What opens it is the
 * **enrolment ticket** the refused sign-in carried, held in memory by {@link MfaTicket}; with no
 * ticket there is nothing to do here and the page says so and offers the way back, instead of
 * rendering a form that could only fail. The ticket opens the enrolment endpoints and nothing
 * else, and the password is asked again before a secret is handed over.
 *
 * Confirming the first code returns a session, which this page adopts: the person arrives where
 * they were going, signed in, without typing the password a third time.
 */
@Component({
  selector: 'q-enrol-second-factor-page',
  imports: [TPipe, RouterLink, MfaEnrolmentForm],
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>
        <h1 class="q-title heading">{{ 'mfa.enrol.title' | t }}</h1>
        @if (ticket.ticket(); as current) {
          <q-mfa-enrolment [ticket]="current" [required]="true" (completed)="onCompleted($event)" />
        } @else {
          <p class="q-body" role="alert" data-testid="mfa-no-ticket">
            {{ 'mfa.enrol.noTicket' | t }}
          </p>
          <a class="q-body-sm back" routerLink="/login">{{ 'mfa.enrol.toSignIn' | t }}</a>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
    }

    .page {
      min-height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--q-surface-1);
      padding: 16px;
      box-sizing: border-box;
    }

    .card {
      width: 100%;
      max-width: 400px;
      padding: 32px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    .wordmark {
      color: var(--q-ink);
    }

    .dot {
      color: var(--q-primary);
    }

    .heading {
      margin: 16px 0 16px;
    }

    .back {
      display: block;
      margin-top: 16px;
      color: var(--q-primary);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnrolSecondFactorPage {
  protected readonly ticket = inject(MfaTicket);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected onCompleted(session: StaffSessionResponse | null): void {
    this.ticket.clear();
    if (session === null) {
      // Not reachable from a ticket (the platform answers one with a session), but never a dead end.
      void this.router.navigateByUrl('/login');
      return;
    }
    this.auth.adoptSession(session);
    void this.router.navigateByUrl(takeReturnTo());
  }
}

/** The deep link the guard saved before sending the person to sign in, if it is a same-document path. */
function takeReturnTo(): string {
  let target = '/today';
  try {
    const saved = globalThis.sessionStorage?.getItem(RETURN_TO_KEY);
    if (saved && saved.startsWith('/') && !saved.startsWith('//')) {
      target = saved;
    }
    globalThis.sessionStorage?.removeItem(RETURN_TO_KEY);
  } catch {
    // No storage: land on the default.
  }
  return target;
}
