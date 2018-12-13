(ns scraper.bots.dias
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [scraper.core :as s]))

; ------------------------------------------------------------------------------
; Dias Imobiliária
; - parse properties page
; - save properties links
; - extract data from property page
; ------------------------------------------------------------------------------

(def base-url "http://www.diasimobiliaria.com.br/imoveis-paginacao.php")

(defn make-params [& ks]
  (let [opts {:rent {:tipo 3}
              :sale {:tipo 2}
              :merchant {:categoria 4}
              :residential {:categoria 5}
              :terrain {:categoria 7}
              :belo-horizonte {:bairro 71}}]
    (apply merge
           (map opts ks))))

(defn- parse-price [node]
  (-> (string/split (.text node) #",")
      first
      (string/replace
        #"\D"
        "")))

(defn- find-node-containing [doc s]
  (-> (.select doc ".texto-imovel")
      (.select (str ".cinza:contains(" s ")"))))

(defn- get-val-text [doc]
  (some-> doc
          .text
          (string/split #": ")
          last))

(defn extract-details
  "Extact the following items:
  link
  img-link
  Código
  Tipo do imóvel
  Tipo de negociação
  Bairro
  Cidade
  Área
  Perímetro
  Área da construção
  Cômodos e benfeitorias
  Características gerais
  Benfeitorias públicas
  Valor
  "
  [doc]
  (let [ad-link (.location doc)
        img-link (-> (.select doc "#foto-segura img") (.attr "abs:src"))
        code (-> (.select doc ".texto-imovel > div") .text (string/split #" ") second)
        area (-> (.select doc ".texto-imovel") (.select ".cinza:contains(Área do terreno)")
                 (some-> (.select "span") first .text))
        perimeter (-> (.select doc ".texto-imovel") (.select ".cinza:contains(Área do terreno)")
                      (some-> (.select "span") second .text))
        price (-> (.select doc ".texto-imovel .preto") parse-price)]
    (into
       [ad-link img-link code area perimeter price]
       (->> ["Tipo do imóvel"
             "Tipo de negociação"
             "bairro"
             "cidade"
             "Área da construção"
             "Cômodos e benfeitorias"
             "Características gerais"
             "Benfeitorias públicas"]
            (map (comp get-val-text (partial find-node-containing doc)))))))
       ; (map get-val-text
       ;      (map (partial find-node-containing doc)
       ;           ["Tipo do imóvel"
       ;            "Tipo de negociação"
       ;            "bairro"
       ;            "cidade"
       ;            "Área da construção"
       ;            "Cômodos e benfeitorias"
       ;            "Características gerais"
       ;            "Benfeitorias públicas"])))))

(defn preppend-header [rows]
  (cons
   ["ad-link", "img-link", "code", "area", "perimeter", "price",
    "property type",
    "deal type",
    "neighborhood",
    "city/state",
    "building area",
    "rooms and improvements"
    "general caracteristics",
    "public improvements"]
   rows))

(defn properties-links [page]
  (let [property-link #(-> (.select % ".imovel > a") (.attr "abs:href"))
        links (map property-link (.select page ".imovel"))]
    (log/info "got" (count links) "links")
    links))

(defn exec! [{:keys [base-url params project filename]}]
  (loop [params params
         rows []]
    ;; if we get properties links after parsing the response
    (if-let [props-links
             (-> (->> (s/parse-query-params params)
                      (s/append-params base-url))
                 s/get-page-with-proxy-layer
                 properties-links
                 seq)]
       (recur (update params :p inc)
              (into rows
                    ;; for each property link, request the page for each
                    ;; property and extract the data
                    ;; wait for a while so we don't get blocked
                    (map (comp (fn [x] (s/rand-delay) x)
                               extract-details
                               s/get-page-with-proxy-layer)
                         props-links)))
       (do (log/info "Saving" (count rows) "rows to disk")
           (s/save-to-csv!
            (preppend-header rows)
            (io/file filename))))))

#_(time
   (exec!
     {:base-url base-url
      :params (merge (make-params :rent :residential) {:p 0})
      :filename "aluguel.csv"
      :project "dias-imobiliaria"}))
