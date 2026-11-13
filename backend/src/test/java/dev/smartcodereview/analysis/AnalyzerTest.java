package dev.smartcodereview.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    @Test
    void parsesPatchWithLineNumbersAndKeys() {
        List<Hunk> hunks = PatchParser.parse("@@ -1,3 +1,4 @@ class A\n a\n-b\n+B\n+c\n d");
        assertEquals(1, hunks.size());
        List<Model.DiffLine> lines = hunks.getFirst().lines();
        assertEquals(List.of("ctx", "del", "add", "add", "ctx"), lines.stream().map(Model.DiffLine::type).toList());
        assertEquals(List.of("N1", "O2", "N2", "N3", "N4"), lines.stream().map(Model.DiffLine::key).toList());
    }

    @Test
    void extractsSymbolsIgnoringStringsAndComments() {
        String source = """
                /** Service. */
                export class EventService {
                  private readonly http = inject(HttpClient);

                  // braces in strings must not confuse the matcher: "{"
                  save(event: Event): Observable<Event> {
                    const url = `/api/events/${event.id}`;
                    return this.http.put<Event>(url, event);
                  }
                }

                export const formatTitle = (e: Event) => e.title.trim();
                """;
        List<SymbolExtractor.Symbol> symbols = SymbolExtractor.extract(CodeText.of(source), "event.service.ts");
        SymbolExtractor.Symbol cls = symbols.stream().filter(s -> s.name().equals("EventService")).findFirst().orElseThrow();
        SymbolExtractor.Symbol save = symbols.stream().filter(s -> s.name().equals("save")).findFirst().orElseThrow();
        assertEquals(1, cls.startLine()); // includes the doc comment
        assertEquals(10, cls.endLine());
        assertEquals(5, save.startLine()); // includes the line comment above
        assertEquals(9, save.endLine());
        assertEquals("EventService", save.container());
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("formatTitle") && s.kind().equals("function")));
    }

    @Test
    void buildsFlowAcrossFilesAndRecognisesQuickWins() throws Exception {
        String component = """
                import { EventService } from './event.service';

                export class EventPage {
                  private readonly events = inject(EventService);

                  submit(form: Form): void {
                    const event = toEvent(form);
                    this.events.saveEvent(event).subscribe();
                  }
                }
                """;
        String service = """
                import { HttpClient } from '@angular/common/http';

                export class EventService {
                  saveEvent(event: Event): Observable<Event> {
                    return this.http.post<Event>('/api/events', event);
                  }
                }
                """;
        Snapshot snapshot = new Snapshot(1, "now", info(), List.of(
                added("src/app/event-page.component.ts", component),
                added("src/app/event.service.ts", service),
                added("src/assets/i18n/de.json", "{\n  \"save\": \"Speichern\"\n}\n")));

        Analysis analysis = new Analyzer(defaultRules()).analyze(snapshot, Map.of(
                "src/app/event-page.component.ts", component,
                "src/app/event.service.ts", service));

        Segment submit = segment(analysis, "EventPage.submit");
        Segment save = segment(analysis, "EventService.saveEvent");
        assertEquals("logic", submit.category());
        assertEquals("Banale REST-Aufrufe", save.group());
        assertEquals("Übersetzungen", analysis.segments().stream()
                .filter(s -> s.path().endsWith("de.json")).findFirst().orElseThrow().group());

        assertEquals(1, analysis.flows().size());
        Model.Flow flow = analysis.flows().getFirst();
        assertEquals(List.of(submit.id(), save.id()), flow.steps().stream().map(Model.FlowStep::segmentId).toList());
        assertEquals(8, flow.edges().getFirst().line()); // the call site
    }

    private static Segment segment(Analysis analysis, String symbol) {
        Segment s = analysis.segments().stream().filter(x -> symbol.equals(x.symbol())).findFirst().orElse(null);
        assertNotNull(s, "segment " + symbol);
        return s;
    }

    private static DiffFile added(String path, String content) {
        String[] lines = content.split("\n", -1);
        int n = content.endsWith("\n") ? lines.length - 1 : lines.length;
        StringBuilder patch = new StringBuilder("@@ -0,0 +1," + n + " @@\n");
        for (int i = 0; i < n; i++) {
            patch.append('+').append(lines[i]).append('\n');
        }
        return new DiffFile(path, null, "added", n, 0, Languages.of(path), false, PatchParser.parse(patch.toString()));
    }

    private static PullRequestInfo info() {
        return new PullRequestInfo("github", "o", "r", 1, "t", "", "me", "", false, "open", "main", "feature",
                "base", "head", "", "", 0, 0, 0, null);
    }

    private static Rules defaultRules() throws Exception {
        try (InputStream in = AnalyzerTest.class.getResourceAsStream("/rules/default-rules.json")) {
            return Rules.parse(new ObjectMapper().readTree(in), "test");
        }
    }
}
