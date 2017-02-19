(ns armidillo.midi-test
  (:require [armidillo.midi :as mid]
            [clojure.test :refer :all]
            [overtone.midi :as m]
            [taoensso.timbre :as log]))


(log/set-level! :warn)


(def midi-device-select (atom 0))
(def test-handler-events (atom []))


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
   {:chan 5
    ;; accepts a set of note types, defaulting to #{:note-off :note-on},
    ;; or a predecate fn that takes the event
    :filter #(pos? (:vel %))
    ;; handler can take either fn or collection of fns,
    ;; it treats the latter case like banks for a 16 pad kit
    :handler test-handler})

  (#'mid/midi-clj-handler {:chan 4 :vel 50 :note 36} 00000)
  (#'mid/midi-clj-handler {:chan 4 :vel 50 :note 36} 11111)
  (#'mid/midi-clj-handler {:chan 4 :vel 0 :note 36} 22222)
  (#'mid/midi-clj-handler {:chan 9 :vel 50 :note 36} 33333)

  (Thread/sleep 100)

  (is (= 2 (count @test-handler-events)))
  (is (= [00000 11111] (map :time @test-handler-events))))


(deftest listener-event-params
  (let [event {:chan 0 :vel 50 :note 24 :cmd 144}]
    (mid/listener
     :test-listener
     {:handler test-handler
      :event {:foobar 123
              :barbaz :other}})
    (#'mid/midi-clj-handler event 12345)
    (is (= 123 (:foobar (first @test-handler-events))))
    (is (= :other (:barbaz (first @test-handler-events))))))


(deftest assoc-event-metadata
  (let [event {:chan 0 :vel 50 :note 24 :cmd 144}
        event2 (assoc event :note 87)
        timestamp 12345]
    (is (= (assoc event
                  :chan 1
                  :time timestamp
                  :type :note-on
                  :pitch :c
                  :octave 0
                  :octave-note 0)
           (#'mid/assoc-event-metadata event timestamp)))
    (is (= (assoc event2
                  :chan 1
                  :time timestamp
                  :type :note-on
                  :pitch :d#
                  :octave 5
                  :octave-note 3)
           (#'mid/assoc-event-metadata event2 timestamp)))))


(deftest listener-set-filter
  (mid/listener
   :test-listener
   {:chan 5
    ;; accepts a set of note types, defaulting to #{:note-off :note-on},
    ;; or a predecate fn that takes the event
    :filter #{:note-on}
    ;; handler can take either fn or collection of fns,
    ;; it treats the latter case like banks for a 16 pad kit
    :handler test-handler})

  (doseq [cmd [144 128 999 144]]
    (#'mid/midi-clj-handler {:chan 4 :vel 50 :cmd cmd :note 36} 00000))

  (Thread/sleep 100)

  (is (= 2 (count @test-handler-events)))
  (is (every? (comp (partial = :note-on) :type) @test-handler-events))
  (is (every? (comp (partial = 144) :cmd) @test-handler-events)))


(deftest kit-works
  (mid/kit
   :test-kit
   {:chan 5
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
    (#'mid/midi-clj-handler {:chan 4 :vel 50 :note note :cmd 144} 00000))

  (Thread/sleep 100)

  (is (= 6 (count @test-handler-events)))
  (is (= [1 1 2 2 3 3] (map :test-bank @test-handler-events)))
  (is (= [0 15 0 15 0 15] (map :bank-note @test-handler-events))))
