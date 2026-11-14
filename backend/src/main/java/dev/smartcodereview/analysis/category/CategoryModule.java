package dev.smartcodereview.analysis.category;

import dev.smartcodereview.model.Model.Group;

import java.util.List;

/**
 * One review category as a self-contained module. A module describes itself ({@link #info()}) and builds its
 * groups from the segment facts of the PR ({@link #groups}); it never refers to another module. Adding or
 * removing a module changes only its own category: a segment that several modules claim simply becomes one
 * place per module (see docs, "Orte").
 *
 * <p>Group ids must start with the module id plus "/" so that places can be traced back to their category.
 * Modules are pure functions of the context, which is what lets modules from outside the application plug in
 * later (see {@code CategoryModules}).
 */
public interface CategoryModule {

    String id();

    CategoryInfo info();

    /**
     * How the UI shows this category. "groups" (default): a list of groups with code blocks. Other values are
     * exceptions for special UIs; "flow" = call tree diagram plus blocks in call order.
     */
    default String view() {
        return VIEW_GROUPS;
    }

    /** Where the module comes from: "builtin" (shipped), "rules" (defined in a rules file), later "external". */
    default String origin() {
        return ORIGIN_RULES;
    }

    /** The groups of this category for the PR; empty = the category is not shown. */
    List<Group> groups(CategoryContext context);

    String VIEW_GROUPS = "groups";
    String VIEW_FLOW = "flow";
    String ORIGIN_BUILTIN = "builtin";
    String ORIGIN_RULES = "rules";
}
