import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { StaffMember } from '../../core/api/staff-member';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  EMERGENCY_RELATIONSHIPS,
  EmergencyContact,
  EmergencyContactInput,
  EmergencyRelationship,
  StaffMembersApi,
} from './staff-members-api';
import { isPlausiblePhone } from './staff-profile-draft';

/** The most contacts one person can have (`iam.staff_emergency_contacts.slot` is 1..3). */
const MAX_CONTACTS = 3;

type Phase = 'hidden' | 'loading' | 'shown' | 'error';

interface ContactDraft {
  relationshipCode: EmergencyRelationship;
  name: string;
  phone: string;
}

/**
 * A person's emergency contacts (ADR 0139, gap map row `9.2b`), on their card.
 *
 * **These are a third party's name and phone, held by a tenant that has no
 * relationship with that third party.** So the panel is built to touch them as
 * little as it can:
 *
 *  - **Nothing is fetched until somebody asks.** Opening a card does not read
 *    the contacts; «Показать контакты» does. Every read writes an audit fact
 *    (`staff.emergency_contact.read`, ADR 0027) and there is no bulk read, so
 *    an eager fetch would put a fact in the log for every card anyone merely
 *    glanced at -- and a log that cries wolf stops being read.
 *  - **They go away again.** «Скрыть» and any change of person drop them from
 *    memory and the page, so a card left open on a shared screen shows no
 *    stranger's number.
 *  - **The panel says out loud that looking is recorded.**
 *
 * Reading needs `staff.emergency-contact.read`; changing needs
 * `staff.profile.manage`. The host passes what the viewer holds and the platform
 * enforces both regardless (ADR 0025). A replace moves the member's version (the
 * contacts are part of the member), which {@link versionChanged} hands back so
 * the card's next profile save does not go out with a stale `If-Match`.
 */
@Component({
  selector: 'q-staff-emergency-contacts',
  imports: [TPipe],
  templateUrl: './staff-emergency-contacts.html',
  styleUrl: './staff-emergency-contacts.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffEmergencyContacts {
  private readonly api = inject(StaffMembersApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly member = input.required<StaffMember>();
  /** Whether the viewer may read the contacts at all. */
  readonly canRead = input(false);
  /** Whether the viewer may change them. */
  readonly canManage = input(false);

  /** The member's new version after a replace -- the card must carry it into its next save. */
  readonly versionChanged = output<number>();

  protected readonly phase = signal<Phase>('hidden');
  protected readonly contacts = signal<readonly EmergencyContact[]>([]);
  private readonly memberVersion = signal(0);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly editing = signal(false);
  protected readonly drafts = signal<readonly ContactDraft[]>([]);
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly relationships = EMERGENCY_RELATIONSHIPS;
  protected readonly maxContacts = MAX_CONTACTS;

  /** Changes only when the card moves to a different person, not when the same person's record is re-read. */
  private readonly memberId = computed(() => this.member().memberId);

  constructor() {
    // A different person on a docked card must never inherit the previous
    // person's contacts, open or not.
    effect(() => {
      void this.memberId();
      untracked(() => this.hide());
    });
  }

  protected async show(): Promise<void> {
    this.phase.set('loading');
    this.errorMessage.set(null);
    try {
      const result = await this.api.emergencyContacts(this.tenantId(), this.member().memberId);
      this.contacts.set(result.contacts);
      this.memberVersion.set(result.memberVersion);
      this.phase.set('shown');
    } catch (error) {
      this.errorMessage.set(this.describe(error));
      this.phase.set('error');
    }
  }

  protected hide(): void {
    this.phase.set('hidden');
    this.contacts.set([]);
    this.editing.set(false);
    this.drafts.set([]);
    this.errorMessage.set(null);
    this.saveError.set(null);
    this.attempted.set(false);
  }

  protected startEditing(): void {
    this.drafts.set(
      this.contacts().map((contact) => ({
        relationshipCode: contact.relationshipCode,
        name: contact.name,
        phone: contact.phone,
      })),
    );
    this.saveError.set(null);
    this.attempted.set(false);
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
    this.drafts.set([]);
  }

  protected addDraft(): void {
    if (this.drafts().length >= MAX_CONTACTS) {
      return;
    }
    this.drafts.update((rows) => [...rows, { relationshipCode: 'SPOUSE', name: '', phone: '' }]);
  }

  protected removeDraft(index: number): void {
    this.drafts.update((rows) => rows.filter((_, position) => position !== index));
  }

  protected edit<K extends keyof ContactDraft>(
    index: number,
    field: K,
    value: ContactDraft[K],
  ): void {
    this.drafts.update((rows) =>
      rows.map((row, position) => (position === index ? { ...row, [field]: value } : row)),
    );
  }

  protected rowProblem(row: ContactDraft): 'name' | 'phone' | null {
    if (row.name.trim() === '') {
      return 'name';
    }
    if (row.phone.trim() === '' || !isPlausiblePhone(row.phone)) {
      return 'phone';
    }
    return null;
  }

  protected async save(): Promise<void> {
    this.attempted.set(true);
    if (this.drafts().some((row) => this.rowProblem(row) !== null)) {
      return;
    }
    const inputs: EmergencyContactInput[] = this.drafts().map((row) => ({
      relationshipCode: row.relationshipCode,
      name: row.name.trim(),
      phone: row.phone.trim(),
    }));
    this.busy.set(true);
    this.saveError.set(null);
    try {
      const result = await this.api.replaceEmergencyContacts(
        this.tenantId(),
        this.member().memberId,
        inputs,
        this.memberVersion(),
      );
      this.contacts.set(result.contacts);
      this.memberVersion.set(result.memberVersion);
      this.versionChanged.emit(result.memberVersion);
      this.editing.set(false);
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected relationLabelKey(code: EmergencyRelationship): MessageKey {
    switch (code) {
      case 'SPOUSE':
        return 'staff.emergency.relation.SPOUSE';
      case 'PARENT':
        return 'staff.emergency.relation.PARENT';
      case 'CHILD':
        return 'staff.emergency.relation.CHILD';
      case 'SIBLING':
        return 'staff.emergency.relation.SIBLING';
      case 'FRIEND':
        return 'staff.emergency.relation.FRIEND';
      case 'OTHER':
        return 'staff.emergency.relation.OTHER';
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
