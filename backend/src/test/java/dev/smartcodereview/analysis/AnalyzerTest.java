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

    @Test
    void everyCategoryDescribesItselfAndFlowsKeepTheirOwnView() throws Exception {
        String service = """
                export class EventService {
                  saveEvent(event: Event): Observable<Event> {
                    return this.http.post<Event>('/api/events', event);
                  }
                }
                """;
        Snapshot snapshot = new Snapshot(1, "now", info(), List.of(added("src/app/event.service.ts", service)));
        Analysis analysis = new Analyzer(defaultRules()).analyze(snapshot, Map.of("src/app/event.service.ts", service));
        assertTrue(!analysis.categories().isEmpty());
        for (Model.Category c : analysis.categories()) {
            assertTrue(c.builds() != null && !c.builds().isBlank(), c.id() + " builds");
            assertTrue(c.divides() != null && !c.divides().isBlank(), c.id() + " divides");
            assertEquals(c.id().equals("flows") ? "flow" : "groups", c.view(), c.id());
            assertTrue(c.groups().stream().allMatch(g -> g.id().startsWith(c.id() + "/")), c.id() + " group ids");
        }
    }

    @Test
    void extractsTestCases() {
        String spec = """
                import { PlayerService } from './player.service';

                describe('PlayerService', () => {
                  let service: PlayerService;

                  beforeEach(() => {
                    service = new PlayerService();
                  });

                  it('rejects numbers above 99', () => {
                    const check = () => service.validateNumber(100);
                    expect(check).toThrow();
                  });
                });
                """;
        List<SymbolExtractor.Symbol> ts = SymbolExtractor.extract(CodeText.of(spec), "player.service.spec.ts");
        SymbolExtractor.Symbol test = ts.stream().filter(s -> s.kind().equals("test")).findFirst().orElseThrow();
        assertEquals("PlayerService › rejects numbers above 99", test.displayName());
        assertEquals(10, test.startLine());
        assertEquals(13, test.endLine());
        assertTrue(ts.stream().anyMatch(s -> s.kind().equals("setup") && "PlayerService".equals(s.container())));
        assertTrue(ts.stream().noneMatch(s -> s.name().equals("check")), "helpers inside a test case are no units");

        String java = """
                class PlayerResourceTest {
                    @Test
                    @DisplayName("rejects a taken number")
                    void rejectsTakenNumber() {
                        resource.update(1L, request);
                    }

                    private PlayerUpdateRequest request(int number) {
                        return new PlayerUpdateRequest(number);
                    }
                }
                """;
        List<SymbolExtractor.Symbol> js = SymbolExtractor.extract(CodeText.of(java), "PlayerResourceTest.java");
        assertEquals("test", js.stream().filter(s -> s.name().equals("rejectsTakenNumber")).findFirst().orElseThrow().kind());
        assertEquals("method", js.stream().filter(s -> s.name().equals("request")).findFirst().orElseThrow().kind());

        String go = "package config\n\nfunc TestLocalConfigDir(t *testing.T) {\n\tLocalConfigDir()\n}\n";
        assertEquals("test", SymbolExtractor.extract(CodeText.of(go), "config/config_test.go").getFirst().kind());
    }

    @Test
    void linksTestsToCodeAndListsAllPlacesOfSharedCode() throws Exception {
        String service = """
                export class PlayerService {
                  validateNumber(n: number): void {
                    if (n > 99) {
                      throw new Error('too big');
                    }
                    checkRange(n);
                  }

                  updateNumber(n: number): void {
                    this.validateNumber(n);
                    this.store.save(n);
                  }

                  createPlayer(n: number): void {
                    this.validateNumber(n);
                    this.store.add(n);
                  }
                }

                export function checkRange(n: number): void {
                  if (n < 1) {
                    throw new Error('too small');
                  }
                }
                """;
        String spec = """
                import { PlayerService } from './player.service';

                describe('PlayerService', () => {
                  it('rejects numbers above 99', () => {
                    const service = new PlayerService();
                    expect(() => service.updateNumber(100)).toThrow();
                  });
                });
                """;
        Snapshot snapshot = new Snapshot(1, "now", info(), List.of(
                added("src/app/player.service.ts", service),
                added("src/app/player.service.spec.ts", spec)));
        Analysis analysis = new Analyzer(defaultRules()).analyze(snapshot, Map.of(
                "src/app/player.service.ts", service,
                "src/app/player.service.spec.ts", spec));

        Segment validate = segment(analysis, "PlayerService.validateNumber");
        Segment update = segment(analysis, "PlayerService.updateNumber");
        Segment range = segment(analysis, "checkRange");
        Segment test = segment(analysis, "PlayerService › rejects numbers above 99");

        // one flow per entry point; the shared callee is part of both and needs an ok in each
        assertEquals(2, analysis.flows().size());
        assertEquals(List.of("flows/flow-1", "flows/flow-2"), validate.places());
        assertEquals(2, range.places().size());
        assertEquals(1, update.places().size());
        assertEquals(List.of("tests/src/app/player.service.spec.ts"), test.places());

        // tests never become part of a flow, but are linked to what they reach
        assertTrue(analysis.flows().stream().flatMap(f -> f.steps().stream()).noneMatch(s -> s.segmentId().equals(test.id())));
        Model.TestInfo info = analysis.tests().getFirst();
        assertEquals(test.id(), info.segmentId());
        Map<String, Model.TestCoverage> covers = new java.util.HashMap<>();
        info.covers().forEach(c -> covers.put(c.segmentId(), c));
        assertTrue(covers.get(update.id()).direct());
        assertEquals(6, covers.get(update.id()).line());
        assertEquals(update.id(), covers.get(validate.id()).via());
        assertEquals(validate.id(), covers.get(range.id()).via());
        assertEquals("src/app/player.service.ts", info.subjects().getFirst().path());
        assertEquals("name", info.subjects().getFirst().how());
        assertEquals(List.of(test.id()), validate.testedBy());
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
