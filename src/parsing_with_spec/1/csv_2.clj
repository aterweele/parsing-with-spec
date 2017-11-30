(ns parsing-with-spec.1.csv-2
  "A less simplified CSV"
  (:require [clojure.spec.alpha :as s]))

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

;; We will re-use the definitions of `::table` and `::row` from
;; before.
(s/def ::row
  (s/*
    (s/cat
      :cell ::cell
      :delim #{\,})))

(s/def ::table
  (s/and
    (s/conformer seq)
    (s/* (s/cat
           :row ::row
           :delim #{\newline}))))

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
