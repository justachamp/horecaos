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
} from '@angular/core';

import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { MapCanvas } from './map-canvas';
import {
  RingProblem,
  boundsOf,
  isLatLng,
  isLatitude,
  isLongitude,
  openRing,
  parseCoordinate,
  ringProblems,
} from './geometry';
import { LatLng, MapBounds, MapHandle, PolygonHandle } from './map-provider';

/** How far a new corner is placed from the last one: about fifty metres, enough to be selectable. */
const NEW_CORNER_OFFSET = 0.0005;

/**
 * Draws and edits a polygon: a delivery zone, or whatever else is an outline (ADR 0145, row
 * `X.4`: `PolygonEditor`). Today a zone is a radius typed into a form, so a real city zone with a
 * river or a ring road in it cannot be drawn at all (gap map row `3.6`).
 *
 * **Fully controlled and open.** The outline comes in through {@link ring} and every edit goes
 * out through {@link ringChange} as the whole new outline, with the last corner not repeated as
 * the first. The host owns saving: this component has no API call, no version and no idea what a
 * zone is, which is what lets the same editor serve a zone, a region outline or a map overlay.
 *
 * **The map is one way to edit and the table is another, and the table is always there.** Corners
 * are listed with their coordinates; each can be typed over or removed, and a corner can be added
 * without a pointer. That is the keyboard equivalent of drawing, and it is what remains when the
 * provider is not configured.
 *
 * **It says what is wrong and does not decide.** {@link problemsChange} reports the same list the
 * screen shows (too few corners, two neighbours the same, the outline crossing itself, a corner
 * outside the region), so the host can disable "save" for them. The server remains the authority:
 * `ServiceZoneService` checks the polygon against the region's box and PostGIS decides validity;
 * these checks only make a person read the problem before a round trip rather than after.
 */
@Component({
  selector: 'q-polygon-editor',
  imports: [MapCanvas, TPipe],
  templateUrl: './polygon-editor.html',
  styleUrl: './polygon-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PolygonEditor {
  /** The outline, open. An empty list is a zone not drawn yet. */
  readonly ring = input<readonly LatLng[]>([]);
  readonly center = input.required<LatLng>();
  /** The region's box: the map opens on it, and a corner outside it is a problem. */
  readonly region = input<MapBounds | null>(null);
  readonly editable = input(true, { transform: booleanAttribute });

  readonly ringChange = output<readonly LatLng[]>();
  /** What is wrong with the outline as it stands, including "too few corners" when it is empty. */
  readonly problemsChange = output<readonly RingProblem[]>();

  protected readonly drawing = signal(false);
  protected readonly fit = signal<MapBounds | null>(null);
  protected readonly rowErrors = signal<ReadonlySet<number>>(new Set());
  private readonly handle = signal<MapHandle | null>(null);

  /** Every problem, including for an empty outline: the host needs it to disable saving. */
  private readonly all = computed(() => ringProblems(this.ring(), this.region()));

  /** Only what a person should be told: an outline not begun is not an error. */
  protected readonly shown = computed<
    readonly { readonly problem: RingProblem; readonly key: MessageKey }[]
  >(() =>
    this.ring().length === 0
      ? []
      : this.all().map((problem) => ({ problem, key: PROBLEM_KEYS[problem] })),
  );

  private polygon: PolygonHandle | null = null;
  private stopEdits: (() => void) | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopEdits?.();
      this.polygon?.remove();
    });

    effect(() => this.problemsChange.emit(this.all()));

    effect(() => {
      const map = this.handle();
      const ring = this.ring();
      const editable = this.editable();
      if (map === null) {
        return;
      }
      if (this.polygon === null) {
        this.polygon = map.addPolygon({ ring, editable });
        this.stopEdits = this.polygon.onChanged((edited) => this.ringChange.emit(edited));
      } else {
        this.polygon.setRing(ring);
        this.polygon.setEditable(editable);
      }
    });
  }

  protected onReady(map: MapHandle): void {
    this.handle.set(map);
    const ring = this.ring();
    this.fit.set(ring.length >= 3 ? boundsOf(ring) : this.region());
  }

  protected toggleDrawing(): void {
    if (this.polygon === null) {
      return;
    }
    if (this.drawing()) {
      this.polygon.stopDrawing();
      this.drawing.set(false);
    } else {
      this.polygon.startDrawing();
      this.drawing.set(true);
    }
  }

  protected clear(): void {
    this.rowErrors.set(new Set());
    this.ringChange.emit([]);
  }

  protected addCorner(): void {
    const ring = openRing(this.ring());
    const last = ring[ring.length - 1];
    const seed = last ?? this.center();
    const next: LatLng = last
      ? {
          latitude: seed.latitude + NEW_CORNER_OFFSET,
          longitude: seed.longitude + NEW_CORNER_OFFSET,
        }
      : seed;
    this.ringChange.emit(isLatLng(next) ? [...ring, next] : ring);
  }

  protected removeCorner(index: number): void {
    this.rowErrors.set(new Set());
    this.ringChange.emit(this.ring().filter((_, i) => i !== index));
  }

  /** Committed on `change` (blur or Enter), so a half-typed number is never reported as a corner. */
  protected editCorner(index: number, axis: 'latitude' | 'longitude', text: string): void {
    const value = parseCoordinate(text);
    const valid = value !== null && (axis === 'latitude' ? isLatitude(value) : isLongitude(value));
    const errors = new Set(this.rowErrors());
    if (!valid) {
      errors.add(index);
      this.rowErrors.set(errors);
      return;
    }
    errors.delete(index);
    this.rowErrors.set(errors);
    this.ringChange.emit(
      this.ring().map((corner, i) => (i === index ? { ...corner, [axis]: value } : corner)),
    );
  }
}

const PROBLEM_KEYS: Readonly<Record<RingProblem, MessageKey>> = {
  TOO_FEW: 'ui.map.polygon.problem.tooFew',
  DUPLICATE: 'ui.map.polygon.problem.duplicate',
  CROSSING: 'ui.map.polygon.problem.crossing',
  OUTSIDE_REGION: 'ui.map.polygon.problem.outsideRegion',
};
