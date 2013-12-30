(ns kafka-clj.metadata
  (:require 
            [clj-tcp.client :refer [close-all]]
            [kafka-clj.produce :refer [metadata-request-producer send-metadata-request shutdown]]
            [fun-utils.core :refer [fixdelay]]
            [clojure.tools.logging :refer [info error]]
            [clojure.core.async :refer [go <!]])
  (:import [java.nio ByteBuffer]
           [clj_tcp.client Poison]))

"Keeps track of the metadata
 "

(defn convert-metadata-response [resp]
  ;; transform the resp into a map
  ;; {topic-name { partition-n :host {:host host :port port} } }
  ;; here topic-name:String and partition-n:Integer are keys but not keywords
  ;;{:correlation-id 2,
	;;											 :brokers [{:node-id 0, :host a, :port 9092}],
	;;											 :topics
	;;											 [{:error-code 10,
	;;											   :topic p,
	;;											   :partitions
	;;											   [{:partition-error-code 10,
	;;											     :partition-id 0,
	;;											     :leader 0,
	;;											     :replicas '(0 1),
	;;											     :isr '(0)}]}]}"
  (let [m (let [;convert the brokers to a map {:broker-node-id {:host host :port port}}
                brokers-by-node (into {} (map (fn [{:keys [node-id host port]}] [ node-id {:host host :port port}]) (:brokers resp)))]
                ;convert the response message to a map {topic-name {partition {:host host :port port}}}
                (into {} 
			                 (for [topic (:topics resp) :when (= (:error-code topic) 0)]
			                      [(:topic topic) (into {}
																	                  (for [ partition (:partitions topic)
																	                         :when (= (:partition-error-code partition) 0) 
			                                                     :let [broker (get brokers-by-node (:leader partition))]]
																	                     [(:partition-id partition) broker]))])))
			                    
                  
                
        ]
    m))

(defn track-broker-partitions [brokers partition-ref freq-ms conf]
  "This method will update the partition ref every freq-ms milliseconds
   Arguments
   brokers a list of maps with keys :host :port
   partition-ref must be of type ref
   freq-ms a long value
   "
   (let [producers (into #{} (map-indexed vector (map (fn [{:keys [host port]}] (metadata-request-producer host (int port)) brokers))))]
     (doseq [{:keys [k p]} producers]
       
          ;; read errors from the producer at position k, the next producer is at position k+1
          ;; if an error is found by reading a value of this channel, then send a metadata-request to the next producer if any at k+1
	        (go 
	          (while true
	               (let [error (<! (-> p :client :error-ch))]
                     (error (str error "  Error contacting " p))
                     (if-let [p2 (get producers (inc k))]
                       (send-metadata-request p conf)))))
          
          ;;read responses from the producer at position k
          ;;if any response that is not Poison, call the handle-metadata-respones function
          (go 
            (loop []
              (let [resp (<! (-> p :client :read-ch))]
                (info "Metadata response from " p " " resp)
                (if instance? Poison resp)
                  (info "Shutdown metadata response")
                  (if resp
                    (do
                      (commute partition-ref (fn [x] 
                                               (try (convert-metadata-response partition-ref resp)
                                                 (catch Exception e (do (.printStackTrace e) (error e e) x)))))
                      (recur)))))))
         
		     ;;on fix delay send a metadata request to the first available producer
		     ;;if one fails we send another
		     ;;the error handling here is redundent somewhat but done for completeness
		     ;;the connection and recover error handling is done in the go loops above 
			   (fixdelay freq-ms
		              (loop [ps producers]
		                (if-let [p (first ps)]
		                  (let [x (try
						                     (do (send-metadata-request p {}) nil)
						                     (catch Exception e (do
						                                          (error (str "Error contacting " p) e)
						                                          e)))]
		                    (if x 
		                      (recur (rest ps)))))))))
				                  