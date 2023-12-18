(ns app.main.ui.workspace.viewport.gl
  (:require-macros [app.main.style :as stl])
  (:require-macros [app.util.gl.macros :refer [slurp]])
  (:require
   [app.common.math :as math]
   [app.util.gl :as gl]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def CANVAS_CONTEXT_ID "webgl2")

(def default-vertex-shader (slurp "src/app/util/gl/shaders/default.v.glsl"))
(def default-fragment-shader (slurp "src/app/util/gl/shaders/default.f.glsl"))

(defn resize-canvas-to
  [canvas width height]
  (let [resized-width (not= (.-width canvas) width)
        resized-height (not= (.-height canvas) height)
        resized (or resized-width resized-height)]
    (when resized-width
      (set! (.-width canvas) width))
    (when resized-height
      (set! (.-height canvas) height))
    resized))

(defn resize-canvas
  [canvas]
  (let [width  (math/floor (.-clientWidth canvas))
        height (math/floor (.-clientHeight canvas))]
    (resize-canvas-to canvas width height)))

(defn prepare-gl
  [gl]
  (let [default-program (gl/create-program-from-sources gl default-vertex-shader default-fragment-shader)]))

(defn render-gl
  [gl objects]
  (.clearColor gl 1.0 0.0 1.0 1.0)
  (.clear gl (.-COLOR_BUFFER_BIT gl))

  (.viewport gl 0 0 (.-width (.-canvas gl)) (.-height (.-canvas gl)))

  (for [object objects]


    (.drawArrays gl (.TRIANGLES gl) 0 4)))

(mf/defc canvas
  "A canvas element with a WebGL context."
  {::mf/wrap-props false}
  [props]
  (js/console.log props)
  (js/console.log "default-shaders" default-vertex-shader default-fragment-shader)
  (let [objects    (unchecked-get props "objects")
        canvas-ref (mf/use-ref nil)
        gl-ref     (mf/use-ref nil)]

    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
            (mf/set-ref-val! gl-ref gl)
            (resize-canvas canvas)
            (prepare-gl gl)
            (render-gl gl objects)
            (js/console.log "gl" gl)))))

    [:canvas {:class (stl/css :canvas)
              :ref canvas-ref}]))
