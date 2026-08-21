(ns gl
  "WebGL 2.0 plumbing: create a context, bind the engine's buffers, draw, and answer taps.

  This is the *only* copy. `bin/render.cljs` used to carry the same sequence as a JavaScript
  string it injected into headless Chromium, which meant the picture the CLI verified and the
  picture the page showed came from two hand-maintained transcriptions of one procedure. They
  agreed on the day they were written, which is the most that arrangement ever guarantees.

  Nothing here is a renderer. No geometry, no matrices, no shading is authored in this file:

    mesh       `kami.webgpu.geometry/box`
    instances  `kami.webgpu.submission/pack-instances`   (the 32-float canonical stride)
    globals    `kami.webgpu.submission/pack-globals`     (the 60-float G block)
    shaders    `webgpu/fixtures/glsl/lit.{vert,frag}`    (generated from the one EDN shader)
    picking    `kami.webgpu.pick/pick`

  Every number handed to the GPU comes from the engine. What is written here is: create a
  context, bind buffers, draw, read back.

  Compiled by squint for the browser. `bin/render.cljs` bundles the same module and injects
  it, so the CLI renderer and the preview page execute identical code."
  (:require [kami.webgpu.geometry :as geom]
            [kami.webgpu.submission :as sub]
            [kami.webgpu.pick :as pick]))

;; --------------------------------------------------------------------------

(defn- compile-shader [ctx type src]
  (let [s (.createShader ctx type)]
    (.shaderSource ctx s src)
    (.compileShader ctx s)
    (if (.getShaderParameter ctx s (.-COMPILE_STATUS ctx))
      s
      (throw (js/Error. (str "shader: " (.getShaderInfoLog ctx s)))))))

(defn- link [ctx vert frag]
  (let [p (.createProgram ctx)]
    (.attachShader ctx p (compile-shader ctx (.-VERTEX_SHADER ctx) vert))
    (.attachShader ctx p (compile-shader ctx (.-FRAGMENT_SHADER ctx) frag))
    (.linkProgram ctx p)
    (when-not (.getProgramParameter ctx p (.-LINK_STATUS ctx))
      (throw (js/Error. (str "link: " (.getProgramInfoLog ctx p)))))
    p))

(defn- interleave-mesh
  "Positions and normals into one array, six floats per vertex — the layout the lit vertex
  shader's locations 0 and 1 expect at a 24-byte stride."
  [mesh]
  (let [pos (into-array (apply concat (:positions mesh)))
        nor (into-array (apply concat (:normals mesh)))
        n (/ (.-length pos) 3)
        out (js/Float32Array. (* n 6))]
    (dotimes [i n]
      (dotimes [k 3]
        (aset out (+ (* i 6) k) (aget pos (+ (* i 3) k)))
        (aset out (+ (* i 6) 3 k) (aget nor (+ (* i 3) k)))))
    out))

(defn- lit-depth-texture!
  "A 1×1 fully-lit depth texture, bound where the shadow map goes.

  The shadow pass is not run here, so the PCF taps must read 'unshadowed'. The comparison
  mode is not optional decoration: the shader declares `sampler2DShadow`, and a texture that
  is not in compare mode makes the draw `INVALID_OPERATION` with nothing else to say about
  it — the frame simply comes back empty."
  [ctx program]
  (let [t (.createTexture ctx)]
    (.bindTexture ctx (.-TEXTURE_2D ctx) t)
    (.texImage2D ctx (.-TEXTURE_2D ctx) 0 (.-DEPTH_COMPONENT24 ctx) 1 1 0
                 (.-DEPTH_COMPONENT ctx) (.-UNSIGNED_INT ctx)
                 (js/Uint32Array. #js [0xffffffff]))
    (.texParameteri ctx (.-TEXTURE_2D ctx) (.-TEXTURE_MIN_FILTER ctx) (.-NEAREST ctx))
    (.texParameteri ctx (.-TEXTURE_2D ctx) (.-TEXTURE_MAG_FILTER ctx) (.-NEAREST ctx))
    (.texParameteri ctx (.-TEXTURE_2D ctx) (.-TEXTURE_COMPARE_MODE ctx)
                    (.-COMPARE_REF_TO_TEXTURE ctx))
    (.texParameteri ctx (.-TEXTURE_2D ctx) (.-TEXTURE_COMPARE_FUNC ctx) (.-LEQUAL ctx))
    (when-let [loc (.getUniformLocation ctx program "_group_0_binding_1_fs")]
      (.activeTexture ctx (.-TEXTURE0 ctx))
      (.bindTexture ctx (.-TEXTURE_2D ctx) t)
      (.uniform1i ctx loc 0))
    t))

;; --------------------------------------------------------------------------

(defn create!
  "Set up a context on `canvas` for the lit pass. `glsl` is `{:vert … :frag …}`.

  Returns a handle for `draw!`, or throws. Callers decide what to do when WebGL 2.0 is
  absent — this does not silently fall back to something that is not 3D."
  [canvas glsl]
  (let [ctx (.getContext canvas "webgl2" #js {:antialias true :preserveDrawingBuffer true})]
    (when-not ctx (throw (js/Error. "no webgl2 context")))
    (let [program (link ctx (:vert glsl) (:frag glsl))
          mesh (geom/box 1.0 1.0 1.0)
          verts (interleave-mesh mesh)
          idx (js/Uint16Array. (into-array (:indices mesh)))
          vao (.createVertexArray ctx)]
      (.bindVertexArray ctx vao)
      (let [vb (.createBuffer ctx)]
        (.bindBuffer ctx (.-ARRAY_BUFFER ctx) vb)
        (.bufferData ctx (.-ARRAY_BUFFER ctx) verts (.-STATIC_DRAW ctx))
        (.enableVertexAttribArray ctx 0)
        (.vertexAttribPointer ctx 0 3 (.-FLOAT ctx) false 24 0)
        (.enableVertexAttribArray ctx 1)
        (.vertexAttribPointer ctx 1 3 (.-FLOAT ctx) false 24 12))
      (let [ib (.createBuffer ctx)]
        (.bindBuffer ctx (.-ARRAY_BUFFER ctx) ib)
        ;; the lit shader reads the first 24 floats of the canonical 128-byte instance
        ;; stride: four model rows, colour, material
        (doseq [[loc off] [[2 0] [3 16] [4 32] [5 48] [6 64] [7 80]]]
          (.enableVertexAttribArray ctx loc)
          (.vertexAttribPointer ctx loc 4 (.-FLOAT ctx) false 128 off)
          (.vertexAttribDivisor ctx loc 1))
        (let [eb (.createBuffer ctx)
              gb (.createBuffer ctx)]
          (.bindBuffer ctx (.-ELEMENT_ARRAY_BUFFER ctx) eb)
          (.bufferData ctx (.-ELEMENT_ARRAY_BUFFER ctx) idx (.-STATIC_DRAW ctx))
          (.useProgram ctx program)
          (doseq [n ["G_block_0Vertex" "G_block_0Fragment"]]
            (let [i (.getUniformBlockIndex ctx program n)]
              (when (not= i (.-INVALID_INDEX ctx))
                (.uniformBlockBinding ctx program i 0))))
          (.bindBufferBase ctx (.-UNIFORM_BUFFER ctx) 0 gb)
          (lit-depth-texture! ctx program)
          (.enable ctx (.-DEPTH_TEST ctx))
          {:ctx ctx :canvas canvas :program program :vao vao
           :instance-buffer ib :globals-buffer gb :index-count (count (:indices mesh))})))))

(defn draw!
  "Draw one render-IR frame. Returns `{:instances n :error glError}`."
  [{:keys [ctx canvas vao instance-buffer globals-buffer index-count]} ir w h]
  (let [inst (js/Float32Array. (into-array (sub/pack-instances (:instances ir))))
        g (js/Float32Array. (into-array (sub/pack-globals ir w h)))
        [sr sg sb] (get-in ir [:globals :sky :horizon] [0.53 0.74 0.93])]
    (.bindVertexArray ctx vao)
    (.bindBuffer ctx (.-ARRAY_BUFFER ctx) instance-buffer)
    (.bufferData ctx (.-ARRAY_BUFFER ctx) inst (.-DYNAMIC_DRAW ctx))
    (.bindBuffer ctx (.-UNIFORM_BUFFER ctx) globals-buffer)
    (.bufferData ctx (.-UNIFORM_BUFFER ctx) g (.-DYNAMIC_DRAW ctx))
    (.bindBufferBase ctx (.-UNIFORM_BUFFER ctx) 0 globals-buffer)
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    (.viewport ctx 0 0 w h)
    (.clearColor ctx sr sg sb 1.0)
    (.clear ctx (bit-or (.-COLOR_BUFFER_BIT ctx) (.-DEPTH_BUFFER_BIT ctx)))
    (.drawElementsInstanced ctx (.-TRIANGLES ctx) index-count (.-UNSIGNED_SHORT ctx) 0
                            (count (:instances ir)))
    {:instances (count (:instances ir)) :error (.getError ctx)}))

(defn pick-at
  "Which instance is under `[px py]` in canvas pixels, via the engine's own picker.

  Delegated rather than reimplemented on purpose: the app re-deriving the camera transform is
  how every app ends up with a slightly different answer from the screen, and the error reads
  as a UI bug instead of as duplicated math. It also knows that an instance's `:pos` is its
  **ground** point while the box sits half a height above it, which is exactly the sort of
  thing a second implementation gets wrong near the frame edges and nowhere else."
  [ir px py w h opts]
  (pick/pick ir [px py] w h opts))
