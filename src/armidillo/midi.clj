(ns armidillo.midi
  "Non-blocking buffered and filtered MIDI listeners."
  (:require [clojure.core.async :refer [>!! >! <!! <!] :as a]
            [overtone.midi :as m]
            [taoensso.timbre :as log]))


(declare stop)


(log/set-level! :warn)


(def ^:private listeners (atom {}))
(def ^:private device (atom nil))


(defn ^:private create-listener-buffer [size]
  (a/chan (a/sliding-buffer size)))


(defn ^:private apply-listener [l event]
  (let [event-matcher (if (set? (:filter l))
                        (:type event)
                        event)]
    (when (and (or (nil? (:chan l))
                   (= (:chan l) (:chan event)))
               ((:filter l) event-matcher))
      (log/debug "MIDI listener " (:id l) " receives " event)
      (>!! (:buffer l) (merge event (:event l))))))


(def ^:private note-pitches
  [:c :c# :d :d# :e :f :f# :g :g# :a :a# :b])


(defn ^:private assoc-event-metadata [event timestamp]
  (let [;; midi channels should start at 1 not 0
        chan (inc (:chan event))
        octave (int (- (/ (:note event) (count note-pitches)) 2))
        octave-note (mod (:note event) (count note-pitches))
        pitch (get note-pitches octave-note)
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
           :pitch pitch
           :octave octave
           :octave-note octave-note
           :time timestamp
           :type type_)))


(defn ^:private midi-clj-handler
  [event timestamp]
  (let [enriched-event (assoc-event-metadata event timestamp)]
    (doseq [[id l] @listeners]
      (apply-listener l enriched-event))))


(defn ^:private create-listener-consumer [l]
  (a/go
   (loop []
     (when-let [event (<! (:buffer l))]
       ((:handler l) event)
       (recur)))))


(defn input
  "Select a midi input device. This will happen automatically
   when a listener is defined if one hasn't already been been selected."
  []
  (when @device
    (m/midi-handle-events @device (fn [& _])))
  (if-let [in (m/midi-in)]
    (do
      (reset! device in)
      (m/midi-handle-events @device midi-clj-handler))
    (log/error "No MIDI device found.")))


(defn stop
  "Stop all listeners or a single listener."
  ([]
   (doseq [[id _] @listeners] (stop id)))
  ([id]
   (a/close! (:buffer (id @listeners)))
   (swap! listeners assoc-in [id :status] :stopped)))


(defn start
  "Start all listeners or a single listener.
   If a listener has already been started it will be restarted."
  ([]
   (doseq [[id _] @listeners] (start id)))
  ([id]
   (stop id)
   (swap! listeners assoc-in [id :buffer] (create-listener-buffer
                                           (:buffer-size (id @listeners))))
   (when (nil? @device) (input))
   (create-listener-consumer (id @listeners))
   (swap! listeners assoc-in [id :status] :started)))


(def ^:private default-listener-params
  {:buffer-size 8
   :chan nil
   :event {}
   :filter #{:note-on :note-off}
   :status :stopped})


;; TODO doc
(defn listener
  [id params]
  (when (id @listeners)
    (stop id))
  (as-> params |
   (merge default-listener-params |)
   (assoc | :buffer (create-listener-buffer (:buffer-size |))
            :id id)
   (swap! listeners assoc id |))
  (start id))


;; TODO implement this as :event listener param?
(def ^:private default-kit-params
  {:first-note 36
   :bank-size 16})


(defn ^:private bank-handler
  [params]
  (fn [event]
   (when (<= (:first-note params) (:note event))
     (let [relative-note (- (:note event) (:first-note params))
           relative-bank (int (/ relative-note (:bank-size params)))
           bank-note (rem relative-note (:bank-size params))]
       (when-let [b (nth (:banks params) relative-bank nil)]
         (b (assoc event :bank-note bank-note)))))))


;; TODO doc
(defn kit
  "Oh ya know......................................."
  [id user-params]
  (let [params (merge default-kit-params user-params)]
    (listener id (assoc params :handler (bank-handler params)))))


(defn status
  "Return a map of all listeners and their status, :started or :stopped."
  []
  (into {} (map (fn [[k v]] [k (:status v)]) @listeners)))


(defn forget
  "Stop a listener and forget about it."
  [id]
  (stop id)
  (swap! listeners dissoc id))


(defn logging
  "Set the log level to one of :debug :info :warn :error"
  [id]
  (log/set-level! id))
