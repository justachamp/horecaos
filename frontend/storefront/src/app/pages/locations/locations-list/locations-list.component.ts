import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AddressBookService, addressLine } from '../../../services/address-book.service';
import type { CustomerAddress } from '../../../core/api/customer-api';
import { TranslateService } from '../../../services/translate.service';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { NavigationHistoryService } from '../../../services/navigation-history.service';

export interface DeliveryLocation {
  id: string;
  name: string;
  address: string;
  isDefault: boolean;
}

function addressToDeliveryLocation(addr: CustomerAddress): DeliveryLocation {
  return {
    id: addr.addressId,
    name: addr.label ?? 'Manzil',
    address: addressLine(addr),
    // The platform has no default address. Which address an order goes to is a
    // property of the cart's destination, not of the address book, so this list
    // marks a selection rather than reporting a stored flag.
    isDefault: false,
  };
}

@Component({
  selector: 'app-locations-list',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe],
  templateUrl: './locations-list.component.html',
  styleUrl: './locations-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationsListComponent implements OnInit {
  private readonly history = inject(NavigationHistoryService);
  private readonly router = inject(Router);
  private readonly addressBook = inject(AddressBookService);
  private readonly translate = inject(TranslateService);
  private readonly delivery = inject(DeliverySelectionService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly locations = signal<DeliveryLocation[]>([]);
  readonly editMode = signal(false);
  readonly updatingId = signal<string | null>(null);
  readonly editingId = signal<string | null>(null);
  readonly editingName = signal('');
  readonly pendingDelete = signal<DeliveryLocation | null>(null);
  selectedId: string | null = null;

  toggleEditMode(): void {
    this.editMode.update((v) => !v);
    if (this.editMode()) return;
    this.editingId.set(null);
  }

  /**
   * Picks the address this session is delivering to.
   *
   * Local, and no longer a write. The legacy API had `is_default` on the address
   * and this called a PUT to set it; the platform has no such field, because
   * where an order goes is decided by the cart's destination
   * (`PUT /carts/{id}/destination`) and not by a flag on the address book. A
   * selection stored on the server that checkout then ignored would show one
   * address here and deliver to another.
   */
  onSelectLocation(loc: DeliveryLocation): void {
    if (this.editMode()) return;
    this.selectedId = loc.id;
    // Recorded here rather than written to the server: the platform has no
    // default address, and where an order goes is set on the cart at checkout.
    this.delivery.choose(loc.id);
  }

  private loadAddresses(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    this.error.set(null);
    this.addressBook.list().then(
      (data) => {
        if (showLoading) this.loading.set(false);
        const items = data.map(addressToDeliveryLocation);
        this.locations.set(items);
        // No stored default to restore, so the first is preselected and the
        // customer's own pick wins for the rest of the session.
        this.selectedId =
          items.find((l) => l.id === this.delivery.addressId())?.id ?? items[0]?.id ?? null;
        if (this.selectedId) {
          this.delivery.choose(this.selectedId);
        }
        if (items.length === 0) {
          this.editMode.set(false);
          this.editingId.set(null);
        }
      },
      () => {
        if (showLoading) this.loading.set(false);
        this.error.set(this.translate.get('errors.generic'));
      },
    );
  }

  ngOnInit(): void {
    this.loadAddresses(true);
  }

  close(): void {
    this.history.back('/home');
  }

  addLocation(): void {
    this.router.navigate(['/locations/add']);
  }

  selectLocation(): void {
    if (!this.selectedId) return;
    this.router.navigate(['/home']);
  }

  editLocation(loc: DeliveryLocation): void {
    this.editingId.set(loc.id);
    this.editingName.set(loc.name);
  }

  /**
   * Renames one address.
   *
   * A full replace, because the platform has no partial form: the lines live in
   * one encrypted blob and the point and its provenance are one fact under one
   * constraint. The line and the coordinate are therefore carried through
   * unchanged from what was read, which is why the row being edited is looked up
   * rather than rebuilt from the display model.
   */
  saveLocationEdit(loc: DeliveryLocation): void {
    const name = this.editingName().trim() || loc.name;
    if (this.updatingId()) return;
    this.error.set(null);
    this.updatingId.set(loc.id);
    this.addressBook
      .list()
      .then((all) => {
        const existing = all.find((address) => address.addressId === loc.id);
        if (!existing) {
          throw new Error('That address is no longer in the list.');
        }
        return this.addressBook.replace(loc.id, {
          label: name,
          line1: existing.fields.line1 ?? null,
          latitude: existing.latitude,
          longitude: existing.longitude,
          deliveryInstructions: existing.deliveryInstructions,
        });
      })
      .then(() => {
        this.editingId.set(null);
        this.loadAddresses();
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.updatingId.set(null));
  }

  deleteLocation(loc: DeliveryLocation): void {
    this.pendingDelete.set(loc);
  }

  cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  confirmDelete(): void {
    const loc = this.pendingDelete();
    if (!loc) return;
    this.error.set(null);
    // Archives rather than erases. An order already on its way keeps its own
    // copy of where it is going, so removing this row cannot redirect it.
    this.addressBook
      .remove(loc.id)
      .then(() => this.loadAddresses())
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.pendingDelete.set(null));
  }
}
