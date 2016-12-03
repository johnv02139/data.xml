;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.data.xml.name
  #?@(:clj [(:require [clojure.string :as str]
                      [clojure.data.xml.jvm.name :as jvm]
                      (clojure.data.xml
                       [impl :refer [export-api]]
                       [protocols :as protocols :refer [AsQName]]))
            (:import (clojure.lang Namespace Keyword))]
      :cljs [(:require-macros
              [clojure.data.xml.impl :refer [export-api]])
             (:require [clojure.string :as str]
                       [clojure.data.xml.js.name :as jsn]
                       [clojure.data.xml.protocols :as protocols :refer [AsQName]])
             (:import (goog.string StringBuffer))]))

(export-api
 #?@(:clj  [jvm/parse-qname jvm/make-qname jvm/encode-uri jvm/decode-uri]
     :cljs [jsn/parse-qname jsn/make-qname jsn/encode-uri jsn/decode-uri]))

;; protocol functions can be redefined by extend-*, so we wrap
;; protocols/qname-uri protocols/qname-local within regular fns

(defn uri-symbol [uri]
  (symbol (encode-uri (str "xmlns." uri))))

(defn symbol-uri [ss]
  (let [du (decode-uri (str ss))]
    (if (.startsWith du "xmlns.")
      (subs du 6)
      (throw (ex-info "Uri symbol not valid" {:sym ss})))))

(defn qname-uri
  "Get the namespace uri for this qname"
  [v]
  (protocols/qname-uri v))

(defn qname-local
  "Get the name for this qname"
  [v]
  (protocols/qname-local v))

(defn qname
  ([name] (make-qname "" name ""))
  ([uri name] (make-qname (or uri "") name ""))
  ([uri name prefix] (make-qname (or uri "") name (or prefix ""))))

;; The empty string shall be equal to nil for xml names
(defn namespaced? [qn]
  (not (str/blank? (qname-uri qn))))

(defn- clj-ns-name [ns]
  (cond (instance? Namespace ns) (ns-name ns)
        (keyword? ns) (name ns)
        :else (str ns)))

;; xmlns attributes get special treatment, as they are go into metadata and don't contribute to equality
(def xmlns-uri "http://www.w3.org/2000/xmlns/")
;; TODO find out if xml prefixed names need any special treatment too
                                        ; (def xml-uri "http://www.w3.org/XML/1998/namespace")

(extend-protocol AsQName
  Keyword
  (qname-local [kw] (name kw))
  (qname-uri [kw]
    (if-let [ns (namespace kw)]
      (if (.startsWith ns "xmlns.")
        (decode-uri (subs ns 6))
        (if (= "xmlns" ns)
          xmlns-uri
          (throw (ex-info "Keyword ns is not an xmlns. Needs to be in the form :xmlns.<encoded-uri>/<local>"
                          {:kw kw}))))
      ""))
  #?(:clj String :cljs string)
  (qname-local [s] (qname-local (parse-qname s)))
  (qname-uri   [s] (qname-uri (parse-qname s))))

(defn canonical-name
  ([local] (canonical-name "" local ""))
  ([uri local] (canonical-name uri local ""))
  ([uri local prefix]
   (keyword (when-not (str/blank? uri)
              (encode-uri (str "xmlns." uri)))
            local)))

(defn to-qname [n]
  (make-qname (or (qname-uri n) "") (qname-local n) ""))

#?(:clj
   (defn alias-uri
     "Define a clojure namespace alias for xmlns uri.
  ## Example
  (alias-uri :D \"DAV:\")
  {:tag ::D/propfind}"
     {:arglists '([& {:as alias-nss}])}
     [& ans]
     (loop [[a n & rst :as ans] ans]
       (when (seq ans)
         (assert (<= (count ans)) (pr-str ans))
         (let [xn (uri-symbol n)
               al (symbol (clj-ns-name a))]
           (create-ns xn)
           (alias al xn)
           (recur rst))))))

(defn merge-nss
  "Merge two attribute sets, deleting assignments of empty-string"
  [nss1 nss2]
  (persistent!
   (reduce-kv (fn [a k v]
                (if (str/blank? v)
                  (dissoc! a k)
                  (assoc! a k v)))
              (transient nss1)
              nss2)))

(defn xmlns-attr?
  "Is this qname an xmlns declaration?"
  [qn]
  (let [uri (qname-uri qn)
        local (qname-local qn)]
    (or (= xmlns-uri uri)
        (and (str/blank? uri)
             (= "xmlns" local)))))

(defn separate-xmlns
  "Call cont with two args: attributes and xmlns attributes"
  [attrs cont]
  (loop [attrs* (transient {})
         xmlns* (transient {})
         [qn :as attrs'] (keys attrs)]
    (if (seq attrs')
      (let [val (get attrs qn)]
        (if (xmlns-attr? qn)
          (recur attrs*
                 (assoc! xmlns* qn val)
                 (next attrs'))
          (recur (assoc! attrs* qn val)
                 xmlns*
                 (next attrs'))))
      (cont (persistent! attrs*) (persistent! xmlns*)))))

;(set! *warn-on-reflection* true)

#?(:clj (def ^:private ^"[C" prefix-alphabet
          (char-array
           (map char
                (range (int \a) (inc (int \z))))))
   :cljs (def ^:private prefix-alphabet
           (apply str (map js/String.fromCharCode
                           (range (.charCodeAt "a" 0)
                                  (inc (.charCodeAt "z" 0)))))))

(def ^{:dynamic true
       :doc "Thread local counter for a single document"}
  *gen-prefix-counter*)

(defn gen-prefix
  "Generates an xml prefix.
   Zero-arity can only be called, when *gen-prefix-counter* is bound and will increment it."
  ([] (let [c *gen-prefix-counter*]
        #?(:cljs (when (undefined? c)
                   (throw (ex-info "Not bound: *gen-prefix-counter*" {:v #'*gen-prefix-counter*}))))
        (set! *gen-prefix-counter* (inc c))
        (gen-prefix c)))
  ([n]
   (let [cnt (alength prefix-alphabet)
         sb #?(:clj (StringBuilder.) :cljs (StringBuffer.))]
     (loop [n* n]
       (let [ch (mod n* cnt)
             n** (quot n* cnt)]
         (.append sb (aget prefix-alphabet ch))
         (if (pos? n**)
           (recur n**)
           (str sb)))))))

