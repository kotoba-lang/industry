/**
 * SVG → PNG ラスタライズ。任意依存 `@resvg/resvg-js` を動的 import で使う。
 * 未インストールなら明示エラー (CLI は --svg-out で SVG を吐いて手動 raster 可)。
 */
export interface RasterizeOptions {
  /** 出力幅(px)。未指定なら SVG の固有幅。 */
  width?: number;
}

export async function rasterizeSvg(
  svg: string,
  opts: RasterizeOptions = {},
): Promise<Uint8Array> {
  let mod: any;
  try {
    const specifier = "@resvg/resvg-js";
    mod = await import(specifier);
  } catch {
    throw new Error(
      "rasterize requires optional dependency '@resvg/resvg-js'. " +
        "Install it, or emit SVG with --svg-out and rasterize externally.",
    );
  }
  const Resvg = mod.Resvg;
  const r = new Resvg(svg, {
    ...(opts.width ? { fitTo: { mode: "width", value: opts.width } } : {}),
    font: { loadSystemFonts: true },
  });
  return r.render().asPng();
}
