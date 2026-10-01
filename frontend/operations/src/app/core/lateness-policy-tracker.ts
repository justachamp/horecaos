import { LocationScope } from './api/operations-paths';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from './lateness-policy';
import { LatenessPolicyApi } from './lateness-policy-api';

/**
 * How long a read policy is trusted before the next {@link
 * LatenessPolicyTracker.refresh} asks again. The server caches the resolved
 * document for 60 s (`JdbcPolicyResolver`), so asking more often only pays a
 * round trip to be told the same thing; asking less often keeps a wall
 * display on yesterday's numbers for longer than an owner who just published
 * a change will forgive.
 */
export const LATENESS_POLICY_MAX_AGE_MS = 60_000;

interface Entry {
  readonly policy: LatenessPolicy;
  readonly readAt: number;
}

/**
 * The lateness policy of one or more branches, for a screen that stays open
 * long after it read the policy once: the order board, the kitchen queue, the
 * two kitchen displays and the shell's late counter.
 *
 * Each of those used to resolve the policy once at start-up and hold it for
 * the component's lifetime, so an owner publishing new numbers reached a
 * wall display only when someone reloaded the browser, and a start-up read
 * that failed pinned the screen to the platform default without a sign.
 * A screen now calls {@link refresh} from the poll it already has; this class
 * turns that into one read per branch per {@link LATENESS_POLICY_MAX_AGE_MS},
 * and keeps two rules that a plain re-read would not:
 *
 * - **A failed read is not a loaded policy.** {@link LatenessPolicyApi.read}
 *   answers `null` for a read that failed, and nothing is recorded, so the next
 *   {@link refresh} asks again (each poll, not each minute) until a real
 *   document arrives.
 * - **A failed read never replaces a good one.** A policy read a minute ago is
 *   a better guess than the platform default, so a later failure keeps it.
 *
 * Plain class, not an injectable: it holds per-screen state, and each screen
 * owns its own. Concurrent {@link refresh} calls for one branch share a read.
 */
export class LatenessPolicyTracker {
  private readonly entries = new Map<string, Entry>();
  private readonly inFlight = new Map<string, Promise<void>>();

  constructor(
    private readonly api: Pick<LatenessPolicyApi, 'read'>,
    private readonly maxAgeMs: number = LATENESS_POLICY_MAX_AGE_MS,
  ) {}

  /** The last policy read for this branch, or the platform default before the first successful read. */
  policy(locationId: string): LatenessPolicy {
    return this.entries.get(locationId)?.policy ?? PLATFORM_DEFAULT_LATENESS_POLICY;
  }

  /** Whether a real document has been read for this branch — the default standing in does not count. */
  isLoaded(locationId: string): boolean {
    return this.entries.has(locationId);
  }

  /**
   * Reads the branch's policy unless a read younger than the max age is held.
   * Never rejects: {@link LatenessPolicyApi.read} swallows its own failures.
   */
  refresh(scope: LocationScope): Promise<void> {
    const key = scope.locationId;
    const held = this.entries.get(key);
    if (held !== undefined && Date.now() - held.readAt < this.maxAgeMs) {
      return Promise.resolve();
    }
    const pending = this.inFlight.get(key);
    if (pending !== undefined) {
      return pending;
    }
    const read = this.read(scope).finally(() => this.inFlight.delete(key));
    this.inFlight.set(key, read);
    return read;
  }

  private async read(scope: LocationScope): Promise<void> {
    const policy = await this.api.read(scope);
    if (policy !== null) {
      this.entries.set(scope.locationId, { policy, readAt: Date.now() });
    }
  }
}
