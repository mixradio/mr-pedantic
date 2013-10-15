(ns shuppet.urlbuilder
  (:require
   [clj-http.client :refer [generate-query-string]]
   [clojure.string :refer [lower-case upper-case]]
   [clojure.tools.logging :as log]
   [environ.core :refer [env]])
  (:import
   [java.net URL]
   [javax.crypto Mac]
   [javax.crypto.spec SecretKeySpec]
   [org.apache.commons.codec.binary Base64]
   [org.joda.time DateTime DateTimeZone]))


(def ^:const hmac-sha256-algorithm  "HmacSHA256")
(def ^:const new-line "\n")

(defn- current-time []
  (->>
   (DateTime. (DateTimeZone/UTC))
   (.toString)))

(def ^:const aws-access-key-id (env :service-aws-access-key-id))
(def ^:const aws-access-key-secret (env :service-aws-secret-access-key))

(defn- aws-key []
  (if (empty? aws-access-key-id)
    (System/getenv "AWS_ACCESS_KEY_ID")
    aws-access-key-id))

(defn- aws-secret []
  (if (empty? aws-access-key-secret)
    (System/getenv "AWS_SECRET_KEY")
    aws-access-key-secret))


(def ^:private auth-params
  {"SignatureVersion" "2"
   "AWSAccessKeyId" (aws-key)
   "Timestamp" (current-time)
   "SignatureMethod" hmac-sha256-algorithm})

(defn- bytes [str]
  (.getBytes str "UTF-8"))

(defn- base64 [str]
  (Base64/encodeBase64String str))

(defn- get-mac [key]
  (let [signing-key (SecretKeySpec. (bytes key) hmac-sha256-algorithm)
        mac (Mac/getInstance hmac-sha256-algorithm)]
    (.init mac signing-key)
    mac))

(defn- calculate-hmac [data]
  (try
    (let [mac (get-mac (aws-secret))
          raw-mac (.doFinal mac (bytes data))]
      (base64 raw-mac))
    (catch Exception e
      (log/error e "Failed to generate HMAC"))))

(defn- get-path [url]
  (let [path (.getPath url)]
    (if (empty? path)
      "/"
      path)))

(defn- url-to-sign [method host path query-params]
  (str (upper-case method)
       new-line
       host
       new-line
       path
       new-line
       (generate-query-string query-params)))

(defn- generate-signature [method uri query-params]
  (let [url (URL. uri)
        host (lower-case (.getHost url))
        path (get-path url)
        data (url-to-sign method host path query-params)]
    (calculate-hmac data)))

(defn build-url
  "Builds a signed url, which can be used with the aws rest api"
  [method uri params]
  (let [query-params (merge params auth-params)
        signature (generate-signature method uri query-params)
        query-string (generate-query-string (merge query-params {"Signature" signature}))]
    (str uri "?" query-string)))


;(build-url "get" "https://www.test.com" {"a" "1"})
