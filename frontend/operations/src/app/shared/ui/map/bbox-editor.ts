import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  BoundsProblem,
  boundsOf,
  boundsProblems,
  isLatitude,
  isLongitude,
  parseCoordinate,
} from './geometry';
import { MapCanvas } from './map-canvas';
import { LatLng, MapBounds, MapHandle, RectangleHandle } from './map-provider';

type Field = 'swLat' | 'swLon' | 'neLat' | 'neLon';

/**
 * Sets a region's south-west / north-east box (ADR 0145, row `X.4`: `BoundingBoxEditor`; ADR 0037).
 *
 * The box is what constrains the geocoder and what a zone activation is checked against, so a
 * wrong one is not a cosmetic mistake: an inverted box accepts nothing or everything and fails
 * silently in both directions, which is exactly what `ck_region_bbox_oriented` exists to refuse.
 * Today the box is "editable as four numbers" (gap map row `3.6b`); this keeps those four numbers,
 * unchanged, and adds the rectangle on a map beside them, so an operator can see what box they
 * are about to constrain every address search to.
 *
 * **The four numbers are the primary control and the map is the second view of them.** A drag of
 * the rectangle writes the numbers and a typed number moves the rectangle; with no map (no
 * provider, or no tiles) nothing about the editor changes except that the picture is missing.
 * With no box yet, two clicks on the map are the two opposite corners.
 *
 * Fully controlled, like the other editors: {@link bounds} in, {@link boundsChange} out, and
 * only a box that is in range and the right way round is ever emitted. What is wrong is said, in
 * the words the database's own checks use, and the previous good box stays in force.
 */
@Component({
  selector: 'q-bbox-editor',
  imports: [MapCanvas, TPipe],
  templateUrl: './bbox-editor.html',
  styleUrl: './bbox-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoundingBoxEditor {
  readonly bounds = input<MapBounds | null>(null);
  /** Where the map opens when there is no box. */
  readonly center = input.required<LatLng>();
  readonly editable = input(true, { transform: booleanAttribute });

  /** Only ever a box in range and oriented south-west then north-east. */
  readonly boundsChange = output<MapBounds>();

  protected readonly fit = signal<MapBounds | null>(null);
  protected readonly texts = signal<Record<Field, string>>({
    swLat: '',
    swLon: '',
    neLat: '',
    neLon: '',
  });
  protected readonly problems = signal<readonly BoundsProblem[]>([]);
  protected readonly notNumbers = signal(false);
  /** The first of two clicks, while there is no box to drag. */
  protected readonly firstCorner = signal<LatLng | null>(null);

  protected readonly problemKeys = computed<readonly { problem: BoundsProblem; key: MessageKey }[]>(
    () => this.problems().map((problem) => ({ problem, key: PROBLEM_KEYS[problem] })),
  );

  private readonly handle = signal<MapHandle | null>(null);
  private rectangle: RectangleHandle | null = null;
  private stopEdits: (() => void) | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopEdits?.();
      this.rectangle?.remove();
    });

    // The numbers follow the box unless the person is mid-way through typing a different one:
    // a typed value that already equals the box must not be rewritten under their cursor.
    effect(() => {
      const box = this.bounds();
      if (box === null) {
        return;
      }
      // Untracked: this runs when the box changes, never because a key was pressed.
      const typed = untracked(() => this.typedBounds());
      if (typed === null || !sameBounds(typed, box)) {
        this.texts.set({
          swLat: String(box.southWest.latitude),
          swLon: String(box.southWest.longitude),
          neLat: String(box.northEast.latitude),
          neLon: String(box.northEast.longitude),
        });
        this.problems.set([]);
        this.notNumbers.set(false);
      }
    });

    effect(() => {
      const map = this.handle();
      const box = this.bounds();
      const editable = this.editable();
      if (map === null) {
        return;
      }
      if (box === null) {
        this.stopEdits?.();
        this.rectangle?.remove();
        this.rectangle = null;
        this.stopEdits = null;
        return;
      }
      if (this.rectangle === null) {
        this.rectangle = map.addRectangle({ bounds: box, editable });
        this.stopEdits = this.rectangle.onChanged((dragged) => this.report(dragged));
      } else {
        this.rectangle.setBounds(box);
        this.rectangle.setEditable(editable);
      }
    });
  }

  protected onReady(map: MapHandle): void {
    this.handle.set(map);
    this.fit.set(this.bounds());
  }

  /** With no box yet, two clicks are the two opposite corners. */
  protected onMapClick(at: LatLng): void {
    if (!this.editable() || this.bounds() !== null) {
      return;
    }
    const first = this.firstCorner();
    if (first === null) {
      this.firstCorner.set(at);
      return;
    }
    this.firstCorner.set(null);
    const box = boundsOf([first, at]);
    if (box !== null) {
      this.report(box);
    }
  }

  protected edit(field: Field, text: string): void {
    this.texts.update((current) => ({ ...current, [field]: text }));
    const typed = this.typedBounds();
    const all = Object.values(this.texts());
    if (all.some((value) => parseCoordinate(value) === null)) {
      // Not finished yet. Saying "invalid" about a form being filled in is noise.
      this.problems.set([]);
      this.notNumbers.set(false);
      return;
    }
    if (typed === null) {
      this.notNumbers.set(true);
      return;
    }
    this.notNumbers.set(false);
    const found = boundsProblems(typed);
    this.problems.set(found);
    if (found.length === 0) {
      this.report(typed);
    }
  }

  private report(box: MapBounds): void {
    if (boundsProblems(box).length === 0) {
      this.boundsChange.emit(box);
    }
  }

  private typedBounds(): MapBounds | null {
    const t = this.texts();
    const values = [t.swLat, t.swLon, t.neLat, t.neLon].map(parseCoordinate);
    if (values.some((value) => value === null || Number.isNaN(value))) {
      return null;
    }
    const [swLat, swLon, neLat, neLon] = values as number[];
    if (!isLatitude(swLat) || !isLongitude(swLon) || !isLatitude(neLat) || !isLongitude(neLon)) {
      return null;
    }
    return {
      southWest: { latitude: swLat, longitude: swLon },
      northEast: { latitude: neLat, longitude: neLon },
    };
  }
}

function sameBounds(a: MapBounds, b: MapBounds): boolean {
  return (
    a.southWest.latitude === b.southWest.latitude &&
    a.southWest.longitude === b.southWest.longitude &&
    a.northEast.latitude === b.northEast.latitude &&
    a.northEast.longitude === b.northEast.longitude
  );
}

const PROBLEM_KEYS: Readonly<Record<BoundsProblem, MessageKey>> = {
  OUT_OF_RANGE: 'ui.map.bbox.problem.outOfRange',
  INVERTED: 'ui.map.bbox.problem.inverted',
};
