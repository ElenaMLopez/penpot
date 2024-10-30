(ns frontend-tests.runner
  (:require
   [cljs.test :as t]
   [frontend-tests.basic-shapes-test]
   [frontend-tests.helpers-shapes-test]
   [frontend-tests.logic.comp-remove-swap-slots-test]
   [frontend-tests.logic.copying-and-duplicating-test]
   [frontend-tests.logic.frame-guides-test]
   [frontend-tests.logic.groups-test]
   [frontend-tests.plugins.context-shapes-test]
   [frontend-tests.util-range-tree-test]
   [frontend-tests.util-simple-math-test]
   [frontend-tests.util-snap-data-test]))

(enable-console-print!)

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (.exit js/process 0)
    (.exit js/process 1)))


(defn init
  []
  (t/run-tests 'frontend-tests.helpers-shapes-test
               'frontend-tests.logic.comp-remove-swap-slots-test
               'frontend-tests.logic.copying-and-duplicating-test
               'frontend-tests.logic.frame-guides-test
               'frontend-tests.logic.groups-test
               'frontend-tests.plugins.context-shapes-test
               'frontend-tests.util-range-tree-test
               'frontend-tests.util-snap-data-test
               'frontend-tests.util-simple-math-test
               'frontend-tests.basic-shapes-test))
