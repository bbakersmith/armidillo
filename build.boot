(set-env!
 :source-paths #{"src"}
 :resource-paths #{"test"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [adzerk/boot-test "1.1.1" :scope "test"]
                 [com.taoensso/timbre "4.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [overtone/midi-clj "0.1"]])


(require '[adzerk.boot-test :as boot-test])


(def version "0.1")


(def public-namespaces
  '#{armidillo.log
     armidillo.midi})


(task-options!
 aot {:namespace public-namespaces}
 pom {:project 'bbakersmith/armidillo
      :version version})


(deftask test []
  (boot-test/test))


(deftask autotest []
  (comp (watch) (test)))


(deftask build []
  (comp (aot) (pom) (jar) (install)))
