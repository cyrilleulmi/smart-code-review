import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Mirrors backend dev.smartcodereview.model.Model

export interface User {
  login: string;
  name: string | null;
  avatarUrl: string | null;
}

export interface Me {
  user: User;
  dataDir: string;
  providers: string;
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
}

export interface Group {
  id: string;
  label: string;
  description: string | null;
  flowId: string | null;
  segmentIds: string[];
}

export interface Category {
  id: string;
  label: string;
  description: string;
  icon: string;
  groups: Group[];
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
}

export interface Flow {
  id: string;
  title: string;
  steps: FlowStep[];
  edges: FlowEdge[];
}

export interface Analysis {
  schemaVersion: number;
  generatedAt: string;
  headSha: string;
  rulesHash: string;
  segments: Segment[];
  categories: Category[];
  flows: Flow[];
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
}

export interface LineMark {
  path: string;
  key: string;
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

  prs(): Observable<PullRequestSummary[]> {
    return this.http.get<PullRequestSummary[]>('/api/prs');
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
