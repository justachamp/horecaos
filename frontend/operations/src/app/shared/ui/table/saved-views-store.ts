import { Injectable } from '@angular/core';

/** A named, reusable filter preset — X.18's "saved views". */
export interface SavedView<F> {
  readonly id: string;
  readonly name: string;
  readonly filters: F;
}

/**
 * Named filter presets for one screen, alongside — not instead of — the
 * screen's own live, persisted filters (`TableFilterStore`). A saved view is
 * something an operator names and returns to ("моя смена", "просрочено");
 * the live filters are just whatever they left the screen showing.
 */
@Injectable({ providedIn: 'root' })
export class SavedViewsStore {
  list<F>(viewId: string): readonly SavedView<F>[] {
    try {
      const raw = window.localStorage.getItem(this.key(viewId));
      return raw === null ? [] : (JSON.parse(raw) as readonly SavedView<F>[]);
    } catch {
      return [];
    }
  }

  save<F>(viewId: string, name: string, filters: F): SavedView<F> {
    const view: SavedView<F> = { id: crypto.randomUUID(), name, filters };
    const next = [...this.list<F>(viewId), view];
    this.persist(viewId, next);
    return view;
  }

  remove(viewId: string, id: string): void {
    this.persist(
      viewId,
      this.list(viewId).filter((view) => view.id !== id),
    );
  }

  private persist<F>(viewId: string, views: readonly SavedView<F>[]): void {
    try {
      window.localStorage.setItem(this.key(viewId), JSON.stringify(views));
    } catch {
      // Best-effort, matching TableFilterStore.
    }
  }

  private key(viewId: string): string {
    return `q-data-table.views.${viewId}`;
  }
}
