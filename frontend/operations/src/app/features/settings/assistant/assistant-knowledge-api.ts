import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

/** The three languages the assistant answers in (`ReplyLocale`). */
export type KnowledgeLocale = 'ru' | 'uz' | 'en';

/** Mirrors `AssistantKnowledgeController.EntryResponse`: an entry at its current version. */
export interface KnowledgeEntry {
  readonly id: string;
  /** `TENANT`, `BRAND` or `LOCATION` — the entry's identity, never changed. */
  readonly scope: string;
  readonly brandId?: string | null;
  readonly locationId?: string | null;
  readonly locale: string;
  /** Also the response's `ETag`: what a publish or a retirement must send as `If-Match`. */
  readonly version: number;
  /** `PUBLISHED` or `RETIRED`. */
  readonly status: string;
  readonly questionForm: string;
  readonly answerBody: string;
  readonly authoredBy: string;
  /** RFC 3339, UTC. */
  readonly publishedAt: string;
}

/** Mirrors `AssistantKnowledgeController.VersionResponse`: what one version said, never altered. */
export interface KnowledgeVersion {
  readonly version: number;
  readonly status: string;
  readonly questionForm: string;
  readonly answerBody: string;
  readonly authoredBy: string;
  readonly reason: string;
  readonly publishedAt: string;
}

export interface CreateKnowledgeEntry {
  /** Only at brand scope: absent means the whole brand. */
  readonly locationId?: string | null;
  readonly locale: KnowledgeLocale;
  readonly questionForm: string;
  readonly answerBody: string;
  readonly reason: string;
}

export interface PublishKnowledgeVersion {
  readonly questionForm: string;
  readonly answerBody: string;
  readonly reason: string;
}

/** The longest question form and answer the platform accepts (`AssistantKnowledgeController`'s request records). */
export const KNOWLEDGE_QUESTION_MAX = 300;
export const KNOWLEDGE_QUESTION_MIN = 3;
export const KNOWLEDGE_ANSWER_MAX = 2_000;
export const KNOWLEDGE_REASON_MAX = 1_000;

/**
 * The notes the assistant answers from (ADR 0069), authored by the operations team: versioned,
 * never edited in place, scoped to the company, a brand or one of its branches.
 *
 * `brandId === null` addresses the company-wide route; a brand id addresses the brand's, whose
 * list also holds its branches' entries. The path decides the scope an entry is written at, so the
 * capability check and the entry cannot disagree about it.
 *
 * Every write is a new version. `publish` and `retire` carry the version the caller read as
 * `If-Match`: two people changing one note at once is refused for the second, not merged.
 */
@Injectable({ providedIn: 'root' })
export class AssistantKnowledgeApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string, brandId: string | null): Promise<readonly KnowledgeEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly KnowledgeEntry[]>(settingsPaths.assistantKnowledge(tenantId, brandId)),
    );
    return result.value ?? [];
  }

  async versions(
    tenantId: string,
    brandId: string | null,
    entryId: string,
  ): Promise<readonly KnowledgeVersion[]> {
    const result = await firstValueFrom(
      this.api.get<readonly KnowledgeVersion[]>(
        settingsPaths.assistantKnowledgeVersions(tenantId, brandId, entryId),
      ),
    );
    return result.value ?? [];
  }

  create(
    tenantId: string,
    brandId: string | null,
    body: CreateKnowledgeEntry,
  ): Promise<KnowledgeEntry> {
    return firstValueFrom(
      this.api.post<CreateKnowledgeEntry, KnowledgeEntry>(
        settingsPaths.assistantKnowledge(tenantId, brandId),
        command(body),
      ),
    );
  }

  publish(
    tenantId: string,
    brandId: string | null,
    entryId: string,
    expectedVersion: number,
    body: PublishKnowledgeVersion,
  ): Promise<KnowledgeEntry> {
    return firstValueFrom(
      this.api.post<PublishKnowledgeVersion, KnowledgeEntry>(
        settingsPaths.assistantKnowledgeVersions(tenantId, brandId, entryId),
        command(body),
        { expectedVersion },
      ),
    );
  }

  retire(
    tenantId: string,
    brandId: string | null,
    entryId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<KnowledgeEntry> {
    return firstValueFrom(
      this.api.post<{ readonly reason: string }, KnowledgeEntry>(
        settingsPaths.assistantKnowledgeRetirements(tenantId, brandId, entryId),
        command({ reason }),
        { expectedVersion },
      ),
    );
  }
}
