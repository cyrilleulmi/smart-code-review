package dev.smartcodereview.analysis;

import dev.smartcodereview.analysis.category.CategoryInfo;
import dev.smartcodereview.analysis.category.CategoryModule;
import dev.smartcodereview.analysis.category.FlowCategory;
import dev.smartcodereview.analysis.category.GroupedCategory;
import dev.smartcodereview.analysis.category.SegmentFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Turns the categories of a rules file into category modules, in file order. Every category becomes one
 * {@link CategoryModule}; what a module looks at (rule groups, architecture layer, frontend aspect, concern) is
 * derived from the rules, and so is its description in the UI - unless the rules file states one.
 *
 * <p>Extension point: {@link #build} takes additional modules. They are appended after the rule-defined ones and
 * must not reuse an id. Nothing supplies external modules yet; the hook only keeps the call sites stable.
 */
final class CategoryModules {

    /** Architecture categories (rules file ids) and the layer they show. */
    private static final Map<String, String> LAYER_CATEGORIES = Map.of("domain", Structure.DOMAIN, "usecases", Structure.USECASE,
            "application", Structure.APPLICATION, "adapters", Structure.ADAPTER);
    private static final Map<String, String> LAYER_LABELS = Map.of(Structure.DOMAIN, "Domäne", Structure.USECASE, "Use Cases",
            Structure.APPLICATION, "Application Layer", Structure.ADAPTER, "Adapter");
    private static final Set<String> BY_FILE = Set.of("logic", "tests");

    private CategoryModules() {
    }

    static List<CategoryModule> build(Rules rules, List<CategoryModule> additional) {
        List<CategoryModule> modules = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Rules.CategoryDef def : rules.categories) {
            if (ids.add(def.id())) {
                modules.add(module(rules, def));
            }
        }
        for (CategoryModule m : additional) {
            if (!ids.add(m.id())) {
                throw new IllegalArgumentException("Category id already in use: " + m.id());
            }
            modules.add(m);
        }
        return modules;
    }

    private static CategoryModule module(Rules rules, Rules.CategoryDef def) {
        String id = def.id();
        String layer = LAYER_CATEGORIES.get(id);
        boolean aspect = rules.aspects.stream().anyMatch(r -> r.category().equals(id));
        boolean concern = rules.concerns.stream().anyMatch(r -> r.category().equals(id));
        CategoryInfo info = new CategoryInfo(id, def.label(), def.icon(), def.description(),
                def.builds().isBlank() ? builds(rules, id, layer, aspect, concern) : def.builds(),
                def.divides().isBlank() ? divides(rules, id, layer, aspect, concern) : def.divides());
        if (id.equals(FlowCategory.ID)) {
            return new FlowCategory(info);
        }
        if (id.equals("logic")) {
            // what is neither classified by a rule nor part of a flow
            return new GroupedCategory(info, CategoryModule.ORIGIN_RULES, s -> id.equals(s.category()) && !s.inFlow(),
                    s -> false, SegmentFacts::path);
        }
        Predicate<SegmentFacts> extra = s -> false;
        Function<SegmentFacts, String> extraGroup = SegmentFacts::path;
        if (layer != null) {
            extra = extra.or(s -> layer.equals(s.layer()));
            if (layer.equals(Structure.ADAPTER)) {
                extraGroup = CategoryModules::adapterGroup;
            }
        }
        if (aspect) {
            extra = extra.or(s -> id.equals(s.aspectCategory()));
        }
        if (concern) {
            extra = extra.or(s -> s.concerns().contains(id));
        }
        return new GroupedCategory(info, CategoryModule.ORIGIN_RULES, s -> id.equals(s.category()), extra, extraGroup);
    }

    private static String adapterGroup(SegmentFacts s) {
        return switch (String.valueOf(s.adapterDirection())) {
            case "in" -> "Eingehend (REST, Messaging, UI, …)";
            case "out" -> "Ausgehend (Persistenz, Clients, …)";
            case "mapping" -> "Mapping (DTO ↔ Modell)";
            case "config" -> "Konfiguration (Transport, Security)";
            default -> "Weitere Adapter";
        };
    }

    // ------------------------------------------------------------------ descriptions

    private static String builds(Rules rules, String id, String layer, boolean aspect, boolean concern) {
        if (id.equals(FlowCategory.ID)) {
            return "Jede geänderte Funktion ohne geänderten Aufrufer ist ein Einstiegspunkt. Ab dort werden alle erreichten "
                    + "geänderten Funktionen gesammelt: über Aufrufe, über REST (literale URLs und generierte OpenAPI-Clients) "
                    + "und ab Message-Listenern. Ein Fluss braucht mindestens zwei Segmente, davon eines mit Logik. Tests sind nie "
                    + "Teil eines Flusses; eine gemeinsam genutzte Funktion kann in mehreren Flüssen vorkommen.";
        }
        if (id.equals("logic")) {
            return "Alles, was keine Regel einer anderen Kategorie zugeordnet hat und nicht Teil eines Datenflusses ist: "
                    + "geänderte Logik ohne erkennbaren Aufrufzusammenhang zu anderen Änderungen.";
        }
        List<String> parts = new ArrayList<>();
        String byRule = ruleGroups(rules, id);
        if (!byRule.isEmpty()) {
            parts.add("Segmente, auf die eine Regel dieser Kategorie passt. " + byRule + ".");
        }
        String also = parts.isEmpty() ? "" : "Zusätzlich ";
        if (layer != null) {
            parts.add((also.isEmpty() ? "Jeder" : also + "jeder") + " Code der Architektur-Schicht «" + LAYER_LABELS.get(layer)
                    + "». Die Schicht wird aus den Ordnern des Repositories erkannt und nur vergeben, wenn es Ports & Adapters "
                    + "oder ein Schichten-Layout mit Adaptern nutzt.");
        }
        if (aspect) {
            parts.add((also.isEmpty() ? "Frontend-Code" : also + "Frontend-Code") + " mit passendem Merkmal: " + reasons(rules.aspects, id)
                    + ". Jedes Frontend-Segment kommt in höchstens eine Frontend-Kategorie (die erste passende).");
        }
        if (concern) {
            parts.add((also.isEmpty() ? "Code" : also + "Code") + " aus Frontend und Backend mit passendem Querschnittsthema: "
                    + reasons(rules.concerns, id) + ". Ein Segment kann in mehreren Querschnitts-Kategorien stehen.");
        }
        if (id.equals("tests")) {
            parts.add("Test-Dateien werden in Testfälle zerlegt, damit jeder mit dem getesteten Code verknüpft werden kann.");
        }
        return parts.isEmpty() ? "Keine Regel weist dieser Kategorie Code zu." : String.join(" ", parts);
    }

    /** "Übersetzungen: Übersetzungsdatei · Mapper: Mapper-Datei; reine Feldzuweisungen": each group once, with its reasons. */
    private static String ruleGroups(Rules rules, String id) {
        Map<String, Set<String>> byGroup = new LinkedHashMap<>();
        rules.rules.stream().filter(r -> r.category().equals(id)).forEach(r -> {
            Set<String> reasons = byGroup.computeIfAbsent(r.group(), k -> new LinkedHashSet<>());
            if (!r.reason().isBlank() && !r.reason().equalsIgnoreCase(r.group())) {
                reasons.add(r.reason());
            }
        });
        return byGroup.entrySet().stream()
                .map(e -> e.getKey() + (e.getValue().isEmpty() ? "" : ": " + String.join("; ", e.getValue())))
                .collect(Collectors.joining(" · "));
    }

    private static String divides(Rules rules, String id, String layer, boolean aspect, boolean concern) {
        if (id.equals(FlowCategory.ID)) {
            return "Ein Fluss pro Einstiegspunkt, benannt nach der Aufrufkette (bei Endpunkten und Listenern mit Auslöser). "
                    + "Die Blöcke stehen in Aufrufreihenfolge, eingerückt nach Tiefe, mit Aufrufdiagramm.";
        }
        if (BY_FILE.contains(id)) {
            return "Nach Datei.";
        }
        List<String> ruleGroups = rules.rules.stream().filter(r -> r.category().equals(id)).map(Rules.Rule::group).distinct().toList();
        List<String> parts = new ArrayList<>();
        if (!ruleGroups.isEmpty()) {
            parts.add("Nach Regel-Gruppe: " + String.join(", ", ruleGroups) + ".");
        }
        if (Structure.ADAPTER.equals(layer)) {
            parts.add("Adapter nach Richtung: eingehend, ausgehend, Mapping, Konfiguration, weitere.");
        } else if (layer != null || aspect || concern) {
            parts.add(ruleGroups.isEmpty() ? "Nach Datei." : "Zusätzliche Segmente nach Datei.");
        }
        return parts.isEmpty() ? "Nach Datei." : String.join(" ", parts);
    }

    private static String reasons(List<Rules.Rule> list, String category) {
        return list.stream().filter(r -> r.category().equals(category))
                .map(r -> r.reason().isBlank() ? r.id() : r.reason()).distinct().collect(Collectors.joining(", "));
    }
}
