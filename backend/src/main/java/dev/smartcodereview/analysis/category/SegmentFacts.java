package dev.smartcodereview.analysis.category;

import java.util.List;

/**
 * What category modules may know about a segment. This is the whole input contract of a module: modules never
 * see the analyzer's internals, so one can be added, removed or (later) supplied from outside without touching
 * another.
 *
 * @param category         category a path/line rule assigned (the primary place); "logic" when no rule matched
 * @param group            group label of that rule (a file path for logic and tests)
 * @param layer            ports &amp; adapters layer (domain | usecase | application | adapter) or null
 * @param aspectCategory   frontend aspect category (routing, state, ...) or null
 * @param concerns         cross-cutting categories that apply (security, messaging, ...)
 * @param adapterDirection in | out | mapping | config for adapter code, else null
 * @param inFlow           the segment is a step of a data flow
 */
public record SegmentFacts(String id, String path, String category, String group, String layer, String aspectCategory,
                           List<String> concerns, String adapterDirection, boolean inFlow) {
}
