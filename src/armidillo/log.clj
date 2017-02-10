(ns armidillo.log
  "Log settings."
  (:require [taoensso.timbre :as log]))


(log/set-level! :warn)


(defn set-level [level]
  (log/set-level! level))
