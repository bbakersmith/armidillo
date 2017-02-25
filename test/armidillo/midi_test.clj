(ns armidillo.midi-test
  (:require [armidillo.midi :as mid]
            [clojure.test :refer :all]
            [overtone.midi :as m]
            [taoensso.timbre :as log]))


(log/set-level! :warn)


(def midi-device-select (atom 0))
(def test-handler-events (atom []))


;; clj-midi event channels start at zero
(def test-event-on
  {:channel 0 :command :note-on :note 36 :timestamp 10000 :velocity 50})
(def test-event-off
  {:channel 0 :command :note-off :note 36 :timestamp 10000 :velocity 0})


(defn test-handler [event]
  (swap! test-handler-events conj event))


(defn create-test-bank [bank-number]
  (fn [event]
    (test-handler (assoc event :test-bank bank-number))))


(defn midi-fixture [test-fn]
  (reset! midi-device-select 0)
  (reset! test-handler-events [])
  (reset! @#'mid/listeners nil)
  (reset! @#'mid/device nil)
  ;; mock midi-clj so test can call event handler directly
  (with-redefs [m/midi-in #(swap! midi-device-select inc)
                m/midi-handle-events (constantly nil)]
    (test-fn)))


(use-fixtures :each midi-fixture)


(deftest midi-device-prompt
  ;; devices already defined should only prompt device selection once
  (mid/listener :test-listener {:handler test-handler})
  (mid/listener :test-listener {:handler test-handler})
  (is (= 1 @midi-device-select))
  ;; unless re-selection is explicitly requested
  (mid/input)
  (is (= 2 @midi-device-select)))


(deftest create-and-start-stop-listener
  (mid/listener :test-listener-1 {:handler test-handler})
  (mid/listener :test-listener-2 {:handler test-handler})
  (is (= {:test-listener-1 :started
          :test-listener-2 :started}
         (mid/status)))
  (mid/stop :test-listener-2)
  (is (= {:test-listener-1 :started
          :test-listener-2 :stopped}
         (mid/status)))
  (mid/forget :test-listener-2)
  (is (= {:test-listener-1 :started}
         (mid/status))))


(deftest listener-works
  (mid/listener
   :test-listener
   {:channel 1
    ;; accepts a set of note types, defaulting to #{:note-off :note-on},
    ;; or a predecate fn that takes the event
    :filter #(pos? (:velocity %))
    ;; handler can take either fn or collection of fns,
    ;; it treats the latter case like banks for a 16 pad kit
    :handler test-handler})

  (#'mid/midi-clj-handler (assoc test-event-on :timestamp 10000))
  (#'mid/midi-clj-handler (assoc test-event-on :timestamp 11111))
  (#'mid/midi-clj-handler (assoc test-event-off :timestamp 22222))
  (#'mid/midi-clj-handler (assoc test-event-on
                                 :channel 9
                                 :timestamp 33333))

  (Thread/sleep 100)

  (is (= 2 (count @test-handler-events)))
  (is (= [10000 11111] (map :timestamp @test-handler-events))))


(deftest assoc-event-metadata
  (let [event (#'mid/assoc-event-metadata (assoc test-event-on :note 24))
        event2 (#'mid/assoc-event-metadata (assoc test-event-on :note 87))
        timestamp 12345]
    (is (= :c (:pitch event)))
    (is (= 0 (:octave event)))
    (is (= 0 (:octave-note event)))

    (is (= :d# (:pitch event2)))
    (is (= 5 (:octave event2)))
    (is (= 3 (:octave-note event2)))))


(deftest listener-set-filter
  (mid/listener
   :test-listener
   {:channel 1
    ;; accepts a set of note types, defaulting to #{:note-off :note-on},
    ;; or a predecate fn that takes the event
    :filter #{:note-on}
    ;; handler can take either fn or collection of fns,
    ;; it treats the latter case like banks for a 16 pad kit
    :handler test-handler})

  (doseq [command [:note-on :note-off :control-change :note-on]]
    (#'mid/midi-clj-handler (assoc test-event-on :command command)))

  (Thread/sleep 100)

  (is (= 2 (count @test-handler-events)))
  (is (every? (comp (partial = :note-on) :command) @test-handler-events)))


(deftest kit-works
  (mid/kit
   :test-kit
   {:channel 1
    ;; takes a collection of bank fn's which each handle events for values
    ;; 0 - :bank-size
    :banks [(create-test-bank 1) (create-test-bank 2) (create-test-bank 3)]
    ;; bank size, along with first-note, determines range of valid notes
    ;; for each handler (one or more)
    ;; channel to filter on, defaults to nil meaning all channels
    :bank-size 16
    ;; first note of first bank, all subsequent notes and banks increment
    ;; from here
    :first-note 36})

  ;;            N  1  1  2  2  3  3  N
  (doseq [note [35 36 51 52 67 68 83 84]]
    (#'mid/midi-clj-handler (assoc test-event-on :note note)))

  (Thread/sleep 100)

  (is (= 6 (count @test-handler-events)))
  (is (= [1 1 2 2 3 3] (map :test-bank @test-handler-events)))
  (is (= [0 15 0 15 0 15] (map :bank-note @test-handler-events))))
