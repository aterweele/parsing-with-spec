(ns parsing-with-spec.1.csv
  "We write a spec for a simplified version of CSV data."
  (:require [clojure.spec.alpha :as s]))

;;; simplified CSV -- first attempt

;; a cell is any sequence of characters that is not one of the
;; separators. For ease of implementation, we do allow these
;; separators to appear in the data.
(s/def ::cell
  (s/*
    (s/and
      char?
      (complement #{\, \newline}))))

;; We can use it like so:
(assert (= (s/conform ::cell (seq "abc"))
           [\a \b \c]))

;; a row is a sequence of cells separated by a comma, the cell
;; separator. We require every cell to be followed by the delimiter,
;; including the last one in the sequence. This simplifies the
;; implementation.
(s/def ::row
  (s/*
    (s/cat
      :cell ::cell
      :delim #{\,})))

;; Which we use like this:
(assert (= (s/conform ::row (seq "abc,"))
           [{:cell [\a \b \c], :delim \,}]))

;; a table is a sequence of rows, separated by a newline, the row
;; separator. Much like in `::row`, we require every row to be folowed
;; by the row delimiter.
(s/def ::table
  (s/*
    (s/cat
      :row ::row
      :delim #{\newline})))

(assert (= (s/conform ::table (seq "abc,123,\ndef,456,\n"))
           [{:row [{:cell [\a \b \c], :delim \,}
                   {:cell [\1 \2 \3], :delim \,}]
             :delim \newline}
            {:row [{:cell [\d \e \f], :delim \,}
                   {:cell [\4 \5 \6], :delim \,}]
             :delim \newline}]))

;; This approach has a few undesirable qualities. Note the need to
;; "explode" the input string into a seq with `(seq "...")` before we
;; `s/conform` it. Also, the data in the conformed value is a seq of
;; characters, not a string.

;;; simplified CSV -- second attempt

;; trick from
;; https://clojuredocs.org/clojure.spec/conformer#example-58a0dc93e4b01f4add58fe4d

;; We use `s/conformer` to have spec do the seq for us.

(s/def ::table
  (s/and
    (s/conformer seq)
    (s/* (s/cat
           :row ::row
           :delim #{\newline}))))

;; Note that we put the conformer on the `::table`, which is the
;; "top-level" spec. When we do

(s/conform ::table "a,1,\nb,2,\n")

;; Then the definition of the `::table` spec will first "explode" the
;; string into a seq, then we re-use the character-based definitions
;; from before. This solves the problem of the previous approach which
;; required invoking `seq` on the data before conforming it. However,
;; the output is still not a string.

;;; Simplified CSV -- third attempt

;; My first thought was to use a conformer to turn the conformed value
;; into a string, using `s/and` to place it after characterwise check:
(s/def ::cell
  (s/and
    (s/* (s/and char? (complement #{\, \newline})))
    (s/conformer (partial apply str))))

;; However, this did not work. Specifically, a valid row would not
;; conform.
(s/explain-str ::row (seq "abc,"))

;; Which tells us: "In: `[0]` val: `\a` fails spec:
;; `:parsing-with-spec.core/cell` at: `[:cell]` predicate: (* (and
;; char? (complement #{\newline \,})))"

;; This puzzled me at first, but through some experimentation I
;; figured it out. We can rewrite the definition of `::row` with the
;; original definition of `::cell` inside, which works as we
;; expect. (I have simplified this definition a bit)
(s/cat :cell (s/* (complement #{\, \newline})))

;; However, when we add `s/and` so we can use the conformer, it will
;; stop conforming as we intend.
(s/cat :cell (s/and
               (s/* (complement #{\, \newline}))
               (s/conformer (partial apply str))))

;; At this point, my best guess was that `s/cat` and `s/*` have a
;; special relationship that `s/and` destroys. This turned out to be
;; right. I consulted the spec guide, which says "When regex ops are
;; combined, they describe a single sequence." `s/cat` and `s/*` are
;; regex ops, while `s/and` is not. `s/and` will therefore start a new
;; regex context, which we do not want in this case. What we are
;; looking for is `s/&`, which is the regex op for conjunction. Let's
;; redefine `::cell` using it.
(s/def ::cell
  (s/&
    (s/* (s/and char? (complement #{\, \newline})))
    (s/conformer (partial apply str))))

;; Now, evaluating the expression from before
(assert (= (s/conform ::table "a,1,\nb,2,\n")
           [{:row [{:cell "a", :delim \,}
                   {:cell "1", :delim \,}], :delim \newline}
            {:row [{:cell "b", :delim \,}
                   {:cell "2", :delim \,}], :delim \newline}]))

;; Using conformers without specifying an unformer means that
;; `s/unform` cannot work:
(comment
  (s/unform ::table (s/conform ::table "a,1,\nb,2,\n")))

;; So let's specify an unformers every time we use `s/conformer`.
(s/def ::cell
  (s/&
    (s/* (s/and char? (complement #{\, \newline})))
    (s/conformer (partial apply str) seq)))

(s/def ::table
  (s/and
    (s/conformer seq (partial apply str))
    (s/* (s/cat
           :row ::row
           :delim #{\newline}))))

;; Now we can use `s/unform`:
(assert (= (s/unform ::table (s/conform ::table "a,1,\nb,2,\n"))
           "a,1,\nb,2,\n"))
