package dev.smartcodereview.analysis.category;

/** Id helpers shared by category modules. */
public final class Ids {

    private Ids() {
    }

    /** "Eingehend (REST, …)" -> "eingehend-rest-…": the part of a group id after "<category>/". */
    public static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9äöü/._-]+", "-").replaceAll("(^-|-$)", "");
    }
}
