import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AddressBookService } from '../../../services/address-book.service';
import { TranslateService } from '../../../services/translate.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';

type AddressType = 'home' | 'work' | 'other';

@Component({
  selector: 'app-locations-save',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule, TranslatePipe, BackDirective],
  templateUrl: './locations-save.component.html',
  styleUrl: './locations-save.component.scss',
})
export class LocationsSaveComponent {
  /** Address line from map step (e.g. from navigation state) */
  addressLine = 'Chust ko\'chasi, 1-uy';

  selectedType: AddressType = 'home';

  locationName = '';

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor(
    private router: Router,
    private addressBook: AddressBookService,
    private translate: TranslateService
  ) {
    try {
      const stored = sessionStorage.getItem('new-location');
      if (stored) {
        const data = JSON.parse(stored) as { address?: string; lat?: number; lng?: number };
        if (data.address) {
          this.addressLine = data.address;
        }
      }
    } catch {
      // ignore
    }
  }

  save(): void {
    const name = this.locationName.trim() || 'Manzil';
    let lat: number;
    let lng: number;
    try {
      const stored = sessionStorage.getItem('new-location');
      if (!stored) {
        this.error.set("Manzil topilmadi. Qaytadan xaritadan tanlang.");
        return;
      }
      const data = JSON.parse(stored) as { lat?: number; lng?: number; address?: string };
      lat = data.lat ?? 0;
      lng = data.lng ?? 0;
      this.addressText = data.address ?? '';
    } catch {
      this.error.set("Manzil ma'lumotlari noto'g'ri.");
      return;
    }

    this.error.set(null);
    this.loading.set(true);
    // The label is what the customer typed; the street line is what the geocoder
    // described the pin as. The legacy API took one `name` and used it for both,
    // so a list showed "Uy" with no address under it.
    this.addressBook
      .add({ label: name, line1: this.addressText || null, latitude: lat, longitude: lng })
      .then(() => {
        sessionStorage.removeItem('new-location');
        this.router.navigate(['/locations/list'], { replaceUrl: true }).catch(() => {});
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
  }

  /** The geocoder's description of the dropped pin, carried from the map screen. */
  private addressText = '';
}
