(ns armidillo.midi
  "Non-blocking buffered and filtered MIDI listeners."
  (:require [armidillo.log]
            [clojure.core.async :refer [>!! >! <!! <!] :as a]
            [midi :as m]
            [taoensso.timbre :as log]))


(declare midi-stop)


(def ^:private midi-listeners (atom {}))
(def ^:private midi-in (atom nil))


(defn ^:private create-midi-buffer []
  (let [number-of-events-buffered 8]
    (a/chan (a/sliding-buffer number-of-events-buffered))))


(defn ^:private apply-listener [l event]
  (let [event-matcher (if (set? (:filter l))
                        (:type event)
                        event)]
    (when (and (or (nil? (:chan l))
                   (= (:chan l) (:chan event)))
               ((:filter l) event-matcher))
      (log/debug "MIDI listener " (:id l) " receives " event)
      (>!! (:buffer l) event))))


(def ^:private note-names
  [:c :c# :d :d# :e :f :f# :g :g# :a :a# :b])


(defn ^:private assoc-event-metadata [event timestamp]
  (let [;; midi channels should start at 1 not 0
        chan (inc (:chan event))
        octave (int (- (/ (:note event) (count note-names)) 2))
        octave-note (mod (:note event) (count note-names))
        note-name (get note-names octave-note)
        ;; http://www.midimountain.com/midi/midi_status.htm
        type_ (case (:cmd event)
                128 :note-off
                144 :note-on
                160 :poly-aftertouch
                176 :control-change
                192 :program-change
                224 :pitch-wheel
                240 :sysex
                :unknown)]
    (assoc event
           :chan chan
           :note-name note-name
           :octave octave
           :octave-note octave-note
           :time timestamp
           :type type_)))


(defn ^:private midi-handler
  [event timestamp]
  (let [enriched-event (assoc-event-metadata event timestamp)]
    (doseq [[id l] @midi-listeners]
      (apply-listener l enriched-event))))


(defn ^:private create-midi-consumer [l]
  (a/go
   (loop []
     (when-let [midi-event (<! (:buffer l))]
       ((:handler l) (:device l) midi-event)
       (recur)))))


(defn midi-select []
  (when @midi-in
    (m/midi-handle-events @midi-in (fn [& _])))
  (if-let [in (m/midi-in)]
    (do
      (reset! midi-in in)
      (m/midi-handle-events in midi-handler))
    (log/error "No MIDI device found.")))


;; TODO doc
(defn midi-stop
  ([]
   (doseq [[id _] @midi-listeners] (midi-stop id)))
  ([id]
   (a/close! (:buffer (id @midi-listeners)))
   (swap! midi-listeners assoc-in [id :status] :stopped)))


;; TODO doc
(defn midi-start
  ([]
   (doseq [[id _] @midi-listeners] (midi-start id)))
  ([id]
   (midi-stop id)
   (swap! midi-listeners assoc-in [id :buffer] (create-midi-buffer))
   (when (nil? @midi-in) (midi-select))
   (create-midi-consumer (id @midi-listeners))
   (swap! midi-listeners assoc-in [id :status] :started)))


(def ^:private default-listener-params
  {:chan nil
   :device nil
   :filter #{:note-on :note-off}
   :status :stopped})


;; TODO doc
(defn listener
  [id params]
  (when (id @midi-listeners)
    (midi-stop id))
  (as-> params |
   (merge default-listener-params |)
   (assoc | :buffer (create-midi-buffer)
            :id id)
   (swap! midi-listeners assoc id |))
  (midi-start id))


(def ^:private default-kit-params
  {:first-note 36
   :bank-size 16})


(defn ^:private bank-handler
  [params]
  (fn [device event]
   (when (<= (:first-note params) (:note event))
     (let [relative-note (- (:note event) (:first-note params))
           relative-bank (int (/ relative-note (:bank-size params)))
           bank-note (rem relative-note (:bank-size params))]
       (when-let [b (nth (:banks params) relative-bank nil)]
         (b device (assoc event :bank-note bank-note)))))))


;; TODO doc
(defn kit
  [id user-params]
  (let [params (merge default-kit-params user-params)]
    (listener id (assoc params :handler (bank-handler params)))))


;; TODO show the stop / start state of each listener
;; optionally allow filtering on stopped / started
(defn list-listeners []
  (into {} (map (fn [[k v]] [k (:status v)]) @midi-listeners)))


;; TODO doc
(defn remove-listener
  "Stop a listener and forget about it." [id]
  (midi-stop id)
  (swap! midi-listeners dissoc id))
