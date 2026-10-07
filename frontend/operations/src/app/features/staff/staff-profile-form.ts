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
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  DraftProblem,
  EditableStatus,
  spokenLanguageCodes,
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

  private readonly registry = inject(PlatformLocales);

  /** The languages the console can be set to: the registry's staff-UI tier (ADR 0149). */
  protected readonly uiLanguages = computed(() => this.registry.active('STAFF_UI'));

  /** The ISO 639 codes "speaks" offers: the languages the registry has live in the content tier. */
  protected readonly spokenCodes = computed(() =>
    spokenLanguageCodes(this.registry.active('CONTENT')),
  );
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

  /** An interface language's name, in the console's own wording. */
  protected languageName(tag: string): string {
    return localeDisplayName(this.i18n, tag, this.registry);
  }

  /** A spoken language's name: the registry's name of the live language that code belongs to. */
  protected spokenName(code: string): string {
    const tag = this.registry
      .active('CONTENT')
      .find((candidate) => candidate.split('-')[0] === code);
    return tag === undefined ? code : localeDisplayName(this.i18n, tag, this.registry);
  }

  protected submit(): void {
    this.attempted.set(true);
    if (this.problems().length > 0 || this.busy()) {
      return;
    }
    this.submitted.emit(this.draft());
  }
}
