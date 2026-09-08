#!/usr/bin/env nbb
;; nbb run-tests.cljs — kakekomi の単体テスト + 実 corpus の整合性検査
;;
;; 実行: nbb --classpath src:test run-tests.cljs
;;
;; script host は nbb（ADR-2607173000）。bb / bb.edn は使わない。
;; core 自体は fs も clock も持たない純粋な .cljc で、ファイルを読むのは
;; ここだけである。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.edn :as edn]
         '[kotoba.lang.text :as str]
         '[clojure.test :as t]
         '[kakekomi.core :as k]
         'kakekomi.core-test)

(def here (path/dirname (fs/realpathSync *file*)))

(defn- data-file [n]
  (path/join here "data" n))

(defn- read-edn [p]
  (edn/read-string (fs/readFileSync p "utf8")))

(def data-files
  ["playbook.edn" "jurisdiction.edn" "consular-jp.edn" "insurance.edn" "coverage.edn"])

(def corpus
  (vec (mapcat (fn [n]
                 (let [content (read-edn (data-file n))]
                   (when-not (vector? content)
                     (throw (ex-info "corpus file must be a vector of entity maps" {:file n})))
                   (map #(assoc % :source/file n) content)))
               data-files)))

;; ---------------------------------------------------------------------------
;; 実データに対する整合性検査
;;
;; 単体テストと分けている理由: ここが落ちるのはロジックの不具合ではなく
;; データの不備で、直す場所が違う。

(t/deftest every-entity-declares-its-kind
  (t/is (empty? (remove :kakekomi/kind corpus))))

(t/deftest every-entity-carries-a-basis
  (t/testing "確信度も根拠も持たない entity を corpus に入れない"
    (t/is (empty? (map #(select-keys % [:kakekomi/kind :source/file])
                       (k/unsourced corpus))))))

(t/deftest jurisdictions-have-codes-and-a-number
  (doseq [j (k/jurisdictions corpus)]
    (t/is (string? (:jurisdiction/iso3166-alpha3 j))
          (str "alpha-3 が無い: " (:jurisdiction/name-ja j)))
    (t/is (= 3 (count (:jurisdiction/iso3166-alpha3 j))))
    (t/is (seq (k/emergency-numbers corpus (:jurisdiction/iso3166-alpha3 j)))
          (str "緊急番号が 1 つも無い: " (:jurisdiction/iso3166-alpha3 j)))))

(t/deftest jurisdiction-codes-are-unique
  (let [codes (map :jurisdiction/iso3166-alpha3 (k/jurisdictions corpus))]
    (t/is (= (count codes) (count (set codes))))))

(t/deftest playbooks-have-all-three-phases
  (doseq [p (k/playbooks corpus)]
    (t/is (seq (:playbook/immediate p)) (str "immediate が空: " (:playbook/id p)))
    (t/is (seq (:playbook/hours p))     (str "hours が空: " (:playbook/id p)))
    (t/is (seq (:playbook/evidence p))  (str "evidence が空: " (:playbook/id p)))))

(t/deftest coverage-is-declared-and-honest
  (let [rep (k/coverage-report corpus)
        juri (:jurisdiction rep)]
    (t/testing "カバレッジ entity が存在する"
      (t/is (some? juri))
      (t/is (some? (:playbook rep)))
      (t/is (some? (:verification rep))))
    (t/testing "宣言された seeded 数が実データと一致する"
      (t/is (= (:coverage/seeded juri) (count (k/jurisdictions corpus))))
      (t/is (= (:coverage/seeded (:playbook rep)) (count (k/playbooks corpus)))))
    (t/testing "未収集を『対象外』と書いていない"
      (t/is (= :not-yet-collected (:coverage/absent-means juri))))
    (t/testing "網羅していないのに :complete を名乗っていない"
      (t/is (not= :complete (:coverage/status juri))))))

(t/deftest verified-entities-cite-a-source
  (doseq [e corpus
          :when (= :verified-primary-source (:fact/verification e))]
    (t/is (or (:consular/source-url e) (seq (:insurance/source-urls e)))
          (str "verified を名乗るのに出典 URL が無い: "
               (or (:consular/id e) (:insurance/id e))))
    (t/is (some? (or (:consular/last-verified e) (:insurance/last-verified e)))
          (str "verified を名乗るのに確認日が無い: "
               (or (:consular/id e) (:insurance/id e))))))

(t/deftest checklist-works-on-real-data
  (let [c (k/checklist corpus :pickpocket "FRA")]
    (t/is (empty? (:checklist/gaps c)))
    (t/is (< 5 (count (:checklist/steps c))))
    (t/is (some #(str/includes? (:step/text %) "17") (:checklist/steps c))))
  (t/testing "corpus が持たない国は gap として返る"
    (let [c (k/checklist corpus :pickpocket "TUV")]
      (t/is (= 1 (count (:checklist/gaps c)))))))

;; ---------------------------------------------------------------------------

;; The exit code comes from the :end-run-tests report hook, NOT from the return
;; value of `run-tests`. Under nbb that value is nil, so the previous spelling
;;
;;   (let [{:keys [fail error]} (t/run-tests 'ns)]
;;     (js/process.exit (if (pos? (+ fail error)) 1 0)))
;;
;; destructured nil twice, added them to 0, and exited **0 with failures on the
;; screen** -- a red suite and a green suite returned the same value, which
;; makes every other check in this repository decorative. Measured 2026-08-30
;; against this repository's own suite before the change: failures, exit 0.
;; ADR-2608301500.
(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println)
  (println (str "corpus: " (count corpus) " entities / "
                (count (k/jurisdictions corpus)) " jurisdictions / "
                (count (k/playbooks corpus)) " playbooks / "
                (count (k/unverified corpus)) " pending primary-source verification"))
  (js/process.exit (if (t/successful? m) 0 1)))

(t/run-tests 'kakekomi.core-test 'user)
