import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InlineAlert } from '../inline-alert';
import {
  LatLng,
  MAP_PROVIDER,
  MapBounds,
  MapHandle,
  MapUnavailableError,
  MapUnavailableReason,
  PinHandle,
  PolygonHandle,
} from './map-provider';

/** A read-only pin, for a position that is shown and never edited: a courier, an order, a branch. */
export interface MapMarker {
  /** Stable across updates, so a moving courier is one pin moving and not a pin removed and added. */
  readonly id: string;
  readonly position: LatLng;
  /** Already translated, or tenant data. */
  readonly label?: string;
}

/** A read-only outline: a delivery zone on the live map, or a zone in the density view. */
export interface MapArea {
  readonly id: string;
  readonly ring: readonly LatLng[];
  /**
   * 0 to 1, for a view where the fill carries a number (the order-density view, row `7.10`). Left
   * out, the area is a plain outline. The scale is the screen's to choose; the canvas only draws it.
   */
  readonly intensity?: number | null;
  /** Already translated, or tenant data; shown when the area is hovered. */
  readonly label?: string | null;
}

/**
 * The map surface (ADR 0145 decision 1, row `X.4`): one place that asks the provider-neutral
 * {@link MapProvider} for a map and tells a screen honestly when it cannot have one.
 *
 * **Its three states are all real and none is hidden.** `loading` while the vendor's script is
 * fetched; `ready` once a map exists; `unavailable` when there is nothing to draw, with the
 * reason as a sentence. The reason matters because the fixes are different: no provider set up
 * (the owner has not obtained a key), no tiles in this environment (address search only), or the
 * vendor's origin could not be reached (try again). An empty grey rectangle says none of them and
 * invites an operator to wonder whether the data is missing.
 *
 * **Nothing here is needed to do the job.** Every component built on this one also shows the
 * coordinates as plain fields, so a provider outage never blanks a screen that merely contains a
 * map, and a person who cannot use a pointer can still place a pin (ADR 0145, negative
 * consequences; the accessibility rule that a drag must have a keyboard equivalent).
 *
 * It owns only the surface and two kinds of display-only overlay, {@link markers} and
 * {@link areas}, which cover the live courier map, today's orders and the zone-density view.
 * Anything a person edits is an editor's business (`q-map-pin`, `q-polygon-editor`,
 * `q-bbox-editor`), reached through {@link ready}, which hands over the live {@link MapHandle}.
 */
@Component({
  selector: 'q-map-canvas',
  imports: [TPipe, InlineAlert],
  templateUrl: './map-canvas.html',
  styleUrl: './map-canvas.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapCanvas {
  private readonly provider = inject(MAP_PROVIDER);
  private readonly destroyRef = inject(DestroyRef);

  /** Where the map opens. Read once; use {@link fitTo} or {@link recenter} afterwards. */
  readonly center = input.required<LatLng>();
  readonly zoom = input(12);
  /** Fitted when it first has a value and again whenever it changes. */
  readonly fitTo = input<MapBounds | null>(null);
  readonly markers = input<readonly MapMarker[]>([]);
  readonly areas = input<readonly MapArea[]>([]);
  /** The accessible name of the map region. */
  readonly label = input<string | null>(null);
  readonly height = input(360);

  /** The live map, once it exists. Editors add their overlays to it. */
  readonly ready = output<MapHandle>();
  readonly unavailable = output<MapUnavailableReason>();
  /** A click on empty map, in coordinates. */
  readonly mapClick = output<LatLng>();

  protected readonly surface = viewChild.required<ElementRef<HTMLElement>>('surface');

  protected readonly state = signal<'loading' | 'ready' | 'unavailable'>('loading');
  protected readonly reason = signal<MapUnavailableReason | null>(null);
  private readonly handle = signal<MapHandle | null>(null);

  protected readonly unavailableKey = computed<MessageKey>(() => {
    switch (this.reason()) {
      case 'NO_TILES':
        return 'ui.map.unavailable.noTiles';
      case 'LOAD_FAILED':
        return 'ui.map.unavailable.loadFailed';
      default:
        return 'ui.map.unavailable.notConfigured';
    }
  });

  /** Only a failed load is worth retrying; "not configured" and "no tiles" are facts about the environment. */
  protected readonly canRetry = computed(() => this.reason() === 'LOAD_FAILED');

  private attempt = 0;
  private destroyed = false;
  private stopClicks: (() => void) | null = null;
  private readonly pins = new Map<string, PinHandle>();
  private readonly outlines = new Map<string, PolygonHandle>();

  constructor() {
    afterNextRender(() => void this.start());

    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stopClicks?.();
      this.handle()?.destroy();
    });

    effect(() => {
      const map = this.handle();
      const box = this.fitTo();
      if (map && box) {
        map.fitBounds(box);
      }
    });

    effect(() => {
      const map = this.handle();
      if (map) {
        this.syncMarkers(map, this.markers());
      }
    });

    effect(() => {
      const map = this.handle();
      if (map) {
        this.syncAreas(map, this.areas());
      }
    });
  }

  /** Moves the viewport without touching the overlays. */
  recenter(center: LatLng, zoom?: number): void {
    this.handle()?.setCenter(center, zoom);
  }

  protected retry(): void {
    void this.start();
  }

  private async start(): Promise<void> {
    const attempt = ++this.attempt;
    this.state.set('loading');
    this.reason.set(null);
    try {
      await this.provider.load();
      if (this.destroyed || attempt !== this.attempt) {
        return;
      }
      const map = this.provider.createMap(this.surface().nativeElement, {
        center: this.center(),
        zoom: this.zoom(),
      });
      this.stopClicks = map.onClick((position) => this.mapClick.emit(position));
      this.handle.set(map);
      this.state.set('ready');
      this.ready.emit(map);
    } catch (failure) {
      if (this.destroyed || attempt !== this.attempt) {
        return;
      }
      const reason: MapUnavailableReason =
        failure instanceof MapUnavailableError ? failure.reason : 'LOAD_FAILED';
      this.reason.set(reason);
      this.state.set('unavailable');
      this.unavailable.emit(reason);
    }
  }

  /** Keyed, so a courier that moved is a pin that moved, not a hundred pins torn down and rebuilt. */
  private syncMarkers(map: MapHandle, markers: readonly MapMarker[]): void {
    const wanted = new Set(markers.map((marker) => marker.id));
    for (const [id, pin] of this.pins) {
      if (!wanted.has(id)) {
        pin.remove();
        this.pins.delete(id);
      }
    }
    for (const marker of markers) {
      const existing = this.pins.get(marker.id);
      if (existing) {
        existing.setPosition(marker.position);
      } else {
        this.pins.set(
          marker.id,
          map.addPin({ position: marker.position, draggable: false, label: marker.label }),
        );
      }
    }
  }

  private syncAreas(map: MapHandle, areas: readonly MapArea[]): void {
    const wanted = new Set(areas.map((area) => area.id));
    for (const [id, outline] of this.outlines) {
      if (!wanted.has(id)) {
        outline.remove();
        this.outlines.delete(id);
      }
    }
    for (const area of areas) {
      const existing = this.outlines.get(area.id);
      if (existing) {
        existing.setRing(area.ring);
        existing.setLook({ intensity: area.intensity ?? null, label: area.label ?? null });
      } else {
        this.outlines.set(
          area.id,
          map.addPolygon({
            ring: area.ring,
            editable: false,
            intensity: area.intensity,
            label: area.label,
          }),
        );
      }
    }
  }
}
