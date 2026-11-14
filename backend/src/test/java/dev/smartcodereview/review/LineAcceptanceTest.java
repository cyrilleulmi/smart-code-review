package dev.smartcodereview.review;

import dev.smartcodereview.review.LineAcceptance.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineAcceptanceTest {

    private static final List<String> THREE = List.of("quick-wins/mapper", "flows/flow-1", "security/a-ts");

    @Test
    void acceptingAtOnePlaceMakesTheOthersHalfAccepted() {
        LineAcceptance s = LineAcceptance.NONE.accept(THREE, "flows/flow-1", true);
        assertEquals(Status.ACCEPTED, s.status(THREE, "flows/flow-1"));
        assertEquals(Status.PARTIAL, s.status(THREE, "quick-wins/mapper"));
        assertEquals(Status.PARTIAL, s.status(THREE, "security/a-ts"));
        assertEquals(Status.PARTIAL, s.status(THREE, null));
        assertEquals(List.of("flows/flow-1"), s.acceptedElsewhere(THREE, "security/a-ts"));
        assertTrue(s.acceptedElsewhere(THREE, "flows/flow-1").isEmpty());
    }

    @Test
    void severalAcceptsAreTrackedAndListedAsElsewhere() {
        LineAcceptance s = LineAcceptance.NONE.accept(THREE, "flows/flow-1", true).accept(THREE, "security/a-ts", true);
        assertEquals(Status.PARTIAL, s.status(THREE, "quick-wins/mapper"));
        assertEquals(List.of("flows/flow-1", "security/a-ts"), s.acceptedElsewhere(THREE, "quick-wins/mapper"));
        assertEquals(List.of("security/a-ts"), s.acceptedElsewhere(THREE, "flows/flow-1"));
    }

    @Test
    void acceptedEverywhereOnceAllPlacesAreAccepted() {
        LineAcceptance s = LineAcceptance.NONE;
        for (String p : THREE) {
            assertFalse(s.full());
            s = s.accept(THREE, p, true);
        }
        assertTrue(s.full());
        assertTrue(s.at().isEmpty());
        assertEquals(Status.ACCEPTED, s.status(THREE, null));
        assertEquals(Status.ACCEPTED, s.status(THREE, "quick-wins/mapper"));
    }

    @Test
    void takingBackOnePlaceOfAFullyAcceptedLineKeepsTheOthers() {
        LineAcceptance s = LineAcceptance.of(THREE, THREE).accept(THREE, "flows/flow-1", false);
        assertFalse(s.full());
        assertEquals(Set.of("quick-wins/mapper", "security/a-ts"), s.at());
        assertEquals(Status.OPEN, s.accept(THREE, "quick-wins/mapper", false).accept(THREE, "security/a-ts", false)
                .status(THREE, "flows/flow-1"));
    }

    @Test
    void takingBackTheLastAcceptReturnsToOpen() {
        LineAcceptance s = LineAcceptance.NONE.accept(THREE, "flows/flow-1", true).accept(THREE, "flows/flow-1", false);
        assertEquals(LineAcceptance.NONE, s);
        assertEquals(Status.OPEN, s.status(THREE, "quick-wins/mapper"));
    }

    @Test
    void markWithoutPlaceOrForAForeignPlaceAppliesToAllPlaces() {
        assertTrue(LineAcceptance.NONE.accept(THREE, null, true).full());
        assertTrue(LineAcceptance.NONE.accept(THREE, "somewhere/else", true).full());
        LineAcceptance part = LineAcceptance.NONE.accept(THREE, "flows/flow-1", true);
        assertEquals(LineAcceptance.NONE, part.accept(THREE, null, false));
        assertEquals(Status.ACCEPTED, part.accept(THREE, null, true).status(THREE, "security/a-ts"));
    }

    @Test
    void lineWithOnePlaceHasNoHalfState() {
        List<String> one = List.of("flows/flow-1");
        LineAcceptance s = LineAcceptance.NONE.accept(one, "flows/flow-1", true);
        assertTrue(s.full());
        assertEquals(Status.ACCEPTED, s.status(one, "flows/flow-1"));
        assertEquals(Status.OPEN, LineAcceptance.NONE.status(one, "flows/flow-1"));
        assertEquals(LineAcceptance.NONE, s.accept(one, "flows/flow-1", false));
    }

    @Test
    void lineWithoutPlacesIsAcceptedOnlyByAnExplicitMark() {
        assertEquals(Status.OPEN, LineAcceptance.NONE.status(List.of(), null));
        assertEquals(Status.ACCEPTED, LineAcceptance.NONE.accept(List.of(), null, true).status(List.of(), null));
    }

    @Test
    void placesThatAreSwitchedOffNoLongerCount() {
        // accepted at two places; the third category is switched off afterwards -> accepted everywhere
        LineAcceptance s = LineAcceptance.NONE.accept(THREE, "flows/flow-1", true).accept(THREE, "security/a-ts", true);
        List<String> remaining = List.of("flows/flow-1", "security/a-ts");
        assertEquals(Status.ACCEPTED, s.status(remaining, null));
        // an accept at a place that no longer exists is ignored when normalising
        assertEquals(Status.OPEN, new LineAcceptance(false, Set.of("gone/x")).status(remaining, "flows/flow-1"));
    }
}
