import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LabelsByLocale, LocaleSetView } from '../../../core/i18n/locale-labels';

/**
 * `CommentPresetController` — control-plane, not the operations prefix, the
 * same pre-existing mismatch `catalog-paths.ts` documents at length for
 * every other catalog authoring endpoint this console calls. Kept as its
 * own small constant here rather than reusing `catalogPaths` because these
 * endpoints are `TENANT`-scoped (no `brandId`), a different shape from
 * `catalogPaths`' own `BrandScope`.
 */
const CONTROL_PLANE = '/api/v1/control-plane';

/** Mirrors `CommentPresetController.PresetResponse` (row 2.1b). */
export interface PresetResponse {
  readonly presetId: string;
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  /**
   * Every language the preset has wording in (row 10.12): the platform triple
   * from its columns, then any other from the per-locale table, each once.
   * A screen reads this, not the three fields above, which stay for callers
   * that predate it.
   */
  readonly labels: LabelsByLocale;
  readonly posModifierCode: string | null;
  readonly sortOrder: number;
  /** `ACTIVE` or `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
}

/**
 * `labelRu`/`labelUz`/`labelEn` are the platform triple and stay *required* —
 * the OpenAPI contract cannot relax a published required request field — so an
 * editor that offers only some of the platform languages fills the others (see
 * `platformColumns`). `labels` is wording by locale, additive: the way to word
 * a language beyond the triple, and what the tenant's default language is
 * checked against.
 */
export interface NewPreset {
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly labels: LabelsByLocale;
  readonly posModifierCode?: string | null;
  readonly sortOrder?: number;
}

/**
 * Whole-record edit: POS mapping, sort order and status together, with an
 * expected version. The platform triple is always named — for a language the
 * editor does not offer, with the wording the preset already has, unchanged —
 * and `labels` names only the offered languages the operator filled in, so a
 * language beyond the triple that is not offered keeps its wording.
 */
export interface PresetEdit {
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly labels: LabelsByLocale;
  readonly posModifierCode?: string | null;
  readonly sortOrder: number;
  readonly status: string;
  readonly expectedVersion: number;
}

/** The tenant-wide coded kitchen-instruction vocabulary (row 2.1b) — the settings screen's own API. */
@Injectable({ providedIn: 'root' })
export class CommentPresetsApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string): Promise<readonly PresetResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PresetResponse[]>(this.path(tenantId)),
    );
    return result.value ?? [];
  }

  /** The languages this editor offers — the union of the tenant's brands' (row 10.12). */
  async localeSet(tenantId: string): Promise<LocaleSetView> {
    const result = await firstValueFrom(
      this.api.get<LocaleSetView>(`${this.path(tenantId)}/locale-set`),
    );
    return result.value;
  }

  /** Refused (409) when the code is already registered for this tenant. */
  create(tenantId: string, body: NewPreset): Observable<PresetResponse> {
    return this.api.post<NewPreset, PresetResponse>(this.path(tenantId), command(body));
  }

  /** Archiving is `status: 'ARCHIVED'` here, not a delete — a preset already on a product must stay resolvable. */
  update(tenantId: string, presetId: string, body: PresetEdit): Observable<PresetResponse> {
    return this.api.put<PresetEdit, PresetResponse>(
      `${this.path(tenantId)}/${encodeURIComponent(presetId)}`,
      command(body),
    );
  }

  private path(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${encodeURIComponent(tenantId)}/comment-presets`;
  }
}
