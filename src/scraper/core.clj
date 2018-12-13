(ns scraper.core
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as log])
  (:import
   (org.jsoup Jsoup)))

; - Caching:
;   > Cache pages for later scraping.
;   > Update cached pages for every n days.
; - Cookies:
;   > Check returned cookies.
;   > Keep cookies and user agents consistent with the IP group.
; - Masking the bot:
;   > Change IP every few requests. [ok]
;   > Random delays [ok]
;   > Vary how the crawler navigates the page (Frontera).
;   > Perform groups of requests with the same user agent, cookies, and IP
; address to make it look like a normal user sessions (Crawlera's session
; feature

; ------------------------------------------------------------------------------
; Resouce Location
; ------------------------------------------------------------------------------

(defn project-root [] (System/getProperty "user.dir"))
(defn resources-root [] (io/file (project-root) "resources"))
(defn cache-root [] (io/file (resources-root) "cache"))
(defn results-root [] (io/file (project-root) "results"))

; ------------------------------------------------------------------------------
; Utils
; ------------------------------------------------------------------------------

(defn parse-query-params
  "Given a map of query params, returns them in the form expected by the
  server: k1=v1&k2=v2..."
  [opts]
  (let [to-query-params (fn [[k v]]
                          (str (name k) "=" v))]
    (->> opts
         (map to-query-params)
         (interpose "&")
         (apply str))))

(defn append-params
  "Returns url with query params. params can be a string of the query params
  or a map."
  [url params]
  (str url "?"
       (if (map? params) (parse-query-params params) params)))

(defn rand-delay
  "Programs sleeps for n seconds. Defaults to 5"
  ([] (rand-delay 5))
  ([n]
   (Thread/sleep (* 1000 (inc (rand-int n))))))

; ------------------------------------------------------------------------------
; CSV, IO
; ------------------------------------------------------------------------------

(defn maps->csv-table
  "Takes a coll of maps and returns a coll of seqs, with the keys from the
  first map as the first seq."
  [maps]
  (let [headers (->> maps first keys (map name))
        rows (map vals maps)]
    (cons headers rows)))

(defn save-to-csv!
  "Save rows as a csv file on path. Optionally takes the same options of
  clojure.java.io/writer."
  [rows path & opts]
  (with-open [wrtr (if opts
                       (apply io/writer path opts)
                       (io/writer path))]
    (csv/write-csv wrtr
                   rows)))

(defn load-resource
  "Reads an edn resource, returning it."
  [filepath]
  (-> (io/file (resources-root) filepath)
      slurp
      clojure.edn/read-string))

; ------------------------------------------------------------------------------
; General
; ------------------------------------------------------------------------------

(def app-db
  "Mutable app container. Initially an empty map."
  (atom {}))

(defn get-page
  "Retuns a Jsoup page object."
  ([url] (get-page url identity))
  ([url conn-config-f]
   (-> (Jsoup/connect url)
       conn-config-f
       .get)))

(defn get-proxies-list
  "Gets a list of 100 proxies from https://www.sslproxies.org/.
  Each proxy is a vector of [^String ip ^Integer port].
  Note: Wrap the connection in a try catch due to unreliable proxies."
  []
  (let [doc (get-page "https://www.sslproxies.org/")]
    (for [tr (.select doc "tbody tr")
          :let [ip (-> (.select tr "td") first .text)
                port (-> (.select tr "td") second .text Integer/parseInt)]]
      [ip port])))

(defn get-page-with-proxy-layer
  "Returns a Jsoup object. Uses a set of proxies to get a page.
  If a HTTP error is met, nil is returned, with the error being
  logged."
  [url]
  ;; Fetch proxies and zip them with the user-agents when not yet
  ;; initialized:
  (when-not (seq (:proxy-and-ua-set @app-db))
    (swap! app-db assoc :proxy-and-ua-set
           (set
            (mapv vector
                  (get-proxies-list)
                  (shuffle (load-resource "user-agents.edn"))))))
  ;; Use a random proxy and user-agent to mask the request:
  (let [proxy-and-ua (-> (:proxy-and-ua-set @app-db) seq rand-nth)
        [[ip port] ua] proxy-and-ua
        conn-config #(-> % ; Jsoup conn
                         (.proxy ip port)
                         (.userAgent ua)
                         (.header "Content-Language" "en-US"))]
    (try
      (log/info "Accessing" url "with" ip port)
      (get-page url conn-config)
      (catch Exception e
        (if (= (.getMessage e) "HTTP error fetching URL")
          (do (log/warn (.getMessage e) "=>" (.getStatusCode e))
              nil)
          (do (log/warn "Exception in `get-page-with-proxy-layer`:"
                        (.getMessage e))
              (swap! app-db update :proxy-and-ua-set
                     disj proxy-and-ua)
              (log/info "Trying again with different proxy.")
              (get-page-with-proxy-layer url)))))))
