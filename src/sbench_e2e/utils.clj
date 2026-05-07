(ns sbench-e2e.utils
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn post [base-url, run-id, path, data]
  (let [data' (assoc data :run-id run-id)
        response (http/post (str base-url path)
                   {:headers {"Content-Type" "application/json"}
                    :body (json/generate-string
                           data)
                    ; :throw-exceptions false
                    })]
    (if (not= (:status response) 200)
      (do
        (print (json/parse-string (:body response) true))
        (throw (Exception. (str "Got status " (:status response)))))
      (json/parse-string (:body response) true))))

