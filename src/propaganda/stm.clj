(ns propaganda.stm
  (:use propaganda.values)
  (:require [propaganda.generic-operators :as generic-operators]))

;; Propagator framework

(def alerting? (ref false))
(def alert-queue (ref clojure.lang.PersistentQueue/EMPTY))

(defn alert-propagators
  "Simple implementation of alerting propagators."
  [propagators]
  (dosync
   (alter alert-queue into propagators)
   (when (not @alerting?)
     (ref-set alerting? true)
     (while (peek @alert-queue)
       (let [propagator (peek @alert-queue)]
         (alter alert-queue pop)
         (propagator)))
     (ref-set alerting? false))))

;; TODO: Having a global ref is not nice - alternatives welcomed
(def ^:private all-propagators
  (ref nil))

(defn alert-all-propagators!
  []
  (alert-propagators @all-propagators))

(defprotocol Cell
  (new-neighbour! [this new-neighbour])
  (add-content    [this increment])
  (get-content    [this]))

(def ^:dynamic *merge*
  "The merge function used by the cells. Must be bound."
  (fn [& args]
    (throw (Exception. "Missing propaganda.stm/*merge* binding."))))

;; TODO: Could be bound to something throwing an exception as is the
;; case with *merge*
(def ^:dynamic *contradictory?* (default-contradictory?))

(defn make-cell
  []
  (let [neighbours (ref nil)
        content    (ref nothing)]
    (reify
      Cell
      (new-neighbour!
        [this new-neighbour]
        (dosync
         (when (not (contains? (set @neighbours) new-neighbour))
           (alter neighbours conj new-neighbour)
           (alter all-propagators conj new-neighbour)
           (alert-propagators [new-neighbour]))))
      (add-content
        [this increment]
        (dosync
         (let [answer (*merge* @content increment)]
           (cond
            (= answer @content)       :ok
            (*contradictory?* answer) (throw (Exception.
                                              (str "Inconsistency: "
                                                   (:reason answer))))
            :else                     (do
                                        (ref-set content answer)
                                        (alert-propagators @neighbours))))))
      (get-content
        [this]
        @content))))

(defn propagator
  "Adds a new propagator (to-do) to the neighbours and guarantees that
  it is called (although adding it should have that side-effect, but not
  doing it causes a failure - there is something I haven't thought
  through)"
  [neighbours to-do]
  (doseq [cell neighbours]
    (new-neighbour! cell to-do))
  (alert-propagators [to-do]))

(defn lift-to-cell-contents
  "Returns a safe-guarded version of f which ensures that all arguments
  are different than nothing."
  [f]
  (fn [& args]
    (if (some nothing? args)
      nothing
      (apply f args))))

(defn function->propagator-constructor
  "Returns a propagtor constructor which will lift the content of f
  applied to the first cells to the last cell."
  [f]
  (fn [& cells]
    (let [inputs (butlast cells)
          output (last cells)
          lifted-f (lift-to-cell-contents f)]
      (propagator
       inputs
       (fn [] (add-content
              output
              (apply lifted-f (map get-content inputs))))))))

(defn compound-propagator
  "Constructs a propagtor which will observe the neighbours cells and
  run to-build when their values are all different from nothing."
  [neighbours to-build]
  (let [done? (ref false)
        test (fn [] (when-not @done?
                     (when-not (some nothing? (map get-content neighbours))
                       (ref-set done? true)
                       (to-build))))]
    (propagator neighbours test)))

;; Useful propagators

(defn constant
  [value]
  (function->propagator-constructor (fn [] value)))