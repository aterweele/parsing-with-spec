(ns parsing-with-spec.3.practical
  "A spec for an intentionally bad data format."
  (:require [clojure.spec.alpha :as s]
            [parsing-with-spec.1.csv]))

;; At a fictional company, managers are required to meet a minimum
;; happiness level. Given CSV data that describes employees' happiness
;; and manager status, determine if there are managers who do not meet
;; the threshold.

;; The data format CSV rows that look like "3,0" where the first
;; number is happiness on a 1-10 scale and the second number is either
;; zero or one, encoding a boolean for managerhood. Managers must be
;; happier than 7.

;; First, we define conformers to parse strings.

;; a long written in base 10 as a string.
(s/def ::long
  (s/conformer
    #(try (Long. %)
          (catch NumberFormatException e
            :clojure.spec.alpha/invalid))))

(s/def ::int-encoded-boolean
  (s/conformer
    #(get {"0" false, "1" true} % :clojure.spec.alpha/invalid)))

(s/def ::happiness (s/and number? #(<= 1 % 10)))

;; a spec to throw away :delim, :row, and :cell, leaving a seq of
;; seqs.
(s/def :table/as-nested-list
  (s/conformer
    (partial map (comp (partial map :cell) :row))))

;; managers must meet the minimum happiness requirement.
(defn happy-enough?
  [{:keys [happiness manager]}]
  (if manager
    (< 7 happiness)
    true))

(s/def ::employee-row
  (s/cat
    :happiness (s/and ::long ::happiness)
    :manager ::int-encoded-boolean))

(s/def ::employee-statuses
  (s/and
    ;; First interpret as a table.
    :parsing-with-spec.1.csv/table
    ;; Remove extraneous structure.
    :table/as-nested-list
    ;; interpret each row as an employee and determine if they are
    ;; happy enough.
    (s/coll-of (s/and ::employee-row happy-enough?))))
