(ns scraper.etaoin
  (:require
   [etaoin.api :refer :all]
   [etaoin.keys :as k]))

(defn exec! []
  (let [driver (chrome)]
    (go driver "https://en.wikipedia.org/")
    (wait-visible driver [{:id :simpleSearch} {:tag :input :name :search}])

    (fill driver {:tag :input :name :search} "Clojure programming language")
    (fill driver {:tag :input :name :search} k/enter)
    (wait-visible driver {:class :mw-search-results})

    (click driver [{:class :mw-search-results} {:class :mw-search-result-heading} {:tag :a}])
    (wait-visible driver {:id :firstHeading})

    (get-url driver)
    (get-title driver)
    (has-text? driver "Clojure")

    (back driver)
    (forward driver)
    (refresh driver)
    (get-title driver)
    (quit driver)))
