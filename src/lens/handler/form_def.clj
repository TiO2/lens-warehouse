(ns lens.handler.form-def
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!! timeout]]
            [async-error.core :refer [<??]]
            [clojure.core.reducers :as r]
            [lens.logging :refer [debug]]
            [liberator.core :refer [resource]]
            [lens.handler.util :as hu]
            [lens.handler.study :as study]
            [lens.handler.inquiry-type :as inquiry-type]
            [lens.api :as api]
            [lens.reducers :as lr]
            [clojure.string :as str]
            [lens.util :as util]
            [schema.core :as s :refer [Str Any]]
            [pull.core :refer [pull]]))

(defn path [path-for form-def]
  (path-for :form-def-handler :eid (hu/entity-id form-def)))

(defn link [path-for form-def]
  {:href (path path-for form-def)
   :label (:form-def/name form-def)})

(defn render-embedded [path-for timeout form-def]
  (-> {:data
       (-> {:id (:form-def/id form-def)
            ;;TODO: alias
            :name (:form-def/name form-def)}
           (assoc-when :desc (:form-def/desc form-def))
           (assoc-when :keywords (:form-def/keywords form-def))
           (assoc-when :inquiry-type (-> form-def :form-def/inquiry-type
                                         :inquiry-type/name)))
       :links
       {:self
        (link path-for form-def)}}
      #_(assoc-count
          (util/try-until timeout (api/num-form-subjects form-def))
          (path-for :form-count-handler :id (:form-def/id form-def)))))

(defn render-embedded-list [path-for timeout form-defs]
  (r/map #(render-embedded path-for timeout %) form-defs))

(defn- default-sort [form-def]
  [(or (-> form-def :form-def/inquiry-type :inquiry-type/rank) Long/MAX_VALUE)
   (:form-def/name form-def)])

(defnk render-list [search-conn study [:request path-for [:params page-num
                                                          {filter nil}]]]
  (if (str/blank? filter)
    (let [form-defs (sort-by default-sort (:study/form-defs study))
          next-page? (not (lr/empty? (hu/paginate (inc page-num) form-defs)))
          path #(study/child-list-path :form-def path-for study %)]
      {:data
       {:total (count form-defs)}
       :links
       (-> {:up (study/link path-for study)
            :self {:href (path page-num)}}
           (hu/assoc-prev page-num path)
           (hu/assoc-next next-page? page-num path))

       :queries
       {:lens/filter
        (hu/render-filter-query (study/child-list-path :form-def path-for study))}

       :forms
       {:lens/create-form-def
        (study/render-create-form-def-form path-for study)}

       :embedded
       {:lens/form-defs
        (->> (hu/paginate page-num form-defs)
             (render-embedded-list path-for (timeout 100))
             (into []))}})
    (letk [[total page] (<?? (api/list-matching-form-defs search-conn study filter))]
      {:data
       {:total total}
       :links
       {:up (study/link path-for study)
        :self {:href (study/child-list-path :form-def path-for study 1)}}

       :queries
       {:lens/filter
        (hu/render-filter-query (study/child-list-path :form-def path-for study))}

       :forms
       {:lens/create-form-def
        (study/render-create-form-def-form path-for study)}

       :embedded
       {:lens/form-defs
        (->> page
             (render-embedded-list path-for (timeout 100))
             (into []))}})))

(def list-handler
  "Resource of all form-defs of a study."
  (resource
    (study/child-list-resource-defaults)

    :handle-ok render-list

    :handle-exception
    (fnk [exception [:request path-for] :as ctx]
      {:data
       {:message (.getMessage exception)
        :ex-data (ex-data exception)
        :cause-message (some-> (.getCause exception) (.getMessage))}
       :links
       (if-let [study (:study ctx)]
         {:up (study/link path-for study)
          :self {:href (study/child-list-path :form-def path-for study 1)}}
         {:up {:href (path-for :service-document-handler)}})})))

(defn- find-item-group-ref-path [path-for form-def]
  (path-for :find-item-group-ref-handler :eid (hu/entity-id form-def)))

(defn- item-group-refs-path [path-for form-def]
  (path-for :item-group-refs-handler :eid (hu/entity-id form-def) :page-num 1))

(defn- create-item-group-ref-path [path-for form-def]
  (path-for :create-item-group-ref-handler :eid (hu/entity-id form-def)))

(defn create-item-group-ref-form [path-for form-def]
  {:href (create-item-group-ref-path path-for form-def)
   :params {:item-group-id {:type Str}}})

(defn inquiry-type-link [path-for form-def]
  (some->> (:form-def/inquiry-type form-def)
           (inquiry-type/link path-for)))

(defnk render [form-def [:request path-for]]
  {:data
   (-> {:id (:form-def/id form-def)
        ;;TODO: alias
        :name (:form-def/name form-def)}
       (assoc-when :desc (:form-def/desc form-def))
       (assoc-when :keywords (:form-def/keywords form-def))
       (assoc-when :inquiry-type-id (-> form-def :form-def/inquiry-type
                                        :inquiry-type/id)))

   :links
   (-> {:up (study/link path-for (:study/_form-defs form-def))
        :self (link path-for form-def)
        :profile {:href (path-for :form-def-profile-handler)}
        :lens/item-group-refs {:href (item-group-refs-path path-for form-def)}}
       (assoc-when :lens/inquiry-type (inquiry-type-link path-for form-def)))

   :queries
   {:lens/find-item-group-ref
    {:href (find-item-group-ref-path path-for form-def)
     :params {:item-group-id {:type Str}}}}

   :forms
   {:lens/create-item-group-ref
    (create-item-group-ref-form path-for form-def)}

   :ops #{:update :delete}})

(def schema
  {:name Str
   (s/optional-key :desc) Str
   (s/optional-key :keywords) [Str]
   (s/optional-key :inquiry-type-id) Str})

(defn- inquiry-type-exists-schema [db]
  (s/pred #(api/find-inquiry-type db %) 'inquiry-type-exists?))

(defn- intern-schema [db]
  (assoc schema (s/optional-key :inquiry-type-id)
                (inquiry-type-exists-schema db)))

(defn resolve-inquiry-type [db new-entity]
  (if-let [id (:inquiry-type-id new-entity)]
    (-> (assoc new-entity :inquiry-type (api/find-inquiry-type db [:db/id] id))
        (dissoc :inquiry-type-id))
    new-entity))

(defn- select-props [form-def]
  (pull form-def [:form-def/name :form-def/desc :form-def/keywords
                  {:form-def/inquiry-type [:db/id]}]))

(def handler
  "Handler for GET, PUT and DELETE on a form-def.

  Implementation note on PUT:

  The resource compares the current ETag with the If-Match header based on a
  possibly old version of the form-def taken from a database outside of the
  transaction. The update transaction is than tried with name and desc
  from that possibly old form-def as reference. The transaction only succeeds if
  the name and desc are still the same on the in-transaction form-def."
  (resource
    (hu/entity-resource-defaults)

    :processable?
    (fnk [db [:request [:params eid]] :as ctx]
      (let [form-def (api/find-entity db :form-def (hu/to-eid eid))
            schema (assoc (intern-schema db) :id (s/eq (:form-def/id form-def)))]
        ((hu/entity-processable schema) ctx)))

    :exists?
    (hu/exists-pull? :form-def [:db/id :form-def/id :form-def/name
                                :form-def/desc :form-def/keywords
                                {:form-def/inquiry-type
                                 [:db/id :inquiry-type/id :inquiry-type/name]}
                                {:study/_form-defs
                                 [:db/id :study/id :study/name]}])

    :etag
    (hu/etag #(-> % :form-def :form-def/name)
             #(-> % :form-def :form-def/desc)
             #(-> % :form-def :form-def/keywords)
             #(-> % :form-def :form-def/inquiry-type :inquiry-type/name)
             3)

    :put!
    (fnk [conn db form-def new-entity]
      (let [new-entity (resolve-inquiry-type db new-entity)
            new-entity (util/prefix-namespace :form-def new-entity)]
        (debug {:type :update :sub-type :form-def :new-entity new-entity})
        {:update-error (api/update-form-def conn form-def (select-props form-def)
                                            (select-props new-entity))}))

    :delete!
    (fnk [conn form-def] (api/retract-entity conn (:db/id form-def)))

    :handle-ok render))

(defn form-def-count-handler [path-for]
  (resource
    (hu/resource-defaults)

    :exists? (hu/exists? :form-def)

    :handle-ok
    (fnk [entity]
      {:value (api/num-form-subjects entity)
       :links
       {:up {:href (path path-for entity)}
        :self {:href (path-for :form-count-handler :id (:form/id entity))}}})

    :handle-not-found
    (hu/error-body path-for "Form not found.")))

(def ^:private CreateParamSchema
  {:id util/NonBlankStr
   :name util/NonBlankStr
   (s/optional-key :desc) Str
   (s/optional-key :keywords) [Str]
   (s/optional-key :inquiry-type-id) Str
   Any Any})

(def create-handler
  (resource
    (study/create-resource-defaults)

    :processable? (hu/validate-params CreateParamSchema)

    :post!
    (fnk [conn db study [:request params]]
      (let [{:keys [id name]} params
            opts (->> (select-keys params [:desc :keywords :inquiry-type-id])
                      (util/remove-nil-valued-entries)
                      (resolve-inquiry-type db)
                      (util/prefix-namespace :form-def))]
        (if-let [entity (api/create-form-def conn study id name opts)]
          {:entity entity}
          (throw (ex-info "Duplicate!" {:type :duplicate})))))

    :location
    (fnk [entity [:request path-for]] (path path-for entity))

    :handle-exception
    (study/duplicate-exception "The form-def exists already.")))

;; ---- For Childs ------------------------------------------------------------

(defnk build-up-link [[:request path-for [:params eid]]]
  {:links {:up {:href (path-for :form-def-handler :eid eid)}}})

(def ^:private ChildListParamSchema
  {:eid util/Base62EntityId
   :page-num util/PosInt
   Any Any})

(defn child-list-resource-defaults []
  (assoc
    (hu/resource-defaults)

    :processable? (hu/coerce-params ChildListParamSchema)

    :exists? (hu/exists? :form-def)))

(defn redirect-resource-defaults []
  (assoc
    (hu/redirect-resource-defaults)

    :handle-unprocessable-entity
    (hu/error-handler "Unprocessable Entity" build-up-link)))

(defn create-resource-defaults []
  (assoc
    (hu/create-resource-defaults)

    :exists? (hu/exists? :form-def)))
