import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { StaffMember } from '../../core/api/staff-member';
import { I18n } from '../../core/i18n/i18n';
import { localeDisplayName } from '../../core/i18n/locale-labels';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  DraftProblem,
  EditableStatus,
  OFFERED_LANGUAGES,
  ProfileDraft,
  draftOf,
  problemsOf,
  toggleLanguage,
} from './staff-profile-draft';

/**
 * The profile form (ADR 0139), shared by its two hosts: the person card, where a
 * manager edits somebody's record and the employment half is shown, and
 * «Мой профиль», where the person edits their own and it is not.
 *
 * It owns the typing and the checks that can be made without the platform
 * (a name is required, a phone has a plausible shape, an end date is not before
 * the start) and emits the draft as typed; the host turns it into the right
 * request (`staff-profile-draft.ts`) and shows the platform's answer through
 * {@link serverError}. A form that opens and is saved untouched changes nothing:
 * the draft starts from the record exactly as it was read.
 *
 * **The sign-in phone is not on this form and the card says so.** The contact
 * phone starts as the number the person signs in with and then belongs to them;
 * changing it here never changes how they sign in (ADR 0139: both the sign-in
 * identifier and the reset email are Keycloak's, with no console flow in v1).
 */
@Component({
  selector: 'q-staff-profile-form',
  imports: [TPipe],
  templateUrl: './staff-profile-form.html',
  styleUrl: './staff-profile-form.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffProfileForm implements OnInit {
  protected readonly i18n = inject(I18n);

  readonly member = input.required<StaffMember>();
  /** Shows the employment fields -- the status, the employee number, the dates and the reason. */
  readonly manager = input(false);
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);

  readonly submitted = output<ProfileDraft>();
  readonly cancelled = output<void>();

  protected readonly draft = signal<ProfileDraft>({
    firstName: '',
    lastName: '',
    phone: '',
    uiLocale: '',
    spokenLanguages: [],
    status: '',
    employeeNumber: '',
    employedFrom: '',
    employedUntil: '',
    reason: '',
  });
  /** Problems are only shown after a first try to save, so a form is not red before anyone has typed. */
  protected readonly attempted = signal(false);

  protected readonly languages = OFFERED_LANGUAGES;
  protected readonly problems = computed<readonly DraftProblem[]>(() =>
    problemsOf(this.draft(), this.manager()),
  );
  /** Whether the status can be changed here: only between active and on leave. */
  protected readonly statusEditable = computed(() => this.draft().status !== '');

  ngOnInit(): void {
    this.draft.set(draftOf(this.member()));
  }

  protected set<K extends keyof ProfileDraft>(field: K, value: ProfileDraft[K]): void {
    this.draft.update((current) => ({ ...current, [field]: value }));
  }

  protected has(problem: DraftProblem): boolean {
    return this.attempted() && this.problems().includes(problem);
  }

  protected isSpoken(code: string): boolean {
    return this.draft().spokenLanguages.includes(code);
  }

  protected toggleSpoken(code: string): void {
    this.set('spokenLanguages', toggleLanguage(this.draft().spokenLanguages, code));
  }

  protected setStatus(value: string): void {
    this.set('status', value as EditableStatus);
  }

  /** The language's own name -- `uz` is the console's Uzbek (Latin). */
  protected languageName(code: string): string {
    return localeDisplayName(this.i18n, code === 'uz' ? 'uz-Latn' : code);
  }

  protected submit(): void {
    this.attempted.set(true);
    if (this.problems().length > 0 || this.busy()) {
      return;
    }
    this.submitted.emit(this.draft());
  }
}
