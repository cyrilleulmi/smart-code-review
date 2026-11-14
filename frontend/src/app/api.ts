import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Mirrors backend dev.smartcodereview.model.Model

export interface User {
  login: string;
  name: string | null;
  avatarUrl: string | null;
}

export interface Account {
  provider: string;
  label: string;
  host: string;
  user: User | null;
  error: string | null;
}

export interface Me {
  accounts: Account[];
  dataDir: string;
  /** running release, e.g. "0.3.0" */
  version: string;
}

export interface ProviderInfo {
  label: string;
  /** "#12" on GitHub, "!12" on GitLab */
  sigil: string;
  noun: string;
}

const PROVIDERS: Record<string, ProviderInfo> = {
  github: { label: 'GitHub', sigil: '#', noun: 'Pull Request' },
  gitlab: { label: 'GitLab', sigil: '!', noun: 'Merge Request' },
};

export function providerInfo(provider: string | null | undefined): ProviderInfo {
  return PROVIDERS[provider ?? ''] ?? { label: provider ?? '?', sigil: '#', noun: 'Pull Request' };
}

/** Nested GitLab groups travel as "group~sub" in refs and URLs; show them with slashes. */
export function ownerLabel(owner: string): string {
  return owner.replaceAll('~', '/');
}

/** e.g. "cyrilleulmi/spongecoach#13" or "gitlab-org/cli!4347" */
export function prLabel(pr: { provider: string; owner: string; repo: string; number: number }): string {
  return `${ownerLabel(pr.owner)}/${pr.repo}${providerInfo(pr.provider).sigil}${pr.number}`;
}

export type PrRole = 'authored' | 'review-requested';

export interface PullRequestSummary {
  provider: string;
  owner: string;
  repo: string;
  number: number;
  title: string;
  author: string;
  url: string;
  draft: boolean;
  createdAt: string;
  updatedAt: string;
  roles: PrRole[];
  progress: { ok: number; total: number } | null;
}

export interface PullRequestInfo {
  provider: string;
  owner: string;
  repo: string;
  number: number;
  title: string;
  body: string;
  author: string;
  url: string;
  draft: boolean;
  state: string;
  baseRef: string;
  headRef: string;
  baseSha: string;
  headSha: string;
  createdAt: string;
  updatedAt: string;
  additions: number;
  deletions: number;
  changedFiles: number;
  startSha: string | null;
}

export interface LineRef {
  segmentId: string;
  symbol: string;
}

export interface DisplayLine {
  type: 'add' | 'del' | 'ctx' | 'gap';
  oldLine: number | null;
  newLine: number | null;
  key: string | null;
  text: string | null;
  own: boolean;
  inDiff: boolean;
  gapSize: number | null;
  refs: LineRef[] | null;
}

export interface Segment {
  id: string;
  path: string;
  language: string;
  kind: 'hunk' | 'symbol' | 'module';
  symbol: string | null;
  symbolKind: string | null;
  startLine: number | null;
  endLine: number | null;
  category: string;
  group: string;
  ruleId: string | null;
  reason: string;
  lineKeys: string[];
  lines: DisplayLine[];
  /** ids of all groups showing this segment ("quick-wins/mapper", "flows/flow-2", ...); each needs its own ok */
  places: string[];
  /** test segments that reach this code */
  testedBy: string[];
  /** ports & adapters layer (domain | application | usecase | adapter), null if none */
  layer: string | null;
  frontend: boolean;
}

export interface Group {
  id: string;
  label: string;
  description: string | null;
  flowId: string | null;
  segmentIds: string[];
}

/** Categories with their own UI (data flows) say so in `view`; older analyses only have the id to go by. */
export function isFlowView(c: { id: string; view?: string | null }): boolean {
  return (c.view ?? (c.id === 'flows' ? 'flow' : 'groups')) === 'flow';
}

export interface Category {
  id: string;
  label: string;
  /** what the category is for */
  description: string;
  icon: string;
  groups: Group[];
  /** how the category is composed / split into groups (absent in analyses of older versions) */
  builds?: string | null;
  divides?: string | null;
  /** 'groups' = list of groups; other values are exceptions with their own UI ('flow') */
  view?: string | null;
  /** 'builtin' | 'rules' (| later 'external') */
  origin?: string | null;
}

export interface FlowStep {
  segmentId: string;
  order: number;
  depth: number;
  parentSegmentId: string | null;
}

export interface FlowEdge {
  from: string;
  to: string;
  line: number | null;
  via: string;
  /** "http" = frontend REST call matched to a backend endpoint (via = "PUT /api/x/{}") */
  kind: 'call' | 'http';
}

export interface Flow {
  id: string;
  title: string;
  steps: FlowStep[];
  edges: FlowEdge[];
}

export interface TestSubject {
  name: string;
  path: string | null;
  inPr: boolean;
  how: 'name' | 'call';
}

export interface TestCoverage {
  segmentId: string;
  direct: boolean;
  via: string | null;
  line: number | null;
}

export interface TestInfo {
  segmentId: string;
  name: string;
  path: string;
  subjects: TestSubject[];
  covers: TestCoverage[];
}

export interface ArchitectureInfo {
  /** hexagonal: ports & adapters; layered: domain / application / adapter packages without ports */
  style: 'hexagonal' | 'layered' | null;
  evidence: string[];
}

export interface Component {
  id: string;
  name: string;
  paths: string[];
  /** column in the component diagram */
  area: string;
  segmentIds: string[];
}

export interface ComponentEdge {
  from: string;
  to: string;
  calls: number;
  http: boolean;
}

export interface Settings {
  disabledCategories: string[];
}

export interface Analysis {
  schemaVersion: number;
  generatedAt: string;
  headSha: string;
  rulesHash: string;
  segments: Segment[];
  categories: Category[];
  flows: Flow[];
  tests: TestInfo[];
  architecture: ArchitectureInfo | null;
  components: Component[];
  componentEdges: ComponentEdge[];
}

export interface LogEntry {
  at: string;
  by: string;
  action: string;
  scope: string;
  count: number;
}

export interface ReviewState {
  schemaVersion: number;
  pr: string;
  headSha: string | null;
  ok: Record<string, string[]>;
  log: LogEntry[];
  /** place -> path -> keys that are ok at that place only */
  okAt: Record<string, Record<string, string[]>>;
}

export interface FileSummary {
  path: string;
  previousPath: string | null;
  status: string;
  additions: number;
  deletions: number;
  language: string;
}

export interface ReviewBundle {
  pr: PullRequestInfo;
  fetchedAt: string;
  dataPath: string;
  files: FileSummary[];
  analysis: Analysis;
  state: ReviewState;
}

export interface Comment {
  id: number;
  kind: 'review' | 'issue';
  path: string | null;
  line: number | null;
  side: 'LEFT' | 'RIGHT' | null;
  key: string | null;
  body: string;
  author: string;
  avatarUrl: string | null;
  createdAt: string;
  url: string;
  inReplyTo: number | null;
}

export interface NewComment {
  path?: string | null;
  line?: number | null;
  side?: 'LEFT' | 'RIGHT' | null;
  body: string;
  inReplyTo?: number | null;
  /** both set for context lines (GitLab needs both to anchor a comment) */
  oldLine?: number | null;
  newLine?: number | null;
}

export interface LineMark {
  path: string;
  key: string;
  /** group id the mark applies to; null = all places of the line */
  place: string | null;
}

export interface PrKey {
  provider: string;
  owner: string;
  repo: string;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/me');
  }

  settings(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  saveSettings(settings: Settings): Observable<Settings> {
    return this.http.put<Settings>('/api/settings', settings);
  }

  prs(): Observable<PullRequestSummary[]> {
    return this.http.get<PullRequestSummary[]>('/api/prs');
  }

  resolve(url: string): Observable<PrKey> {
    return this.http.get<PrKey>('/api/resolve', { params: { url } });
  }

  bundle(pr: PrKey): Observable<ReviewBundle> {
    return this.http.get<ReviewBundle>(base(pr));
  }

  reload(pr: PrKey): Observable<ReviewBundle> {
    return this.http.post<ReviewBundle>(`${base(pr)}/reload`, null);
  }

  reanalyse(pr: PrKey): Observable<ReviewBundle> {
    return this.http.post<ReviewBundle>(`${base(pr)}/reanalyse`, null);
  }

  state(pr: PrKey): Observable<ReviewState> {
    return this.http.get<ReviewState>(`${base(pr)}/state`);
  }

  mark(pr: PrKey, lines: LineMark[], ok: boolean, scope: string): Observable<ReviewState> {
    return this.http.post<ReviewState>(`${base(pr)}/marks`, { lines, ok, scope, by: 'ui' });
  }

  comments(pr: PrKey): Observable<Comment[]> {
    return this.http.get<Comment[]>(`${base(pr)}/comments`);
  }

  postComment(pr: PrKey, comment: NewComment): Observable<Comment> {
    return this.http.post<Comment>(`${base(pr)}/comments`, comment);
  }
}

function base(pr: PrKey): string {
  return `/api/prs/${pr.provider}/${pr.owner}/${pr.repo}/${pr.number}`;
}

export function errorMessage(err: unknown): string {
  const e = err as { error?: { message?: string }; message?: string; status?: number };
  return e?.error?.message ?? (e?.status === 0 ? 'Backend nicht erreichbar' : e?.message ?? 'Unbekannter Fehler');
}
