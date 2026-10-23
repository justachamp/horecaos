import { TestBed } from '@angular/core/testing';

import { ApiError } from './problem-details';
import { IntentKeys } from './intent-keys';

describe('IntentKeys', () => {
  const refusal = new ApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'no' });
  const offline = new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' });

  it('hands the same key to a retry after the request never arrived -- the second tap replays the first', async () => {
    const keys = TestBed.inject(IntentKeys);
    const seen: string[] = [];

    await expect(
      keys.run('pickup:ship-1', async (key) => {
        seen.push(key);
        throw offline;
      }),
    ).rejects.toBe(offline);
    expect(keys.holds('pickup:ship-1')).toBe(true);

    await keys.run('pickup:ship-1', async (key) => {
      seen.push(key);
    });

    expect(seen).toHaveLength(2);
    expect(seen[1]).toBe(seen[0]);
    expect(keys.holds('pickup:ship-1')).toBe(false);
  });

  it('drops the key once the platform has answered, even with a refusal: a new tap is a new decision', async () => {
    const keys = TestBed.inject(IntentKeys);
    const seen: string[] = [];

    await expect(
      keys.run('accept:offer-1', async (key) => {
        seen.push(key);
        throw refusal;
      }),
    ).rejects.toBe(refusal);
    await keys.run('accept:offer-1', async (key) => {
      seen.push(key);
    });

    expect(keys.holds('accept:offer-1')).toBe(false);
    expect(seen[1]).not.toBe(seen[0]);
  });

  it('keeps one intent’s key apart from another’s', async () => {
    const keys = TestBed.inject(IntentKeys);
    const seen = new Map<string, string>();

    for (const intent of ['a', 'b']) {
      await expect(
        keys.run(intent, async (key) => {
          seen.set(intent, key);
          throw offline;
        }),
      ).rejects.toBe(offline);
    }

    expect(seen.get('a')).not.toBe(seen.get('b'));
  });
});
