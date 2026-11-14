package dev.smartcodereview.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.analysis.category.CategoryContext;
import dev.smartcodereview.analysis.category.CategoryInfo;
import dev.smartcodereview.analysis.category.CategoryModule;
import dev.smartcodereview.analysis.category.FlowCategory;
import dev.smartcodereview.analysis.category.GroupedCategory;
import dev.smartcodereview.analysis.category.SegmentFacts;
import dev.smartcodereview.model.Model.Flow;
import dev.smartcodereview.model.Model.FlowStep;
import dev.smartcodereview.model.Model.Group;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryModulesTest {

    private static final String RULES = """
            {
              "categories": [
                { "id": "quick-wins", "label": "Quick wins", "icon": "bolt", "description": "Mechanisch." },
                { "id": "flows", "label": "Datenflüsse", "icon": "flow", "description": "Aufrufketten." },
                { "id": "adapters", "label": "Adapter", "icon": "plug", "description": "Ränder." },
                { "id": "security", "label": "Sicherheit", "icon": "shield", "description": "Security." },
                { "id": "fe-state", "label": "Zustand", "icon": "data", "description": "Stores.", "builds": "Handgeschrieben." },
                { "id": "logic", "label": "Einzelne Änderungen", "icon": "code", "description": "Rest." }
              ],
              "rules": [
                { "id": "translations", "category": "quick-wins", "group": "Übersetzungen", "reason": "Übersetzungsdatei", "paths": ["**/i18n/**"] },
                { "id": "mappers", "category": "quick-wins", "group": "Mapper", "reason": "Mapper", "paths": ["**/*Mapper.*"] }
              ],
              "aspects": [ { "id": "state", "category": "fe-state", "reason": "Store", "paths": ["**/*.store.ts"] } ],
              "concerns": [ { "id": "sec", "category": "security", "reason": "Sicherheitsrelevanter Code", "text": ["@PreAuthorize"] } ]
            }
            """;

    private static Rules rules() throws Exception {
        return Rules.parse(new ObjectMapper().readTree(RULES), "h");
    }

    private static SegmentFacts facts(String id, String path, String category, String group, String layer, String aspect,
                                      List<String> concerns, String direction, boolean inFlow) {
        return new SegmentFacts(id, path, category, group, layer, aspect, concerns, direction, inFlow);
    }

    private static CategoryModule module(List<CategoryModule> modules, String id) {
        return modules.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyCategoryOfTheRulesBecomesOneModuleInFileOrder() throws Exception {
        List<CategoryModule> modules = CategoryModules.build(rules(), List.of());
        assertEquals(List.of("quick-wins", "flows", "adapters", "security", "fe-state", "logic"),
                modules.stream().map(CategoryModule::id).toList());
        assertEquals(CategoryModule.VIEW_FLOW, module(modules, "flows").view());
        assertEquals(CategoryModule.VIEW_GROUPS, module(modules, "quick-wins").view());
    }

    @Test
    void modulesDescribeHowTheyBuildAndDivide() throws Exception {
        List<CategoryModule> modules = CategoryModules.build(rules(), List.of());
        CategoryInfo quick = module(modules, "quick-wins").info();
        assertTrue(quick.builds().contains("Übersetzungen: Übersetzungsdatei · Mapper"), quick.builds());
        assertTrue(quick.divides().contains("Nach Regel-Gruppe: Übersetzungen, Mapper"), quick.divides());
        assertTrue(module(modules, "security").info().builds().contains("Sicherheitsrelevanter Code"));
        assertTrue(module(modules, "adapters").info().divides().contains("nach Richtung"));
        assertEquals("Handgeschrieben.", module(modules, "fe-state").info().builds()); // rules file wins
        assertFalse(module(modules, "flows").info().builds().isBlank());
        assertFalse(module(modules, "logic").info().divides().isBlank());
    }

    @Test
    void aSegmentClaimedByTwoModulesAppearsInBothAndRemovingOneDoesNotAffectTheOther() throws Exception {
        List<SegmentFacts> segs = List.of(
                facts("s1", "a/FooMapper.java", "quick-wins", "Mapper", "adapter", null, List.of("security"), "mapping", false),
                facts("s2", "b/Bar.java", "logic", "b/Bar.java", null, null, List.of(), null, false),
                facts("s3", "c/Baz.java", "logic", "c/Baz.java", null, null, List.of(), null, true));
        CategoryContext ctx = new CategoryContext(segs, List.of(), List.of("Übersetzungen", "Mapper"));
        List<CategoryModule> modules = CategoryModules.build(rules(), List.of());

        assertEquals(List.of("s1"), ids(module(modules, "quick-wins").groups(ctx)));
        assertEquals(List.of("s1"), ids(module(modules, "security").groups(ctx)));
        assertEquals("adapters/mapping-dto-modell", module(modules, "adapters").groups(ctx).getFirst().id());
        assertEquals(List.of("s2"), ids(module(modules, "logic").groups(ctx))); // s3 is part of a flow

        List<CategoryModule> without = modules.stream().filter(m -> !m.id().equals("security")).toList();
        assertEquals(ids(module(modules, "quick-wins").groups(ctx)), ids(module(without, "quick-wins").groups(ctx)));
    }

    @Test
    void flowModuleHasOneGroupPerFlowAndItsOwnView() {
        Flow f = new Flow("flow-1", "a → b", List.of(new FlowStep("s1", 1, 0, null), new FlowStep("s2", 2, 1, "s1")), List.of());
        FlowCategory flows = new FlowCategory(new CategoryInfo("flows", "Datenflüsse", "flow", "", "", ""));
        List<Group> groups = flows.groups(new CategoryContext(List.of(), List.of(f), List.of()));
        assertEquals("flows/flow-1", groups.getFirst().id());
        assertEquals("flow-1", groups.getFirst().flowId());
        assertEquals(List.of("s1", "s2"), groups.getFirst().segmentIds());
        assertEquals(CategoryModule.VIEW_FLOW, flows.view());
    }

    @Test
    void additionalModulesAreAppendedAndMayNotReuseAnId() throws Exception {
        CategoryModule extra = GroupedCategory.byRule(new CategoryInfo("ext", "Extern", "code", "", "", ""));
        List<CategoryModule> modules = CategoryModules.build(rules(), List.of(extra));
        assertEquals("ext", modules.getLast().id());
        CategoryModule clash = GroupedCategory.byRule(new CategoryInfo("logic", "Doppelt", "code", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> CategoryModules.build(rules(), List.of(clash)));
    }

    private static List<String> ids(List<Group> groups) {
        return groups.stream().flatMap(g -> g.segmentIds().stream()).toList();
    }
}
