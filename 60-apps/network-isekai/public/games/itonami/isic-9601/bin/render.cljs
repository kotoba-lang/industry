(ns render
  "Render the 3D street from a terminal, through the real WebGL 2.0 backend.

  This is the end-to-end check CLAUDE.md's 3D rule asks for, in a form that runs without a
  screen: build the canonical render-IR, pack it with the engine's own packers, hand it to
  headless Chromium, compile the engine's own GLSL, draw, read the pixels back, write a
  PNG.

  **It is not a second renderer.** Nothing here authors geometry, matrices, lighting or
  shading:

    mesh       `kami.webgpu.geometry/box`
    instances  `kami.webgpu.submission/pack-instances`  (the 32-float canonical stride)
    globals    `kami.webgpu.submission/pack-globals`    (the 60-float G block)
    shaders    `webgpu/fixtures/glsl/lit.{vert,frag}`   (generated from the one EDN shader)

  What this file owns is plumbing — create a context, bind buffers, draw, read back — which
  is precisely the part `kami.webgl` owns in the browser and cannot own here, because
  `kami.webgl` is `.cljs` bound to a live canvas.

  Two honest limits, both printed at the end of a run:

  * **The shadow pass is not run.** The browser executor renders depth from the sun first
    and samples it; here the sampler is bound to a 1×1 fully-lit depth texture, so the
    image is the lit pass without shadowing. Shadow parity needs the two-pass path.
  * **The GPU is SwiftShader**, Chromium's software rasteriser, because this container has
    no GPU. That exercises the real GLSL compiler and the real GL state machine, which is
    what the check is for; it is not a statement about any particular driver.

  Usage:
    nbb bin/render.cljs [--out FILE] [--width N] [--height N] [--cleared N] [--engine DIR]"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [promesa.core :as p]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [kami.webgpu.geometry :as geom]
            [kami.webgpu.submission :as sub]))

(def argv (vec *command-line-args*))
(defn opt [k default]
  (let [i (.indexOf (into-array argv) (str "--" k))]
    (if (neg? i) default (nth argv (inc i) default))))
(defn num-opt [k d] (js/parseFloat (opt k (str d))))

(def W (int (num-opt "width" 900)))
(def H (int (num-opt "height" 1600)))
(def out (opt "out" "preview/street.png"))
(def cleared (int (num-opt "cleared" 0)))
(def engine-root
  (or (opt "engine" nil)
      (path/resolve (js/process.cwd) "../../../../../../orgs/kotoba-lang")))

(defn- glsl [f]
  (let [p (path/join engine-root "webgpu" "fixtures" "glsl" f)]
    (when-not (fs/existsSync p)
      (println (str "missing " p))
      (println "  west update --fetch smart webgpu   (or pass --engine <dir>)")
      (js/process.exit 3))
    (fs/readFileSync p "utf8")))

;; --------------------------------------------------------------------------
;; the frame, entirely from the engine
;; --------------------------------------------------------------------------

(def ir (w3/render-ir (assoc (world/init) :cleared cleared) (/ (double W) (double H))))
(def mesh (geom/box 1.0 1.0 1.0))

(def payload
  #js {:VERT (glsl "lit.vert")
       :FRAG (glsl "lit.frag")
       :POS  (clj->js (vec (mapcat identity (:positions mesh))))
       :NOR  (clj->js (vec (mapcat identity (:normals mesh))))
       :IDX  (clj->js (:indices mesh))
       :INST (clj->js (sub/pack-instances (:instances ir)))
       :G    (clj->js (sub/pack-globals ir W H))
       :W W :H H :N (count (:instances ir))})

;; --------------------------------------------------------------------------
;; plumbing
;; --------------------------------------------------------------------------

(def page-js "
  const {VERT, FRAG, POS, NOR, IDX, INST, G, W, H, N} = window.__frame;
  const cv = Object.assign(document.createElement('canvas'), {width: W, height: H});
  const gl = cv.getContext('webgl2', {antialias: true, preserveDrawingBuffer: true});
  if (!gl) return {ok: false, reason: 'no webgl2 context'};

  function sh(type, src) {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) return gl.getShaderInfoLog(s);
    return s;
  }
  const vs = sh(gl.VERTEX_SHADER, VERT); if (typeof vs === 'string') return {ok:false, reason:'VS '+vs};
  const fs = sh(gl.FRAGMENT_SHADER, FRAG); if (typeof fs === 'string') return {ok:false, reason:'FS '+fs};
  const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return {ok:false, reason:'link '+gl.getProgramInfoLog(p)};

  const verts = new Float32Array(POS.length * 2);
  for (let i = 0; i < POS.length / 3; i++) {
    verts[i*6+0]=POS[i*3]; verts[i*6+1]=POS[i*3+1]; verts[i*6+2]=POS[i*3+2];
    verts[i*6+3]=NOR[i*3]; verts[i*6+4]=NOR[i*3+1]; verts[i*6+5]=NOR[i*3+2];
  }
  const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
  const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);
  gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 24, 0);
  gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 24, 12);

  // instance attrs read out of the canonical 32-float stride (128 bytes); the lit shader
  // wants the first 24 floats — model rows, colour, material
  const ib = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ib);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(INST), gl.STATIC_DRAW);
  [[2,0],[3,16],[4,32],[5,48],[6,64],[7,80]].forEach(([loc, off]) => {
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 4, gl.FLOAT, false, 128, off);
    gl.vertexAttribDivisor(loc, 1);
  });

  const eb = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, eb);
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(IDX), gl.STATIC_DRAW);

  const gb = gl.createBuffer(); gl.bindBuffer(gl.UNIFORM_BUFFER, gb);
  gl.bufferData(gl.UNIFORM_BUFFER, new Float32Array(G), gl.STATIC_DRAW);
  let bound = 0;
  ['G_block_0Vertex','G_block_0Fragment'].forEach(n => {
    const i = gl.getUniformBlockIndex(p, n);
    if (i !== gl.INVALID_INDEX) { gl.uniformBlockBinding(p, i, 0); bound++; }
  });
  gl.bindBufferBase(gl.UNIFORM_BUFFER, 0, gb);

  gl.useProgram(p);
  // no shadow pass here: bind a 1x1 fully-lit depth texture so the PCF taps read 'unshadowed'
  const st = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, st);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.DEPTH_COMPONENT24, 1, 1, 0,
                gl.DEPTH_COMPONENT, gl.UNSIGNED_INT, new Uint32Array([0xffffffff]));
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  // the shader declares `sampler2DShadow`, so the texture must be in compare mode or the
  // draw is INVALID_OPERATION with nothing else to say about it
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_COMPARE_MODE, gl.COMPARE_REF_TO_TEXTURE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_COMPARE_FUNC, gl.LEQUAL);
  const sloc = gl.getUniformLocation(p, '_group_0_binding_1_fs');
  if (sloc) { gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, st); gl.uniform1i(sloc, 0); }

  gl.viewport(0, 0, W, H);
  gl.enable(gl.DEPTH_TEST);
  gl.clearColor(0.53, 0.74, 0.93, 1.0);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  gl.drawElementsInstanced(gl.TRIANGLES, IDX.length, gl.UNSIGNED_SHORT, 0, N);

  const err = gl.getError();
  const px = new Uint8Array(W * H * 4);
  gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
  let nonSky = 0; const colors = new Set();
  for (let i = 0; i < px.length; i += 4) {
    colors.add((px[i]>>4)+','+(px[i+1]>>4)+','+(px[i+2]>>4));
    if (!(Math.abs(px[i]-135)<12 && Math.abs(px[i+1]-189)<12 && Math.abs(px[i+2]-237)<12)) nonSky++;
  }
  return {ok:true, glError:err, blocks:bound, nonSkyPixels:nonSky, distinctColors:colors.size,
          renderer:(gl.getExtension('WEBGL_debug_renderer_info') ?
                    gl.getParameter(gl.getExtension('WEBGL_debug_renderer_info').UNMASKED_RENDERER_WEBGL) : 'n/a'),
          png: cv.toDataURL('image/png')};
")

(defn -main []
  (println (str "  engine  " engine-root))
  (println (str "  frame   " (count (:instances ir)) " instances · " W "x" H
                " · eye " (pr-str (get-in ir [:globals :eye]))))
  (p/let [pw (js/import "playwright")
          browser (.launch (.-chromium pw)
                           #js {:args #js ["--no-sandbox" "--use-gl=swiftshader"
                                           "--enable-unsafe-swiftshader"]
                                :executablePath (or (.-PW_CHROMIUM js/process.env)
                                                    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome")})
          page (.newPage browser)
          _ (.addInitScript page #js {:content (str "window.__frame = " (js/JSON.stringify payload) ";")})
          _ (.goto page "about:blank")
          raw (.evaluate page (str "(() => {" page-js "})()"))
          _ (.close browser)]
    (let [r (js->clj raw :keywordize-keys true)]
      (if-not (:ok r)
        (do (println (str "  FAILED  " (:reason r))) (js/process.exit 4))
        (let [abs (path/resolve (js/process.cwd) out)
              b64 (second (str/split (:png r) #","))]
          (fs/mkdirSync (path/dirname abs) #js {:recursive true})
          (fs/writeFileSync abs (js/Buffer.from b64 "base64"))
          (println (str "  gpu     " (:renderer r) "  (software rasteriser — no GPU in this container)"))
          (println (str "  draw    glError=" (:glError r) "  uniform blocks bound=" (:blocks r)))
          (println (str "  pixels  " (:nonSkyPixels r) " non-background · "
                        (:distinctColors r) " distinct colours"))
          (println (str "  wrote   " abs))
          (println "  note    shadow pass not run (1x1 lit depth texture bound); lit pass only")
          (when (zero? (:nonSkyPixels r))
            (println "  FAILED  nothing was drawn — the frame is a flat background")
            (js/process.exit 5)))))))

(-main)
