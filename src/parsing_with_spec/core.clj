(ns parsing-with-spec.core
  (:require [clojure.spec.alpha :as s]))

;; TODO: fix indentation according to Clojure norms.

;; Recall the docstring of `s/cat`:

(-> #'s/cat meta :doc)

;; Takes key+pred pairs, e.g.

;;   (s/cat :e even? :o odd?)

;;   Returns a regex op that matches (all) values in sequence,
;;   returning a map containing the keys of each pred and the
;;   corresponding value.

;; TODO: make an example that doesn't use s/* first
(s/def ::even-count? (s/* (s/cat :left some? :right some?)))

(s/conform ::even-count? [1 1 1 1])
;; which evaluates to
[{:left 1, :right 1} {:left 1, :right 1}]

;;; simplified CSV -- first attempt

;; TODO: add examples for each.

;; a cell is any sequence of characters that is not one of the
;; separators. For ease of implementation, we do allow these
;; separators to appear in the data.
(s/def ::cell (s/* (s/and char? (complement #{\, \newline}))))

;; a row is a sequence of cells separated by a comma, the cell
;; separator. We require every cell to be followed by the delimiter,
;; including the last one in the sequence. This simplifies the
;; implementation.
(s/def ::row (s/* (s/cat :cell ::cell :delim #{\,})))

;; a table is a sequence of rows, separated by a newline, the row
;; separator. Much like in `::row`, we require every row to be folowed
;; by the row delimiter.
(s/def ::table (s/* (s/cat :row ::row :delim #{\newline})))

(s/conform ::table (seq "a,1,\nb,2,\n"))

;; yielding
[{:row [{:cell [\a], :delim \,}
        {:cell [\1], :delim \,}], :delim \newline}
 {:row [{:cell [\b], :delim \,}
        {:cell [\2], :delim \,}], :delim \newline}]

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
(s/conform ::table "a,1,\nb,2,\n")

;; Will give us
[{:row [{:cell "a", :delim \,}
        {:cell "1", :delim \,}], :delim \newline}
 {:row [{:cell "b", :delim \,}
        {:cell "2", :delim \,}], :delim \newline}]

;;; A less simplified CSV

;; CSV can support the separator characters in the data if the cell is
;; quoted in double quotes. But, this removes the ability to write
;; double quotes in the data. Therefore, we will also introduce an
;; escape sequence for the double quote, written as \". But, this will
;; remove the ability to write a literal backslash. Therefore, we will
;; also introduce \\ as an escape sequence for a single backslash.

(def escape-characters #{\\ \"})

(s/def ::escape-sequence
  (s/cat
    :escape #{\\}
    :char escape-characters))

;; Now, a cell can either be unquoted as before or quoted with escape
;; sequences as before. Here's a first attempt.
(s/def ::cell
  (s/alt
    :unquoted (s/* (s/and char? (complement #{\, \newline})))
    :quoted (s/cat
              :start #{\"}
              :data (s/alt
                      :literal (complement escape-characters)
                      :escaped ::escape-sequence)
              :end #{\"})))
;; But after writing this, I found that `"\"aa\""` matches `:unquoted`
;; form
(s/conform ::cell (seq "\"aa\""))

;; which evaluates to
[:unquoted [\" \a \a \"]]

;; I made two mistakes. First, `s/alt` matches the first applicable
;; alternative. The second mistake is that I forgot to use `s/*` in
;; the `:quoted` alternative. Let's try again.
(s/def ::cell
  (s/alt
    :quoted (s/cat
              :start #{\"}
              :data (s/* (s/alt
                           :literal (complement escape-characters)
                           :escaped ::escape-sequence))
              :end #{\"})
    :unquoted (s/* (s/and char? (complement #{\, \newline})))))

(s/conform ::cell (seq "\"aa\""))

[:quoted {:start \"
          :data [[:literal \a] [:literal \a]]
          :end \"}]

;; Notice that we have abandoned the conformance for the moment. The
;; approach of most CSV libraries is to yield a string for every
;; cell. This approach will instead tell us about how the original
;; data looked. Consider
(s/conform ::table "abc,\"abc,\\\"\",\n")

;; which evaluates to
[{:row
  [{:cell [:unquoted [\a \b \c]], :delim \,}
   {:cell
    [:quoted
     {:start \",
      :data
      [[:literal \a]
       [:literal \b]
       [:literal \c]
       [:literal \,]
       [:escaped {:escape \\, :char \"}]],
      :end \"}],
    :delim \,}
   {:cell [:unquoted []]}],
  :delim \newline}
 {:row [{:cell [:unquoted []]}]}]

;; There are two things to notice here. First, we know that the second
;; cell in the first row was written as quoted, and within that, it
;; contains an escaped double quote.

;; Second, note that there are two rows and three cells in the first
;; row. In both cases, this is more than intended.

;; I ended up asking about this on #clojure. Pastebin of minimal repro
;; is https://pastebin.com/BKYGCWKP

;; also note: irc user leaves is interested in the notes

;; the follow-up https://pastebin.com/GqA5BdaC

;; It's possible that I've run into an issue akin to
;; https://dev.clojure.org/jira/browse/CLJ-2105. A little more
;; investigation suggests that this is a bug.
