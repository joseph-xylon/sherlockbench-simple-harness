(ns sbench-e2e.run-bench
  (:require [sbench-e2e.utils :as utils]
            [sbench-e2e.prompts :as prompts]
            [cheshire.core :as json]))

(defn start-run [postfn problem-set attempts-per-problem]
  (let [post-data (conj (utils/map-of problem-set attempts-per-problem)
                        {:client-id "sbench_e2e"})
        {:keys [run-id attempts]} (postfn nil "start-run" post-data)
        postfn' (partial postfn run-id)]
    
    (println (str "Started new run with id: " run-id))
    {:run-id run-id :attempts attempts :postfn postfn'}))

(defn list-to-map
  "openai doesn't like arrays much so just assign alphabetical keys"
  [input-list]
  (let [keys (map (comp str char) (range 97 (+ 97 (count input-list))))]
    (zipmap keys (map #(hash-map :type %) input-list))))

(defn normalise-args
  "map back to list"
  [input-map]
  (vals (sort input-map)))

(defn handle-tool-call [postfn attempt-id arg-spec call]
  (let [arguments (json/parse-string (:arguments (:function call)))
        args-norm (normalise-args arguments)
        fnoutput (:output (postfn "test-function" {:attempt-id attempt-id
                                                      :args args-norm}))]
    (println args-norm "->" fnoutput)

    {:role "tool"
     :content (json/generate-string fnoutput)
     :tool_call_id (:id call)}))

(defn investigation [postfn llmfn messages attempt]
  (let [{:keys [attempt-id arg-spec output-type test-limit attempts-remaining]} attempt
        mapped-args (list-to-map arg-spec)
        tools [{:type "function",
                :function {:name "mystery_function"
                           :parameters {:type "object"
                                        :properties mapped-args
                                        :required (keys mapped-args)
                                        :additionalProperties false}}}]]
    (loop [messages messages
           max-loop 1] ; for testing
      (prn messages)
      (let [{[{{:keys [content tool_calls]} :message} & _] :choices :as completion} (llmfn messages tools)
            tool-message (handle-tool-call postfn (:attempt-id attempt) arg-spec (first tool_calls))]
        (if (< 0 max-loop)
          (recur (conj messages tool-message) (- max-loop 1))
          "loop overflow")
        ))))

(defn verification [postfn llmfn attempt inv]
  true)

(defn complete-attempt []
  true)

(defn main-loop [{:keys [run-id attempts postfn llmfn]}]
  (doseq [attempt [(first attempts)]]
    (let [messages (prompts/make-initial-messages (:test-limit attempt))
          messages' (investigation postfn llmfn messages attempt)]
      (verification postfn llmfn attempt messages')))
  (complete-attempt))
