package dev.smartcodereview.review;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Review state of <em>one changed line</em> that may be shown at several places (groups of several categories,
 * flows). The rules live here and nowhere else (the UI mirrors them in {@code acceptance.ts}):
 *
 * <ul>
 *   <li>Every place needs its own accept. A line is {@link Status#ACCEPTED accepted} at a place once accepted
 *       there, and accepted everywhere once all its places are.</li>
 *   <li>A line accepted at some of its places is {@link Status#PARTIAL half accepted} at the others - it is
 *       already accepted elsewhere, but still waits for the reviewer here.</li>
 *   <li>A mark without a place (file view, agents) or on a place the line is not shown at applies to all
 *       places at once.</li>
 *   <li>Taking back an accept at one place of a fully accepted line leaves the line accepted at all other places.</li>
 * </ul>
 *
 * <p>The state is stored as {@code full} ("accepted at all places", file field {@code ok}) or as the set of places
 * accepted so far (file field {@code okAt}); {@link #accept} keeps it normalised, so a line never is in both.
 *
 * @param full accepted at all of its places (also when it has none)
 * @param at   places accepted so far; empty when {@code full}
 */
public record LineAcceptance(boolean full, Set<String> at) {

    public enum Status {
        /** Accepted nowhere. */
        OPEN,
        /** Accepted at another place, not (yet) here / not everywhere. */
        PARTIAL,
        /** Accepted (here / everywhere). */
        ACCEPTED
    }

    public static final LineAcceptance NONE = new LineAcceptance(false, Set.of());

    public LineAcceptance {
        at = Set.copyOf(at);
    }

    /** The places (of {@code places}) at which the line is accepted. */
    public Set<String> acceptedIn(List<String> places) {
        if (full) {
            return new LinkedHashSet<>(places);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String p : places) {
            if (at.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Status at one place, or overall when place is null / not one of the places. Overall: ACCEPTED when
     * accepted everywhere, PARTIAL when at some places, else OPEN.
     */
    public Status status(List<String> places, String place) {
        Set<String> accepted = acceptedIn(places);
        if (isTargeted(places, place)) {
            if (accepted.contains(place)) {
                return Status.ACCEPTED;
            }
            return accepted.isEmpty() ? Status.OPEN : Status.PARTIAL;
        }
        if (full || (!places.isEmpty() && accepted.size() == places.size())) {
            return Status.ACCEPTED;
        }
        return accepted.isEmpty() ? Status.OPEN : Status.PARTIAL;
    }

    /** The other places the line is accepted at (what a half-accepted line points to). */
    public List<String> acceptedElsewhere(List<String> places, String place) {
        return acceptedIn(places).stream().filter(p -> !p.equals(place)).toList();
    }

    /** Accepts (ok) or takes back the accept at a place; place null = all places. */
    public LineAcceptance accept(List<String> places, String place, boolean ok) {
        if (!isTargeted(places, place)) {
            return ok ? new LineAcceptance(true, Set.of()) : NONE;
        }
        Set<String> accepted = acceptedIn(places);
        if (ok) {
            accepted.add(place);
        } else {
            accepted.remove(place);
        }
        return normalised(places, accepted);
    }

    /** The state with exactly these places accepted. */
    public static LineAcceptance of(List<String> places, Collection<String> accepted) {
        return normalised(places, new LinkedHashSet<>(accepted));
    }

    private static LineAcceptance normalised(List<String> places, Set<String> accepted) {
        accepted.retainAll(places);
        if (!places.isEmpty() && accepted.size() == places.size()) {
            return new LineAcceptance(true, Set.of());
        }
        return new LineAcceptance(false, accepted);
    }

    /** A mark addresses one place only when the line really is shown at several places including this one. */
    private static boolean isTargeted(List<String> places, String place) {
        return place != null && places.size() > 1 && places.contains(place);
    }
}
