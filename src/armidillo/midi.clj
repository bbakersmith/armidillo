(ns armidillo.midi
  (:require [clojure.core.async :as a]
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
                        (:command event)
                        event)]
    (when (and (or (nil? (:channel l))
                   (= (:channel l) (:channel event)))
               ((:filter l) event-matcher))
      (log/debug "MIDI listener " (:id l) " receives " event)
      (a/>!! (:buffer l) event))))


(def ^:private note-pitches
  [:c :c# :d :d# :e :f :f# :g :g# :a :a# :b])


(defn ^:private assoc-event-metadata [event]
  (let [;; midi channels should start at 1 not 0
        channel (inc (:channel event))
        octave (int (- (/ (:note event) (count note-pitches)) 2))
        octave-note (mod (:note event) (count note-pitches))
        pitch (get note-pitches octave-note)]
    (-> event
        (select-keys [:command :note :timestamp :velocity])
        (assoc :channel channel
               :pitch pitch
               :octave octave
               :octave-note octave-note))))


(defn ^:private midi-clj-handler
  [event]
  (let [enriched-event (assoc-event-metadata event)]
    (doseq [[id l] @listeners]
      (apply-listener l enriched-event))))


(defn ^:private create-listener-consumer [l]
  (a/go
   (loop []
     (when-let [event (a/<! (:buffer l))]
       (try
        ((:handler l) event)
        (catch Throwable t
          (log/error t
                     "Listener consumer for '"
                     (:id l)
                     "' failed to handle event")))
       (recur)))))


(defn logging
  "Set the log level to one of :debug :info :warn :error"
  [id]
  (log/set-level! id))


(defn input
  "Select a MIDI input device from a pop-up window. This will happen
   automatically when a listener is defined if an input hasn't already been
   selected."
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


(defn forget
  "Stop a listener and forget about it."
  [id]
  (stop id)
  (swap! listeners dissoc id))


(defn status
  "Return a map of all listeners and their statuses"
  []
  (into {} (map (fn [[k v]] [k (:status v)]) @listeners)))


(def ^:private default-listener-params
  {:buffer-size 8
   :channel nil
   :filter (comp #{:note-on :note-off} :command)
   :status :stopped})


;; TODO doc
(defn listener
  "[Create a listener.](1_introduction.html#listeners)."
  [id params]
  (when (id @listeners)
    (stop id))
  (as-> params |
   (merge default-listener-params |)
   (assoc | :buffer (create-listener-buffer (:buffer-size |))
            :id id)
   (swap! listeners assoc id |))
  (start id))


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
  "[Create a kit.](1_introduction.html#kits)"
  [id user-params]
  (let [params (merge default-kit-params user-params)]
    (listener id (assoc params :handler (bank-handler params)))))
