(ns sbench-e2e.utils
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defmacro map-of
  [& xs]
  `(hash-map ~@(mapcat (juxt keyword identity) xs)))

(defn post [base-url run-id path data]
  (let [data' (assoc data :run-id run-id)
        response (http/post (str base-url path)
                   {:headers {"Content-Type" "application/json"}
                    :body (json/generate-string
                           data)
                    ; :throw-exceptions false
                    })]
    (if (not= (:status response) 200)
      (do
        (print (json/parse-string (:body response) true))  ; true means keywords
        (throw (Exception. (str "Got status " (:status response)))))
      (json/parse-string (:body response) true))))

(defn get [base-url path]
  (let [response (http/get (str base-url path) {})]
    (if (not= (:status response) 200)
      (do
        (print (json/parse-string (:body response) true))
        (throw (Exception. (str "Got status " (:status response)))))
      (json/parse-string (:body response) true))))

(defn show-config [config]
  (let [{categories :problem-sets} (get (:server-url config) "problem-sets")]
    (println
"Available problem sets:
=======================")
    (doseq [[category problems] categories]
      (println (str "\n" (name category) ":"))
      (doseq [{:keys [name id]} problems]
        (println
         (format "  - %-30s :: %-5s" name id))))))
