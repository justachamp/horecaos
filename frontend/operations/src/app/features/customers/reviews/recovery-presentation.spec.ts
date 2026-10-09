import { describe, expect, it } from 'vitest';

import {
  isClosed,
  slaTone,
  staffLabel,
  stars,
  stateTone,
  timelineEntryOf,
} from './recovery-presentation';
import { timelineEntry } from './recovery-fixtures.testing';

const TEXT = {
  kind: (kind: string) => `kind:${kind}`,
  outcome: (code: string) => `outcome:${code}`,
  compensation: (code: string) => `comp:${code}`,
  time: (iso: string) => `at:${iso}`,
  breached: 'Breached',
};

describe('recovery presentation', () => {
  it('shows a breached clock as the one red thing, an at-risk clock as amber, and a clock met as green', () => {
    expect(slaTone('BREACHED')).toBe('danger');
    expect(slaTone('AT_RISK')).toBe('warning');
    expect(slaTone('MET')).toBe('success');
    expect(slaTone('ON_TRACK')).toBe('none');
  });

  it('gives each state its own tone and calls only the two terminal states closed', () => {
    expect(stateTone('NEW')).toBe('info');
    expect(stateTone('IN_PROGRESS')).toBe('warning');
    expect(stateTone('RESOLVED')).toBe('success');
    expect(stateTone('CLOSED_UNRESOLVED')).toBe('none');
    expect(
      ['NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED_UNRESOLVED'].map((s) => isClosed(s as never)),
    ).toEqual([false, false, true, true]);
  });

  it('draws five stars, filled up to the rating', () => {
    expect(stars(1)).toBe('★☆☆☆☆');
    expect(stars(5)).toBe('★★★★★');
    expect(stars(0)).toBe('☆☆☆☆☆');
  });

  it('names a staff member by name, else by the non-personal reference, else not at all', () => {
    expect(staffLabel({ id: 'm1', displayReference: 'S-0142', name: 'Aziza Karimova' })).toBe(
      'Aziza Karimova',
    );
    expect(staffLabel({ id: 'm1', displayReference: 'S-0142', name: '  ' })).toBe('S-0142');
    expect(staffLabel(null)).toBeNull();
  });

  it('carries a note only on a NOTE line and the outcome only on a closing one', () => {
    const note = timelineEntryOf(
      timelineEntry({
        kind: 'NOTE',
        actorType: 'STAFF',
        actor: { id: 'm1', displayReference: 'S-0142', name: 'Aziza Karimova' },
        note: 'Phoned her; call back tomorrow',
      }),
      TEXT,
    );
    expect(note.detail).toBe('Phoned her; call back tomorrow');
    expect(note.actor).toEqual({ kind: 'USER', displayName: 'Aziza Karimova', subject: 'S-0142' });

    const resolved = timelineEntryOf(
      timelineEntry({ kind: 'RESOLVED', detailCode: 'REMEDY_GRANTED', actorType: 'STAFF' }),
      TEXT,
    );
    expect(resolved.detail).toBe('outcome:REMEDY_GRANTED');
    expect(resolved.title).toBe('kind:RESOLVED');

    const opened = timelineEntryOf(timelineEntry({ kind: 'OPENED', note: 'never shown' }), TEXT);
    expect(opened.detail).toBeNull();
  });

  it('leaves the actor off a fact nobody caused, and marks a passed clock with a red badge', () => {
    const breach = timelineEntryOf(timelineEntry({ kind: 'FIRST_RESPONSE_BREACHED' }), TEXT);

    expect(breach.actor).toBeNull();
    expect(breach.badge).toEqual({ label: 'Breached', tone: 'danger' });
    expect(timelineEntryOf(timelineEntry({ kind: 'OPENED' }), TEXT).badge).toBeNull();
  });

  it('words a linked compensation by its kind and shows an unknown outcome as nothing rather than a code', () => {
    const attached = timelineEntryOf(
      timelineEntry({
        kind: 'COMPENSATION_ATTACHED',
        detailCode: 'ORDER_REMEDY',
        actorType: 'STAFF',
      }),
      TEXT,
    );
    expect(attached.detail).toBe('comp:ORDER_REMEDY');

    const unknown = timelineEntryOf(
      timelineEntry({ kind: 'RESOLVED', detailCode: 'SOMETHING_NEW', actorType: 'STAFF' }),
      TEXT,
    );
    expect(unknown.detail).toBeNull();
  });
});
