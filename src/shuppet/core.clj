(ns shuppet.core
  (:require [shuppet
             [util :as util]
             [git :as git]
             [sqs :as sqs]
             [campfire :as cf]
             [validator :refer [validate]]]
            [cluppet
             [core :as cl-core]
             [signature :as cl-sign]]
            [clojure.string :refer [lower-case split]]
            [clojure.tools.logging :refer [info warn error]]
            [clj-http.client :as client]
            [environ.core :as env]
            [slingshot.slingshot :refer [try+ throw+]]
            [clj-time.local :refer [local-now to-local-date-time]]
            [clj-time.core :refer [plus after? minutes]]))

(def no-schedule-services  (atom {}))
(def default-stop-interval 60)
(def max-stop-interval 720)

(def default-keys-map
  {:key (env/env :service-aws-access-key-id-poke)
   :secret (env/env :service-aws-secret-access-key-poke)})

(defn onix-app-names
  []
  (let [url (str  (env/env :environment-entertainment-onix-url) "/applications")
        response (client/get url {:as :json
                                  :throw-exceptions false})
        status (:status response)]
    (if (= 200 status)
      (get-in response [:body :applications])
      (cf/error {:title "Failed to get application list from Onix."
                 :url url
                 :status status
                 :message (:body response)}))))

(defn local-app-names
  []
  (split (env/env :service-local-app-names) #","))

(defn git-config-as-string
  [environment app-name]
  (git/get-data environment app-name))

(defn create-git-config
  [appname master-only]
  (git/create-application appname master-only))

(defn aws-keys-map
  [environment]
  {:key (env/env (keyword (str "service-aws-access-key-id-" environment)))
   :secret (env/env (keyword (str "service-aws-secret-access-key-" environment)))} )

                                        ;change env arg to flag
(defmacro with-ent-bindings
  "Specific Entertainment bindings"
  [environment & body]
  `(let [local?# (= "local" ~environment)]
     (binding [cl-sign/*aws-credentials* (if local?#
                                           default-keys-map
                                           (aws-keys-map ~environment))]
       ~@body)))

(defn- tooling-service?
  [name]
  ((set (split (env/env :service-tooling-applications) #",")) name))

(defn- process-report [report env]
  (doseq [item report]
    (when (= :CreateLoadBalancer (:action item))
      (sqs/announce-elb (:elb-name item) env)))
  report)

(defn stop-schedule-temporarily
  [env name interval]
  (let [interval (or interval default-stop-interval)
        interval (if (> interval max-stop-interval) max-stop-interval interval)
        t-key (keyword (str env "-" name))]
    (swap! no-schedule-services merge {t-key (plus (local-now) (minutes interval))})))

(defn restart-app-schedule
  [env name]
  (swap! no-schedule-services dissoc (keyword (str env "-" name))))

(defn get-app-schedule
  [env name]
  (@no-schedule-services (keyword (str env "-" name))))

(defn- is-stopped?
  [env name]
  (let [schedule (get-app-schedule env name)]
    (if schedule
      (if (after? (local-now) schedule)
        (not (empty? (swap! no-schedule-services dissoc (keyword (str env "-" name)))))
        true)
      false)))

(defn get-config
  ([env]
     (cl-core/evaluate-string (git/get-data env)))
  ([env app]
     (get-config (git/get-data env) env app))
  ([env-str-config env app]
     (cl-core/evaluate-string [env-str-config (git/get-data env app)]
                      {:$app-name app
                       :$env env})))

(defn apply-config
  ([env]
     (with-ent-bindings env
       (->
        (cl-core/apply-config (get-config env))
        (process-report env))))
  ([env app]
     (apply-config (git/get-data env) env app))
  ([env-str-config env app]
      (with-ent-bindings env
        (->
         (cl-core/apply-config (get-config env-str-config env app))
         (process-report env)))))

(defn filtered-apply-config
  [env-str-config env app]
  (when-not (is-stopped? env app)
       (apply-config env app)))

(defn- env-config? [config]
  (re-find #"\(def +\$" config))

(defn validate-config
  [env app config]
  (let [env (or env "poke")
        app (or app "app-name")
        config (if (env-config? config)
                 (cl-core/evaluate-string config)
                 (cl-core/evaluate-string [(git/get-data env) config]
                                  {:$app-name app
                                   :$env env}))]
    (validate config)
    config))

(defn create-config
  [env app master-only]
  (when-not (= "local" env)
    (let [master-only (or master-only (tooling-service? app))]
      (create-git-config app master-only))))

(defn clean-config
  [env app]
  (with-ent-bindings env
    (cl-core/clean-config (get-config env app))))

(defn- filter-tooling-services
  [environment names]
  (if (= environment "prod")
    (remove tooling-service? names)
    names))

(defn app-names [env]
  (with-ent-bindings env
    (filter-tooling-services env (onix-app-names))))

(defn update-configs [env-str-config env]
  (let [apps (app-names env)]
    (doseq [app apps]
      (try+
       {:app app
        :report (filtered-apply-config env-str-config env app)}
       (catch [:type :shuppet.git/git] {:keys [message]}
         (warn message)
         {:app app
          :error message})
       (catch  [:type :cluppet.core/invalid-config] {:keys [message]}
         (cf/error {:environment env
                    :title "error while loading config"
                    :app-name app
                    :message message })
         (error (str app " config in " env " cannot be loaded: " message))
         {:app app
          :error message})
       (catch Exception e
         (error (str app " in " env " failed: " (.getMessage e) " stacktrace: " (util/str-stacktrace e)))
         {:app app
          :error (.getMessage e)})
       (catch Object e
         (error (str app " in " env " failed: " e))
         {:app app
          :error e})))))

(defn configure-apps
  [env]
  (apply-config env)
  (update-configs (git/get-data env) env))
