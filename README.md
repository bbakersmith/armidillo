# Armidillo

Non-blocking buffered and filtered MIDI listeners for clojure projects.

    [bitsynthesis/armidillo "0.3"]


## Overview

Incoming MIDI events are processed in parallel, but individually are passed
to each listener sequentially.

    event 1 --> listener 1 --> listener 2 --> listener 3
    event 2 --> listener 1 --> listener 2 --> listener 3
    event 3 --> listener 1 --> listener 2 --> listener 3


Events are added to a listener's buffer if they are the same channel
(or channel is nil) and the filter returns a truthy value.

    +---------------------------------------+
    | chan?    filter?    buffer    handler |
    +--+------------------------------------+
        |         |         |          |
    --->|         |         |          |
        +-------->+         |          |
        |         +-------->+          |
        |         |         |          |

A consumer thread passes messages on the buffer to the handler function.

        |         |         +--------->+


## Listeners

Define a listener with a handler function that accepts [midi events](#events).

If a MIDI device hasn't been selected when a listener is defined, the user
will be prompted to select one. The listener will begin handling events
immediately.

    (require '[armidillo.midi :as midi])

    (defn my-handler [midi-event]
      ;; do something with incoming midi events
      (println midi-event))

    (midi/listener
      :my-listener
      {:chan 1
       :event {:custom-param "any value"}
       :filter (comp #{:note-on} :type)
       :handler my-handler})

Return the status of all active listeners:

    (midi/status)

Listeners can be stopped and started individually:

    (midi/stop :my-listener)
    (midi/start :my-listener)

Or en masse:

    (midi/stop)
    (midi/start)


## Events

    {
     :cmd          144       ;; midi status code
     :note         39        ;; command key or note number
     :pitch        :d#       ;; note pitch
     :octave       1         ;; octave number
     :octave-note  3         ;; note number relative to the octave
     :time         123456    ;; timestamp
     :type         :note-on  ;; type of command based on status code
     :vel          90        ;; command value or note velocity
    }

Attach listener-specific metadata, or override existing event values,
with the listener's `:event` option.

    :event {:custom-param "any value"
            :vel 127}


## Filters

Filters are functions which take a midi event as an argument and return a truthy or falsey value. The result determines whether events are added to
the listener's [buffer](#buffers).

Only listen for :note-on and :note-off events, this is the default.

    :filter (comp #{:note-on :note-off} :type)

Filter on type of control change and velocity (value)
greater than 63:

    :filter #(and (= :control-change (:type %))
                  (< 63 (:vel %)))


## Buffers

Listeners use a sliding buffer for messages that pass the [filter](#filters).
If the buffer fills up it drops older events. This is to keep midi events up to date, both by preventing blocking of other listeners, and protecting
against large numbers of events backing up if the handler can't keep up with
the incoming rate.

Adjust the size of the buffer, set to 8 by default:

    :buffer-size 100


## Debugging

Adjust the log level to get more output about events received and processed.

    (midi/logging :debug)
