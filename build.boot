(set-env!
 :source-paths #{"src"}
 :resource-paths #{"test"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [adzerk/boot-test "1.1.1" :scope "test"]
                 [boot-codox "0.10.2" :scope "test"]
                 [com.taoensso/timbre "4.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [overtone/midi-clj "0.5.0"]])


(require '[adzerk.boot-test :as boot-test]
         '[codox.boot :as codox])


(def project 'bitsynthesis/armidillo)
(def version "0.4")


(task-options!
 aot {:namespace '#{armidillo.midi}}
 pom {:project project
      :version version})


(deftask test []
  (boot-test/test))


(deftask autotest []
  (comp (watch) (test)))


(deftask build []
  (comp (aot) (pom) (jar) (install)))


(deftask doc []
  (comp (codox/codox
         :name "Armidillo"
         :description (str "Non-blocking buffered and filtered "
                           "MIDI listeners for clojure projects.")
         :metadata {:doc/format :markdown}
         :source-uri (str "https://github.com"
                          "/bitsynthesis/armidillo"
                          "/blob/{version}/{filepath}#L{line}")
         :version version)
        (target)))
