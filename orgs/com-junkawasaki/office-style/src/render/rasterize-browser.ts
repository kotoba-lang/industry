/**
 * ブラウザ用 SVG → PNG ラスタライズ。OffscreenCanvas (or 通常 canvas) + Image で
 * 描画し PNG バイトを返す。Node 専用の @resvg/resvg-js を使わないブラウザ経路。
 *
 * DOM 型に依存しないよう globalThis 経由で参照する (tsconfig lib=ES2022 のまま)。
 */
export interface RasterizeOptions {
  width?: number;
}

type AnyGlobal = Record<string, any>;

function g(): AnyGlobal {
  return globalThis as unknown as AnyGlobal;
}

/** SVG 文字列を Image にデコードする (data-URL 経由)。 */
async function loadSvgImage(svg: string): Promise<any> {
  const G = g();
  const blob = new G.Blob([svg], { type: "image/svg+xml" });
  // createImageBitmap が使えれば最速 (Worker でも可)。
  if (typeof G.createImageBitmap === "function") {
    return G.createImageBitmap(blob);
  }
  // フォールバック: HTMLImageElement (メインスレッドのみ)。
  const url = G.URL.createObjectURL(blob);
  try {
    const img = new G.Image();
    await new Promise<void>((res, rej) => {
      img.onload = () => res();
      img.onerror = () => rej(new Error("svg image decode failed"));
      img.src = url;
    });
    return img;
  } finally {
    G.URL.revokeObjectURL(url);
  }
}

function makeCanvas(w: number, h: number): any {
  const G = g();
  if (typeof G.OffscreenCanvas === "function") return new G.OffscreenCanvas(w, h);
  const c = G.document.createElement("canvas");
  c.width = w; c.height = h;
  return c;
}

async function canvasToPng(canvas: any): Promise<Uint8Array> {
  // OffscreenCanvas.convertToBlob / HTMLCanvasElement.toBlob 両対応。
  const blob: any = canvas.convertToBlob
    ? await canvas.convertToBlob({ type: "image/png" })
    : await new Promise((res) => canvas.toBlob(res, "image/png"));
  return new Uint8Array(await blob.arrayBuffer());
}

/** ブラウザで SVG → PNG (Uint8Array)。 */
export async function rasterizeSvgBrowser(
  svg: string,
  opts: RasterizeOptions = {},
): Promise<Uint8Array> {
  const G = g();
  if (typeof G.document === "undefined" && typeof G.OffscreenCanvas !== "function") {
    throw new Error("rasterizeSvgBrowser requires a browser/worker environment (canvas)");
  }
  const img = await loadSvgImage(svg);
  const iw = img.width || 1280;
  const ih = img.height || 720;
  const width = opts.width ?? iw;
  const height = Math.round((width / iw) * ih);
  const canvas = makeCanvas(width, height);
  const cx = canvas.getContext("2d");
  cx.drawImage(img, 0, 0, width, height);
  if (typeof img.close === "function") img.close(); // ImageBitmap 解放
  return canvasToPng(canvas);
}
