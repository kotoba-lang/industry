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
            ["node:http" :as http]
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

(def here
  "This package's directory, resolved from the script's own path rather than the working
  directory — see `preview/build.cljs` for what assuming a cwd cost."
  (path/resolve (path/dirname *file*) ".."))

(def W (int (num-opt "width" 900)))
(def H (int (num-opt "height" 1600)))
(def out (opt "out" (path/join here "preview/street.png")))
(def cleared (int (num-opt "cleared" 0)))
(def backend
  "`auto` (default), `webgpu`, or `webgl2`. Both read the SAME packed instances and the SAME 60-float
  globals block — `fixtures/lit-shader.wgsl` and `fixtures/glsl/lit.*` declare an identical
  `struct G` and identical vertex/instance locations, because one is transpiled from the
  other. That is the render-IR contract holding in practice rather than on paper."
  (opt "backend" "auto"))
(def engine-root
  ;; resolved from this script, not from the working directory — see preview/build.cljs
  (or (opt "engine" nil)
      (path/resolve here "../../../../../../orgs/kotoba-lang")))

(defn- fixture [& parts]
  (let [p (apply path/join engine-root "webgpu" "fixtures" parts)]
    (when-not (fs/existsSync p)
      (println (str "missing " p))
      (println "  west update --fetch smart webgpu   (or pass --engine <dir>)")
      (js/process.exit 3))
    (fs/readFileSync p "utf8")))

(defn- glsl [f] (fixture "glsl" f))
(defn- wgsl [f] (fixture f))

;; --------------------------------------------------------------------------
;; the frame, entirely from the engine
;; --------------------------------------------------------------------------

(def ir (w3/render-ir (assoc (world/init) :cleared cleared) (/ (double W) (double H))))
(def mesh (geom/box 1.0 1.0 1.0))

(def payload
  #js {:VERT (glsl "lit.vert")
       :FRAG (glsl "lit.frag")
       :WGSL (wgsl "lit-shader.wgsl")
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


(def webgpu-js "
  const {WGSL, POS, NOR, IDX, INST, G, W, H, N} = window.__frame;
  const stages = [];
  const step = s => { stages.push(s); return s; };
  if (!navigator.gpu) return {ok:false, stages, reason:'navigator.gpu is absent (not a secure context?)'};
  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) return {ok:false, stages, reason:'no WebGPU adapter'};
  step('adapter');
  const dev = await adapter.requestDevice(); step('device');
  window.__keep = {adapter, dev};
  let lost = null; dev.lost.then(i => { lost = i.reason + ': ' + i.message; });
  const problems = [];
  dev.addEventListener('uncapturederror', e => problems.push(String((e.error && e.error.message) || e.error)));

  const mod = dev.createShaderModule({code: WGSL});
  const ci = await mod.getCompilationInfo();
  const errs = ci.messages.filter(m => m.type === 'error');
  if (errs.length) return {ok:false, stages, reason:'WGSL ' + errs.map(m => m.lineNum+':'+m.message).join(' | ')};
  step('wgsl-compiled');

  const cv = Object.assign(document.createElement('canvas'), {width: W, height: H});
  const ctx = cv.getContext('webgpu');
  const fmt = navigator.gpu.getPreferredCanvasFormat();
  ctx.configure({device: dev, format: fmt, alphaMode: 'opaque'});
  step('canvas-configured');

  function buf(data, usage) {
    const b = dev.createBuffer({size: Math.ceil(data.byteLength / 4) * 4, usage: usage | GPUBufferUsage.COPY_DST});
    dev.queue.writeBuffer(b, 0, data);
    return b;
  }
  const verts = new Float32Array(POS.length * 2);
  for (let i = 0; i < POS.length / 3; i++) {
    verts[i*6+0]=POS[i*3]; verts[i*6+1]=POS[i*3+1]; verts[i*6+2]=POS[i*3+2];
    verts[i*6+3]=NOR[i*3]; verts[i*6+4]=NOR[i*3+1]; verts[i*6+5]=NOR[i*3+2];
  }
  const vb = buf(verts, GPUBufferUsage.VERTEX);
  const ib = buf(new Float32Array(INST), GPUBufferUsage.VERTEX);
  const eb = buf(new Uint16Array(IDX.length % 2 ? [...IDX, 0] : IDX), GPUBufferUsage.INDEX);
  const gb = buf(new Float32Array(G), GPUBufferUsage.UNIFORM);
  step('buffers');

  // no shadow pass: a 1x1 depth texture reads as fully lit, matching the WebGL2 path here
  const shadowTex = dev.createTexture({size:[1,1], format:'depth32float',
    usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.RENDER_ATTACHMENT});
  const shadowSamp = dev.createSampler({compare:'less-equal'});
  const layout = dev.createBindGroupLayout({entries:[
    {binding:0, visibility:GPUShaderStage.VERTEX|GPUShaderStage.FRAGMENT, buffer:{type:'uniform'}},
    {binding:1, visibility:GPUShaderStage.FRAGMENT, texture:{sampleType:'depth'}},
    {binding:2, visibility:GPUShaderStage.FRAGMENT, sampler:{type:'comparison'}}]});
  const bind = dev.createBindGroup({layout, entries:[
    {binding:0, resource:{buffer:gb}},
    {binding:1, resource:shadowTex.createView()},
    {binding:2, resource:shadowSamp}]});
  step('bindgroup');

  // Creating this pipeline is the real check: it validates the canonical WGSL's @group(0)
  // bindings and all eight vertex/instance locations against the layout the engine's own
  // packers produce. A mismatch fails HERE, before any pixel exists.
  const pipe = dev.createRenderPipeline({
    layout: dev.createPipelineLayout({bindGroupLayouts:[layout]}),
    vertex: {module: mod, entryPoint:'vs', buffers:[
      {arrayStride:24, stepMode:'vertex', attributes:[
        {shaderLocation:0, offset:0,  format:'float32x3'},
        {shaderLocation:1, offset:12, format:'float32x3'}]},
      {arrayStride:128, stepMode:'instance', attributes:[
        {shaderLocation:2, offset:0,  format:'float32x4'},
        {shaderLocation:3, offset:16, format:'float32x4'},
        {shaderLocation:4, offset:32, format:'float32x4'},
        {shaderLocation:5, offset:48, format:'float32x4'},
        {shaderLocation:6, offset:64, format:'float32x4'},
        {shaderLocation:7, offset:80, format:'float32x4'}]}]},
    fragment: {module: mod, entryPoint:'fs', targets:[{format: fmt}]},
    primitive: {topology:'triangle-list', cullMode:'back'},
    depthStencil: {format:'depth24plus', depthWriteEnabled:true, depthCompare:'less'}});
  step('pipeline');

  const depth = dev.createTexture({size:[W,H], format:'depth24plus', usage: GPUTextureUsage.RENDER_ATTACHMENT});
  const enc = dev.createCommandEncoder();
  const pass = enc.beginRenderPass({
    colorAttachments:[{view: ctx.getCurrentTexture().createView(), loadOp:'clear', storeOp:'store',
                       clearValue:{r:0.53, g:0.74, b:0.93, a:1}}],
    depthStencilAttachment:{view: depth.createView(), depthLoadOp:'clear', depthStoreOp:'store',
                            depthClearValue:1.0}});
  pass.setPipeline(pipe); pass.setBindGroup(0, bind);
  pass.setVertexBuffer(0, vb); pass.setVertexBuffer(1, ib);
  pass.setIndexBuffer(eb, 'uint16');
  pass.drawIndexed(IDX.length, N);
  pass.end();
  dev.queue.submit([enc.finish()]);
  step('submitted');

  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));

  // census through a 2D copy rather than copyTextureToBuffer, which needs a live device
  const c2 = Object.assign(document.createElement('canvas'), {width:W, height:H});
  const g2 = c2.getContext('2d'); g2.drawImage(cv, 0, 0);
  const px = g2.getImageData(0, 0, W, H).data;
  let nonSky = 0; const colors = new Set();
  for (let i = 0; i < px.length; i += 4) {
    colors.add((px[i]>>4)+','+(px[i+1]>>4)+','+(px[i+2]>>4));
    if (!(Math.abs(px[i]-135)<12 && Math.abs(px[i+1]-189)<12 && Math.abs(px[i+2]-237)<12)) nonSky++;
  }
  step('read-back');
  // 'drew something' must be MORE THAN ONE COLOUR, not 'more than zero non-background
  // pixels'. A blank canvas is not the sky colour — it is black or transparent — so the
  // background test counts every pixel of an empty frame as drawn. That is not a hypothetical:
  // this check passed a completely blank WebGPU frame once, and `auto` reported
  // 'used webgpu · 1440000 non-background · 1 distinct colours'. A scene of 149 coloured
  // boxes cannot be one colour.
  //
  // Nor is `device.lost` enough on its own: it resolves asynchronously, so reading it
  // immediately after submit sometimes sees the loss and sometimes does not. The pixels
  // are the reliable evidence, which is the whole argument for verifying rather than
  // asking.
  const drew = colors.size > 1;
  return {ok: !lost && problems.length === 0 && drew,
          stages, lost, reason: lost ? ('device lost — ' + lost) : problems.join(' | '),
          glError: problems.length, blocks: 3, nonSkyPixels: nonSky, distinctColors: colors.size,
          format: fmt,
          renderer: [adapter.info && adapter.info.vendor, adapter.info && adapter.info.architecture,
                     adapter.info && adapter.info.description].filter(Boolean).join(' / ') || 'webgpu',
          png: cv.toDataURL('image/png')};
")

(def chromium-args
  "Flags measured in this container, not copied from anywhere. `--enable-unsafe-swiftshader`
  alone gives a WebGL2 context but no WebGPU *adapter*; adding `--enable-unsafe-webgpu`,
  `--enable-features=Vulkan` and `--use-angle=swiftshader` is what makes `requestAdapter()`
  return one."
  #js ["--no-sandbox" "--use-gl=swiftshader" "--enable-unsafe-swiftshader"
       "--enable-unsafe-webgpu" "--enable-features=Vulkan" "--use-angle=swiftshader"])

(def port 8731)

(defn- serve!
  "A one-page localhost server.

  WebGPU is only exposed in a SECURE CONTEXT, and `about:blank` is not one — with the page
  loaded there `navigator.gpu` is not merely adapterless, it is absent entirely, which
  reads exactly like 'this browser has no WebGPU'. That cost a round of flag-guessing
  before the flags turned out never to have been the problem. `http://127.0.0.1` is a
  secure context, so the page is served rather than injected."
  []
  (let [srv (.createServer http (fn [_ res]
                                  (.writeHead res 200 #js {"Content-Type" "text/html"})
                                  (.end res "<!doctype html><meta charset=utf-8><title>street</title>")))]
    (.listen srv port "127.0.0.1")
    srv))

(defn -main []
  (println (str "  engine  " engine-root))
  (println (str "  backend " backend (when (= backend "auto") "  (WebGPU first, WebGL 2.0 fallback)")))
  (println (str "  frame   " (count (:instances ir)) " instances · " W "x" H
                " · eye " (pr-str (get-in ir [:globals :eye]))))
  (p/let [srv (serve!)
          pw (js/import "playwright")
          browser (.launch (.-chromium pw)
                           #js {:args chromium-args
                                :executablePath (or (.-PW_CHROMIUM js/process.env)
                                                    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome")})
          page (.newPage browser)
          _ (.addInitScript page #js {:content (str "window.__frame = " (js/JSON.stringify payload) ";")})
          _ (.goto page (str "http://127.0.0.1:" port "/"))
          ;; `auto` is the point of having two backends. `kami.webgl/pick-backend` answers
          ;; "does this browser know the word WebGPU", which is not the same question as
          ;; "can it draw" — here it says :webgpu and the device dies on submit. So auto
          ;; TRIES WebGPU and falls back on the evidence, the policy being
          ;; `kami.webgl/backend-from-probe`.
          gpu-raw (when (not= backend "webgl2")
                    (.evaluate page (str "(async () => {" webgpu-js "})()")))
          gpu-r (when gpu-raw (js->clj gpu-raw :keywordize-keys true))
          used (cond (= backend "webgl2") "webgl2"
                     (:ok gpu-r) "webgpu"
                     (= backend "webgpu") "webgpu"     ; asked for it explicitly: report the failure
                     :else "webgl2")
          _ (when (and (= used "webgl2") (not= backend "webgl2"))
              (println (str "  fallback WebGPU → WebGL 2.0: " (:reason gpu-r)))
              (when (:stages gpu-r)
                (println (str "           got as far as " (str/join " → " (:stages gpu-r))))))
          raw (if (= used "webgpu")
                gpu-raw
                (.evaluate page (str "(async () => {" page-js "})()")))
          _ (.close browser)
          _ (.close srv)]
    (let [r (js->clj raw :keywordize-keys true)]
      (println (str "  used    " used))
      (when (:stages r)
        (println (str "  stages  " (str/join " → " (:stages r)))))
      (if-not (:ok r)
        (do (println (str "  FAILED  " (:reason r)))
            (when (= backend "webgpu")
              (println "  note    WGSL compiled and the render pipeline validated against the")
              (println "          canonical vertex/instance layout — the failure is the device,")
              (println "          not the frame. Measured in this container: Dawn drops its")
              (println "          instance right after submit under every flag combination")
              (println "          tried (6), including on a 3-line clear-to-red shader."))
            (js/process.exit 4))
        (let [abs (path/resolve here out)
              b64 (second (str/split (:png r) #","))]
          (fs/mkdirSync (path/dirname abs) #js {:recursive true})
          (fs/writeFileSync abs (js/Buffer.from b64 "base64"))
          (println (str "  gpu     " (:renderer r)
                        (when (:format r) (str "  format=" (:format r)))))
          (println (str "  draw    error=" (:glError r) "  bindings=" (:blocks r)))
          (println (str "  pixels  " (:nonSkyPixels r) " non-background · "
                        (:distinctColors r) " distinct colours"))
          (println (str "  wrote   " abs))
          (println "  note    shadow pass not run (1x1 lit depth texture bound); lit pass only")
          (when (zero? (:nonSkyPixels r))
            (println "  FAILED  nothing was drawn — the frame is a flat background")
            (js/process.exit 5)))))))

(-main)
