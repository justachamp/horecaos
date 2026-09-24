import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import { OrderAddressReveal } from './order-detail';

export interface AddressSubmission {
  readonly line1: string;
  readonly line2: string;
  readonly city: string;
  readonly district: string;
  readonly postalCode: string;
  readonly entrance: string;
  readonly floor: string;
  readonly apartment: string;
  readonly landmark: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly deliveryInstructions: string;
  readonly recipientName: string;
  readonly recipientPhone: string;
}

interface AddressDraft {
  line1: string;
  line2: string;
  city: string;
  district: string;
  postalCode: string;
  entrance: string;
  floor: string;
  apartment: string;
  landmark: string;
  latitude: string;
  longitude: string;
  deliveryInstructions: string;
}

const EMPTY_DRAFT: AddressDraft = {
  line1: '',
  line2: '',
  city: '',
  district: '',
  postalCode: '',
  entrance: '',
  floor: '',
  apartment: '',
  landmark: '',
  latitude: '0',
  longitude: '0',
  deliveryInstructions: '',
};

function draftFrom(reveal: OrderAddressReveal | null): AddressDraft {
  if (!reveal) {
    return { ...EMPTY_DRAFT };
  }
  return {
    line1: reveal.line1 ?? '',
    line2: reveal.line2 ?? '',
    city: reveal.city ?? '',
    district: reveal.district ?? '',
    postalCode: reveal.postalCode ?? '',
    entrance: reveal.entrance ?? '',
    floor: reveal.floor ?? '',
    apartment: reveal.apartment ?? '',
    landmark: reveal.landmark ?? '',
    latitude: String(reveal.latitude),
    longitude: String(reveal.longitude),
    deliveryInstructions: reveal.deliveryInstructions ?? '',
  };
}

/**
 * `CHANGE_DELIVERY_ADDRESS` (ADR 0039, wave 10, row `1.2c`) — the one wave-10
 * command that can lower the total as well as raise it (a cheaper ADR 0037
 * zone fee), so `order-detail-pane.ts` always proposes it with
 * `applyImmediately: false` and follows it with the priced-delta confirmation
 * step, same as {@link OrderChangeQuantityDialog}/`ADD_LINES`.
 *
 * Seeded from the order's *current* address — `order-detail-pane.ts` reveals
 * it first through the existing audited `OrderRevealApi.revealAddress` call
 * (§3.8), the same reveal the address panel's own "Reveal address" control
 * uses — and the field set mirrors `new-order-page.ts`'s own
 * `AddressDraft`/`CustomerAddressFields` (подъезд/этаж/квартира/ориентир as
 * separate fields, never one line), reused rather than reinvented.
 *
 * **No map pin, honestly** — same limitation `new-order-page.ts`'s own address
 * pane already states: the coordinate fields default to the revealed
 * address's own lat/lon and are editable as plain numbers, because no map
 * component exists in this console yet to place a pin.
 */
@Component({
  selector: 'q-order-change-address-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-change-address-dialog.html',
  styleUrl: './order-change-address-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderChangeAddressDialog {
  readonly initial = input<OrderAddressReveal | null>(null);
  readonly initialRecipientName = input('');
  readonly initialRecipientPhone = input('');
  readonly busy = input(false);

  readonly confirm = output<AddressSubmission>();
  readonly dismiss = output<void>();

  protected readonly draft = signal<AddressDraft>(draftFrom(this.initial()));
  protected readonly recipientName = signal(this.initialRecipientName());
  protected readonly recipientPhone = signal(this.initialRecipientPhone());
  private lastSeededReveal: OrderAddressReveal | null | undefined = undefined;
  private lastSeededName = this.initialRecipientName();
  private lastSeededPhone = this.initialRecipientPhone();

  protected readonly canSubmit = computed(() => {
    const d = this.draft();
    return (
      d.line1.trim() !== '' &&
      d.city.trim() !== '' &&
      Number.isFinite(Number(d.latitude)) &&
      Number.isFinite(Number(d.longitude)) &&
      this.recipientName().trim() !== '' &&
      this.recipientPhone().trim() !== ''
    );
  });

  constructor() {
    effect(() => {
      const reveal = this.initial();
      if (reveal !== this.lastSeededReveal) {
        this.lastSeededReveal = reveal;
        this.draft.set(draftFrom(reveal));
      }
      const name = this.initialRecipientName();
      if (name !== this.lastSeededName) {
        this.lastSeededName = name;
        this.recipientName.set(name);
      }
      const phone = this.initialRecipientPhone();
      if (phone !== this.lastSeededPhone) {
        this.lastSeededPhone = phone;
        this.recipientPhone.set(phone);
      }
    });
  }

  protected setField(field: keyof AddressDraft, value: string): void {
    this.draft.update((current) => ({ ...current, [field]: value }));
  }

  protected setRecipientName(value: string): void {
    this.recipientName.set(value.slice(0, 255));
  }

  protected setRecipientPhone(value: string): void {
    this.recipientPhone.set(value.slice(0, 32));
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    const d = this.draft();
    this.confirm.emit({
      line1: d.line1.trim(),
      line2: d.line2.trim(),
      city: d.city.trim(),
      district: d.district.trim(),
      postalCode: d.postalCode.trim(),
      entrance: d.entrance.trim(),
      floor: d.floor.trim(),
      apartment: d.apartment.trim(),
      landmark: d.landmark.trim(),
      latitude: Number(d.latitude),
      longitude: Number(d.longitude),
      deliveryInstructions: d.deliveryInstructions.trim(),
      recipientName: this.recipientName().trim(),
      recipientPhone: this.recipientPhone().trim(),
    });
  }
}
