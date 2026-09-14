import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TableToken, TableTokenPosition, TableTokenView } from '../table-token/table-token';

export interface TableMovedEvent {
  readonly tableId: string;
  readonly layoutX: number;
  readonly layoutY: number;
}

/**
 * `X.36` FloorPlanCanvas — the room a host cannot see today, per the gap
 * map's own line for this row: "section layout and table adjacency are
 * invisible, so 'can I fit a party of six at 20:00' is answered from a grid
 * of names". Wave P38 builds this over Settings → Locations' floor-plan
 * tab; the reservations day view (1.5) this row is ultimately meant for is
 * a later wave's own screen.
 *
 * **A drawing surface, not a map.** `layoutX`/`layoutY` (`dinein.tables`)
 * carry no unit and no real-world bound — `FloorPlanController`'s own doc
 * on the new `PUT .../tables/{tableId}` says so — so this canvas is a fixed
 * pixel-space div a table token is dragged around inside, clamped to stay
 * on it, and nothing here claims to be to scale.
 *
 * **Knows nothing about persistence.** `(tableMoved)` fires with a table's
 * proposed new position; saving it (the API call, the `If-Match` version,
 * reverting on a stale-version refusal) is the host's job — this component
 * only renders `tables()` and reports drags, the same separation
 * `q-drag-drop-assign` draws between "the drop happened" and "the server
 * accepted it".
 */
@Component({
  selector: 'q-floor-plan-canvas',
  imports: [TableToken],
  templateUrl: './floor-plan-canvas.html',
  styleUrl: './floor-plan-canvas.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FloorPlanCanvas {
  readonly tables = input.required<readonly TableTokenView[]>();
  readonly disabled = input(false);
  readonly width = input(900);
  readonly height = input(560);

  readonly tableMoved = output<TableMovedEvent>();
  readonly tableSelected = output<string>();

  protected onPositionChange(tableId: string, position: TableTokenPosition): void {
    const clampedX = Math.max(0, Math.min(this.width() - 56, position.x));
    const clampedY = Math.max(0, Math.min(this.height() - 56, position.y));
    this.tableMoved.emit({ tableId, layoutX: clampedX, layoutY: clampedY });
  }
}
