import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** 16px line icons in the spirit of the IntelliJ New UI icon set (stroke = currentColor). */
const PATHS: Record<string, string> = {
  pr: 'M4 3.5a1.5 1.5 0 1 0 0 .01M4 5v6M4 12.5a1.5 1.5 0 1 0 0 .01M12 12.5a1.5 1.5 0 1 0 0 .01M12 11V6.5A2.5 2.5 0 0 0 9.5 4H7M8.5 2.5 7 4l1.5 1.5',
  review: 'M3 3.5h6M3 7.5h6M3 11.5h4M10.5 11l1.5 1.5 3-3',
  bolt: 'M9 1.5 3.5 9H8l-1 5.5L12.5 7H8z',
  flow: 'M2.5 3h4v3h-4zM9.5 10h4v3h-4zM4.5 6v2.5a1.5 1.5 0 0 0 1.5 1.5h3.5',
  code: 'M5.5 4 2 8l3.5 4M10.5 4 14 8l-3.5 4',
  test: 'M6 1.5h4M6.5 1.5v4.2L3 12.6a1.3 1.3 0 0 0 1.1 1.9h7.8a1.3 1.3 0 0 0 1.1-1.9L9.5 5.7V1.5M4.8 9.5h6.4',
  palette: 'M8 1.5a6.5 6.5 0 1 0 0 13c1 0 1.3-.8.8-1.6-.6-.9 0-2 1-2h1.7a3 3 0 0 0 3-3C14.5 4.3 11.6 1.5 8 1.5M5 7a.5.5 0 1 0 0 .01M8 4.5a.5.5 0 1 0 0 .01M11 7a.5.5 0 1 0 0 .01',
  file: 'M4 1.5h5L12.5 5v9.5h-8.5zM9 1.5V5h3.5',
  folder: 'M1.5 3.5h4.5l1.5 1.5h7v8.5h-13z',
  check: 'M3 8.5 6.5 12 13 4.5',
  comment: 'M2.5 3h11v8H7l-3 2.5V11H2.5z',
  refresh: 'M13 3v3.5H9.5M3 13V9.5h3.5M12.4 6.5A5 5 0 0 0 3.6 5M3.6 9.5a5 5 0 0 0 8.8 1.5',
  external: 'M9 2.5h4.5V7M13.5 2.5 7 9M11.5 9.5v4h-9v-9h4',
  chevronRight: 'M6 3.5 10.5 8 6 12.5',
  chevronDown: 'M3.5 6 8 10.5 12.5 6',
  close: 'M4 4l8 8M12 4l-8 8',
  branch: 'M5 3.5a1.5 1.5 0 1 0 0 .01M5 12.5a1.5 1.5 0 1 0 0 .01M11 5.5a1.5 1.5 0 1 0 0 .01M5 5v6M11 7c0 2.5-6 1.5-6 4',
  search: 'M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9M10.3 10.3 14 14',
  analyse: 'M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5',
  collapse: 'M4 6.5 8 3l4 3.5M4 13l4-3.5 4 3.5',
  expand: 'M4 3l4 3.5L12 3M4 9.5 8 13l4-3.5',
  arrowRight: 'M2.5 8h10M9 4.5 12.5 8 9 11.5',
  arrowLeft: 'M13.5 8h-10M7 4.5 3.5 8 7 11.5',
  home: 'M2.5 7.5 8 2.5l5.5 5M4 6.5v7h8v-7',
  plus: 'M8 3v10M3 8h10',
  info: 'M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13M8 7v4.5M8 4.8v.2',
  github: 'M8 1.5a6.5 6.5 0 0 0-2.05 12.67c.33.06.45-.14.45-.31v-1.1c-1.81.39-2.19-.87-2.19-.87-.3-.75-.72-.95-.72-.95-.59-.4.04-.4.04-.4.65.05 1 .67 1 .67.58 1 1.53.71 1.9.54.06-.42.23-.71.41-.87-1.44-.16-2.96-.72-2.96-3.21 0-.71.25-1.29.67-1.74-.07-.17-.29-.83.06-1.72 0 0 .55-.18 1.79.66a6.2 6.2 0 0 1 3.26 0c1.24-.84 1.79-.66 1.79-.66.35.89.13 1.55.06 1.72.42.45.67 1.03.67 1.74 0 2.5-1.52 3.05-2.97 3.21.23.2.44.6.44 1.21v1.79c0 .17.12.37.45.31A6.5 6.5 0 0 0 8 1.5',
  gitlab: 'M8 14.3 1.6 9.6a.5.5 0 0 1-.18-.56L3.3 2.2a.3.3 0 0 1 .57 0L5.3 6.6h5.4l1.43-4.4a.3.3 0 0 1 .57 0l1.88 6.84a.5.5 0 0 1-.18.56z',
  data: 'M3 3.5C3 2.4 13 2.4 13 3.5v9c0 1.1-10 1.1-10 0zM3 3.5c0 1.1 10 1.1 10 0M3 8c0 1.1 10 1.1 10 0',
};

@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 16 16" fill="none" stroke="currentColor"
    stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="d()" /></svg>`,
  styles: `:host { display: inline-flex; flex: none; } svg { display: block; }`,
})
export class Icon {
  readonly name = input.required<string>();
  readonly size = input(16);
  protected readonly d = computed(() => PATHS[this.name()] ?? PATHS['file']);
}

/** IntelliJ-style round badge for a symbol kind (c = class, m = method, f = function, ...). */
@Component({
  selector: 'app-symbol-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `{{ letter() }}`,
  host: { '[class]': '"badge k-" + (kind() ?? "none")', '[attr.title]': 'kind()' },
  styles: `
    :host { display: inline-grid; place-items: center; width: 14px; height: 14px; border-radius: 50%;
      font: 600 9px/1 var(--font-ui); color: #1e1f22; flex: none; }
    :host(.k-class) { background: #4c9eea; }
    :host(.k-interface), :host(.k-type) { background: #59a869; }
    :host(.k-enum) { background: #c7a65b; }
    :host(.k-method) { background: #e57a8c; }
    :host(.k-function), :host(.k-component) { background: #c793e0; }
    :host(.k-const) { background: #d6a65b; }
    :host(.k-none) { background: #6f737a; }
  `,
})
export class SymbolBadge {
  readonly kind = input<string | null>(null);
  protected readonly letter = computed(() => {
    const k = this.kind();
    return k === 'component' ? 'C' : k === 'const' ? 'v' : k === 'type' ? 't' : (k ?? '·').charAt(0);
  });
}
