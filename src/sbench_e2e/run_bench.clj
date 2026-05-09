(ns sbench-e2e.run-bench
  (:require [sbench-e2e.utils :as utils]
            [sbench-e2e.prompts :as prompts]))

(defn start-run [postfn problem-set attempts-per-problem]
  (let [post-data (conj (utils/map-of problem-set attempts-per-problem)
                        {:client-id "sbench_e2e"})
        {:keys [run-id attempts]} (postfn nil "start-run" post-data)
        postfn' (partial postfn run-id)]
    
    (println (str "Started new run with id: " run-id))
    {:run-id run-id :attempts attempts :postfn postfn'}))

(defn list-to-map [input-list]
  "openai doesn't like arrays much so just assign alphabetical keys"
  (let [keys (map (comp str char) (range 97 (+ 97 (count input-list))))]
    (zipmap keys (map #(hash-map :type %) input-list))))

(defn investigation [postfn llmfn messages attempt]
  (let [{:keys [attempt-id arg-spec output-type test-limit attempts-remaining]} attempt
        mapped-args (list-to-map arg-spec)
        tools [{:type "function",
                :function {:name "mystery_function"
                           :parameters {:type "object"
                                        :properties mapped-args
                                        :required (keys mapped-args)
                                        :additionalProperties false}}}]]
    (prn "arg_spec: ")
    (prn arg-spec)
    (prn "attempt-id: ")
    (prn attempt-id)
    (prn tools)
    (loop [messages messages]
      (let [completion (llmfn messages tools)]
        (prn completion)
        
        ))))

(defn verification [postfn llmfn attempt inv]
  true)

(defn complete-attempt []
  true)

(defn main-loop [{:keys [run-id attempts postfn llmfn]}]
  (doseq [attempt [(first attempts)]]
    (let [messages (prompts/make-initial-messages (:test-limit attempts))
          messages' (investigation postfn llmfn messages attempt)]
      (verification postfn llmfn attempt messages')))
  (complete-attempt))
