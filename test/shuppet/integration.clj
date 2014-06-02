(ns shuppet.integration
  (:require [shuppet.test-util :refer :all]
            [clj-http.client :as client]
            [midje.sweet :refer :all]
            [cheshire.core :as json]
            [clojure.data.zip.xml :as xml]
            [environ.core :refer [env]])
  (:import [java.util UUID]))


(defn content-type
  [response]
  (if-let [ct ((:headers response) "content-type")]
    (first (clojure.string/split ct #";"))
    :none))

(defmulti read-body content-type)

(defmethod read-body "application/xml" [http-response]
  (-> http-response
      :body
      .getBytes
      java.io.ByteArrayInputStream.
      clojure.xml/parse clojure.zip/xml-zip))

(defmethod read-body "application/json" [http-response]
  (json/parse-string (:body http-response) true))

(defmethod read-body :none [http-response]
  (throw (Exception. (str "No content-type in response: " http-response))))

(lazy-fact-group :integration
   (fact "Ping resource returns 200 HTTP response"
         (let [response (client/get (url+ "/ping") {:throw-exceptions false})]
           response => (contains {:status 200})))

   (fact "Status returns all required elements"
         (let [response (client/get (url+ "/status") {:throw-exceptions false})
               body (read-body response)]
           response => (contains {:status 200})))

   (fact "Test config is applied without errors"
         (http-get  "/envs/poke/apps/shuppetest/clean") => (contains {:status 200})
         (let [response (http-get  "/envs/poke/apps/shuppetest/apply")
               report (get-in response [:body :report])]
           (:status response) => 200
           (seq report) => truthy)
         (http-get  "/envs/poke/apps/shuppetest/clean")))
