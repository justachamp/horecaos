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

  describe('an intent whose body must not change under the key', () => {
    it('captures the body once, on the first attempt, and sends it again with the key after a request that never arrived', async () => {
      const keys = TestBed.inject(IntentKeys);
      const sent: Array<{ key: string; body: string }> = [];
      let reading = 0;
      const capture = async () => `position-${++reading}`;

      await expect(
        keys.runWith('pickup:ship-1', capture, async (key, body) => {
          sent.push({ key, body });
          throw offline;
        }),
      ).rejects.toBe(offline);
      await keys.runWith('pickup:ship-1', capture, async (key, body) => {
        sent.push({ key, body });
      });

      expect(reading).toBe(1);
      expect(sent).toHaveLength(2);
      expect(sent[1]).toEqual(sent[0]);
      expect(sent[0].body).toBe('position-1');
      expect(keys.holds('pickup:ship-1')).toBe(false);
    });

    it('captures a new body once the platform has answered, even with a refusal: a new tap is a new decision', async () => {
      const keys = TestBed.inject(IntentKeys);
      const sent: Array<{ key: string; body: string }> = [];
      let reading = 0;
      const capture = async () => `position-${++reading}`;

      await expect(
        keys.runWith('accept:offer-1', capture, async (key, body) => {
          sent.push({ key, body });
          throw refusal;
        }),
      ).rejects.toBe(refusal);
      await keys.runWith('accept:offer-1', capture, async (key, body) => {
        sent.push({ key, body });
      });

      expect(reading).toBe(2);
      expect(sent[1].body).toBe('position-2');
      expect(sent[1].key).not.toBe(sent[0].key);
    });

    it('keeps a body of null as the body: a phone with no fix does not get one on the retry', async () => {
      const keys = TestBed.inject(IntentKeys);
      let reading = 0;
      const seen: Array<number | null> = [];
      const capture = async () => (reading++ === 0 ? null : 7);

      await expect(
        keys.runWith('pickup:ship-1', capture, async (_key, body: number | null) => {
          seen.push(body);
          throw offline;
        }),
      ).rejects.toBe(offline);
      await keys.runWith('pickup:ship-1', capture, async (_key, body: number | null) => {
        seen.push(body);
      });

      expect(seen).toEqual([null, null]);
    });
  });

  it('forgets every key and every body it holds when told, as at sign-out', async () => {
    const keys = TestBed.inject(IntentKeys);
    await expect(
      keys.runWith(
        'pickup:ship-1',
        async () => 'position-1',
        async () => {
          throw offline;
        },
      ),
    ).rejects.toBe(offline);
    expect(keys.holds('pickup:ship-1')).toBe(true);

    keys.clear();

    expect(keys.holds('pickup:ship-1')).toBe(false);
    let reading = 0;
    await keys.runWith(
      'pickup:ship-1',
      async () => `position-${++reading}`,
      async () => undefined,
    );
    expect(reading).toBe(1);
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
