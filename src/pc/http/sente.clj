(ns pc.http.sente
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.auth :as auth]
            [pc.http.datomic2 :as datomic2]
            [pc.email :as email]
            [pc.models.layer :as layer]
            [pc.models.chat :as chat]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.datomic :as pcd]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn uuid
  []
  (UUID/randomUUID))

(defn user-id-fn [req]
  (-> req :cookies (get "prcrsr-client-id") :value))

;; hash-map of document-id to connected users
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
;; e.g {:12345 {:uuid-1 {show-mouse?: true} :uuid-1 {:show-mouse? false}}}
(defonce document-subs (atom {}))

(defn notify-transaction [data]
  ;; TODO: store client-uuid as a proper uuid everywhere
  (doseq [[uid _] (dissoc (get @document-subs (:document/id data)) (str (:session/uuid data)))]
    (log/infof "notifying %s about new transactions for %s" uid (:document/id data))
    ((:send-fn @sente-state) uid [:datomic/transaction data]))
  (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data data)))]
    (log/infof "notifying %s about new server timestamp for %s" (:session/uuid data) (:document/id data))
    ((:send-fn @sente-state) (str (:session/uuid data)) [:datomic/transaction (assoc data :tx-data server-timestamps)])))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defn client-uuid->uuid
  "Get the client's user-id from the client-uuid"
  [client-uuid]
  (str/replace client-uuid #"-[^-]+$" ""))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-uuid req)))

(defn clean-document-subs [uuid]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id user-ids]]
                                   (if-not (contains? user-ids uuid)
                                     acc
                                     (let [new-user-ids (dissoc user-ids uuid)]
                                       (if (empty? new-user-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-user-ids)))))
                                 ds ds))))

(defn close-connection [client-uuid]
  (log/infof "closing connection for %s" client-uuid)
  (let [uuid (client-uuid->uuid client-uuid)]
    (doseq [uid (reduce (fn [acc [doc-id clients]]
                          (if (contains? clients uuid)
                            (set/union acc (keys clients))
                            acc))
                        #{} @document-subs)]
      (log/infof "notifying %s about %s leaving" uid uuid)
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-uuid uuid}]))
    (clean-document-subs uuid)))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-uuid] :as req}]
  (close-connection client-uuid))

(defmethod ws-handler :frontend/close-connection [{:keys [client-uuid] :as req}]
  (close-connection client-uuid))

(defmethod ws-handler :frontend/unsubscribe [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document-id)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "unsubscribing %s from %s" client-uuid document-id)
    (swap! document-subs update-in [document-id] dissoc cid)
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-uuid cid}]))))

(def colors
  #{"#1abc9c"
    "#2ecc71"
    "#3498db"
    "#9b59b6"
    "#16a085"
    "#27ae60"
    "#2980b9"
    "#8e44ad"
    "#f1c40f"
    "#e67e22"
    "#e74c3c"
    "#f39c12"
    "#d35400"
    "#c0392b"
    ;;"#ecf0f1"
    ;;"#bdc3c7"
    })

(defn subscribe-to-doc [document-id uuid cust-name]
  (swap! document-subs update-in [document-id]
         (fn [subs]
           (let [available-colors (or (seq (apply disj colors (map :color (vals subs))))
                                      (seq colors))]
             (-> subs
                 (update-in [uuid :color] (fn [c] (or c (rand-nth available-colors))))
                 (assoc-in [uuid :cust-name] cust-name))))))

(defmethod ws-handler :frontend/subscribe [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document-id)
        db (pcd/default-db)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "subscribing %s to %s" client-uuid document-id)
    (subscribe-to-doc document-id (client-uuid->uuid client-uuid)
                      (-> req :ring-req :auth :cust :cust/name))
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-joined (merge {:client-uuid cid}
                                                                       (get-in @document-subs [document-id cid]))]))
    (let [resp {:layers (layer/find-by-document db {:db/id document-id})
                :chats (chat/find-by-document db {:db/id document-id})
                :document (pcd/touch+ (d/entity db document-id))
                :client-uuid (client-uuid->uuid client-uuid)}]
      (?reply-fn resp))))

(defmethod ws-handler :frontend/fetch-subscribers [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document-id)]
    (log/infof "fetching subscribers for %s on %s" client-uuid document-id)
    (subscribe-to-doc document-id (client-uuid->uuid client-uuid)
                      (-> req :ring-req :auth :cust :cust/name))
    (?reply-fn {:subscribers (get @document-subs document-id)})))

(defmethod ws-handler :frontend/fetch-created [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [cust (-> req :ring-req :auth :cust)
        ;; TODO: at some point we may want to limit, but it's just a list of longs, so meh
        ;;limit (get ?data :limit 100)
        ;;offset (get ?data :offset 0)
        db (pcd/default-db)
        doc-ids (doc-model/find-created-by-cust db cust)]
    (log/infof "fetching created for %s" client-uuid)
    (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                         :last-updated-instant (doc-model/last-updated-time db doc-id)})
                           doc-ids)})))
    (log/infof "fetching created for %s" client-uuid)
    (?reply-fn {:doc-ids (sort-by - (doc-model/find-created-by-cust (pcd/default-db) cust))})))

(defmethod ws-handler :frontend/transaction [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        datoms (->> ?data :datoms (remove (comp nil? :v)))
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)]
    (log/infof "transacting %s on %s for %s" datoms document-id client-uuid)
    (datomic2/transact! datoms
                        document-id
                        (UUID/fromString (client-uuid->uuid client-uuid))
                        cust-uuid)))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layers (-> ?data :layers)
        cid (client-uuid->uuid client-uuid)]
    (doseq [[uid _] (dissoc (get @document-subs document-id) cid)]
      ((:send-fn @sente-state) uid [:frontend/mouse-move (merge
                                                          {:client-uuid cid
                                                           :tool tool
                                                           :layers layers}
                                                          (when mouse-position
                                                            {:mouse-position mouse-position}))]))))

;; TODO: maybe need a compare-and-set! here
(defmethod ws-handler :frontend/share-mouse [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        show-mouse? (-> ?data :show-mouse?)
        mouse-owner-uuid (-> ?data :mouse-owner-uuid)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "toggling share-mouse to %s from %s for %s" show-mouse? mouse-owner-uuid cid)
    (swap! document-subs (fn [subs]
                           (if (get-in subs [document-id mouse-owner-uuid])
                             (assoc-in subs [document-id mouse-owner-uuid :show-mouse?] show-mouse?)
                             subs)))
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/share-mouse
                                    {:client-uuid cid
                                     :show-mouse? show-mouse?
                                     :mouse-owner-uuid mouse-owner-uuid}]))))

(defmethod ws-handler :frontend/update-self [{:keys [client-uuid ?data] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [doc-id (-> ?data :document/id)
          cid (client-uuid->uuid client-uuid)]
      (log/infof "updating self for %s" (:cust/uuid cust))
      (let [new-cust (cust/update! cust {:cust/name (:cust/name ?data)})]
        (doseq [[uid _] (get @document-subs doc-id)]
          ;; TODO: use update-subscriber for everything
          ((:send-fn @sente-state) uid [:frontend/update-subscriber
                                        {:client-uuid cid
                                         :subscriber-data {:cust-name (:cust/name new-cust)}}]))))))

(defmethod ws-handler :frontend/send-invite [{:keys [client-uuid ?data ?reply-fn] :as req}]
  ;; This may turn out to be a bad idea, but error handling is done through creating chats
  (let [[chat-id] (pcd/generate-eids (pcd/conn) 1)
        doc-id (-> ?data :document/id)
        db (pcd/default-db)
        send-chat (fn [body]
                    @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                              :document/id doc-id}
                                             {:chat/body body
                                              :server/timestamp (java.util.Date.)
                                              :document/id doc-id
                                              :db/id chat-id
                                              :cust/uuid (auth/prcrsr-bot-uuid db)
                                              ;; default bot color, also used on frontend chats
                                              :chat/color "#00b233"}]))]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)
            cid (client-uuid->uuid client-uuid)]
        (log/infof "%s sending an email to %s on doc %s" (:cust/email cust) email doc-id)
        (try
          (email/send-chat-invite {:cust cust :to-email email :doc-id doc-id})
          (send-chat "Invite sent!")
          (catch Exception e
            (log/error e)
            (.printStackTrace e)
            (send-chat "Sorry! There was a problem sending the invite."))))

      (send-chat "Please sign up to send an invite."))))

(defmethod ws-handler :chsk/ws-ping [req]
  ;; don't log
  nil)

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (try
                       (ws-handler req)
                       (catch Exception e
                         (log/error e)
                         (.printStackTrace e)))
                     (recur)))))

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    fns))
