/**
 * svgraph の SVGraphPresentationProjection 型のローカルミラー。
 * (svgraph は GitHub Packages 公開で認証が要るため、実行時依存にせず型だけ複製。
 *  svgraph/docs/app.d.ts の定義と一致させること。)
 */
export type JsonValue =
  | null | boolean | number | string
  | JsonValue[]
  | { [key: string]: JsonValue };

export interface TemplateRecord {
  template_id: string;
  kind: string;
  node_id: string | null;
  data: Record<string, string>;
  metadata: JsonValue;
}

export interface GuideRecord {
  guide_id: string;
  orientation: string;
  position: number;
  unit: string;
  node_id: string | null;
}

export interface RulerRecord {
  ruler_id: string;
  orientation: string;
  origin: number;
  unit: string;
  spacing: number | null;
  node_id: string | null;
}

export interface TextStyleRecord {
  style_id: string;
  role: string;
  properties: Record<string, JsonValue>;
  node_id: string | null;
}

export interface SlideRecord {
  slide_id: string;
  node_id: string;
  title: string | null;
  view_box: [number, number, number, number];
  data: Record<string, string>;
  metadata: { text?: string; json?: JsonValue };
}

export interface PartRecord {
  part_name: string;
  content_type: string;
  kind: string;
  source_node_id: string | null;
}

export interface SVGraphPresentationProjection {
  kind: "svgraph-presentation";
  slide_size: [number, number];
  slides: SlideRecord[];
  parts: PartRecord[];
  masters: TemplateRecord[];
  layouts: TemplateRecord[];
  guides: GuideRecord[];
  rulers: RulerRecord[];
  text_styles: TextStyleRecord[];
  metadata: Record<string, JsonValue>;
}
