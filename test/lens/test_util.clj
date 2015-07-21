(ns lens.test-util
  (:require [datomic.api :as d]
            [cognitect.transit :as transit]
            [lens.schema :refer [load-base-schema]]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io ByteArrayOutputStream]))

(defn create-db [schema]
  (let [uri "datomic:mem://test"]
    (d/delete-database uri)
    (d/create-database uri)
    (let [conn (d/connect uri)]
      (d/transact conn schema)
      (d/db conn))))

(defn id-attr [ident]
  {:db/ident ident
   :db/valueType :db.type/string
   :db/unique :db.unique/identity
   :db/cardinality :db.cardinality/one})

(defn tempid []
  (d/tempid :db.part/user))

(defn resolve-tempid [tx-data tempid]
  (d/resolve-tempid (:db-after tx-data) (:tempids tx-data) tempid))

(defn str->is [s]
  (io/input-stream (.getBytes s "utf-8")))

(defn transit->is [o]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) o)
    (io/input-stream (.toByteArray out))))

(defn path-for [handler & args] (pr-str {:handler handler :args args}))

(defn connect [] (d/connect "datomic:mem:test"))

(defn database-fixture [f]
  (do
    (d/create-database "datomic:mem:test")
    (load-base-schema (connect)))
  (f)
  (d/delete-database "datomic:mem:test"))

(defn request [method & kvs]
  (reduce-kv
    (fn [m k v]
      (if (sequential? k)
        (assoc-in m k v)
        (assoc m k v)))
    {:request-method method
     :headers {"accept" "*/*"}
     :path-for path-for
     :params {}
     :db (d/db (connect))}
    (apply hash-map kvs)))

(defn execute [handler method & kvs]
  (handler (apply request method kvs)))

(defn location [resp]
  (edn/read-string (get-in resp [:headers "Location"])))

(defn href [resp]
  (edn/read-string (-> resp :body :links :self :href)))

(defn error-msg [resp]
  (-> resp :body :data :message))

(def error-msg' [:body :data :message])
