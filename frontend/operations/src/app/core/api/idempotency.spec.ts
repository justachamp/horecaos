import { IntentCommandRegistry, command, newIdempotencyKey, wasReplayed } from './idempotency';

describe('newIdempotencyKey / command', () => {
  it('mints a UUID-shaped key', () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  });

  it('mints a different key on every call', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });

  it('command() pairs the body with a fresh key', () => {
    const body = { reasonCode: 'CUSTOMER_REQUESTED' };
    const first = command(body);
    const second = command(body);

    expect(first.body).toBe(body);
    expect(first.key).not.toBe(second.key);
  });
});

describe('wasReplayed', () => {
  it('is true only when the server sent Idempotency-Replayed: true', () => {
    expect(wasReplayed(new Headers({ 'Idempotency-Replayed': 'true' }))).toBe(true);
    expect(wasReplayed(new Headers({ 'Idempotency-Replayed': 'false' }))).toBe(false);
    expect(wasReplayed(new Headers())).toBe(false);
  });
});

describe('IntentCommandRegistry', () => {
  it('mints a key on the first call for an id', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();

    const first = registry.next('order-1', { reasonCode: 'CUSTOMER_REQUESTED' });

    expect(first.body).toEqual({ reasonCode: 'CUSTOMER_REQUESTED' });
    expect(first.key).toBeTruthy();
  });

  it('reuses the same command -- same key, same body reference -- on a retry with an unchanged body', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();
    const body = { reasonCode: 'CUSTOMER_REQUESTED' };

    const first = registry.next('order-1', body);
    // A retry rebuilds the body object (a fresh literal), not the same reference --
    // this is the realistic shape of "the operator clicked the same button again".
    const second = registry.next('order-1', { reasonCode: 'CUSTOMER_REQUESTED' });

    expect(second.key).toBe(first.key);
    expect(second).toBe(first);
  });

  it('mints a fresh key when the body changes -- a genuinely new intent', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();

    const first = registry.next('order-1', { reasonCode: 'CUSTOMER_REQUESTED' });
    const second = registry.next('order-1', { reasonCode: 'OUT_OF_STOCK' });

    expect(second.key).not.toBe(first.key);
  });

  it('holds independent commands per id', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();

    const forOrderA = registry.next('order-a', { reasonCode: 'X' });
    const forOrderB = registry.next('order-b', { reasonCode: 'X' });

    expect(forOrderA.key).not.toBe(forOrderB.key);
  });

  it('mints a fresh key after forget, even with the same body -- a settled intent does not get replayed by a later, unrelated click', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();
    const body = { reasonCode: 'CUSTOMER_REQUESTED' };

    const first = registry.next('order-1', body);
    registry.forget('order-1');
    const second = registry.next('order-1', body);

    expect(second.key).not.toBe(first.key);
  });

  it('forgetting an id that was never held is a no-op, not a throw', () => {
    const registry = new IntentCommandRegistry<{ reasonCode: string }>();

    expect(() => registry.forget('never-called')).not.toThrow();
  });
});
