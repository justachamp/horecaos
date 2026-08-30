import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, from, of } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap, tap, catchError } from 'rxjs/operators';
import { YaMapComponent, YaPlacemarkDirective } from 'angular8-yandex-maps';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { GeocodingService, type GeocodeSuggestion as ReverseGeocodeSuggestion } from '../../../services/geocoding.service';

export type GeocodeSuggestion = ReverseGeocodeSuggestion;

const DEFAULT_CENTER = [41.2995, 69.2401] as [number, number]; // Tashkent [lat, lng]

@Component({
  selector: 'app-locations-add',
  standalone: true,
  imports: [CommonModule, FormsModule, YaMapComponent, YaPlacemarkDirective, TranslatePipe, BackDirective],
  templateUrl: './locations-add.component.html',
  styleUrl: './locations-add.component.scss',
})
export class LocationsAddComponent implements OnInit, OnDestroy {

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
  suggestions = signal<GeocodeSuggestion[]>([]);
  searchLoading = signal(false);
  showDropdown = signal(false);

  private searchSubject = new Subject<string>();
  private searchSub: { unsubscribe: () => void } | null = null;
  private searchId = 0;

  constructor(
    private geocoding: GeocodingService,
    private router: Router
  ) {
    this.searchSub = this.searchSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const id = ++this.searchId;
          this.searchLoading.set(true);
          return from(this.geocoding.search(q)).pipe(
            tap((items: ReverseGeocodeSuggestion[]) => {
              if (id === this.searchId) {
                this.searchLoading.set(false);
                this.suggestions.set(items);
                this.showDropdown.set(items.length > 0);
              }
            }),
            catchError(() => {
              if (id === this.searchId) {
                this.searchLoading.set(false);
                this.suggestions.set([]);
                this.showDropdown.set(false);
              }
              return of([] as ReverseGeocodeSuggestion[]);
            })
          );
        })
      )
      .subscribe();
  }

  private static readonly GEO_OPTIONS: PositionOptions = {
    enableHighAccuracy: true,
    timeout: 10000,
    maximumAge: 0,
  };

  ngOnInit(): void {
    this.checkPermission();
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
      LocationsAddComponent.GEO_OPTIONS
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
      LocationsAddComponent.GEO_OPTIONS
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
      LocationsAddComponent.GEO_OPTIONS
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
    if (value.trim().length >= 2) {
      this.searchSubject.next(value);
    } else {
      this.suggestions.set([]);
      this.showDropdown.set(false);
    }
  }

  selectSuggestion(s: GeocodeSuggestion): void {
    this.searchQuery.set(s.address);
    this.suggestions.set([]);
    this.showDropdown.set(false);
    this.latitude.set(s.lat);
    this.longitude.set(s.lng);
    this.address.set(s.address);
    // Animate map pan to selected location
    if (this.mapInstance) {
      this.mapInstance.panTo([s.lat, s.lng], { duration: 300 });
    }
  }

  closeDropdown(): void {
    this.showDropdown.set(false);
  }

  goToSave(): void {
    const lat = this.latitude();
    const lng = this.longitude();
    const address = this.address() || '';
    const newLocation = { lat, lng, address };
    sessionStorage.setItem('new-location', JSON.stringify(newLocation));
    this.router.navigate(['/locations/save'], {
      state: { address }
    });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
    this.mapInstance = null;
  }
}
