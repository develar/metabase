(ns metabase.driver.mongo-test
  "Tests for Mongo driver."
  (:require [clojure.test :refer :all]
            [expectations :refer [expect]]
            [java-time :as t]
            [medley.core :as m]
            [metabase
             [driver :as driver]
             [query-processor :as qp]
             [query-processor-test :as qp.t :refer [rows]]]
            [metabase.automagic-dashboards.core :as magic]
            [metabase.db.metadata-queries :as metadata-queries]
            [metabase.driver
             [mongo :as mongo]
             [util :as driver.u]]
            [metabase.driver.mongo.query-processor :as mongo-qp]
            [metabase.models
             [field :refer [Field]]
             [table :as table :refer [Table]]]
            [metabase.test.data :as data]
            [metabase.test.data
             [datasets :as datasets]
             [interface :as tx]]
            [toucan.db :as db]
            [toucan.util.test :as tt])
  (:import org.bson.types.ObjectId))

;; ## Constants + Helper Fns/Macros
;; TODO - move these to metabase.test-data ?
(def ^:private table-names
  "The names of the various test data `Tables`."
  [:categories
   :checkins
   :users
   :venues])

;; ## Tests for connection functions
(deftest can-connect-test?
  (datasets/test-driver :mongo
    (doseq [{:keys [details expected message]} [{:details  {:host   "localhost"
                                                            :port   3000
                                                            :dbname "bad-db-name"}
                                                 :expected false}
                                                {:details  {}
                                                 :expected false}
                                                {:details  {:host   "localhost"
                                                            :port   27017
                                                            :dbname "metabase-test"}
                                                 :expected true}
                                                {:details  {:host   "localhost"
                                                            :dbname "metabase-test"}
                                                 :expected true
                                                 :message  "should use default port 27017 if not specified"}
                                                {:details  {:host   "123.4.5.6"
                                                            :dbname "bad-db-name?connectTimeoutMS=50"}
                                                 :expected false}
                                                {:details  {:host   "localhost"
                                                            :port   3000
                                                            :dbname "bad-db-name?connectTimeoutMS=50"}
                                                 :expected false}]]

      (is (= expected
             (driver.u/can-connect-with-details? :mongo details))
          message))))

(def ^:private native-query
  "[{\"$project\": {\"_id\": \"$_id\"}},
    {\"$match\": {\"_id\": {\"$eq\": 1}}},
    {\"$group\": {\"_id\": null, \"count\": {\"$sum\": 1}}},
    {\"$sort\": {\"_id\": 1}},
    {\"$project\": {\"_id\": false, \"count\": true}}]")

(deftest native-query-test
  (datasets/test-driver :mongo
    (is (= {:status    :completed
            :row_count 1
            :data      {:rows             [[1]]
                        :cols             [{:name         "count"
                                            :display_name "count"
                                            :base_type    :type/Integer
                                            :source       :native
                                            :field_ref    [:field-literal "count" :type/Integer]}]
                        :native_form      {:collection "venues"
                                           :query      native-query}
                        :results_timezone "UTC"}}
           (-> (qp/process-query {:native   {:query      native-query
                                             :collection "venues"}
                                  :type     :native
                                  :database (data/id)})
               (m/dissoc-in [:data :results_metadata] [:data :insights]))))))

;; ## Tests for individual syncing functions

(deftest describe-database-test
  (datasets/test-driver :mongo
    (is (= {:tables #{{:schema nil, :name "checkins"}
                      {:schema nil, :name "categories"}
                      {:schema nil, :name "users"}
                      {:schema nil, :name "venues"}}}
           (driver/describe-database :mongo (data/db))))))

(deftest describe-table-tets
  (datasets/test-driver :mongo
    (is (= {:schema nil
            :name   "venues"
            :fields #{{:name          "name"
                       :database-type "java.lang.String"
                       :base-type     :type/Text}
                      {:name          "latitude"
                       :database-type "java.lang.Double"
                       :base-type     :type/Float}
                      {:name          "longitude"
                       :database-type "java.lang.Double"
                       :base-type     :type/Float}
                      {:name          "price"
                       :database-type "java.lang.Long"
                       :base-type     :type/Integer}
                      {:name          "category_id"
                       :database-type "java.lang.Long"
                       :base-type     :type/Integer}
                      {:name          "_id"
                       :database-type "java.lang.Long"
                       :base-type     :type/Integer
                       :pk?           true}}}
           (driver/describe-table :mongo (data/db) (Table (data/id :venues)))))))

;; Make sure that all-NULL columns work and are synced correctly (#6875)
(tx/defdataset ^:private all-null-columns
  [["bird_species"
    [{:field-name "name", :base-type :type/Text}
     {:field-name "favorite_snack", :base-type :type/Text}]
    [["House Finch" nil]
     ["Mourning Dove" nil]]]])

(datasets/expect-with-driver :mongo
  [{:name "_id",            :database_type "java.lang.Long",   :base_type :type/Integer, :special_type :type/PK}
   {:name "favorite_snack", :database_type "NULL",             :base_type :type/*,       :special_type nil}
   {:name "name",           :database_type "java.lang.String", :base_type :type/Text,    :special_type :type/Name}]
  (data/dataset all-null-columns
    (map (partial into {})
         (db/select [Field :name :database_type :base_type :special_type]
           :table_id (data/id :bird_species)
           {:order-by [:name]}))))


;;; table-rows-sample
(datasets/expect-with-driver :mongo
  [[1 "Red Medicine"]
   [2 "Stout Burgers & Beers"]
   [3 "The Apple Pan"]
   [4 "Wurstküche"]
   [5 "Brite Spot Family Restaurant"]]
  (driver/sync-in-context :mongo (data/db)
    (fn []
      (vec (take 5 (metadata-queries/table-rows-sample (Table (data/id :venues))
                                                       [(Field (data/id :venues :id))
                                                        (Field (data/id :venues :name))]))))))


;; ## Big-picture tests for the way data should look post-sync
(deftest table-sync-test
  (datasets/test-driver :mongo
    (is (= [{:active true, :name "categories"}
            {:active true, :name "checkins"}
            {:active true, :name "users"}
            {:active true, :name "venues"}]
           (for [field (db/select [Table :name :active]
                         :db_id (data/id)
                         {:order-by [:name]})]
             (into {} field)))
        "Test that Tables got synced correctly")))

;; Test that Fields got synced correctly, and types are correct
(datasets/expect-with-driver :mongo
  [[{:special_type :type/PK,        :base_type :type/Integer,  :name "_id"}
    {:special_type :type/Name,      :base_type :type/Text,     :name "name"}]
   [{:special_type :type/PK,        :base_type :type/Integer,  :name "_id"}
    {:special_type nil,             :base_type :type/Instant,  :name "date"}
    {:special_type :type/Category,  :base_type :type/Integer,  :name "user_id"}
    {:special_type nil,             :base_type :type/Integer,  :name "venue_id"}]
   [{:special_type :type/PK,        :base_type :type/Integer,  :name "_id"}
    {:special_type nil,             :base_type :type/Instant,  :name "last_login"}
    {:special_type :type/Name,      :base_type :type/Text,     :name "name"}
    {:special_type :type/Category,  :base_type :type/Text,     :name "password"}]
   [{:special_type :type/PK,        :base_type :type/Integer,  :name "_id"}
    {:special_type :type/Category,  :base_type :type/Integer,  :name "category_id"}
    {:special_type :type/Latitude,  :base_type :type/Float,    :name "latitude"}
    {:special_type :type/Longitude, :base_type :type/Float,    :name "longitude"}
    {:special_type :type/Name,      :base_type :type/Text,     :name "name"}
    {:special_type :type/Category,  :base_type :type/Integer,  :name "price"}]]
  (vec (for [table-name table-names]
         (vec (for [field (db/select [Field :name :base_type :special_type]
                            :active   true
                            :table_id (data/id table-name)
                            {:order-by [:name]})]
                (into {} field))))))


(tx/defdataset ^:private with-bson-ids
  [["birds"
     [{:field-name "name", :base-type :type/Text}
      {:field-name "bird_id", :base-type :type/MongoBSONID}]
     [["Rasta Toucan" (ObjectId. "012345678901234567890123")]
      ["Lucky Pigeon" (ObjectId. "abcdefabcdefabcdefabcdef")]]]])

(deftest bson-ids-test
  (datasets/test-driver :mongo
    (is (= [[2 "Lucky Pigeon" (ObjectId. "abcdefabcdefabcdefabcdef")]]
           (rows (data/dataset with-bson-ids
                   (data/run-mbql-query birds
                     {:filter [:= $bird_id "abcdefabcdefabcdefabcdef"]}))))
        "Check that we support Mongo BSON ID and can filter by it (#1367)")))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                             ISODate(...) AND ObjectId(...) HANDLING (#3741, #4448)                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest encode-function-calls-test
  (is (= "[{\"$match\":{\"date\":{\"$gte\":[\"___ISODate\", \"2012-01-01\"]}}}]"
         (#'mongo-qp/encode-fncalls "[{\"$match\":{\"date\":{\"$gte\":ISODate(\"2012-01-01\")}}}]")))
  (is (= "[{\"$match\":{\"entityId\":{\"$eq\":[\"___ObjectId\", \"583327789137b2700a1621fb\"]}}}]"
         (#'mongo-qp/encode-fncalls "[{\"$match\":{\"entityId\":{\"$eq\":ObjectId(\"583327789137b2700a1621fb\")}}}]")))
  (testing "make sure fn calls with no arguments work as well (#4996)"
    (is (= "[{\"$match\":{\"date\":{\"$eq\":[\"___ISODate\"]}}}]"
           (#'mongo-qp/encode-fncalls "[{\"$match\":{\"date\":{\"$eq\":ISODate()}}}]")))))

(deftest decode-function-calls-test
  (testing "ISODate()"
    (is (=
         (t/local-date "2012-01-01")
         (#'mongo-qp/maybe-decode-fncall ["___ISODate" "2012-01-01"])))
    (is (=
         [{:$match {:date {:$gte (t/local-date "2012-01-01")}}}]
         (#'mongo-qp/decode-fncalls [{:$match {:date {:$gte ["___ISODate" "2012-01-01"]}}}]))))
  (testing "ObjectID()"
    (is (=
         (ObjectId. "583327789137b2700a1621fb")
         (#'mongo-qp/maybe-decode-fncall ["___ObjectId" "583327789137b2700a1621fb"])))
    (is (=
         [{:$match {:entityId {:$eq (ObjectId. "583327789137b2700a1621fb")}}}]
         (#'mongo-qp/decode-fncalls [{:$match {:entityId {:$eq ["___ObjectId" "583327789137b2700a1621fb"]}}}])))))

(datasets/expect-with-driver :mongo
  5
  (count (rows (qp/process-query {:native   {:query      "[{\"$match\": {\"date\": {\"$gte\": ISODate(\"2015-12-20\")}}}]"
                                             :collection "checkins"}
                                  :type     :native
                                  :database (data/id)}))))

(datasets/expect-with-driver :mongo
  0
  ;; this query shouldn't match anything, so we're just checking that it completes successfully
  (count (rows (qp/process-query {:native   {:query      "[{\"$match\": {\"_id\": {\"$eq\": ObjectId(\"583327789137b2700a1621fb\")}}}]"
                                             :collection "venues"}
                                  :type     :native
                                  :database (data/id)}))))


;; tests for `most-common-object-type`
(expect
  String
  (#'mongo/most-common-object-type [[Float 20] [Integer 10] [String 30]]))

;; make sure it handles `nil` types correctly as well (#6880)
(expect
  nil
  (#'mongo/most-common-object-type [[Float 20] [nil 40] [Integer 10] [String 30]]))


;; make sure x-rays don't use features that the driver doesn't support
(datasets/expect-with-driver :mongo
  true
  (->> (magic/automagic-analysis (Field (data/id :venues :price)) {})
       :ordered_cards
       (mapcat (comp :breakout :query :dataset_query :card))
       (not-any? #{[:binning-strategy [:field-id (data/id :venues :price)] "default"]})))

;; if we query a something an there are no values for the Field, the query should still return successfully! (#8929
;; and #8894)
(datasets/expect-with-driver :mongo
  ;; if the column does not come back in the results for a given document we should fill in the missing values with nils
  {:rows [[1 "African"  nil]
          [2 "American" nil]
          [3 "Artisan"  nil]]}
  ;; add a temporary Field that doesn't actually exist to test data categories
  (tt/with-temp Field [_ {:name "parent_id", :table_id (data/id :categories)}]
    ;; ok, now run a basic MBQL query against categories Table. When implicit Field IDs get added the `parent_id`
    ;; Field will be included
    (->
     (data/run-mbql-query categories
       {:order-by [[:asc [:field-id $id]]]
        :limit    3})
     qp.t/data
     (select-keys [:columns :rows]))))
