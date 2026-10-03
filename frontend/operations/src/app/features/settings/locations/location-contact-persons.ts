import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { StaffMember, hasName } from '../../../core/api/staff-member';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { StaffMembersApi } from '../../staff/staff-members-api';
import { isPlausiblePhone } from '../../staff/staff-profile-draft';
import {
  BranchContactInput,
  BranchContactPerson,
  CONTACT_RELATIONSHIPS,
  ContactRelationship,
  LocationContactsApi,
  MAX_BRANCH_CONTACTS,
} from './location-contacts-api';

type Phase = 'loading' | 'ready' | 'denied' | 'error';

/** One row of the editor: a colleague picked from the branch's people, or an outside person typed in. */
interface ContactDraft {
  relationshipCode: ContactRelationship;
  kind: 'COLLEAGUE' | 'OUTSIDE';
  staffMemberId: string;
  name: string;
  phone: string;
  /** The colleague already on this row has left; the platform refuses to keep them. */
  former: boolean;
}

/**
 * «Контактные лица» on the location screen (ADR 0139, gap map row `9.2b`):
 * who to call about a branch -- its manager, its landlord, the security desk.
 *
 * **This is not the branch's published phone.** `contactPhone`, shown above on
 * the same tab, is the line customers and couriers are given; these are an
 * internal list, readable by whoever can read the branch (`location.read`) and
 * changed by whoever can write it (`location.write`). The two are kept apart on
 * the screen as they are in the data.
 *
 * **A colleague is a reference, not a copy.** Choosing one stores the person
 * and nothing else; the name and number shown are read from their own record
 * each time, so a manager who changes their phone changes it here too. An
 * outside person is a name and a phone typed in -- a third party's details held
 * without a relationship to the platform, which ADR 0139 flags as a legal
 * question (lawful basis, notice) that is still open. The editor says so.
 *
 * The colleague picker lists the people who work *at this branch*
 * (`staff.profile.read` at the branch's scope): a branch manager can reach that
 * route with a branch grant, where the tenant-wide list would refuse them. A
 * viewer who cannot read it still edits outside persons and keeps any colleague
 * already listed.
 *
 * The set is replaced as a whole under the *location's* version
 * (`If-Match`), so two managers editing at once get a conflict and not a silent
 * overwrite.
 */
@Component({
  selector: 'q-location-contact-persons',
  imports: [TPipe],
  templateUrl: './location-contact-persons.html',
  styleUrl: './location-contact-persons.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationContactPersons {
  private readonly api = inject(LocationContactsApi);
  private readonly staffApi = inject(StaffMembersApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();

  protected readonly phase = signal<Phase>('loading');
  protected readonly contacts = signal<readonly BranchContactPerson[]>([]);
  private readonly version = signal(0);
  protected readonly loadError = signal<string | null>(null);

  protected readonly editing = signal(false);
  protected readonly drafts = signal<readonly ContactDraft[]>([]);
  protected readonly colleagues = signal<readonly StaffMember[]>([]);
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly relationships = CONTACT_RELATIONSHIPS;
  protected readonly maxContacts = MAX_BRANCH_CONTACTS;
  protected readonly canWrite = computed(() => this.capabilities.has('LOCATION_WRITE'));

  /** Changes only when the screen moves to another branch. */
  private readonly locationId = computed(() => this.scope().locationId);

  constructor() {
    effect(() => {
      void this.locationId();
      untracked(() => void this.load());
    });
  }

  private async load(): Promise<void> {
    this.phase.set('loading');
    this.editing.set(false);
    this.notice.set(null);
    try {
      const set = await this.api.list(this.scope());
      this.contacts.set(set.contacts);
      this.version.set(set.version);
      this.phase.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.phase.set('denied');
        return;
      }
      this.loadError.set(this.describe(error));
      this.phase.set('error');
    }
  }

  /** A colleague by the name the tenant keeps, else by the non-personal reference; an outside person by the name typed. */
  protected labelOf(contact: BranchContactPerson): string {
    return contact.name ?? contact.staffMemberReference ?? '—';
  }

  protected relationLabelKey(code: ContactRelationship): MessageKey {
    switch (code) {
      case 'MANAGER':
        return 'settings.locations.contacts.relation.MANAGER';
      case 'OWNER':
        return 'settings.locations.contacts.relation.OWNER';
      case 'LANDLORD':
        return 'settings.locations.contacts.relation.LANDLORD';
      case 'SECURITY':
        return 'settings.locations.contacts.relation.SECURITY';
      case 'MAINTENANCE':
        return 'settings.locations.contacts.relation.MAINTENANCE';
      case 'OTHER':
        return 'settings.locations.contacts.relation.OTHER';
    }
  }

  protected colleagueLabel(member: StaffMember): string {
    return hasName(member) ? member.displayName : member.displayReference;
  }

  // ------------------------------------------------------------- editing

  protected async startEditing(): Promise<void> {
    this.drafts.set(
      this.contacts().map((contact) => ({
        relationshipCode: contact.relationshipCode,
        kind: contact.staffMemberId !== null ? 'COLLEAGUE' : 'OUTSIDE',
        staffMemberId: contact.staffMemberId ?? '',
        name: contact.staffMemberId !== null ? '' : (contact.name ?? ''),
        phone: contact.staffMemberId !== null ? '' : (contact.phone ?? ''),
        former: contact.formerColleague,
      })),
    );
    this.attempted.set(false);
    this.saveError.set(null);
    this.notice.set(null);
    this.editing.set(true);
    // The picker: the people who work here. A viewer whose grant does not reach
    // that read still edits outside persons.
    this.colleagues.set(await this.staffApi.listAtLocation(this.scope()).catch(() => []));
  }

  protected cancelEditing(): void {
    this.editing.set(false);
    this.drafts.set([]);
  }

  protected addDraft(kind: ContactDraft['kind']): void {
    if (this.drafts().length >= MAX_BRANCH_CONTACTS) {
      return;
    }
    this.drafts.update((rows) => [
      ...rows,
      {
        relationshipCode: kind === 'COLLEAGUE' ? 'MANAGER' : 'LANDLORD',
        kind,
        staffMemberId: '',
        name: '',
        phone: '',
        former: false,
      },
    ]);
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
      rows.map((row, position) =>
        // Picking somebody else is the way out of a row that holds a leaver.
        position === index
          ? { ...row, [field]: value, ...(field === 'staffMemberId' ? { former: false } : {}) }
          : row,
      ),
    );
  }

  /** What stops this row being saved, or `null`. */
  protected rowProblem(row: ContactDraft): 'colleague' | 'former' | 'name' | 'phone' | null {
    if (row.kind === 'COLLEAGUE') {
      if (row.staffMemberId === '') {
        return 'colleague';
      }
      return row.former ? 'former' : null;
    }
    if (row.name.trim() === '') {
      return 'name';
    }
    return row.phone.trim() === '' || !isPlausiblePhone(row.phone) ? 'phone' : null;
  }

  protected problemKey(problem: 'colleague' | 'former' | 'name' | 'phone'): MessageKey {
    switch (problem) {
      case 'colleague':
        return 'settings.locations.contacts.colleague.required';
      case 'former':
        return 'settings.locations.contacts.colleague.former';
      case 'name':
        return 'staff.emergency.name.required';
      case 'phone':
        return 'staff.emergency.phone.invalid';
    }
  }

  /** The colleague already on a row stays pickable even if they no longer work at this branch. */
  protected optionsFor(row: ContactDraft): readonly StaffMember[] {
    return this.colleagues();
  }

  protected isKnownColleague(row: ContactDraft): boolean {
    return (
      row.staffMemberId === '' || this.colleagues().some((m) => m.memberId === row.staffMemberId)
    );
  }

  protected listedColleagueLabel(row: ContactDraft): string {
    const listed = this.contacts().find((contact) => contact.staffMemberId === row.staffMemberId);
    return listed ? this.labelOf(listed) : row.staffMemberId;
  }

  protected async save(): Promise<void> {
    this.attempted.set(true);
    if (this.drafts().some((row) => this.rowProblem(row) !== null)) {
      return;
    }
    const inputs: BranchContactInput[] = this.drafts().map((row) =>
      row.kind === 'COLLEAGUE'
        ? // A colleague is a reference alone: the platform refuses a copied name or phone.
          { relationshipCode: row.relationshipCode, staffMemberId: row.staffMemberId }
        : {
            relationshipCode: row.relationshipCode,
            name: row.name.trim(),
            phone: row.phone.trim(),
          },
    );
    this.busy.set(true);
    this.saveError.set(null);
    try {
      const set = await this.api.replace(this.scope(), inputs, this.version());
      this.contacts.set(set.contacts);
      this.version.set(set.version);
      this.editing.set(false);
      this.notice.set(this.i18n.t('staff.profile.saved'));
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
