import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, from, of } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap, tap, catchError } from 'rxjs/operators';
import { YaMapComponent, YaPlacemarkDirective } from 'angular8-yandex-maps';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { GeocodingService, type AddressSuggestion } from '../../../services/geocoding.service';
import { MapConfigService, canLookUp, hasTiles } from '../../../services/map-config.service';

const DEFAULT_CENTER = [41.2995, 69.2401] as [number, number]; // Tashkent [lat, lng]

/**
 * Adding an address: a pin on a map with the address it names, or, when the platform has no map to
 * offer, the address typed in full (ADR 0145 decision 6).
 *
 * **The browser asks the platform, never a geocoder vendor.** The map's browser key, the type-ahead
 * and the reverse lookup all come from the platform (`MapConfigService`, `GeocodingService`), which
 * applies the region's box, rate limits and meters them. What the customer *confirms* is the pin:
 * the saved row is `CUSTOMER_PIN`, never `GEOCODER` (the server refuses it from this surface).
 *
 * **Two modes, decided once from `map-config`.** With tiles on offer the screen is what it always
 * was: the customer's position, a draggable pin, the address under it. With no provider (or no
 * browser key) there is no map to confirm anything on, so the screen is a text field and a
 * continue button, the address saves with no coordinates and is `NOT_GEOCODED` (the restaurant
 * confirms it), and a suggestion that is chosen only fills the text. A lookup that fails while the
 * map works (provider down, customer told to slow down) leaves the search field as plain text and
 * the pin flow untouched.
 */
@Component({
  selector: 'app-locations-add',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    YaMapComponent,
    YaPlacemarkDirective,
    TranslatePipe,
    BackDirective,
  ],
  templateUrl: './locations-add.component.html',
  styleUrl: './locations-add.component.scss',
})
export class LocationsAddComponent implements OnInit, OnDestroy {
  /** `checking` until `map-config` has answered; `map` when tiles are on offer; `text` when they are not. */
  mode = signal<'checking' | 'map' | 'text'>('checking');
  /** The platform has lookups on offer at all (a provider is set up with at least one lookup feature). */
  lookupsConfigured = signal(false);
  /** The last type-ahead could not be answered: the field is plain text until one can. */
  suggestionsUnavailable = signal(false);
  /** Permission state: 'prompt' | 'granted' | 'denied' | 'checking' */
  permissionState = signal<'checking' | 'prompt' | 'granted' | 'denied'>('checking');
  /** Loading map or geocoding */
  loading = signal(true);
  /** Geocoding in progress (e.g. after drag) */
  resolvingAddress = signal(false);
  /** Fetching user location in progress */
  gettingLocation = signal(false);
  latitude = signal<number | null>(null);
  longitude = signal<number | null>(null);
  address = signal<string>('');

  /** Human-readable coords for UI */
  coordsText = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    if (lat == null || lng == null) return '';
    return `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
  });

  /** Map center [lat, lng] for ya-map */
  mapCenter = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    return [lat ?? DEFAULT_CENTER[0], lng ?? DEFAULT_CENTER[1]] as [number, number];
  });

  mapState: ymaps.IMapState = { controls: ['zoomControl', 'geolocationControl'] };
  placemarkOptions = { draggable: true };

  private mapInstance: ymaps.Map | null = null;
  searchQuery = signal('');
  suggestions = signal<AddressSuggestion[]>([]);
  searchLoading = signal(false);
  showDropdown = signal(false);

  private searchSubject = new Subject<string>();
  private searchSub: { unsubscribe: () => void } | null = null;
  private searchId = 0;

  constructor(
    private geocoding: GeocodingService,
    private mapConfig: MapConfigService,
    private router: Router,
  ) {
    this.searchSub = this.searchSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const id = ++this.searchId;
          this.searchLoading.set(true);
          return from(this.geocoding.suggest(q)).pipe(
            tap((lookup) => {
              if (id === this.searchId) {
                this.searchLoading.set(false);
                this.suggestionsUnavailable.set(!lookup.available);
                this.suggestions.set([...lookup.items]);
                this.showDropdown.set(lookup.items.length > 0);
              }
            }),
            catchError(() => {
              if (id === this.searchId) {
                this.searchLoading.set(false);
                this.suggestionsUnavailable.set(true);
                this.suggestions.set([]);
                this.showDropdown.set(false);
              }
              return of(null);
            }),
          );
        }),
      )
      .subscribe();
  }

  private static readonly GEO_OPTIONS: PositionOptions = {
    enableHighAccuracy: true,
    timeout: 10000,
    maximumAge: 0,
  };

  ngOnInit(): void {
    void this.start();
  }

  /** Asks the platform what the map is, once, and chooses the mode the rest of the screen runs in. */
  private async start(): Promise<void> {
    const config = await this.mapConfig.ensureLoaded();
    this.lookupsConfigured.set(canLookUp(config));
    if (hasTiles(config)) {
      this.mode.set('map');
      this.checkPermission();
    } else {
      // No map to confirm a pin on: the typed address is the answer, and it saves NOT_GEOCODED.
      this.mode.set('text');
      this.loading.set(false);
    }
  }

  private checkPermission(): void {
    if (!navigator.geolocation) {
      this.onLocationDenied();
      return;
    }
    const perm = navigator.permissions;
    if (perm?.query) {
      perm
        .query({ name: 'geolocation' })
        .then((result: PermissionStatus) => {
          const state = result.state as 'granted' | 'denied' | 'prompt';
          this.permissionState.set(state);
          result.onchange = () => {
            const next = result.state as 'granted' | 'denied' | 'prompt';
            this.permissionState.set(next);
            if (next === 'granted') {
              this.requestLocationAccess();
            }
          };
          if (state === 'denied') {
            this.onLocationDenied();
            return;
          }
          this.initMapWithPosition();
        })
        .catch(() => {
          this.permissionState.set('prompt');
          this.initMapWithPosition();
        });
    } else {
      this.permissionState.set('prompt');
      this.initMapWithPosition();
    }
  }

  private onLocationDenied(): void {
    this.permissionState.set('denied');
    this.loading.set(false);
    this.gettingLocation.set(false);
    this.mapInstance = null;
  }

  private initMapWithPosition(): void {
    if (!navigator.geolocation) {
      this.onLocationDenied();
      return;
    }
    this.loading.set(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        this.permissionState.set('granted');
        this.initMap(pos.coords.latitude, pos.coords.longitude);
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          this.onLocationDenied();
          return;
        }
        // Allowed but GPS timed out / unavailable — still show map at default center.
        this.permissionState.set('granted');
        this.initMap(DEFAULT_CENTER[0], DEFAULT_CENTER[1]);
      },
      LocationsAddComponent.GEO_OPTIONS,
    );
  }

  private initMap(lat: number, lng: number): void {
    this.loading.set(true);
    this.latitude.set(lat);
    this.longitude.set(lng);
    this.resolveAddress(lat, lng, () => this.loading.set(false));
  }

  /** Re-prompt the browser for geolocation access (user-initiated). */
  requestLocationAccess(event?: Event): void {
    event?.stopPropagation();
    if (!navigator.geolocation) {
      this.onLocationDenied();
      return;
    }
    this.gettingLocation.set(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        this.gettingLocation.set(false);
        this.permissionState.set('granted');
        this.initMap(pos.coords.latitude, pos.coords.longitude);
      },
      (err) => {
        this.gettingLocation.set(false);
        if (err.code === err.PERMISSION_DENIED) {
          this.onLocationDenied();
        }
      },
      LocationsAddComponent.GEO_OPTIONS,
    );
  }

  goToMyLocation(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.permissionState() === 'denied') {
      this.requestLocationAccess();
      return;
    }
    if (!navigator.geolocation) {
      this.onLocationDenied();
      return;
    }
    this.gettingLocation.set(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const lat = pos.coords.latitude;
        const lng = pos.coords.longitude;
        this.permissionState.set('granted');
        this.latitude.set(lat);
        this.longitude.set(lng);
        this.updateAddressFromCoords(lat, lng);
        if (this.mapInstance) {
          this.mapInstance.panTo([lat, lng], { duration: 300 });
        }
        this.gettingLocation.set(false);
      },
      (err) => {
        this.gettingLocation.set(false);
        if (err.code === err.PERMISSION_DENIED) {
          this.permissionState.set('denied');
        }
      },
      LocationsAddComponent.GEO_OPTIONS,
    );
  }

  onMapReady(event: { target: ymaps.Map }): void {
    this.mapInstance = event.target;
  }

  onMapClick(event: { event: ymaps.Event }): void {
    const coords = event.event.get('coords');
    if (!coords || !Array.isArray(coords)) return;
    const [lat, lng] = coords;
    this.latitude.set(lat);
    this.longitude.set(lng);
    this.updateAddressFromCoords(lat, lng);
    // Smooth pan to center on clicked location
    if (this.mapInstance) {
      this.mapInstance.panTo([lat, lng], { duration: 250 });
    }
  }

  onPlacemarkDragEnd(event: { target: ymaps.Placemark }): void {
    const coords = event.target.geometry?.getCoordinates();
    if (!coords) return;
    const newLat = coords[0];
    const newLng = coords[1];
    this.latitude.set(newLat);
    this.longitude.set(newLng);
    this.updateAddressFromCoords(newLat, newLng);
  }

  private updateAddressFromCoords(lat: number, lng: number): void {
    this.resolvingAddress.set(true);
    const fallback = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
    this.geocoding
      .describe(lat, lng)
      .then((addr) => this.address.set(addr || fallback))
      .catch(() => this.address.set(fallback))
      .finally(() => this.resolvingAddress.set(false));
  }

  private resolveAddress(lat: number, lng: number, onComplete?: () => void): void {
    this.resolvingAddress.set(true);
    const fallback = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
    this.geocoding
      .describe(lat, lng)
      .then((addr) => this.address.set(addr || fallback))
      .catch(() => this.address.set(fallback))
      .finally(() => {
        this.resolvingAddress.set(false);
        onComplete?.();
      });
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
    // Nothing is asked of a platform that has said it has no lookups; the field is plain text.
    if (this.lookupsConfigured() && value.trim().length >= 2) {
      this.searchSubject.next(value);
    } else {
      this.suggestions.set([]);
      this.showDropdown.set(false);
    }
  }

  selectSuggestion(s: AddressSuggestion): void {
    this.searchQuery.set(s.text);
    this.suggestions.set([]);
    this.showDropdown.set(false);
    if (this.mode() !== 'map') {
      // No map: the line is the address, and nothing is placed that nobody could confirm.
      this.address.set(s.text);
      return;
    }
    // A suggestion has no point; choosing it asks the platform where it is, then the customer
    // confirms that on the map by looking at it (and may drag the pin).
    this.resolvingAddress.set(true);
    this.geocoding
      .resolve(s.text)
      .then((lookup) => {
        const place = lookup.items[0];
        if (!place) {
          this.address.set(s.text);
          return;
        }
        this.latitude.set(place.lat);
        this.longitude.set(place.lng);
        this.address.set(place.address);
        this.mapInstance?.panTo([place.lat, place.lng], { duration: 300 });
      })
      .catch(() => this.address.set(s.text))
      .finally(() => this.resolvingAddress.set(false));
  }

  closeDropdown(): void {
    this.showDropdown.set(false);
  }

  /** Whether Continue may be pressed: a pin is always there in map mode; text mode needs an address typed. */
  canContinue(): boolean {
    return this.mode() === 'map' || this.searchQuery().trim().length >= 3;
  }

  goToSave(): void {
    if (!this.canContinue()) {
      return;
    }
    const typed = this.searchQuery().trim();
    const map = this.mode() === 'map';
    // No map, no point: a coordinate the customer never saw on a map is not one they confirmed.
    const lat = map ? this.latitude() : null;
    const lng = map ? this.longitude() : null;
    // The typed line is the address when there is no map, and also when the type-ahead could not
    // answer: the customer wrote an address and dropped a pin, and the pin's reverse lookup is not.
    const address =
      !map || (this.suggestionsUnavailable() && typed.length > 0) ? typed : this.address() || '';
    const newLocation = { lat, lng, address };
    sessionStorage.setItem('new-location', JSON.stringify(newLocation));
    this.router.navigate(['/locations/save'], {
      state: { address },
    });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
    this.mapInstance = null;
  }
}
