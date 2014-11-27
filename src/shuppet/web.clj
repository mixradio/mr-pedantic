(ns shuppet.web
  (:require [cheshire.core :as json]
            [compojure
             [core :refer [defroutes context GET PUT POST DELETE]]
             [handler :as handler]
             [route :as route]]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [metrics.ring
             [expose :refer [expose-metrics-as-json]]
             [instrument :refer [instrument]]]
            [radix
             [error :refer [wrap-error-handling error-response]]
             [ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
             [setup :as setup]
             [reload :refer [wrap-reload]]]
            [ring.middleware
             [format-params :refer [wrap-json-kw-params]]
             [format-response :refer [wrap-json-response]]
             [params :refer [wrap-params]]]
            [shuppet
             [core :as core]
             [middleware :as middleware]
             [scheduler :as scheduler]]
            [slingshot.slingshot :refer [throw+]]))

(def ^:private version
  (setup/version "shuppet"))

(def ^:private environments
  (env :environments))

(defn- response
  ([body status]
     (merge {:status status
             :headers {"Content-Type" "application/json"}}
      (if (nil? body)
        {}
        {:body body})))
  ([body]
     (response body 200)))

(defn- list-envs
  []
  (response {:environments (str/split environments #",")}))

(defn- show-env-config
  [env]
  {:body (core/get-config env)})

(defn- apply-env-config
  [env]
  {:body {:report (core/apply-config env)}})

(defn- list-apps
  [env]
  {:body {:applications (core/app-names env)}})

(defn- show-app-config
  [env name]
  {:body (core/get-config env name)})

(defn- validate-config
  [env app-name body]
  {:body (core/validate-config env app-name body)})

(defn- apply-app-config
  [env name]
  {:body (core/apply-config env name)})

(defn- apply-apps-config
  [env]
  {:body (core/configure-apps env)})

(defn- create-app-config
  [name]
  (let [resp (core/create-config name)]
    {:body (dissoc resp :status)
     :status (:status resp)}))

(defn- send-error
  [code message]
  (throw+ {:type :_
           :status code
           :message message}))

(defn- env-schedule
  [env]
  (if-let [schedule (scheduler/get-schedule env)]
    {:body schedule}
    {:body {:message "No jobs are currently scheduled"}
     :status 404}))

(defn- stop-schedule
  [env name interval]
  (let [interval (Integer/valueOf (or interval core/default-stop-interval))]
    (core/stop-schedule-temporarily env name interval)
    {:body {:message (str "Scheduler for " name " is stopped for " interval " minutes in environment" env)}}))

(defn- start-schedule
  [env name]
  (core/restart-app-schedule env name)
  (env-schedule env))

(defn- app-schedule
  [env name action interval]
  (case action
    "stop" (stop-schedule env name interval)
    "start" (start-schedule env name)
    (if-let [start-time (core/get-app-schedule env name)]
      {:body {:message (str "The scheduler is currently stopped for " name "  and will be restarted again at " start-time)}}
      (env-schedule env))))

(def ^:private resources
  {:GET (array-map "/healthcheck" "Healthcheck"
                   "/ping" "Ping"
                   "/1.x/icon" "Icon"
                   "/1.x/envs" "All available environments"
                   "/1.x/envs/:env-name" "Read and evaluate the environment configuration :env-name.clj from Git repository :env-name, return the configuration in JSON"
                   "/1.x/envs/:env-name/apply" "Apply the environment configuration"
                   "/1.x/envs/:env-name/apps" "All available applications for the given environment"
                   "/1.x/envs/:env-name/schedule" "Shows the current shuppet schedule, if any"
                   "/1.x/envs/:env-name/apps/apply" "Apply configuration for all applications listed in Onix"
                   "/1.x/envs/:env-name/apps/:app-name" "Read the application configuration :app-name.clj from Git repository :app-name and evaluate it with the environment configuration, return the configuration in JSON. Master branch is used for all environments except for production where prod branch is used instead."
                   "/1.x/envs/:env-name/apps/:app-name/apply" "Apply the application configuration for the given environment"
                   "/1.x/envs/:env-name/apps/:app-name/schedule" "Displays the current schedule for the app, use QS action=start/stop to start/stop the scheduler interval=xx in minutes (the default interval is 60 minutes, and the maximum stop interval is 720 minutes)")
   :POST (array-map "/1.x/apps/:app-name" "Create an application configuration"
                    "/1.x/validate" "Validate the configuration passed in the body, env and app-name are optional parameters")})

(defn healthcheck
  []
  {:body {:name "shuppet"
          :version version
          :success true}})

(defroutes applications-routes

  (GET "/" []
       (list-envs))

  (GET "/:env" [env]
       (show-env-config env))

  (GET "/:env/apply" [env]
       (apply-env-config env))

  (GET "/:env/apps" [env]
       (list-apps env))

  (GET "/:env/apps/apply" [env]
       (apply-apps-config env))

  (GET "/:env/schedule" [env]
       (env-schedule env))

  (POST "/:env/schedule" [env action interval]
        {:body (scheduler/schedule env action interval)})

  (GET "/:env/apps/:name" [env name]
       (show-app-config env name))

  (GET "/:env/apps/:name/schedule" [env name action interval]
       (app-schedule env name action interval))

  (GET "/:env/apps/:name/apply" [env name]
       (apply-app-config env name)))

(defroutes routes
  (context "/1.x" []

   (GET "/icon" []
        {:body (-> (clojure.java.io/resource "shuppet.jpg")
                   (clojure.java.io/input-stream))
         :headers {"Content-Type" "image/jpeg"}})

   (POST "/apps/:name" [name]
         (create-app-config (str/lower-case name)))

   (POST "/validate" [env app-name :as {body :body}]
         (validate-config env app-name (slurp body)))

   (context "/envs" []
            applications-routes))

  (GET "/ping" []
       {:body "pong"
        :headers {"Content-Type" "text/plain"}})

  (GET "/healthcheck" []
       (healthcheck))

  (GET "/resources"
       []
       {:body resources})

  (route/not-found (error-response "Resource not found" 404)))

(def app
  (-> routes
      (middleware/wrap-check-env)
      (middleware/wrap-shuppet-error)
      (instrument)
      (wrap-error-handling)
      (wrap-ignore-trailing-slash)
      (wrap-json-response)
      (wrap-json-kw-params)
      (wrap-params)
      (expose-metrics-as-json)))
