(ns sbench-e2e.run-bench
  (:require [sbench-e2e.utils :as utils]))

(defn start-run [postfn problem-set attempts-per-problem]
  (let [post-data (conj (utils/map-of problem-set attempts-per-problem)
                        {:client-id "sbench_e2e"})
        {:keys [run-id attempts]} (postfn nil "start-run" post-data)]
    
    (println (str "run-id: " run-id))
    (println attempts-per-problem)
    ))
