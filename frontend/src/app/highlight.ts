import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import go from 'highlight.js/lib/languages/go';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import less from 'highlight.js/lib/languages/less';
import markdown from 'highlight.js/lib/languages/markdown';
import powershell from 'highlight.js/lib/languages/powershell';
import properties from 'highlight.js/lib/languages/properties';
import python from 'highlight.js/lib/languages/python';
import scss from 'highlight.js/lib/languages/scss';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';
import { DisplayLine } from './api';

const LANGUAGES = {
  bash, csharp, css, dockerfile, go, java, javascript, json, kotlin, less, markdown, powershell, properties,
  python, scss, sql, typescript, xml, yaml,
};
for (const [name, lang] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, lang);
}

/**
 * Highlights the lines of a code block. The new version (context + added lines) and the old version
 * (context + deleted lines) are highlighted as two continuous texts so multi-line constructs (comments,
 * template strings) stay correct; each display line then takes its HTML from the matching version.
 */
export function highlightLines(lines: DisplayLine[], language: string): string[] {
  const lang = hljs.getLanguage(language) ? language : null;
  const newIdx: number[] = [];
  const oldIdx: number[] = [];
  lines.forEach((l, i) => {
    if (l.type === 'ctx' || l.type === 'add') newIdx.push(i);
    if (l.type === 'ctx' || l.type === 'del') oldIdx.push(i);
  });
  const result: string[] = lines.map((l) => escape(l.text ?? ''));
  if (!lang) {
    return result;
  }
  const newHtml = splitHtml(highlight(newIdx.map((i) => lines[i].text ?? '').join('\n'), lang));
  newIdx.forEach((lineIdx, n) => (result[lineIdx] = newHtml[n] ?? result[lineIdx]));
  const oldHtml = splitHtml(highlight(oldIdx.map((i) => lines[i].text ?? '').join('\n'), lang));
  oldIdx.forEach((lineIdx, n) => {
    if (lines[lineIdx].type === 'del') result[lineIdx] = oldHtml[n] ?? result[lineIdx];
  });
  return result;
}

function highlight(code: string, language: string): string {
  try {
    return hljs.highlight(code, { language, ignoreIllegals: true }).value;
  } catch {
    return escape(code);
  }
}

/** Splits highlighted HTML at newlines, closing and re-opening spans so that every line is valid HTML. */
function splitHtml(html: string): string[] {
  const lines: string[] = [];
  const open: string[] = [];
  let current = '';
  const re = /<span[^>]*>|<\/span>|\n|[^<\n]+|</g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html)) !== null) {
    const token = m[0];
    if (token === '\n') {
      lines.push(current + '</span>'.repeat(open.length));
      current = open.join('');
    } else if (token.startsWith('<span')) {
      open.push(token);
      current += token;
    } else if (token === '</span>') {
      open.pop();
      current += token;
    } else {
      current += token;
    }
  }
  lines.push(current + '</span>'.repeat(open.length));
  return lines;
}

function escape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
