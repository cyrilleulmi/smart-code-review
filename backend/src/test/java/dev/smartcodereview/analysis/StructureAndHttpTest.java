package dev.smartcodereview.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAndHttpTest {

    @Test
    void matchesFrontendCallsToBackendEndpoints() {
        List<Http.Call> calls = Http.calls(List.of(
                "return this.http.put<PlayerDetail>(`/api/players/${playerId}`, { jerseyNumber });",
                "const res = await fetch('/api/events/' + id, {",
                "  method: 'DELETE',"), List.of(0, 1));
        assertEquals(new Http.Call("PUT", "/api/players/{}", 1), calls.get(0));
        assertEquals(new Http.Call("DELETE", "/api/events/{}", 2), calls.get(1));

        Http.Endpoint jaxrs = Http.annotated(List.of("    @PUT", "    @Path(\"/{playerId}\")", "    public PlayerDetailDto update(UUID id) {"),
                List.of("@Path(\"/api/players\")", "public class PlayerResource {"));
        assertEquals(new Http.Endpoint("PUT", "/api/players/{}"), jaxrs);
        assertTrue(Http.matches(calls.get(0), jaxrs));
        assertFalse(Http.matches(new Http.Call("GET", "/api/players/{}", 1), jaxrs));
        assertFalse(Http.matches(new Http.Call("PUT", "/api/teams/{}", 1), jaxrs));
        // base URL from configuration: `${this.base}/players/${id}`
        assertTrue(Http.matches(new Http.Call("PUT", "{}/players/{}", 1), jaxrs));

        Http.Endpoint spring = Http.annotated(List.of("@GetMapping(\"/{id}\")", "Event get(@PathVariable long id) {"),
                List.of("@RequestMapping(\"/api/events\")", "class EventController {"));
        assertEquals(new Http.Endpoint("GET", "/api/events/{}"), spring);
        assertEquals(new Http.Endpoint("PUT", "/api/events/{}"), Http.fileRoute("src/app/api/events/[id]/route.ts", "PUT"));
        assertEquals(List.of(new Http.Endpoint("POST", "/api/login")), Http.registered("router.post('/api/login', login);"));
        assertEquals(List.of(new Http.Endpoint("PUT", "/api/x/{}")), Http.registered("mux.HandleFunc(\"PUT /api/x/{id}\", h)"));
    }

    @Test
    void recognisesPortsAndAdapters() {
        List<String> hex = List.of("src/main/java/shop", "src/main/java/shop/domain", "src/main/java/shop/application",
                "src/main/java/shop/application/port/in", "src/main/java/shop/adapter/in/web", "src/main/java/shop/adapter/out/persistence");
        assertFalse(Structure.hexagonalEvidence(hex).isEmpty());
        assertTrue(Structure.hexagonalEvidence(List.of("backend/src/main/java/com/x/api", "backend/src/main/java/com/x/domain")).isEmpty(),
                "a layered project with a domain package is not ports & adapters");

        assertEquals(Structure.DOMAIN, Structure.layerOf("src/main/java/shop/domain/Order.java"));
        assertEquals(Structure.USECASE, Structure.layerOf("src/main/java/shop/application/port/in/PlaceOrderUseCase.java"));
        assertEquals(Structure.APPLICATION, Structure.layerOf("src/main/java/shop/application/OrderService.java"));
        assertEquals(Structure.ADAPTER, Structure.layerOf("src/main/java/shop/adapter/out/persistence/OrderJpaAdapter.java"));
        assertEquals("out", Structure.adapterDirection("src/main/java/shop/adapter/out/persistence/OrderJpaAdapter.java"));
        assertEquals("in", Structure.adapterDirection("src/main/java/shop/adapter/in/web/OrderController.java"));
        assertEquals(Structure.APPLICATION, Structure.layerOf("src/main/java/shop/application/domain/service/SendMoneyService.java"));
        assertNull(Structure.layerOf("src/main/java/shop/Main.java"));

        assertTrue(Structure.isFrontend("frontend/src/app/players/player-view.ts", null));
        assertTrue(Structure.isFrontend("src/components/Button.tsx", null));
        assertFalse(Structure.isFrontend("backend/src/main/java/x/PlayerResource.java", null));
        assertFalse(Structure.isFrontend("src/app/api/events/route.ts", null));
    }

    @Test
    void buildsOneFlowFromTheComponentThroughRestIntoTheBackend() throws Exception {
        String component = """
                import { PlayerApiService } from './player-api.service';

                @Component({ selector: 'app-player', template: '' })
                export class PlayerView {
                  saveJerseyNumber(value: string): void {
                    const n = Number.parseInt(value, 10);
                    this.api.setJerseyNumber(this.id, n).subscribe();
                  }
                }
                """;
        String service = """
                export class PlayerApiService {
                  setJerseyNumber(playerId: string, jerseyNumber: number): Observable<PlayerDetail> {
                    return this.http.put<PlayerDetail>(`/api/players/${playerId}`, { jerseyNumber });
                  }
                }
                """;
        String resource = """
                package shop.adapter.in.web;

                @Path("/api/players")
                public class PlayerResource {

                    @PUT
                    @Path("/{playerId}")
                    public PlayerDetailDto update(UUID playerId, PlayerUpdateRequest request) {
                        if (request.jerseyNumber() != null) {
                            validateJerseyNumber(request.jerseyNumber());
                        }
                        return null;
                    }

                    private static void validateJerseyNumber(int jerseyNumber) {
                        if (jerseyNumber > 99) {
                            throw new BadRequestException("too big");
                        }
                    }
                }
                """;
        String cPath = "frontend/src/app/players/player-view.ts";
        String sPath = "frontend/src/app/players/player-api.service.ts";
        String rPath = "backend/src/main/java/shop/adapter/in/web/PlayerResource.java";
        Snapshot snapshot = new Snapshot(1, "now", info(), List.of(added(cPath, component), added(sPath, service), added(rPath, resource)));
        Analysis a = new Analyzer(defaultRules()).analyze(snapshot, Map.of(cPath, component, sPath, service, rPath, resource),
                List.of("backend/src/main/java/shop/domain", "backend/src/main/java/shop/adapter/out/persistence"));

        Segment save = segment(a, "PlayerView.saveJerseyNumber");
        Segment update = segment(a, "PlayerResource.update");
        assertEquals("hexagonal", a.architecture().style());
        Model.Flow flow = a.flows().stream().filter(f -> f.steps().getFirst().segmentId().equals(save.id())).findFirst().orElseThrow();
        assertEquals(List.of("PlayerView.saveJerseyNumber", "PlayerApiService.setJerseyNumber", "PlayerResource.update",
                        "PlayerResource.validateJerseyNumber"),
                flow.steps().stream().map(s -> segmentById(a, s.segmentId()).symbol()).toList());
        Model.FlowEdge http = flow.edges().stream().filter(e -> e.to().equals(update.id())).findFirst().orElseThrow();
        assertEquals("http", http.kind());
        assertEquals("PUT /api/players/{}", http.via());

        // extra categories: adapter layer for the resource, frontend aspects for the client code
        assertEquals("adapter", update.layer());
        assertTrue(update.places().stream().anyMatch(p -> p.startsWith("adapters/")));
        assertTrue(save.frontend());
        assertTrue(save.places().stream().anyMatch(p -> p.startsWith("fe-components/")), String.valueOf(save.places()));
        assertTrue(segment(a, "PlayerApiService.setJerseyNumber").places().stream().anyMatch(p -> p.startsWith("fe-api/")));

        // components with the REST call between them
        Model.Component res = a.components().stream().filter(c -> c.name().equals("PlayerResource")).findFirst().orElseThrow();
        assertEquals("adapter-in", res.area());
        assertTrue(a.componentEdges().stream().anyMatch(e -> e.to().equals(res.id()) && e.http()));
    }

    private static Segment segment(Analysis a, String symbol) {
        return a.segments().stream().filter(s -> symbol.equals(s.symbol())).findFirst().orElseThrow(() -> new AssertionError(symbol));
    }

    private static Segment segmentById(Analysis a, String id) {
        return a.segments().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
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
        try (InputStream in = StructureAndHttpTest.class.getResourceAsStream("/rules/default-rules.json")) {
            return Rules.parse(new ObjectMapper().readTree(in), "test");
        }
    }
}
