(ns metabase.util.date
  (:require [clj-time
             [coerce :as coerce]
             [core :as t]
             [format :as time]]
            [clojure.tools.logging :as log]
            [clojure.math.numeric-tower :as math]
            [metabase.util :as u]
            [puppetlabs.i18n.core :refer [trs]])
  (:import [clojure.lang Keyword]
           [java.util Calendar Date TimeZone]
           [java.sql Time Timestamp]
           [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormatter]))


(defprotocol ITimestampCoercible
  "Coerce object to a `java.sql.Timestamp`."
  (->Timestamp ^java.sql.Timestamp [this]
    "Coerce this object to a `java.sql.Timestamp`. Strings are parsed as ISO-8601."))

(declare str->date-time)

(extend-protocol ITimestampCoercible
  nil       (->Timestamp [_]
              nil)
  Timestamp (->Timestamp [this]
              this)
  Date       (->Timestamp [this]
               (Timestamp. (.getTime this)))
  ;; Number is assumed to be a UNIX timezone in milliseconds (UTC)
  Number    (->Timestamp [this]
              (Timestamp. this))
  Calendar  (->Timestamp [this]
              (->Timestamp (.getTime this)))
  ;; Strings are expected to be in ISO-8601 format. `YYYY-MM-DD` strings *are* valid ISO-8601 dates.
  String    (->Timestamp [this]
              (->Timestamp (str->date-time this)))
  DateTime  (->Timestamp [this]
              (->Timestamp (.getMillis this))))


(defprotocol IDateTimeFormatterCoercible
  "Protocol for converting objects to `DateTimeFormatters`."
  (->DateTimeFormatter ^org.joda.time.format.DateTimeFormatter [this]
    "Coerce object to a `DateTimeFormatter`."))

(extend-protocol IDateTimeFormatterCoercible
  ;; Specify a format string like "yyyy-MM-dd"
  String            (->DateTimeFormatter [this] (time/formatter this))
  DateTimeFormatter (->DateTimeFormatter [this] this)
  ;; Keyword will be used to get matching formatter from time/formatters
  Keyword           (->DateTimeFormatter [this]
                      (or (time/formatters this)
                          (throw (Exception. (format "Invalid formatter name, must be one of:\n%s"
                                                     (u/pprint-to-str (sort (keys time/formatters)))))))))


(defn parse-date
  "Parse a datetime string S with a custom DATE-FORMAT, which can be a format string, clj-time formatter keyword, or
  anything else that can be coerced to a `DateTimeFormatter`.

     (parse-date \"yyyyMMdd\" \"20160201\") -> #inst \"2016-02-01\"
     (parse-date :date-time \"2016-02-01T00:00:00.000Z\") -> #inst \"2016-02-01\""
  ^java.sql.Timestamp [date-format, ^String s]
  (->Timestamp (time/parse (->DateTimeFormatter date-format) s)))


(defprotocol ISO8601
  "Protocol for converting objects to ISO8601 formatted strings."
  (->iso-8601-datetime ^String [this timezone-id]
    "Coerce object to an ISO8601 date-time string such as \"2015-11-18T23:55:03.841Z\" with a given TIMEZONE."))

(def ^:private ^{:arglists '([timezone-id])} ISO8601Formatter
  ;; memoize this because the formatters are static. They must be distinct per timezone though.
  (memoize (fn [timezone-id]
             (if timezone-id
               (time/with-zone (time/formatters :date-time) (t/time-zone-for-id timezone-id))
               (time/formatters :date-time)))))

(extend-protocol ISO8601
  nil                    (->iso-8601-datetime [_ _] nil)
  java.util.Date         (->iso-8601-datetime [this timezone-id] (time/unparse (ISO8601Formatter timezone-id) (coerce/from-date this)))
  java.sql.Date          (->iso-8601-datetime [this timezone-id] (time/unparse (ISO8601Formatter timezone-id) (coerce/from-sql-date this)))
  java.sql.Timestamp     (->iso-8601-datetime [this timezone-id] (time/unparse (ISO8601Formatter timezone-id) (coerce/from-sql-time this)))
  org.joda.time.DateTime (->iso-8601-datetime [this timezone-id] (time/unparse (ISO8601Formatter timezone-id) this)))

(def ^:private ^{:arglists '([timezone-id])} time-formatter
  ;; memoize this because the formatters are static. They must be distinct per timezone though.
  (memoize (fn [timezone-id]
             (if timezone-id
               (time/with-zone (time/formatters :time) (t/time-zone-for-id timezone-id))
               (time/formatters :time)))))

(defn format-time
  "Returns a string representation of the time found in `T`"
  [t time-zone-id]
  (time/unparse (time-formatter time-zone-id) (coerce/to-date-time t)))

(defn is-time?
  "Returns true if `V` is a Time object"
  [v]
  (and v (instance? Time v)))

;;; ## Date Stuff

(defn is-temporal?
  "Is VALUE an instance of a datetime class like `java.util.Date` or `org.joda.time.DateTime`?"
  [v]
  (or (instance? java.util.Date v)
      (instance? org.joda.time.DateTime v)))

(defn new-sql-timestamp
  "`java.sql.Date` doesn't have an empty constructor so this is a convenience that lets you make one with the current
  date. (Some DBs like Postgres will get snippy if you don't use a `java.sql.Timestamp`)."
  ^java.sql.Timestamp []
  (->Timestamp (System/currentTimeMillis)))

(defn format-date
  "Format DATE using a given DATE-FORMAT.

   DATE is anything that can coerced to a `Timestamp` via `->Timestamp`, such as a `Date`, `Timestamp`,
   `Long` (ms since the epoch), or an ISO-8601 `String`. DATE defaults to the current moment in time.

   DATE-FORMAT is anything that can be passed to `->DateTimeFormatter`, such as `String`
   (using [the usual date format args](http://docs.oracle.com/javase/7/docs/api/java/text/SimpleDateFormat.html)),
   `Keyword`, or `DateTimeFormatter`.


     (format-date \"yyyy-MM-dd\")                        -> \"2015-11-18\"
     (format-date :year (java.util.Date.))               -> \"2015\"
     (format-date :date-time (System/currentTimeMillis)) -> \"2015-11-18T23:55:03.841Z\""
  (^String [date-format]
   (format-date date-format (System/currentTimeMillis)))
  (^String [date-format date]
   (time/unparse (->DateTimeFormatter date-format) (coerce/from-sql-time (->Timestamp date)))))

(def ^{:arglists '([] [date])} date->iso-8601
  "Format DATE a an ISO-8601 string."
  (partial format-date :date-time))

(defn date-string?
  "Is S a valid ISO 8601 date string?"
  [^String s]
  (boolean (when (string? s)
             (u/ignore-exceptions
               (->Timestamp s)))))

(defn ->Date
  "Coerece DATE to a `java.util.Date`."
  (^java.util.Date []
   (java.util.Date.))
  (^java.util.Date [date]
   (java.util.Date. (.getTime (->Timestamp date)))))


(defn ->Calendar
  "Coerce DATE to a `java.util.Calendar`."
  (^java.util.Calendar []
   (doto (Calendar/getInstance)
     (.setTimeZone (TimeZone/getTimeZone "UTC"))))
  (^java.util.Calendar [date]
   (doto (->Calendar)
     (.setTime (->Timestamp date))))
  (^java.util.Calendar [date, ^String timezone-id]
   (doto (->Calendar date)
     (.setTimeZone (TimeZone/getTimeZone timezone-id)))))


(defn relative-date
  "Return a new `Timestamp` relative to the current time using a relative date UNIT.

     (relative-date :year -1) -> #inst 2014-11-12 ..."
  (^java.sql.Timestamp [unit amount]
   (relative-date unit amount (Calendar/getInstance)))
  (^java.sql.Timestamp [unit amount date]
   (let [cal               (->Calendar date)
         [unit multiplier] (case unit
                             :second  [Calendar/SECOND 1]
                             :minute  [Calendar/MINUTE 1]
                             :hour    [Calendar/HOUR   1]
                             :day     [Calendar/DATE   1]
                             :week    [Calendar/DATE   7]
                             :month   [Calendar/MONTH  1]
                             :quarter [Calendar/MONTH  3]
                             :year    [Calendar/YEAR   1])]
     (.set cal unit (+ (.get cal unit)
                       (* amount multiplier)))
     (->Timestamp cal))))


(def ^:private ^:const date-extract-units
  #{:minute-of-hour :hour-of-day :day-of-week :day-of-month :day-of-year :week-of-year :month-of-year :quarter-of-year
    :year})

(defn date-extract
  "Extract UNIT from DATE. DATE defaults to now.

     (date-extract :year) -> 2015"
  ([unit]
   (date-extract unit (System/currentTimeMillis) "UTC"))
  ([unit date]
   (date-extract unit date "UTC"))
  ([unit date timezone-id]
   (let [cal (->Calendar date timezone-id)]
     (case unit
       :minute-of-hour  (.get cal Calendar/MINUTE)
       :hour-of-day     (.get cal Calendar/HOUR_OF_DAY)
       ;; 1 = Sunday <-> 6 = Saturday
       :day-of-week     (.get cal Calendar/DAY_OF_WEEK)
       :day-of-month    (.get cal Calendar/DAY_OF_MONTH)
       :day-of-year     (.get cal Calendar/DAY_OF_YEAR)
       ;; 1 = First week of year
       :week-of-year    (.get cal Calendar/WEEK_OF_YEAR)
       :month-of-year   (inc (.get cal Calendar/MONTH))
       :quarter-of-year (let [month (date-extract :month-of-year date timezone-id)]
                          (int (/ (+ 2 month)
                                  3)))
       :year            (.get cal Calendar/YEAR)))))


(def ^:private ^:const date-trunc-units
  #{:minute :hour :day :week :month :quarter :year})

(defn- trunc-with-format [format-string date timezone-id]
  (->Timestamp (format-date (time/with-zone (time/formatter format-string)
                              (t/time-zone-for-id timezone-id))
                            date)))

(defn- trunc-with-floor [date amount-ms]
  (->Timestamp (* (math/floor (/ (.getTime (->Timestamp date))
                                 amount-ms))
                  amount-ms)))

(defn- ->first-day-of-week [date timezone-id]
  (let [day-of-week (date-extract :day-of-week date timezone-id)]
    (relative-date :day (- (dec day-of-week)) date)))

(defn- format-string-for-quarter ^String [date timezone-id]
  (let [year    (date-extract :year date timezone-id)
        quarter (date-extract :quarter-of-year date timezone-id)
        month   (- (* 3 quarter) 2)]
    (format "%d-%02d-01'T'ZZ" year month)))

(defn date-trunc
  "Truncate DATE to UNIT. DATE defaults to now.

     (date-trunc :month).
     ;; -> #inst \"2015-11-01T00:00:00\""
  (^java.sql.Timestamp [unit]
   (date-trunc unit (System/currentTimeMillis) "UTC"))
  (^java.sql.Timestamp [unit date]
   (date-trunc unit date "UTC"))
  (^java.sql.Timestamp [unit date timezone-id]
   (case unit
     ;; For minute and hour truncation timezone should not be taken into account
     :minute  (trunc-with-floor date (* 60 1000))
     :hour    (trunc-with-floor date (* 60 60 1000))
     :day     (trunc-with-format "yyyy-MM-dd'T'ZZ" date timezone-id)
     :week    (trunc-with-format "yyyy-MM-dd'T'ZZ" (->first-day-of-week date timezone-id) timezone-id)
     :month   (trunc-with-format "yyyy-MM-01'T'ZZ" date timezone-id)
     :quarter (trunc-with-format (format-string-for-quarter date timezone-id) date timezone-id)
     :year    (trunc-with-format "yyyy-01-01'T'ZZ" date timezone-id))))


(defn date-trunc-or-extract
  "Apply date bucketing with UNIT to DATE. DATE defaults to now."
  ([unit]
   (date-trunc-or-extract unit (System/currentTimeMillis) "UTC"))
  ([unit date]
   (date-trunc-or-extract unit date "UTC"))
  ([unit date timezone-id]
   (cond
     (= unit :default) date

     (contains? date-extract-units unit)
     (date-extract unit date timezone-id)

     (contains? date-trunc-units unit)
     (date-trunc unit date timezone-id))))

(defn format-nanoseconds
  "Format a time interval in nanoseconds to something more readable (µs/ms/etc.)
   Useful for logging elapsed time when using `(System/nanotime)`"
  ^String [nanoseconds]
  (loop [n nanoseconds, [[unit divisor] & more] [[:ns 1000] [:µs 1000] [:ms 1000] [:s 60] [:mins 60] [:hours Integer/MAX_VALUE]]]
    (if (and (> n divisor)
             (seq more))
      (recur (/ n divisor) more)
      (format "%.0f %s" (double n) (name unit)))))

(defmacro profile
  "Like `clojure.core/time`, but lets you specify a MESSAGE that gets printed with the total time,
   and formats the time nicely using `format-nanoseconds`."
  {:style/indent 1}
  ([form]
   `(profile ~(str form) ~form))
  ([message & body]
   `(let [start-time# (System/nanoTime)]
      (u/prog1 (do ~@body)
        (println (u/format-color '~'green "%s took %s"
                   ~message
                   (format-nanoseconds (- (System/nanoTime) start-time#))))))))

(defn- str->date-time-with-formatters
  "Attempt to parse `DATE-STR` using `FORMATTERS`. First successful
  parse is returned, or nil"
  ([formatters date-str]
   (str->date-time-with-formatters formatters date-str nil))
  ([formatters ^String date-str ^TimeZone tz]
   (let [dtz (some-> tz .getID t/time-zone-for-id)]
     (first
      (for [formatter formatters
            :let [formatter-with-tz (time/with-zone formatter dtz)
                  parsed-date (u/ignore-exceptions (time/parse formatter-with-tz date-str))]
            :when parsed-date]
        parsed-date)))))

(def ^:private date-time-with-millis-no-t
  "This primary use for this formatter is for Dates formatted by the built-in SQLite functions"
  (->DateTimeFormatter "yyyy-MM-dd HH:mm:ss.SSS"))

(def ^:private ordered-date-parsers
  "When using clj-time.format/parse without a formatter, it tries all default formatters, but not ordered by how
  likely the date formatters will succeed. This leads to very slow parsing as many attempts fail before the right one
  is found. Using this retains that flexibility but improves performance by trying the most likely ones first"
  (let [most-likely-default-formatters [:mysql :date-hour-minute-second :date-time :date
                                        :basic-date-time :basic-date-time-no-ms
                                        :date-time :date-time-no-ms]]
    (concat (map time/formatters most-likely-default-formatters)
            [date-time-with-millis-no-t]
            (vals (apply dissoc time/formatters most-likely-default-formatters)))))

(defn str->date-time
  "Like clj-time.format/parse but uses an ordered list of parsers to be faster. Returns the parsed date or nil if it
  was unable to be parsed."
  (^org.joda.time.DateTime [^String date-str]
   (str->date-time date-str nil))
  ([^String date-str ^TimeZone tz]
   (str->date-time-with-formatters ordered-date-parsers date-str tz)))

(def ^:private ordered-time-parsers
  (let [most-likely-default-formatters [:hour-minute :hour-minute-second :hour-minute-second-fraction]]
    (concat (map time/formatters most-likely-default-formatters)
            [(time/formatter "HH:mmZ") (time/formatter "HH:mm:SSZ") (time/formatter "HH:mm:SS.SSSZ")])))

(defn str->time
  "Parse `TIME-STR` and return a `java.sql.Time` instance. Returns nil
  if `TIME-STR` can't be parsed."
  ([^String date-str]
   (str->time date-str nil))
  ([^String date-str ^TimeZone tz]
   (some-> (str->date-time-with-formatters ordered-time-parsers date-str tz)
           coerce/to-long
           Time.)))
