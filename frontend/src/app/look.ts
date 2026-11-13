import { Segment } from './api';

/**
 * Visual distinctions shared by blocks and diagrams: a colour per connection, per layer (area) and per
 * language. The colours themselves are CSS tokens in styles.scss (--edge-n, --area-x, --lang-x).
 */

export const EDGE_COLORS = 8;

/** 1..8, cycling: connection i of a flow keeps its colour in diagram, rail, call chip and "aufgerufen von". */
export function edgeColorIndex(i: number): number {
  return (i % EDGE_COLORS) + 1;
}

export function edgeColor(i: number): string {
  return `var(--edge-${edgeColorIndex(i)})`;
}

const AREAS: Record<string, { label: string; token: string }> = {
  frontend: { label: 'Frontend', token: 'frontend' },
  'adapter-in': { label: 'Adapter ein', token: 'api' },
  api: { label: 'API', token: 'api' },
  usecase: { label: 'Use Case', token: 'usecase' },
  application: { label: 'Application', token: 'application' },
  logic: { label: 'Backend', token: 'logic' },
  'adapter-out': { label: 'Adapter aus', token: 'data' },
  data: { label: 'Daten', token: 'data' },
  domain: { label: 'Domäne', token: 'domain' },
  test: { label: 'Test', token: 'test' },
};

export function areaLabel(area: string | null | undefined): string {
  return area ? AREAS[area]?.label ?? area : '';
}

export function areaColor(area: string | null | undefined): string {
  return area && AREAS[area] ? `var(--area-${AREAS[area].token})` : 'var(--border-strong)';
}

/** Area of a segment: its component's column, or at least frontend / backend. */
export function areaOfSegment(seg: Segment, byComponent: Map<string, string>): string | null {
  return byComponent.get(seg.id) ?? (seg.frontend ? 'frontend' : seg.category === 'tests' ? 'test' : null);
}

const LANGUAGES: Record<string, { label: string; token: string }> = {
  typescript: { label: 'TS', token: 'ts' },
  javascript: { label: 'JS', token: 'js' },
  java: { label: 'Java', token: 'java' },
  kotlin: { label: 'Kotlin', token: 'kotlin' },
  go: { label: 'Go', token: 'go' },
  csharp: { label: 'C#', token: 'csharp' },
  xml: { label: 'HTML', token: 'html' },
  css: { label: 'CSS', token: 'css' },
  scss: { label: 'SCSS', token: 'css' },
  less: { label: 'LESS', token: 'css' },
  sql: { label: 'SQL', token: 'sql' },
  python: { label: 'Python', token: 'python' },
};

export function langLabel(language: string): string {
  return LANGUAGES[language]?.label ?? '';
}

export function langColor(language: string): string {
  return LANGUAGES[language] ? `var(--lang-${LANGUAGES[language].token})` : 'var(--fg-dim)';
}
