import { describe, expect, it } from 'vitest';
import { NOT_ACCEPTED, accept, acceptedElsewhere, statusOf } from './acceptance';

const THREE = ['quick-wins/mapper', 'flows/flow-1', 'security/a-ts'];

describe('acceptance (mirror of LineAcceptance.java)', () => {
  it('accepting at one place makes the others half accepted', () => {
    const s = accept(NOT_ACCEPTED, THREE, 'flows/flow-1', true);
    expect(statusOf(s, THREE, 'flows/flow-1')).toBe('accepted');
    expect(statusOf(s, THREE, 'quick-wins/mapper')).toBe('partial');
    expect(statusOf(s, THREE, null)).toBe('partial');
    expect(acceptedElsewhere(s, THREE, 'security/a-ts')).toEqual(['flows/flow-1']);
  });

  it('tracks several accepts and lists all of them as elsewhere', () => {
    const s = accept(accept(NOT_ACCEPTED, THREE, 'flows/flow-1', true), THREE, 'security/a-ts', true);
    expect(acceptedElsewhere(s, THREE, 'quick-wins/mapper')).toEqual(['flows/flow-1', 'security/a-ts']);
  });

  it('is accepted everywhere once all places are, and keeps the others when one is taken back', () => {
    let s = NOT_ACCEPTED;
    for (const p of THREE) {
      s = accept(s, THREE, p, true);
    }
    expect(s.full).toBe(true);
    expect(statusOf(s, THREE, null)).toBe('accepted');
    s = accept(s, THREE, 'flows/flow-1', false);
    expect(s.full).toBe(false);
    expect([...s.at].sort()).toEqual(['quick-wins/mapper', 'security/a-ts']);
  });

  it('applies marks without a place, or for a foreign place, to all places', () => {
    expect(accept(NOT_ACCEPTED, THREE, null, true).full).toBe(true);
    expect(accept(NOT_ACCEPTED, THREE, 'x/y', true).full).toBe(true);
    expect(accept(accept(NOT_ACCEPTED, THREE, 'flows/flow-1', true), THREE, null, false)).toBe(NOT_ACCEPTED);
  });

  it('has no half state for a line with a single place', () => {
    const one = ['flows/flow-1'];
    expect(accept(NOT_ACCEPTED, one, 'flows/flow-1', true).full).toBe(true);
    expect(statusOf(NOT_ACCEPTED, one, 'flows/flow-1')).toBe('open');
    expect(statusOf(NOT_ACCEPTED, [], null)).toBe('open');
  });
});
