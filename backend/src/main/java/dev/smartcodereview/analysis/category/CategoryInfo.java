package dev.smartcodereview.analysis.category;

/**
 * The self-description of a category, shown in the UI.
 *
 * @param summary what the category is for (one or two sentences)
 * @param builds  how it is composed: which code ends up here and why
 * @param divides how it is split into groups
 */
public record CategoryInfo(String id, String label, String icon, String summary, String builds, String divides) {
}
