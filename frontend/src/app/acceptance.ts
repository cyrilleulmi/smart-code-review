/**
 * Accept state of one changed line that is shown at several places (groups of several categories, flows).
 * This mirrors `LineAcceptance.java` in the backend - keep both in step; the rules:
 *
 * - every place needs its own accept; a line is accepted everywhere once all its places are accepted;
 * - a line accepted at some places is half accepted ('partial') at the others;
 * - a mark without a place, or on a place the line is not shown at, applies to all places;
 * - taking back one place of a fully accepted line leaves the other places accepted.
 */
export type AcceptStatus = 'open' | 'partial' | 'accepted';

/** full: accepted at all places (file field `ok`); at: places accepted so far (file field `okAt`). */
export interface Acceptance {
  readonly full: boolean;
  readonly at: ReadonlySet<string>;
}

export const NOT_ACCEPTED: Acceptance = { full: false, at: new Set() };

/** A mark addresses one place only when the line is shown at several places including that one. */
function targeted(places: readonly string[], place: string | null): place is string {
  return place != null && places.length > 1 && places.includes(place);
}

/** The places (of `places`) the line is accepted at. */
export function acceptedIn(a: Acceptance, places: readonly string[]): string[] {
  return a.full ? [...places] : places.filter((p) => a.at.has(p));
}

/** Status at one place; at no place (or a foreign one) the overall status. */
export function statusOf(a: Acceptance, places: readonly string[], place: string | null): AcceptStatus {
  const accepted = acceptedIn(a, places);
  if (targeted(places, place)) {
    return accepted.includes(place) ? 'accepted' : accepted.length ? 'partial' : 'open';
  }
  if (a.full || (places.length > 0 && accepted.length === places.length)) {
    return 'accepted';
  }
  return accepted.length ? 'partial' : 'open';
}

/** The other places the line is accepted at: what a half-accepted line points to. */
export function acceptedElsewhere(a: Acceptance, places: readonly string[], place: string | null): string[] {
  return acceptedIn(a, places).filter((p) => p !== place);
}

/** Accepts (ok) or takes back the accept at a place; place null = all places. */
export function accept(a: Acceptance, places: readonly string[], place: string | null, ok: boolean): Acceptance {
  if (!targeted(places, place)) {
    return ok ? { full: true, at: new Set() } : NOT_ACCEPTED;
  }
  const accepted = new Set(acceptedIn(a, places));
  if (ok) {
    accepted.add(place);
  } else {
    accepted.delete(place);
  }
  return accepted.size === places.length ? { full: true, at: new Set() } : { full: false, at: accepted };
}
