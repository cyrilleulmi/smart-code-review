/**
 * English for the German texts the backend sends (analysis, default-rules.json, error messages). They stay German in
 * the data files - ids are derived from them and agents read them - and are translated only for display
 * (serverText in i18n.ts).
 *
 * SERVER_EN: fixed texts, mostly from default-rules.json (category labels and descriptions, rule groups and reasons,
 * Erwartungs-Regeln, test smells). SERVER_EN_PATTERNS: texts the backend composes (Java, e.g. CategoryModules,
 * CaseChecks, CommitChecks, the providers); `tr` translates a part. A text of an own rules.json that is not listed here
 * is shown as it is. When a German text in the backend changes, change its key here too.
 *
 * i18n.spec.ts checks that every text of default-rules.json is translated.
 */
export const SERVER_EN: Record<string, string> = {
  // ------------------------------------------------------------------ categories (default-rules.json)
  'Quick wins': 'Quick wins',
  'Höchstwahrscheinlich irrelevante oder rein mechanische Änderungen: generierter Code, Übersetzungen, DTOs, Mapper, banale REST-Aufrufe, Imports. Schnell überfliegen und abhaken.':
    'Most likely irrelevant or purely mechanical changes: generated code, translations, DTOs, mappers, trivial REST calls, imports. Skim them quickly and check them off.',
  'Datenflüsse': 'Data flows',
  'Geänderte Funktionen, die sich gegenseitig aufrufen – in Aufrufreihenfolge, damit man den Fluss am Stück nachvollziehen kann. Auch über REST (literale URLs oder generierte OpenAPI-Clients) und ab Message-Listenern.':
    'Changed functions that call each other – in call order, so the flow can be followed in one go. Also over REST (literal URLs or generated OpenAPI clients) and from message listeners.',
  'API-Verträge': 'API contracts',
  'OpenAPI-, Proto- und GraphQL-Verträge: Änderungen betreffen Client und Server gleichzeitig – die Sprünge führen zu den geänderten Aufrufern und Implementierungen der Operation.':
    'OpenAPI, Proto and GraphQL contracts: changes affect client and server at the same time – the links lead to the changed callers and implementations of the operation.',
  'Domäne': 'Domain',
  'Architektur: fachliche Kernmodelle, Regeln, Entities (bei Schichten-Architektur auch Repository-Interfaces).':
    'Architecture: core domain models, business rules, entities (in a layered architecture also repository interfaces).',
  'Use Cases': 'Use cases',
  'Ports & Adapters: Anwendungsfälle, die die Domäne für einen konkreten Ablauf orchestrieren.':
    'Ports & adapters: use cases that orchestrate the domain for a concrete flow.',
  'Application Layer': 'Application layer',
  'Architektur: Application Services und Ports – Abläufe, Transaktionsgrenzen, Aufrufe externer Systeme.':
    'Architecture: application services and ports – flows, transaction boundaries, calls to external systems.',
  'Adapter': 'Adapters',
  'Architektur: Technik an den Rändern – eingehend (REST, Messaging, UI), ausgehend (Persistenz, externe Clients), Mapping und Konfiguration.':
    'Architecture: technology at the edges – inbound (REST, messaging, UI), outbound (persistence, external clients), mapping and configuration.',
  'Sicherheit & Berechtigungen': 'Security & permissions',
  'Authentisierung, Rollen, Filter, CSRF/CORS, Interceptors und Guards, Token-Weitergabe – auch in Konfigurationsdateien. Braucht einen Security-Blick, selbst wenn kein Controller geändert ist.':
    'Authentication, roles, filters, CSRF/CORS, interceptors and guards, token propagation – also in configuration files. Needs a security review even when no controller changed.',
  'Messaging & Events': 'Messaging & events',
  'Listener, Topics, Bestätigung (acknowledge), Wiederholung und Dead-Letter: Parsing, Validierung, Transaktion und Acknowledge als einen Ablauf prüfen.':
    'Listeners, topics, acknowledgement, retries and dead letters: check parsing, validation, transaction and acknowledge as one flow.',
  'Transaktionen & Nebenläufigkeit': 'Transactions & concurrency',
  '@Transactional, optimistisches Locking (@Version), Retries, Virtual Threads und parallele Schritte: Reihenfolge, Grenzen und Teilfehler prüfen.':
    '@Transactional, optimistic locking (@Version), retries, virtual threads and parallel steps: check order, boundaries and partial failures.',
  'Datenbank & Migrationen': 'Database & migrations',
  'Migrationen (Liquibase/Flyway/SQL), JPA-Entities, Repositories und Queries. Migration und Entity derselben Tabelle stehen zusammen.':
    'Migrations (Liquibase/Flyway/SQL), JPA entities, repositories and queries. The migration and the entity of the same table are shown together.',
  'Migrationen und JPA-Entities, die dieselbe Tabelle nennen, kommen in die Gruppe «Tabelle x» und verweisen aufeinander; übrige nach Regel-Gruppe (Migrationen, Repositories & Queries, Entitäten). Datenbankzugriff und -Konfiguration aus anderen Kategorien nach Datei.':
    'Migrations and JPA entities that name the same table go into the group “Table x” and link to each other; the rest by rule group (migrations, repositories & queries, entities). Database access and configuration from other categories by file.',
  'Konfiguration, Build & Betrieb': 'Configuration, build & operations',
  'Profile und application.properties, Abhängigkeiten und Build, Docker, CI, Deployment und Infrastruktur – wirken zur Laufzeit, auch ohne Codeänderung.':
    'Profiles and application.properties, dependencies and build, Docker, CI, deployment and infrastructure – take effect at runtime, even without a code change.',
  'Frontend: Routing & Navigation': 'Frontend: routing & navigation',
  'Routen, Guards, Seiten-Einstiege: Wer kommt wohin, was ist geschützt?': 'Routes, guards, page entry points: who gets where, what is protected?',
  'Frontend: Zustand & Stores': 'Frontend: state & stores',
  'Stores, Reducer, Effects, gemeinsamer Zustand: Woher kommen die Daten im UI, wer ändert sie?':
    'Stores, reducers, effects, shared state: where does the data in the UI come from, who changes it?',
  'Frontend: API-Anbindung': 'Frontend: API integration',
  'HTTP-Aufrufe ans Backend: URL, Methode, Fehlerbehandlung, Ladezustand.': 'HTTP calls to the backend: URL, method, error handling, loading state.',
  'Frontend: Formulare & Validierung': 'Frontend: forms & validation',
  'Eingaben, Validierung, Absenden – was passiert bei ungültigen Werten?': 'Input, validation, submit – what happens with invalid values?',
  'Frontend: Komponenten-Logik': 'Frontend: component logic',
  'Verhalten der UI-Komponenten: Event-Handler, Lifecycle, berechnete Werte.': 'Behaviour of the UI components: event handlers, lifecycle, computed values.',
  'Frontend: Templates & Markup': 'Frontend: templates & markup',
  'Was der Benutzer sieht: HTML/JSX, Bindings, Bedingungen, Barrierefreiheit.': 'What the user sees: HTML/JSX, bindings, conditions, accessibility.',
  'Einzelne Änderungen': 'Single changes',
  'Geänderte Logik ohne erkennbaren Aufrufzusammenhang zu anderen Änderungen.': 'Changed logic without a recognisable call relation to other changes.',
  'Tests': 'Tests',
  'Geänderte Tests – mit den Komponenten und Codestellen, die sie (statisch ermittelt) aufrufen.':
    'Changed tests – with the components and code locations they call (determined statically).',
  'Styles & Assets': 'Styles & assets',
  'CSS, Bilder, Icons und andere Assets.': 'CSS, images, icons and other assets.',

  // ------------------------------------------------------------------ rule groups and reasons (default-rules.json)
  'Generiert & Lockfiles': 'Generated & lockfiles',
  'Generierte Datei oder Lockfile': 'Generated file or lockfile',
  'Übersetzungen': 'Translations',
  'Übersetzungsdatei': 'Translation file',
  'Test-Datei': 'Test file',
  'Style- oder Asset-Datei': 'Style or asset file',
  'Migrationen': 'Migrations',
  'Datenbank-Migration': 'Database migration',
  'Schnittstellen-Vertrag (Client und Server werden daraus generiert)': 'Interface contract (client and server are generated from it)',
  'Laufzeit-Konfiguration & Profile': 'Runtime configuration & profiles',
  'Konfiguration, die zur Laufzeit wirkt (Profile, Datenbank, Listener, Security)': 'Configuration that takes effect at runtime (profiles, database, listeners, security)',
  'Deployment, CI & Infrastruktur': 'Deployment, CI & infrastructure',
  'Container, Pipeline, Deployment oder Infrastruktur': 'Container, pipeline, deployment or infrastructure',
  'Build & Abhängigkeiten': 'Build & dependencies',
  'Abhängigkeiten oder Build-Schritte (Versionen, Code-Generierung, Plugins)': 'Dependencies or build steps (versions, code generation, plugins)',
  'Dokumentation': 'Documentation',
  'Werkzeug-Konfiguration': 'Tool configuration',
  'Werkzeug-/Projekt-Konfiguration (Compiler, Linter, Editor)': 'Tool/project configuration (compiler, linter, editor)',
  'DTOs & Typen': 'DTOs & types',
  'DTO-/Modell-Datei (Name)': 'DTO/model file (name)',
  'Mapper': 'Mappers',
  'Mapper-Datei (Name)': 'Mapper file (name)',
  'Imports & Formatierung': 'Imports & formatting',
  'Nur Imports/Exports geändert': 'Only imports/exports changed',
  'Nur Formatierung / verschobene Zeilen (gleicher Inhalt)': 'Only formatting / moved lines (same content)',
  'Repositories & Queries': 'Repositories & queries',
  'Repository-Interface / Query': 'Repository interface / query',
  'Entitäten (JPA)': 'Entities (JPA)',
  'JPA-Entity (Tabellenstruktur)': 'JPA entity (table structure)',
  'Reine Typdeklaration (interface / type / enum)': 'Pure type declaration (interface / type / enum)',
  'Reine Feldzuweisungen in einer Mapping-Funktion': 'Plain field assignments in a mapping function',
  'Banale REST-Aufrufe': 'Trivial REST calls',
  'Einfacher REST-Aufruf / Durchreichen ohne eigene Logik': 'Simple REST call / pass-through without logic of its own',
  'Nur Typ-/Felddeklarationen': 'Only type/field declarations',
  'Deklarative Felder & Konfiguration': 'Declarative fields & configuration',
  'Nur literale Werte (Felddefinitionen, Labels, Optionen)': 'Only literal values (field definitions, labels, options)',
  'Logging': 'Logging',
  'Nur Logging-Ausgaben': 'Only logging output',
  'Routing-Datei': 'Routing file',
  'Routing-Konfiguration': 'Routing configuration',
  'Store-/State-Datei': 'Store/state file',
  'Gemeinsamer Zustand': 'Shared state',
  'HTTP-Aufruf ans Backend': 'HTTP call to the backend',
  'Formular / Validierung': 'Form / validation',
  'Template-Datei': 'Template file',
  'Vor allem Markup (JSX / Template)': 'Mostly markup (JSX / template)',
  'Komponente': 'Component',
  'Sicherheitsrelevanter Code': 'Security-relevant code',
  'Security-Datei': 'Security file',
  'Security-Konfiguration': 'Security configuration',
  'Messaging / Events': 'Messaging / events',
  'Messaging-Adapter': 'Messaging adapter',
  'Messaging-Konfiguration': 'Messaging configuration',
  'Transaktion / Nebenläufigkeit': 'Transaction / concurrency',
  'Datenbankzugriff': 'Database access',
  'Datenbank-Konfiguration': 'Database configuration',

  // ------------------------------------------------------------------ Erwartungs-Regeln (testRules)
  'Zugriffsprüfung geändert → Test für verweigerten Zugriff': 'Access check changed → test for denied access',
  'Test für den verweigerten Zugriff (401/403) fehlt': 'Test for the denied access (401/403) is missing',
  'Neuer Endpunkt → Test über HTTP': 'New endpoint → test over HTTP',
  'Neuer Endpunkt ohne Test über HTTP': 'New endpoint without a test over HTTP',
  'Validierung geändert → Test mit ungültigem Wert oder Grenzwert': 'Validation changed → test with an invalid or boundary value',
  'Test mit ungültigem Wert oder Grenzwert fehlt': 'Test with an invalid or boundary value is missing',
  '@Transactional geändert → Integrationstest': '@Transactional changed → integration test',
  'Integrationstest für die Transaktion fehlt': 'Integration test for the transaction is missing',
  'Message-Listener geändert → Test, der ihn aufruft': 'Message listener changed → test that calls it',
  'Test, der den Listener aufruft, fehlt': 'Test that calls the listener is missing',

  // ------------------------------------------------------------------ Test-Smells (testSmells)
  'keine Assertion': 'no assertion',
  'Der Test prüft nichts: er läuft durch, solange nichts wirft. Ein Fehler im Code bleibt unbemerkt.':
    'The test checks nothing: it passes as long as nothing throws. A bug in the code goes unnoticed.',
  'nur schwache Assertions': 'only weak assertions',
  'Alle Assertions prüfen nur, ob etwas existiert oder wahr ist – nicht, welchen Wert es hat.':
    'All assertions only check whether something exists or is true – not which value it has.',
  'fokussiert (fit/only)': 'focused (fit/only)',
  'Der Test (oder seine Suite) ist fokussiert: in der CI laufen nur noch fokussierte Tests, alle anderen werden übersprungen.':
    'The test (or its suite) is focused: CI runs only focused tests, all others are skipped.',
  'wartet mit sleep': 'waits with sleep',
  'Feste Wartezeit im Test: macht ihn langsam und je nach Rechner instabil. Besser auf ein Ereignis oder eine Bedingung warten (Fake-Timer, Awaitility, waitFor).':
    'Fixed wait in the test: makes it slow and, depending on the machine, flaky. Better wait for an event or a condition (fake timers, Awaitility, waitFor).',
  'mockt die getestete Einheit': 'mocks the unit under test',
  'Der Test ersetzt die Einheit, die er prüfen soll, durch einen Mock oder Spy – er prüft dann den Mock, nicht den Code.':
    'The test replaces the unit it should check with a mock or spy – it then checks the mock, not the code.',
  'deaktiviert (neu)': 'disabled (new)',
  'Ein neuer Test, der gar nicht läuft (xit, @Disabled, skip): er prüft nichts, solange er ausgeschaltet ist.':
    'A new test that does not run at all (xit, @Disabled, skip): it checks nothing while it is switched off.',

  // ------------------------------------------------------------------ fixed texts of the analysis (Java)
  'Teil eines Datenflusses': 'Part of a data flow',
  'Kein Aufrufzusammenhang zu anderen Änderungen gefunden': 'No call relation to other changes found',
  'Eingehend (REST, Messaging, UI, …)': 'Inbound (REST, messaging, UI, …)',
  'Ausgehend (Persistenz, Clients, …)': 'Outbound (persistence, clients, …)',
  'Mapping (DTO ↔ Modell)': 'Mapping (DTO ↔ model)',
  'Konfiguration (Transport, Security)': 'Configuration (transport, security)',
  'Weitere Adapter': 'Other adapters',
  'Nach Datei.': 'By file.',
  'Keine Regel weist dieser Kategorie Code zu.': 'No rule assigns code to this category.',
  'Kein Test erreicht diesen Code.': 'No test reaches this code.',
  'Nur bestehende, unveränderte Tests erreichen diesen Code – keiner wurde dafür angepasst.':
    'Only existing, unchanged tests reach this code – none was adapted for it.',
  'Die Tests, die diesen Code erreichen, stammen aus früheren Commits; kein späterer Commit passt sie an.':
    'The tests that reach this code come from earlier commits; no later commit adapts them.',

  // ------------------------------------------------------------------ error messages (providers, ReviewService)
  'Kein Token konfiguriert': 'No token configured',
  'Beim ganzen Repository gibt es keinen Pull Request, an den Kommentare gehen könnten':
    'For the whole repository there is no pull request that comments could go to',
  'Kommentar ist leer': 'Comment is empty',
  'Das Cookie enthält Zeichen, die in einem HTTP-Header nicht erlaubt sind (z. B. Zeilenumbrüche).':
    'The cookie contains characters that are not allowed in an HTTP header (e.g. line breaks).',
  'PR zuerst öffnen': 'Open the PR first',
  'Abgebrochen': 'Cancelled',
  'Eintrag zu gross': 'Entry too large',
  'path fehlt': 'path missing',
  'approved fehlt': 'approved missing',
  'Ein ganzes Repository lässt sich weder genehmigen noch mergen': 'A whole repository can neither be approved nor merged',
  'Es fehlen Genehmigungen': 'Approvals are missing',
  'Es sind noch Threads offen': 'There are unresolved threads',
  'Der Merge Request ist ein Entwurf': 'The merge request is a draft',
  'Die Pipeline muss erfolgreich sein': 'The pipeline must succeed',
  'Die Pipeline läuft noch': 'The pipeline is still running',
  'Es gibt Merge-Konflikte': 'There are merge conflicts',
  'Der Branch muss zuerst rebased werden': 'The branch must be rebased first',
  'Es sind Änderungen verlangt': 'Changes are requested',
  'Ein anderer Merge Request blockiert diesen': 'Another merge request blocks this one',
  'Externe Status-Checks müssen bestehen': 'External status checks must pass',
  'Der Merge Request ist nicht offen': 'The merge request is not open',
  'GitLab prüft noch, ob sich der Merge Request mergen lässt': 'GitLab is still checking whether the merge request can be merged',
  'resolved fehlt': 'resolved missing',
  'Kein GitHub-Token gefunden. Setze GITHUB_TOKEN oder melde dich mit `gh auth login` an.':
    'No GitHub token found. Set GITHUB_TOKEN or sign in with `gh auth login`.',
  'Kein GitLab-Token gefunden. Setze GITLAB_TOKEN (Scope: api) – ohne Token lassen sich nur öffentliche Merge Requests per URL öffnen.':
    'No GitLab token found. Set GITLAB_TOKEN (scope: api) – without a token only public merge requests can be opened by URL.',
};

type Replace = (m: RegExpExecArray, tr: (text: string) => string) => string;

/** Parts of the self-description of a category (CategoryModules.builds / divides), each consumed from the start. */
const DESCRIPTION_PARTS: [RegExp, Replace][] = [
  [/^Jede geänderte Funktion ohne geänderten Aufrufer ist ein Einstiegspunkt\. Ab dort werden alle erreichten geänderten Funktionen gesammelt: über Aufrufe, über REST \(literale URLs und generierte OpenAPI-Clients\) und ab Message-Listenern\. Ein Fluss braucht mindestens zwei Segmente, davon eines mit Logik\. Tests sind nie Teil eines Flusses; eine gemeinsam genutzte Funktion kann in mehreren Flüssen vorkommen\.\s*/,
    () => 'Every changed function without a changed caller is an entry point. From there, all changed functions it reaches are collected: through calls, over REST (literal URLs and generated OpenAPI clients) and from message listeners. A flow needs at least two segments, one of them with logic. Tests are never part of a flow; a shared function can appear in several flows. '],
  [/^Alles, was keine Regel einer anderen Kategorie zugeordnet hat und nicht Teil eines Datenflusses ist: geänderte Logik ohne erkennbaren Aufrufzusammenhang zu anderen Änderungen\.\s*/,
    () => 'Everything that no rule assigned to another category and that is not part of a data flow: changed logic without a recognisable call relation to other changes. '],
  [/^Segmente, auf die eine Regel dieser Kategorie passt\. (.+?)\.(?=\s+(?:Zusätzlich|Jeder|Frontend-Code|Code|Test-Dateien)\b|\s*$)\s*/,
    (m, tr) => `Segments matched by a rule of this category. ${ruleGroups(m[1], tr)}. `],
  [/^(Zusätzlich j|J)eder Code der Architektur-Schicht «([^»]+)»\. Die Schicht wird aus den Ordnern des Repositories erkannt und nur vergeben, wenn es Ports & Adapters oder ein Schichten-Layout mit Adaptern nutzt\.\s*/,
    (m, tr) => `${m[1].startsWith('Z') ? 'In addition, all' : 'All'} code of the architecture layer “${tr(m[2])}”. The layer is recognised from the folders of the repository and only assigned when it uses ports & adapters or a layered layout with adapters. `],
  [/^(Zusätzlich )?Frontend-Code mit passendem Merkmal: (.+?)\. Jedes Frontend-Segment kommt in höchstens eine Frontend-Kategorie \(die erste passende\)\.\s*/,
    (m, tr) => `${m[1] ? 'In addition, frontend' : 'Frontend'} code with a matching trait: ${list(m[2], ', ', tr)}. Each frontend segment goes into at most one frontend category (the first that matches). `],
  [/^(Zusätzlich )?Code aus Frontend und Backend mit passendem Querschnittsthema: (.+?)\. Ein Segment kann in mehreren Querschnitts-Kategorien stehen\.\s*/,
    (m, tr) => `${m[1] ? 'In addition, code' : 'Code'} from frontend and backend with a matching cross-cutting concern: ${list(m[2], ', ', tr)}. A segment can be in several cross-cutting categories. `],
  [/^Test-Dateien werden in Testfälle zerlegt, damit jeder mit dem getesteten Code verknüpft werden kann\. Umgekehrt zeigt die Abdeckung, welche geänderten Funktionen kein geänderter Test erreicht\.\s*/,
    () => 'Test files are split into test cases so that each can be linked to the code it tests. Conversely, the coverage shows which changed functions no changed test reaches. '],
  [/^Ein Fluss pro Einstiegspunkt, benannt nach der Aufrufkette \(bei Endpunkten und Listenern mit Auslöser\)\. Die Blöcke stehen in Aufrufreihenfolge, eingerückt nach Tiefe, mit Aufrufdiagramm\.\s*/,
    () => 'One flow per entry point, named after the call chain (for endpoints and listeners with the trigger). The blocks are in call order, indented by depth, with a call diagram. '],
  [/^Nach Regel-Gruppe: (.+?)\.(?=\s+(?:Adapter nach|Nach Datei|Zusätzliche Segmente)\b|\s*$)\s*/, (m, tr) => `By rule group: ${list(m[1], ', ', tr)}. `],
  [/^Adapter nach Richtung: eingehend, ausgehend, Mapping, Konfiguration, weitere\.\s*/,
    () => 'Adapters by direction: inbound, outbound, mapping, configuration, other. '],
  [/^Zusätzliche Segmente nach Datei\.\s*/, () => 'Additional segments by file. '],
  [/^Nach Datei\.\s*/, () => 'By file. '],
];

/** «Übersetzungen: Übersetzungsdatei · Mapper: Mapper-Datei; reine Feldzuweisungen» */
function ruleGroups(text: string, tr: (text: string) => string): string {
  return text.split(' · ').map((g) => {
    const i = g.indexOf(': ');
    return i < 0 ? tr(g) : `${tr(g.substring(0, i))}: ${list(g.substring(i + 2), '; ', tr)}`;
  }).join(' · ');
}

function list(text: string, sep: string, tr: (text: string) => string): string {
  return text.split(sep).map((x) => tr(x)).join(sep);
}

/** A composed category description: every part known, else null (shown in German). */
function description(text: string, tr: (text: string) => string): string | null {
  let rest = text;
  let out = '';
  while (rest.length) {
    const part = DESCRIPTION_PARTS.find(([re]) => re.test(rest));
    if (!part) return null;
    const m = part[0].exec(rest)!;
    out += part[1](m, tr);
    rest = rest.substring(m[0].length);
  }
  return out.trim();
}

const n = (s: string) => Number(s);

export const SERVER_EN_PATTERNS: [RegExp, Replace][] = [
  // category descriptions composed by CategoryModules (builds / divides)
  [/^(Jede geänderte Funktion|Alles, was keine Regel|Segmente, auf die|Jeder Code|Frontend-Code|Code aus Frontend|Test-Dateien|Ein Fluss pro|Nach Regel-Gruppe|Adapter nach|Zusätzliche Segmente)[\s\S]*$/,
    (m, tr) => description(m[0], tr) ?? m[0]],
  // flow groups, persistence groups, references, gaps (Analyzer, FlowCategory)
  [/^(\d+) Schritte$/, (m) => `${m[1]} steps`],
  [/^Tabelle (.+)$/, (m) => `Table ${m[1]}`],
  [/^aufgerufen von (.+)$/, (m) => `called by ${m[1]}`],
  [/^(\d+) weitere Zeilen \(zu gross für die Anzeige\)$/, (m) => `${m[1]} more lines (too large to show)`],
  // Fall- und Effekt-Abgleich (CaseChecks) and Fallmatrix column labels (CaseMatrices)
  [/^Grenze (.+)$/, (m) => `Boundary ${m[1]}`],
  [/^wirft (.+)$/, (m) => `throws ${m[1]}`],
  [/^Fehlerausgang (.+)$/, (m) => `error exit ${m[1]}`],
  [/^setzt (.+)$/, (m) => `sets ${m[1]}`],
  [/^speichert: (.+)$/, (m) => `saves: ${m[1]}`],
  [/^sendet: (.+)$/, (m) => `sends: ${m[1]}`],
  [/^ruft (.+)$/, (m) => `calls ${m[1]}`],
  [/^(\d+) Verzweigungen, (\d+) Tests?$/, (m) => `${m[1]} branches, ${m[2]} ${n(m[2]) === 1 ? 'test' : 'tests'}`],
  [/^Wert (\d+)$/, (m) => `Value ${m[1]}`],
  // Commit-Bezug der Tests (CommitChecks)
  [/^Der Commit ist ein Fix \(Titel «([\s\S]*)»\) und ändert Code \(([\s\S]*)\), aber keinen Test\. Ein Test, der den Fehler zeigt, fehlt im Commit\.$/,
    (m) => `The commit is a fix (title “${m[1]}”) and changes code (${m[2]}), but no test. A test that shows the bug is missing from the commit.`],
  [/^Test geschrieben in (\w+) «([\s\S]*?)», Erwartung nachträglich in (\w+) «([\s\S]*?)» geändert: («[\s\S]*»)\. (?:Der Commit ändert auch anderes – vielleicht ein gewolltes neues Verhalten\.|Verdächtig: ([\s\S]*) – wurde der Test an den Code angepasst\?)$/,
    (m) => `Test written in ${m[1]} “${m[2]}”, expectation changed later in ${m[3]} “${m[4]}”: ${m[5]}. `
      + (m[6] == null
        ? 'The commit also changes other things – maybe an intended new behaviour.'
        : `Suspicious: ${m[6].replace('der Commit ändert nur Tests', 'the commit only changes tests')
          .replace('sein Titel passt Tests an', 'its title adjusts tests')} – was the test adapted to the code?`)],
  [/^([\s\S]+) geändert in (\w+), ohne Test im Commit\. ([\s\S]+)$/, (m, tr) => `${m[1]} changed in ${m[2]}, without a test in the commit. ${tr(m[3])}`],
  // error messages (providers, ReviewService)
  [/^Keine bekannte Pull- oder Merge-Request-URL: ([\s\S]*)$/, (m) => `Not a known pull or merge request URL: ${m[1]}`],
  [/^(\S+) kann hier nicht genehmigen oder mergen$/, (m) => `${m[1]} cannot approve or merge here`],
  [/^(\S+) kann hier nicht genehmigen$/, (m) => `${m[1]} cannot approve here`],
  [/^(\S+) kann hier nicht mergen$/, (m) => `${m[1]} cannot merge here`],
  [/^Lässt sich nicht mergen \(GitLab: ([\s\S]*)\)$/, (m) => `Cannot be merged (GitLab: ${m[1]})`],
  [/^GitLab lehnt den Merge ab \((\d+)\)(?:: ([\s\S]*))?$/, (m, tr) => `GitLab refuses the merge (${m[1]})${m[2] ? ': ' + tr(m[2]) : ''}`],
  [/^Es gibt neue Commits \((\w+)\), die noch nicht geladen sind\. Zuerst neu laden\.$/,
    (m) => `There are new commits (${m[1]}) that are not loaded yet. Reload first.`],
  [/^(\S+) kann hier keine Threads auflösen$/, (m) => `${m[1]} cannot resolve threads here`],
  [/^(\S+) nimmt kein Cookie entgegen$/, (m) => `${m[1]} does not take a cookie`],
  [/^Unbekannter Provider: ([\s\S]*)$/, (m) => `Unknown provider: ${m[1]}`],
  [/^Datei ist nicht Teil des Pull Requests: ([\s\S]*)$/, (m) => `File is not part of the pull request: ${m[1]}`],
  [/^Inhalt der Datei ist nicht verfügbar: ([\s\S]*)$/, (m) => `Content of the file is not available: ${m[1]}`],
  [/^rules\.json ist ungültig: ([\s\S]*)$/, (m) => `rules.json is invalid: ${m[1]}`],
  [/^Diskussion zu Kommentar (\S+) nicht gefunden$/, (m) => `Discussion of comment ${m[1]} not found`],
  [/^Archiv des Repositorys nicht lesbar: ([\s\S]*)$/, (m) => `Archive of the repository not readable: ${m[1]}`],
  [/^Ungültige Antwort von (\S+): HTML statt JSON\. Login-Seite eines SSO-Proxys\? Ist das Cookie abgelaufen\?$/,
    (m) => `Invalid response from ${m[1]}: HTML instead of JSON. Login page of an SSO proxy? Has the cookie expired?`],
  [/^Ungültige Antwort von (\S+): ([\s\S]*)$/, (m) => `Invalid response from ${m[1]}: ${m[2]}`],
  [/^(\S+) nicht erreichbar: ([\s\S]*)$/, (m) => `${m[1]} not reachable: ${m[2]}`],
  [/^(\S+) (\d{3}) auf (\S+)(: [\s\S]*)?$/, (m) => `${m[1]} ${m[2]} on ${m[3]}${m[4] ?? ''}`],
];
