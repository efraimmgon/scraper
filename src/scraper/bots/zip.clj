(ns scraper.bots.zip
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [scraper.core :as s]))

; ------------------------------------------------------------------------------
; GET all streets, neighborhoods, zip codes, for a city
; ------------------------------------------------------------------------------
; https://cep.guiamais.com.br/busca/sinop-mt?page=1

(defn- extract-data [tr]
  (let [parse-street #(-> (string/split % #"-") first)
        [street & others] (map #(.text %) (.select tr "td"))]
    (cons (parse-street street) others)))

(defn- preppend-header
  "Preppend the table header"
  [rows]
  (cons ["logradouro"
         "bairro"
         "cidade, estado"
         "bairro e cidade"
         "cep"]
        rows))

(defn- get-data [base-url params]
  (some-> (s/append-params base-url
                           (s/parse-query-params params))
          s/get-page-with-proxy-layer
          (as-> doc (map extract-data (.select doc "tbody tr")))))

(defn- save! [rows filename]
  (log/info "Saving" (count rows) "rows to disk.")
  (s/save-to-csv!
   (preppend-header rows)
   (io/file filename)))

(defn exec! [{:keys [base-url params filename]}]
  (loop [params params
         rows []]
    (if-let [data (seq (get-data base-url params))]
      (do (s/rand-delay)
          (recur (update params :page inc)
                 (into rows data)))
      (save! rows filename))))

(comment
  (def base-url "https://cep.guiamais.com.br/busca/guaranta+do+norte-mt")
  
  (exec! {:base-url base-url
          :params {:page 1}
          :filename "bairros+ruas-gta.csv"})


  (def doc (get-page (append-params base-url "page=1"))))
