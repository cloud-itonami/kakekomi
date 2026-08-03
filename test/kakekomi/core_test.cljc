(ns kakekomi.core-test
  "kakekomi.core の単体テスト。

  ここでは実データを読まない——core は純粋なので、インラインの最小 fixture で
  振る舞いを固定できる。実際の data/*.edn に対する整合性検査は run-tests.cljs
  側（corpus-integrity）が行う。両者を混ぜると、データを 1 行足しただけで
  ロジックのテストが落ちるようになる。"
  (:require [clojure.test :refer [deftest is testing]]
            [kakekomi.core :as k]))

(def fixture
  [{:kakekomi/kind "playbook"
    :playbook/id "pickpocket"
    :playbook/crime-type :pickpocket
    :playbook/immediate ["安全な場所へ移動する"]
    :playbook/hours ["カードを停止する"]
    :playbook/days ["保険会社へ連絡する"]
    :playbook/evidence ["被害届"]
    :playbook/pitfalls ["紛失として処理させない"]
    :fact/confidence :well-established}
   {:kakekomi/kind "jurisdiction"
    :jurisdiction/iso3166-alpha3 "FRA"
    :jurisdiction/iso3166-alpha2 "FR"
    :jurisdiction/emergency-police "17"
    :jurisdiction/emergency-universal "112"
    :jurisdiction/police-report-term "plainte"
    :fact/confidence :well-established
    :fact/verification :pending-primary-source}
   {:kakekomi/kind "consular-procedure"
    :consular/id "jp-emergency-travel-document"
    :consular/nationality "JPN"
    :consular/procedure :emergency-travel-document
    :consular/label-ja "帰国のための渡航書"
    :fact/confidence :well-established
    :fact/verification :verified-primary-source}
   {:kakekomi/kind "consular-registry"
    :consular/id "jp-tabireji"
    :consular/nationality "JPN"
    :consular/label-ja "たびレジ"
    :consular/timing :before-departure
    :fact/confidence :well-established}
   {:kakekomi/kind "insurance-requirement"
    :insurance/id "police-report-required"
    :insurance/label-ja "携行品損害には現地警察の証明が要る"
    :insurance/timing :on-site
    :fact/confidence :well-established}
   {:kakekomi/kind "coverage"
    :coverage/plane :jurisdiction
    :coverage/seeded 1
    :coverage/universe 249
    :coverage/absent-means :not-yet-collected}])

(deftest lookup-by-code
  (testing "alpha-3 / alpha-2 / 小文字のいずれでも引ける"
    (is (= "FRA" (:jurisdiction/iso3166-alpha3 (k/jurisdiction fixture "FRA"))))
    (is (= "FRA" (:jurisdiction/iso3166-alpha3 (k/jurisdiction fixture "FR"))))
    (is (= "FRA" (:jurisdiction/iso3166-alpha3 (k/jurisdiction fixture "fra")))))
  (testing "持っていない法域は nil"
    (is (nil? (k/jurisdiction fixture "ZWE")))
    (is (false? (boolean (k/covered? fixture "ZWE"))))))

(deftest emergency-numbers-omit-absent-keys
  (testing "値を持たないキーは nil で埋めずに落とす"
    (let [n (k/emergency-numbers fixture "FRA")]
      (is (= {:police "17" :universal "112"} n))
      (is (not (contains? n :medical))))))

(deftest checklist-is-ordered-by-phase
  (let [c (k/checklist fixture :pickpocket "FRA")
        phases (map :step/phase (:checklist/steps c))]
    (testing "immediate → hours → days の順に並ぶ"
      (is (= phases (sort-by {:immediate 0 :hours 1 :days 2} phases))))
    (testing "playbook と jurisdiction の両方から step が入る"
      (is (contains? (set (map :step/source (:checklist/steps c))) :playbook))
      (is (contains? (set (map :step/source (:checklist/steps c))) :jurisdiction)))
    (testing "現地名称が hours 段に入る"
      (is (some #(re-find #"plainte" (:step/text %)) (:checklist/steps c))))))

(deftest checklist-excludes-before-departure-items
  (testing "たびレジ（渡航前）は被害後の checklist に混ざらない"
    (let [c (k/checklist fixture :pickpocket "FRA")]
      (is (not-any? #(re-find #"たびレジ" (:step/text %)) (:checklist/steps c))))))

(deftest gaps-are-reported-not-swallowed
  (testing "未収集の法域は gap として返る（黙って落とさない）"
    (let [c (k/checklist fixture :pickpocket "ZWE")]
      (is (= 1 (count (:checklist/gaps c))))
      (is (= :jurisdiction-missing (:gap/kind (first (:checklist/gaps c)))))
      (is (= :not-yet-collected (:gap/means (first (:checklist/gaps c)))))))
  (testing "未収集の犯罪類型も同じく gap になる"
    (let [c (k/checklist fixture :drink-spiking "FRA")]
      (is (= :playbook-missing (:gap/kind (first (:checklist/gaps c)))))))
  (testing "両方欠けたら gap は 2 件"
    (is (= 2 (count (:checklist/gaps (k/checklist fixture :drink-spiking "ZWE")))))))

(deftest honesty-predicates
  (testing "一次情報未確認の entity を数えられる"
    (is (= 1 (count (k/unverified fixture)))))
  (testing "根拠を持たない entity は無い"
    (is (empty? (k/unsourced fixture)))))

(deftest crime-types-enumerable
  (is (= #{:pickpocket} (k/crime-types fixture))))
