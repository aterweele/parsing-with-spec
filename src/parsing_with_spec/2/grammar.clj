(ns parsing-with-spec.2.grammar
  (:require [clojure.spec.alpha :as s]))

;; `s/def` allows for the recursive definitions of specs. Let's write
;; a spec for simply nested parentheses like `()`, `(())`, `((()))`,
;; and so on.
(s/def ::simple-nesting
  (s/?
    (s/cat
      :open #{\(}
      :inner ::simple-nesting
      :close #{\)})))

;; Question: what is the difference in the operation of
;; `::simple-nesting` and the following spec?

(s/def ::simple-nesting-redux
  (s/cat
      :open #{\(}
      :nest (s/? ::simple-nesting-redux)
      :close #{\)}))

;; Note that we have moved `s/?` "inside".

;; Answer: while both will accept `()`, `(())`, `((()))`, etc.,
;; `::simple-nesting-redux` will not accept the empty string.
(s/valid? ::simple-nesting-redux (seq ""))

;; Simple nesting demonstrates an straightforward recursion. Let's
;; write a spec for balanced parentheses that will parse strings like
;; `()()` and `(()())`.
(s/def ::balanced
  (s/*
    (s/cat
      :open #{\(}
      :nest ::balanced
      :close #{\)})))

;; Notice the similarity between `::balanced` and `::simple-nesting`

;; We only have to extend `::balanced` a little to make a spec for
;; s-expressions. We have to add a case that an s-expression can be an
;; atom.

;; Whitespace separates atoms, so we must define what that is first.
(def whitespace?
  #{\newline \space \tab \formfeed \backspace \return})

(s/def ::whitespace (s/+ whitespace?))

;; Now, an atom is a sequence of characters that are not whitespace or
;; parentheses.
(s/def ::atom
  (s/+ #(and
          (not (whitespace? %))
          (not (#{\( \)} %)))))

;; We can equivalently define `::atom` with a little less repetition.
(s/def ::atom
  (s/+ (complement (some-fn whitespace? #{\( \)}))))

(s/def ::s-expression
  (s/*
    (s/alt
      :atom ::atom
      :whitespace ::whitespace
      :sub (s/cat
             :left #{\(}
             :nest ::s-expression
             :right #{\)}))))

;; Let's try it out!
(s/conform ::s-expression (seq "(abc  def)"))

;; evaluates to
[[:sub {:left  \(
        :nest  [[:atom [\a \b \c]]
                [:whitespace [\space \space]]
                [:atom [\d \e \f]]]
        :right \)}]]

;; As in the CSV example, we have to apply `seq` to the input data
;; before we `s/conform` it, and atoms and whitespace are expressed as
;; sequences of characters. Let's use conformers to solve both
;; problems.

(s/def ::atom
  (s/&
    (s/+ (complement (some-fn whitespace? #{\( \)})))
    (s/conformer (partial apply str))))

(s/def ::whitespace
  (s/&
    (s/+ whitespace?)
    (s/conformer (partial apply str))))

(s/def ::s-expression
  (s/and
    (s/conformer seq)
    (s/*
      (s/alt
        :atom ::atom
        :whitespace ::whitespace
        :sub (s/cat
               :left #{\(}
               :nest ::s-expression
               :right #{\)})))))

;; But now, attempting to conform will fail with an exception saying
;; "Don't know how to create `ISeq` from: `java.lang.Character`". I
;; have not accounted for the fact that `::s-expression` is
;; recursively defined, so when recursing, it tries to apply `seq` to
;; an "already-exploded" string--a character.
(comment
  (s/conform ::s-expression "(abc  def)"))

;; We can fix this by diving `::s-expression` into a top level that
;; applies `seq` and a separate spec for the recursive spec that is
;; defined characterwise.
(s/def ::s-expression
  (s/and
    (s/conformer seq)
    ::s-expression*))

(s/def ::s-expression*
  (s/*
    (s/alt
      :atom ::atom
      :whitespace ::whitespace
      :sub (s/cat
             :left #{\(}
             :nest ::s-expression*
             :right #{\)}))))

;; Now we can conform a string...
(s/conform ::s-expression "(abc  def)")

;; ...and get a conformed value with atoms and whitespace that are
;; strings.
[[:sub {:left  \(
        :nest  [[:atom "abc"]
                [:whitespace "  "]
                [:atom "def"]]
        :right \)}]]
