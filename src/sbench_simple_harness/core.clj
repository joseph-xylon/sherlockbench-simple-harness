(ns sbench-simple-harness.core
  (:require [wkok.openai-clojure.api :as api]
            [wkok.openai-clojure.openai :as openai-impl]
            [martian.core :as martian]
            [schema.core :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [sbench-simple-harness.utils :as utils]
            [sbench-simple-harness.run-bench :as run-bench])
  (:import [java.time Instant])
  (:gen-class))

;; Qwen3.5 recommended sampling settings (thinking mode, general tasks)
(def ^:private temperature 1.0)
(def ^:private top-p 0.95)
(def ^:private top-k 20)
(def ^:private min-p 0)
(def ^:private presence-penalty 1.5)

;; openai-clojure coerces the request body against the bundled OpenAI swagger
;; spec, which silently strips any params not in the spec (e.g. Qwen's top_k and
;; min_p). Open up the chat-completion body schema so these pass through.
(defonce ^:private allow-extra-chat-params
  (alter-var-root #'openai-impl/m
                  (fn [m]
                    (delay
                     (martian/update-handler @m :create-chat-completion
                                             update-in [:body-schema :body]
                                             assoc schema/Any schema/Any)))))

(defn read-edn-file [file-path]
  (edn/read-string
   (slurp file-path)))

(def ^:private max-retries 5)

(defn- log-api-error [attempt exception]
  (spit "api-errors.log"
        (str (Instant/now) " retry=" attempt " error=" (.getMessage exception) "\n")
        :append true))

(defn- check-finish-reason
  "A finish_reason other than stop/tool_calls means the completion is unusable
   (e.g. \"length\" = truncated); throw so call-with-retry treats it as a
   failed call."
  [response]
  (let [finish-reason (-> response :choices first :finish_reason)]
    (if (contains? #{"stop" "tool_calls"} finish-reason)
      response
      (throw (ex-info (str "unusable finish_reason: " finish-reason)
                      {:finish_reason finish-reason})))))

(defn- call-with-retry [request-params api-opts]
  (loop [attempt 0]
    (let [result (try
                   {:ok (check-finish-reason
                         (api/create-chat-completion request-params api-opts))}
                   (catch Exception e {:err e}))]
      (if-let [err (:err result)]
        (if (< attempt max-retries)
          (do
            (log-api-error (inc attempt) err)
            (println (str "### SYSTEM: API error (retry " (inc attempt) "/" max-retries "): " (.getMessage err)))
            (recur (inc attempt)))
          (throw err))
        (:ok result)))))

;; llama.cpp speaks reasoning via `reasoning_content` (the field used
;; internally and in trajectories); OpenRouter uses `reasoning` in both
;; directions. Translate at the API boundary.
(defn- openrouter? [config]
  (clojure.string/includes? (str (:llm-api config)) "openrouter"))

(defn- reasoning->openrouter [messages]
  (mapv (fn [{:keys [reasoning_content] :as m}]
          (cond-> (dissoc m :reasoning_content)
            (seq reasoning_content) (assoc :reasoning reasoning_content)))
        messages))

(defn- reasoning<-openrouter [response]
  (update-in response [:choices 0 :message]
             (fn [{:keys [reasoning] :as m}]
               (cond-> (dissoc m :reasoning :reasoning_details)
                 (seq reasoning) (assoc :reasoning_content reasoning)))))

(defn- ->openrouter-request
  "OpenRouter only enforces schemas via the OpenAI `json_schema`
   response_format; llama.cpp's `json_object` + `schema` variant is accepted
   but the schema is silently ignored."
  [params]
  (cond-> (update params :messages reasoning->openrouter)
    (get-in params [:response_format :schema])
    (update :response_format
            (fn [{:keys [schema]}]
              {:type "json_schema"
               :json_schema {:name (:title schema "response")
                             :strict true
                             :schema schema}}))))

(defn call-qwen
  ([config messages]
   (call-qwen config messages nil))
  ([config messages extra-params]
   (cond-> (call-with-retry
    (cond-> (merge {:model (:model config)
            :messages messages
            :temperature temperature
            :top_p top-p
            :top_k top-k
            :min_p min-p
            :presence_penalty presence-penalty
            :reasoning {:enabled true}
            }
           (when-let [slot (:slot config)] {:id_slot slot})
           extra-params)
      (openrouter? config) ->openrouter-request)
    {:api-endpoint (:llm-api config)
     :api-key (:api-key config)
     :request {:timeout 600000}})
     (openrouter? config) reasoning<-openrouter)))

(defn print-usage []
  (println
"Usage:

First argument must be one of:
- start :: start a test run (optional second arg: attempts-per-problem)
- list  :: list problem-sets

Optional flags (any action):
- --config <file> :: merge this edn file over resources/config.edn
- --slot <n>      :: pin requests to a llama-server slot (id_slot)
"))

(defn print-start-usage []
  (println
"Usage:

\"start\" action requires :problem-set in config.
Optional second arg: attempts-per-problem (default 1)
"))

(defn- parse-flag
  "Pull `<flag> <value>` out of args; returns [value remaining-args]."
  [flag args]
  (let [[before [_ value & after]] (split-with #(not= flag %) args)]
    [value (concat before after)]))

(defn -main [& args]
  (let [[config-path args] (parse-flag "--config" args)
        [slot args] (parse-flag "--slot" args)
        config (cond-> (read-edn-file "resources/config.edn")
                 config-path (merge (read-edn-file config-path))
                 slot (assoc :slot (Integer/parseInt slot)))
        postfn (partial utils/post (:server-url config))]
    (case (first args)
      "start" (let [[_ attempts] args
                    problem-set (:problem-set config)]
                (if (nil? problem-set)
                  (print-start-usage)
                  (let [run-deets (run-bench/start-run postfn problem-set (or attempts "1"))]
                    (run-bench/main-loop (assoc run-deets
                                                :llmfn (partial call-qwen config)
                                                :prompt-config (:prompt config)
                                                :interleaved-thinking (:interleaved-thinking config)))
                    (run-bench/complete-run (:postfn run-deets) (:run-id run-deets) (:model config)))))
      "list"  (utils/show-config config)
      "show_config" (prn (:server-url config))
      (print-usage))))


(comment
  (def config (read-edn-file "resources/config.edn"))

  (call-qwen config [{:role "user" :content "Would you love a monsterman?"}])

  (utils/post (:server-url config) nil "start-run" {:client-id "boop"
                                                    :attempts-per-problem 10
                                                    :problem-set "sherlock1/all"})
  
  (print-usage)

  (utils/show-config config)

  (-main "start" "sherlock1/all")

  (let [config (read-edn-file "resources/config.edn")
        postfn (partial utils/post (:server-url config))
        run-deets (run-bench/start-run postfn "sherlock1/all" 1)
        postfn' (partial postfn (:run-id run-deets))]
    (postfn' "next-verification" {:attempt-id (:attempt-id (first (:attempts run-deets)))})
      )
  
  )
