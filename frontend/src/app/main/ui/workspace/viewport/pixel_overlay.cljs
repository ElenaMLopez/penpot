;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.pixel-overlay
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.rasterizer :as thr]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.workspace.shapes :as shapes]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [promesa.core :as p]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn- resolve-svg-images!
  [svg-node]
  (let [image-nodes (dom/query-all svg-node "image:not([href^=data])")
        noop-fn     (constantly nil)]
    (->> (rx/from image-nodes)
         (rx/mapcat
          (fn [image]
            (let [href (dom/get-attribute image "href")]
              (->> (http/fetch {:method :get :uri href})
                   (rx/mapcat (fn [response] (.blob ^js response)))
                   (rx/mapcat wapi/read-file-as-data-url)
                   (rx/tap (fn [data]
                             (dom/set-attribute! image "href" data)))
                   (rx/reduce noop-fn))))))))

(defn- svg-as-data-url
  "Transforms SVG as data-url resolving any blob, http or https url to
  its data equivalent."
  [svg]
  (let [svg-clone (.cloneNode svg true)]
    (->> (resolve-svg-images! svg-clone)
         (rx/map (fn [_] (dom/svg-node->data-uri svg-clone))))))

(defn format-viewbox [vbox]
  (str/join " " [(:x vbox 0)
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(mf/defc overlay-frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  []
  (let [data     (mf/deref refs/workspace-page)
        objects  (:objects data)
        root     (get objects uuid/zero)
        shapes   (->> (:shapes root)
                      (map (d/getf objects)))]
    [:g.shapes
     (for [shape shapes]
       (cond
         (not (cph/frame-shape? shape))
         [:& shapes/shape-wrapper
          {:shape shape
           :key (:id shape)}]

         (cph/is-direct-child-of-root? shape)
         [:& shapes/root-frame-wrapper
          {:shape shape
           :key (:id shape)
           :objects objects}]

         :else
         [:& shapes/nested-frame-wrapper
          {:shape shape
           :key (:id shape)
           :objects objects}]))]))

(mf/defc pixel-overlay
  {::mf/wrap-props false}
  [props]
  (let [vport         (unchecked-get props "vport")
        viewport-ref  (unchecked-get props "viewport-ref")
        viewport-node (mf/ref-val viewport-ref)
        canvas-ref    (mf/use-ref nil)
        img-ref       (mf/use-ref nil)

        update-str (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (when-let [zoom-view-node (.getElementById js/document "picker-detail")]
             (let [canvas-node   (mf/ref-val canvas-ref)

                   {brx :left bry :top} (dom/get-bounding-rect viewport-node)
                   x (- (.-clientX event) brx)
                   y (- (.-clientY event) bry)

                   zoom-context (.getContext zoom-view-node "2d" #js {:willReadFrequently true})
                   canvas-context (.getContext canvas-node "2d" #js {:willReadFrequently true})
                   pixel-data (.getImageData canvas-context x y 1 1)
                   rgba (.-data pixel-data)
                   r (obj/get rgba 0)
                   g (obj/get rgba 1)
                   b (obj/get rgba 2)
                   a (obj/get rgba 3)
                   area-data (.getImageData canvas-context (- x 25) (- y 20) 50 40)]
               (-> (js/createImageBitmap area-data)
                   (p/then
                    (fn [image]
                      ;; Draw area
                      (obj/set! zoom-context "imageSmoothingEnabled" false)
                      (.drawImage zoom-context image 0 0 200 160))))
               (st/emit! (dwc/pick-color [r g b a]))))))

        handle-pointer-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                     (dwc/pick-color-select true (kbd/shift? event)))))

        handle-pointer-up-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/commit-undo-transaction :mouse-down-picker)
                     (dwc/stop-picker))
           (modal/disallow-click-outside!)))

        handle-image-load
        (mf/use-callback
         (mf/deps img-ref)
         (fn []
           (let [canvas-node (mf/ref-val canvas-ref)
                 img-node (mf/ref-val img-ref)
                 canvas-context (.getContext canvas-node "2d")]
             (.drawImage canvas-context img-node 0 0))))

        handle-draw-picker-canvas
        (mf/use-callback
         (mf/deps img-ref)
         (fn []
           (let [img-node (mf/ref-val img-ref)
                 svg-node (dom/get-element "render")]
             (->> (rx/of {:node svg-node})
                  (rx/mapcat thr/render-node)
                  (rx/map wapi/create-uri)
                  (rx/tap #(js/console.log %))
                  (rx/subs (fn [uri]
                     (obj/set! img-node "src" uri)))))))

        handle-svg-change
        (mf/use-callback
         (fn []
           (rx/push! update-str :update)))]

    (mf/use-effect
     (fn []
       (let [listener (events/listen js/document EventType.KEYDOWN  handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/use-effect
     (fn []
       (let [config #js {:attributes true
                         :childList true
                         :subtree true
                         :characterData true}
             svg-node (dom/get-element "render")
             observer (js/MutationObserver. handle-svg-change)
             ]
         (.observe observer svg-node config)
         (handle-svg-change)

         ;; Disconnect on unmount
         #(.disconnect observer))))

    [:*
     [:div.pixel-overlay
      {:id "pixel-overlay"
       :tab-index 0
       :class (cur/get-static "picker")
       :on-pointer-down handle-pointer-down-picker
       :on-pointer-up handle-pointer-up-picker
       :on-pointer-move handle-pointer-move-picker}
      [:div {:style {:display "none"}}
       [:img {:ref img-ref
              :on-load handle-image-load
              :style {:position "absolute"
                      :width "100%"
                      :height "100%"}}]
       [:canvas {:ref canvas-ref
                 :width (:width vport 0)
                 :height (:height vport 0)
                 :style {:position "absolute"
                         :width "100%"
                         :height "100%"}}]]]]))
