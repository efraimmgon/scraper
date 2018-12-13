
(require '[clojure.string :as string])
(clojure.repl/source clojure.java.io/writer)



(let [url "http://www.diasimobiliaria.com.br/locacao/residencial/1644"]
  (dotimes [i 10]
    (println i)
    (try
      (-> (Jsoup/connect url)
          (.proxy "189.115.92.71" 80)
          .get)
      (catch Exception e
        (log/warn (.getMessage e))))))
