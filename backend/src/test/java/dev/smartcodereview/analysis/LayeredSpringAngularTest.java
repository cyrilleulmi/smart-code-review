package dev.smartcodereview.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.ApiOperation;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.LineRef;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.RepoStructure;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Spring Boot + Angular repository with a layered (onion-style) package split, adapter entry points for REST
 * and JMS, an OpenAPI contract with generated clients/server interfaces, JPA entities and Liquibase migrations.
 */
class LayeredSpringAngularTest {

    private static final String BASE = "server/src/main/java/ch/example/app/";

    private static final String SPEC = """
            openapi: 3.1.0
            info:
              title: Browser API
              version: 1.0.0
            servers:
              - url: /rest
            paths:
              /players/{playerId}:
                get:
                  operationId: getPlayer
                  responses:
                    '200':
                      description: ok
                put:
                  operationId: updatePlayer
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/PlayerUpdate'
                  responses:
                    '200':
                      description: ok
            components:
              schemas:
                PlayerUpdate:
                  type: object
            """;

    private static final String COMPONENT = """
            import { Component, inject } from '@angular/core';
            import { PlayersService } from '../../../gen/api/players.service';

            @Component({ selector: 'app-player-page', template: '' })
            export class PlayerPageComponent {
              private readonly players = inject(PlayersService);

              saveJerseyNumber(playerId: string, value: number): void {
                this.players.updatePlayer(playerId, { jerseyNumber: value }).subscribe();
              }
            }
            """;

    private static final String GENERATED_CLIENT = """
            export class PlayersService {
              updatePlayer(playerId: string, body: PlayerUpdate): Observable<PlayerDto> {
                return this.httpClient.request('put', `${this.basePath}/players/${playerId}`, { body });
              }
            }
            """;

    private static final String CONTROLLER = """
            package ch.example.app.adapter.rest;

            import ch.example.app.application.service.PlayerService;

            @RestController
            @RequestMapping("/rest")
            public class PlayerController implements PlayersApi {

                private final PlayerService playerService;

                @Override
                public ResponseEntity<PlayerDto> updatePlayer(String playerId, PlayerUpdateDto update) {
                    Player player = playerService.changeJerseyNumber(playerId, update.getJerseyNumber());
                    return ResponseEntity.ok(new PlayerDto(player.getId()));
                }
            }
            """;

    private static final String SERVICE = """
            package ch.example.app.application.service;

            import ch.example.app.domain.Player;
            import ch.example.app.domain.repository.PlayerRepository;

            @Service
            public class PlayerService {

                private final PlayerRepository playerRepository;

                @Transactional
                public Player changeJerseyNumber(String playerId, int jerseyNumber) {
                    Player player = playerRepository.findById(playerId).orElseThrow();
                    player.setJerseyNumber(jerseyNumber);
                    return playerRepository.save(player);
                }

                @Transactional
                public void applyEvent(PlayerEvent event) {
                    if (event.version() <= currentVersion(event.playerId())) {
                        return;
                    }
                    changeJerseyNumber(event.playerId(), event.jerseyNumber());
                }

                private long currentVersion(String playerId) {
                    return playerRepository.findById(playerId).map(Player::getVersion).orElse(0L);
                }
            }
            """;

    private static final String LISTENER = """
            package ch.example.app.adapter.messaging;

            import ch.example.app.application.service.PlayerService;

            @Component
            public class PlayerEventListener {

                private final PlayerService playerService;

                @JmsListener(destination = "${app.players.subscription}", containerFactory = "jmsListenerContainerFactory")
                public void onMessage(Message message) throws JMSException {
                    PlayerEvent event = readEvent(message);
                    playerService.applyEvent(event);
                    message.acknowledge();
                }

                private PlayerEvent readEvent(Message message) throws JMSException {
                    return MAPPER.readValue(message.getBody(String.class), PlayerEvent.class);
                }
            }
            """;

    private static final String ENTITY = """
            package ch.example.app.domain;

            @Entity
            @Table(name = "player")
            public class Player {

                @Id
                private String id;

                @Column(name = "jersey_number")
                private Integer jerseyNumber;

                @Version
                private long version;
            }
            """;

    private static final String REPOSITORY = """
            package ch.example.app.domain.repository;

            public interface PlayerRepository extends JpaRepository<Player, String> {

                @Query("select p from Player p where p.jerseyNumber = :number")
                List<Player> findByJerseyNumber(int number);
            }
            """;

    private static final String CHANGELOG = """
            databaseChangeLog:
              - changeSet:
                  id: 0042-jersey-number
                  author: dev
                  changes:
                    - addColumn:
                        tableName: player
                        columns:
                          - column:
                              name: jersey_number
                              type: int
            """;

    private static final String PROPERTIES = """
            spring.jms.listener.auto-startup=true
            spring.jpa.hibernate.ddl-auto=validate
            """;

    private static final String SECURITY = """
            package ch.example.app.adapter.configuration;

            @Configuration
            public class SecurityConfiguration {

                @Bean
                SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                    return http.csrf(c -> c.disable()).build();
                }
            }
            """;

    @Test
    void readsOperationsFromAnOpenApiContract() {
        List<ApiOperation> ops = OpenApi.operations("api/openapi.yaml", SPEC);
        assertEquals(2, ops.size(), ops.toString());
        assertEquals(new ApiOperation("api/openapi.yaml", "getPlayer", "GET", "/rest/players/{}", 9, 13), ops.get(0));
        assertEquals("updatePlayer", ops.get(1).operationId());
        assertEquals("PUT", ops.get(1).method());
        assertEquals(14, ops.get(1).line());
        assertEquals(23, ops.get(1).endLine(), "ends before the next top-level key");
        assertTrue(OpenApi.isContract("api/openapi-m2m.yaml"));
        assertTrue(OpenApi.isContract("docs/swagger.json"));
        assertTrue(!OpenApi.isContract("client/src/app/openapi.ts"));

        String json = """
                {
                  "openapi": "3.0.1",
                  "servers": [ { "url": "https://example.org/api" } ],
                  "paths": {
                    "/machines/{id}": {
                      "post": {
                        "operationId": "registerMachine"
                      }
                    }
                  }
                }
                """;
        assertEquals(List.of(new ApiOperation("m2m.openapi.json", "registerMachine", "POST", "/api/machines/{}", 6, 9)),
                OpenApi.operations("m2m.openapi.json", json));
    }

    @Test
    void classifiesTheLayeredSpringAngularRepository() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("api/openapi.yaml", SPEC);
        files.put("client/src/app/features/players/pages/player-page.component.ts", COMPONENT);
        files.put("client/src/app/gen/api/players.service.ts", GENERATED_CLIENT);
        files.put(BASE + "adapter/rest/PlayerController.java", CONTROLLER);
        files.put(BASE + "adapter/messaging/PlayerEventListener.java", LISTENER);
        files.put(BASE + "adapter/configuration/SecurityConfiguration.java", SECURITY);
        files.put(BASE + "application/service/PlayerService.java", SERVICE);
        files.put(BASE + "domain/Player.java", ENTITY);
        files.put(BASE + "domain/repository/PlayerRepository.java", REPOSITORY);
        files.put("server/src/main/resources/db/changelog/changes/0042-jersey-number.yaml", CHANGELOG);
        files.put("server/src/main/resources/application.properties", PROPERTIES);

        List<DiffFile> diff = new ArrayList<>();
        files.forEach((path, content) -> diff.add(added(path, content)));
        Set<String> dirs = new TreeSet<>(List.of(BASE + "application/model", BASE + "adapter/api", BASE + "adapter/mapping",
                BASE + "common", "deploy/infra/modules"));
        RepoStructure structure = new RepoStructure(List.copyOf(dirs), false, List.of("api/openapi.yaml"),
                OpenApi.operations("api/openapi.yaml", SPEC));
        Analysis a = new Analyzer(defaultRules()).analyze(new Snapshot(1, "now", info(), diff), files, structure);

        // layered (onion) split with adapters, not ports & adapters
        assertEquals("layered", a.architecture().style());

        // the browser calls the generated client by operationId; the controller implements the generated interface
        Segment save = segment(a, "PlayerPageComponent.saveJerseyNumber");
        Segment update = segment(a, "PlayerController.updatePlayer");
        Segment change = segment(a, "PlayerService.changeJerseyNumber");
        Model.Flow ui = a.flows().stream().filter(f -> f.steps().getFirst().segmentId().equals(save.id())).findFirst().orElseThrow();
        assertEquals(List.of(save.id(), update.id(), change.id()), ui.steps().stream().map(Model.FlowStep::segmentId).toList());
        Model.FlowEdge http = ui.edges().stream().filter(e -> e.to().equals(update.id())).findFirst().orElseThrow();
        assertEquals("http", http.kind());
        assertEquals("PUT /rest/players/{} (updatePlayer)", http.via());
        assertTrue(save.places().stream().anyMatch(p -> p.startsWith("fe-api/")), String.valueOf(save.places()));

        // the JMS listener is an incoming adapter and the trigger of its own flow
        Segment onMessage = segment(a, "PlayerEventListener.onMessage");
        Model.Flow jms = a.flows().stream().filter(f -> f.steps().getFirst().segmentId().equals(onMessage.id())).findFirst().orElseThrow();
        assertTrue(jms.title().startsWith("JMS-Listener: onMessage"), jms.title());
        assertTrue(jms.steps().stream().anyMatch(s -> s.segmentId().equals(change.id())), "listener reaches the shared service method");
        assertTrue(onMessage.places().stream().anyMatch(p -> p.startsWith("adapters/eingehend")), String.valueOf(onMessage.places()));
        assertTrue(onMessage.places().stream().anyMatch(p -> p.startsWith("messaging/")), String.valueOf(onMessage.places()));

        // cross-cutting concerns
        assertTrue(change.places().stream().anyMatch(p -> p.startsWith("transactions/")), String.valueOf(change.places()));
        assertTrue(change.places().stream().anyMatch(p -> p.startsWith("persistence/")), String.valueOf(change.places()));
        assertEquals("application", change.layer());
        Segment filterChain = segment(a, "SecurityConfiguration.filterChain");
        assertTrue(filterChain.places().stream().anyMatch(p -> p.startsWith("security/")), String.valueOf(filterChain.places()));
        assertTrue(filterChain.places().contains("adapters/konfiguration-transport-security"), String.valueOf(filterChain.places()));

        // persistence: migration and entity of the same table form one group and link to each other
        Segment migration = byPath(a, "server/src/main/resources/db/changelog/changes/0042-jersey-number.yaml");
        assertEquals("persistence", migration.category());
        assertEquals("Tabelle player", migration.group());
        Segment entity = segment(a, "Player");
        assertEquals("persistence", entity.category());
        assertEquals("Tabelle player", entity.group());
        assertTrue(refs(migration).stream().anyMatch(r -> r.segmentId().equals(entity.id())));
        assertTrue(refs(entity).stream().anyMatch(r -> r.segmentId().equals(migration.id())));
        Segment repository = segment(a, "PlayerRepository");
        assertEquals("persistence", repository.category(), "a repository with JPQL is no quick win: " + a.segments().stream()
                .filter(s -> s.path().endsWith("PlayerRepository.java")).map(s -> s.symbol() + "/" + s.symbolKind() + "/" + s.ruleId()).toList());

        // the contract links to caller and implementation; generated code and runtime configuration
        Segment contract = byPath(a, "api/openapi.yaml");
        assertEquals("api-contract", contract.category());
        assertTrue(refs(contract).stream().anyMatch(r -> r.segmentId().equals(update.id())), String.valueOf(refs(contract)));
        assertTrue(refs(contract).stream().anyMatch(r -> r.segmentId().equals(save.id())));
        assertEquals("quick-wins", byPath(a, "client/src/app/gen/api/players.service.ts").category());
        Segment properties = byPath(a, "server/src/main/resources/application.properties");
        assertEquals("runtime", properties.category());
        assertTrue(properties.places().stream().anyMatch(p -> p.startsWith("messaging/")), String.valueOf(properties.places()));
        assertTrue(properties.places().stream().anyMatch(p -> p.startsWith("persistence/")), String.valueOf(properties.places()));
    }

    @Test
    void recognisesAdapterRolesOfALayeredRepository() {
        assertEquals("in", Structure.adapterDirection(BASE + "adapter/messaging/PlayerEventListener.java"));
        assertEquals("in", Structure.adapterDirection(BASE + "adapter/api/MachineController.java"));
        assertEquals("in", Structure.adapterDirection(BASE + "adapter/errorhandling/GlobalExceptionHandler.java"));
        assertEquals("mapping", Structure.adapterDirection(BASE + "adapter/mapping/PlayerRestMapper.java"));
        assertEquals("config", Structure.adapterDirection(BASE + "adapter/configuration/ServiceBusConfiguration.java"));
        assertEquals(Structure.DOMAIN, Structure.layerOf(BASE + "domain/repository/PlayerRepository.java"));
        assertEquals(Structure.APPLICATION, Structure.layerOf(BASE + "application/model/PlayerResult.java"));
        assertEquals(null, Structure.layerOf(BASE + "common/NotFoundException.java"));
        // infrastructure as code below deploy/ is no adapter layer
        assertTrue(Structure.hexagonalEvidence(List.of("deploy/infra", "server/src/main/java/x/domain")).isEmpty());
        assertTrue(Structure.isFrontend("client/src/app/features/players/pages/player-page.component.ts", null));
    }

    private static List<LineRef> refs(Segment s) {
        return s.lines().stream().filter(l -> l.refs() != null).flatMap(l -> l.refs().stream()).toList();
    }

    private static Segment segment(Analysis a, String symbol) {
        return a.segments().stream().filter(s -> symbol.equals(s.symbol())).findFirst().orElseThrow(() -> new AssertionError(symbol));
    }

    private static Segment byPath(Analysis a, String path) {
        return a.segments().stream().filter(s -> s.path().equals(path)).findFirst().orElseThrow(() -> new AssertionError(path));
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
        try (InputStream in = LayeredSpringAngularTest.class.getResourceAsStream("/rules/default-rules.json")) {
            return Rules.parse(new ObjectMapper().readTree(in), "test");
        }
    }
}
