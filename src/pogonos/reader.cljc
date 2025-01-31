(ns pogonos.reader
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [pogonos.protocols :as proto]
            [pogonos.strings :as pstr])
  #?(:clj (:import [java.io Reader])))

(defn ->reader [x]
  (if (satisfies? proto/IReader x)
    x
    (proto/->reader x)))

(defn close [reader] (proto/close reader))

(deftype StringReader [src ^:unsynchronized-mutable offset]
  proto/IReader
  (read-line [_]
    (when (< offset (count src))
      (let [i (str/index-of src "\n" offset)
            offset' (or (some-> i inc) (count src))
            ret (subs src offset offset')]
        (set! offset offset')
        ret)))
  (end? [_] (>= offset (count src)))
  (close [_]))

(defn make-string-reader ^pogonos.reader.StringReader [s]
  (StringReader. s 0))

(extend-protocol proto/ToReader
  #?(:clj String :cljs string)
  (->reader [this]
    (make-string-reader this)))

#?(:clj
   (do
     (defmacro ! [x]
       #?(:bb `@~x :clj x))

     (defmacro update! [x v]
       #?(:bb `(vreset! ~x ~v)
          :clj `(set! ~x ~v)))

     (deftype FileReader
              [^Reader reader
               ^chars buf
               #?(:bb offset :clj ^:unsynchronized-mutable ^int offset)
               #?(:bb size :clj ^:unsynchronized-mutable ^int size)]
       proto/IReader
       (read-line [_]
         (loop [^StringBuilder sb nil]
           (when (>= (! offset) (! size))
             (update! size (.read reader buf))
             (update! offset (int 0)))
           (if (neg? (! size))
             (when sb (.toString sb))
             (let [sb (or sb (StringBuilder.))
                   i (int
                      (loop [i (! offset)]
                        (if (< i (! size))
                          (if (= (aget buf i) \newline)
                            (inc i)
                            (recur (inc i)))
                          -1)))]
               (if (>= i 0)
                 (do (.append sb buf (! offset) (- i (! offset)))
                     (update! offset i)
                     (.toString sb))
                 (do (.append sb buf (! offset) (- (! size) (! offset)))
                     (update! offset (! size))
                     (recur sb)))))))
       (end? [_]
         (or (and (>= (! offset) (! size))
                  (< (! size) (count buf)))
             (neg? (! size))))
       (close [_]
         (.close reader)))))

#?(:clj
   (defn make-file-reader ^pogonos.reader.FileReader [file]
     #?(:bb (FileReader. (io/reader file) (char-array 256)
                         (volatile! 0) (volatile! 0))
        :clj (FileReader. (io/reader file) (char-array 256) 0 0))))

#?(:clj
   (extend-protocol proto/ToReader
     java.io.File
     (->reader [this]
       (make-file-reader this))
     java.net.URI
     (->reader [this]
       (make-file-reader this))
     java.net.URL
     (->reader [this]
       (make-file-reader this))
     java.io.Reader
     (->reader [this]
       (make-file-reader this))
     java.io.InputStream
     (->reader [this]
       (make-file-reader this))))

(defprotocol ILineBufferingReader
  (set-line! [this l])
  (set-line-num! [this n])
  (set-col-num! [this n])
  (line [this])
  (line-num [this])
  (col-num [this])
  (base-reader [this]))

(declare read-line)

(deftype LineBufferingReader
    [in
     ^:unsynchronized-mutable line
     ^:unsynchronized-mutable lnum
     ^:unsynchronized-mutable cnum]
  ILineBufferingReader
  (set-line! [_ l]
    (set! line l))
  (set-line-num! [_ n]
    (set! lnum n))
  (set-col-num! [_ n]
    (set! cnum n))
  (line [_] line)
  (line-num [_] lnum)
  (col-num [_] cnum)
  (base-reader [_] in)
  proto/IReader
  (read-line [this]
    (read-line this))
  (end? [_]
    (proto/end? in))
  (close [_]
    (proto/close in)))

(defn make-line-buffering-reader [in]
  (->LineBufferingReader in nil 0 0))

(defn- read-line* [^LineBufferingReader reader]
  (let [prev (line reader)
        line (proto/read-line (base-reader reader))]
    (set-line! reader line)
    (set-col-num! reader 0)
    (when (and prev line)
      (set-line-num! reader (inc (line-num reader))))))

(defn- with-current-line [reader f]
  (when (>= (col-num reader) (count (line reader)))
    (read-line* reader))
  (when-let [l (line reader)]
    (f l)))

(defn- ensure-line-fed [reader]
  (when (nil? (line reader))
    (read-line* reader))
  (line reader))

(defn read-line [reader]
  (with-current-line reader
    (fn [line]
      (let [ret (cond-> line
                  (not= (col-num reader) 0)
                  (subs (col-num reader)))]
        (set-col-num! reader (count line))
        ret))))

(defn read-to-line-end [reader]
  (let [line (ensure-line-fed reader)]
    (when (< (col-num reader) (count line))
      (let [ret (subs line (col-num reader))]
        (set-col-num! reader (count line))
        ret))))

(defn read-until [reader s]
  (with-current-line reader
    (fn [line]
      (when-let [[i pre] (pstr/split line s (col-num reader))]
        (set-col-num! reader i)
        pre))))

(defn read-char [reader]
  (with-current-line reader
    (fn [line]
      (when-let [c (pstr/char-at line (col-num reader))]
        (set-col-num! reader (inc (col-num reader)))
        c))))

(defn unread-char [reader]
  (set-col-num! reader (dec (col-num reader)))
  nil)

(defn end? [reader]
  (if-some [line (ensure-line-fed reader)]
    (and (>= (col-num reader) (count line))
         (proto/end? reader))
    true))

(defn blank-trailing? [reader]
  (let [line (ensure-line-fed reader)]
    (when (< (col-num reader) (count line))
      (every? #{\space \tab \return \newline}
              (subs line (col-num reader))))))
