package dev.smartcodereview.analysis.category;

import dev.smartcodereview.model.Model.Group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The common shape of a category: a segment is <em>primary</em> here when a rule assigned this category to it
 * (grouped by the rule's group), and may be an <em>extra</em> when another signal also points here (architecture
 * layer, frontend aspect, cross-cutting concern; grouped by file or a module-specific label). Primary and extra
 * segments of one category share the groups of the same label.
 */
public class GroupedCategory implements CategoryModule {

    private final CategoryInfo info;
    private final String origin;
    private final Predicate<SegmentFacts> primary;
    private final Predicate<SegmentFacts> extra;
    private final Function<SegmentFacts, String> extraGroup;

    public GroupedCategory(CategoryInfo info, String origin, Predicate<SegmentFacts> primary,
                           Predicate<SegmentFacts> extra, Function<SegmentFacts, String> extraGroup) {
        this.info = info;
        this.origin = origin;
        this.primary = primary;
        this.extra = extra;
        this.extraGroup = extraGroup;
    }

    /** A category that only collects what rules assigned to it. */
    public static GroupedCategory byRule(CategoryInfo info) {
        return new GroupedCategory(info, ORIGIN_RULES, s -> info.id().equals(s.category()), s -> false, s -> s.path());
    }

    @Override
    public String id() {
        return info.id();
    }

    @Override
    public CategoryInfo info() {
        return info;
    }

    @Override
    public String origin() {
        return origin;
    }

    @Override
    public List<Group> groups(CategoryContext ctx) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        List<String> order = ctx.groupOrder();
        ctx.segments().stream()
                .filter(primary)
                .sorted(Comparator.comparingInt((SegmentFacts s) -> order.indexOf(s.group()) < 0 ? 999 : order.indexOf(s.group()))
                        .thenComparing(SegmentFacts::group))
                .forEach(s -> byGroup.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s.id()));
        ctx.segments().stream()
                .filter(s -> !primary.test(s) && !id().equals(s.category()) && extra.test(s))
                .sorted(Comparator.comparing(SegmentFacts::path))
                .forEach(s -> byGroup.computeIfAbsent(extraGroup.apply(s), k -> new ArrayList<>()).add(s.id()));
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((label, ids) -> groups.add(new Group(id() + "/" + Ids.slug(label), label, null, null, ids)));
        return groups;
    }
}
