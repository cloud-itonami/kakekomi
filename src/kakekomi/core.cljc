(ns kakekomi.core
  "駆け込み — 国外で犯罪被害に遭った渡航者の初動 corpus に対する純粋なクエリ層。

  この名前空間は状態を持たず、時計を読まず、ファイルを読まない。data/*.edn を
  読むのは呼び出し側の責任で、ここへは読み込んだ entity の列として渡す
  （kotoba-lang/ao と同じ runner-free / clock-free の形。repository-rules の
  {:prefix nil :role :library :execution :none} を満たす）。

  この corpus が持つのは手続きの順序と参照先だけである。個別の事案について
  何が正しいかを判定しない。判定しないことは制約ではなく設計であり、
  下流（cloud-itonami/cloud-kyusai の referral draft）が『提案するが決めない』
  形を保てるのはこの層が決めないからである。

  持っていない値を推測で埋めない。法域が corpus に無ければ checklist は
  黙って項目を落とさず :kakekomi/gap を返す——欠落を沈黙させると、下流は
  『該当なし』と読む。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 面の切り出し

(defn by-kind
  "entities から :kakekomi/kind が k のものを返す。"
  [entities k]
  (filter #(= k (:kakekomi/kind %)) entities))

(defn playbooks     [entities] (by-kind entities "playbook"))
(defn jurisdictions [entities] (by-kind entities "jurisdiction"))
(defn consular      [entities] (concat (by-kind entities "consular-procedure")
                                       (by-kind entities "consular-advisory")
                                       (by-kind entities "consular-scope")
                                       (by-kind entities "consular-registry")))
(defn insurance     [entities] (by-kind entities "insurance-requirement"))
(defn coverage      [entities] (by-kind entities "coverage"))

;; ---------------------------------------------------------------------------
;; 索引

(defn playbook
  "犯罪類型 (keyword、例 :pickpocket) の playbook。無ければ nil。"
  [entities crime-type]
  (first (filter #(= crime-type (:playbook/crime-type %)) (playbooks entities))))

(defn crime-types
  "corpus が持つ犯罪類型の集合。"
  [entities]
  (into #{} (keep :playbook/crime-type) (playbooks entities)))

(defn jurisdiction
  "ISO 3166-1 alpha-3（大文字小文字は問わない）で法域を引く。alpha-2 でも引ける。
   無ければ nil——nil は『その国に手続きが無い』ではなく『この corpus がまだ
   持っていない』を意味する（covered? / coverage を参照）。"
  [entities code]
  (when (string? code)
    (let [c (str/upper-case (str/trim code))]
      (first (filter #(or (= c (:jurisdiction/iso3166-alpha3 %))
                          (= c (:jurisdiction/iso3166-alpha2 %)))
                     (jurisdictions entities))))))

(defn covered?
  "その法域を corpus が持っているか。"
  [entities code]
  (some? (jurisdiction entities code)))

(defn emergency-numbers
  "法域の緊急連絡先を {:police .. :medical .. :universal .. :tourist-police ..}
   で返す。値を持たないキーは含めない（nil で埋めない）。"
  [entities code]
  (when-let [j (jurisdiction entities code)]
    (into {}
          (remove (comp nil? val))
          {:police         (:jurisdiction/emergency-police j)
           :medical        (:jurisdiction/emergency-medical j)
           :fire           (:jurisdiction/emergency-fire j)
           :universal      (:jurisdiction/emergency-universal j)
           :alt            (:jurisdiction/emergency-alt j)
           :tourist-police (:jurisdiction/emergency-tourist-police j)})))

(defn consular-procedure
  "国籍 (例 \"JPN\") と手続き種別 (例 :emergency-travel-document) で引く。"
  [entities nationality procedure]
  (first (filter #(and (= nationality (:consular/nationality %))
                       (= procedure (:consular/procedure %)))
                 (consular entities))))

;; ---------------------------------------------------------------------------
;; checklist — 面をまたいで 1 本の時系列に畳む

(def ^:private phase-order [:immediate :hours :days])

(def ^:private phase-label
  {:immediate "直後（安全確保）"
   :hours     "数時間以内（停止と届出）"
   :days      "帰国まで（公館・保険）"})

(defn- step [phase source text]
  {:step/phase phase :step/source source :step/text text})

(defn checklist
  "犯罪類型と法域から、時系列順の行動リストを組み立てる。

   opts:
     :nationality — 領事手続きを差し込む国籍（既定 \"JPN\"）

   返り値は {:checklist/steps [...] :checklist/gaps [...] :checklist/refs {...}}。
   :checklist/gaps が空でないとき、その checklist は不完全である——呼び出し側は
   これを利用者に見せる義務がある。埋められなかった項目を黙って落とすと
   『そこには何も要らない』と読まれる。"
  ([entities crime-type code] (checklist entities crime-type code {}))
  ([entities crime-type code {:keys [nationality] :or {nationality "JPN"}}]
   (let [pb    (playbook entities crime-type)
         j     (jurisdiction entities code)
         nums  (emergency-numbers entities code)
         gaps  (cond-> []
                 (nil? pb) (conj {:gap/kind :playbook-missing
                                  :gap/crime-type crime-type
                                  :gap/means :not-yet-collected
                                  :gap/note "この犯罪類型の playbook を corpus がまだ持っていない。手順が存在しないという意味ではない"})
                 (nil? j)  (conj {:gap/kind :jurisdiction-missing
                                  :gap/code code
                                  :gap/means :not-yet-collected
                                  :gap/note "この法域の緊急番号・被害届名称を corpus がまだ持っていない。渡航先の緊急番号は外務省 国別安全情報で確認すること"}))
         local (when j
                 (cond-> []
                   (seq nums)
                   (conj (step :immediate :jurisdiction
                               (str "緊急通報: "
                                    (str/join " / "
                                              (keep (fn [[k v]] (when v (str (name k) " " v)))
                                                    nums)))))
                   (:jurisdiction/police-report-term j)
                   (conj (step :hours :jurisdiction
                               (str "被害届は現地で「" (:jurisdiction/police-report-term j)
                                    "」と呼ばれる。この名称で求める")))
                   (:jurisdiction/notes j)
                   (conj (step :hours :jurisdiction (:jurisdiction/notes j)))))
         from-pb (when pb
                   (concat (map #(step :immediate :playbook %) (:playbook/immediate pb))
                           (map #(step :hours :playbook %)     (:playbook/hours pb))
                           (map #(step :days :playbook %)      (:playbook/days pb))))
         cons-steps (for [c (consular entities)
                          :when (and (= nationality (:consular/nationality c))
                                     (#{"consular-procedure" "consular-scope"} (:kakekomi/kind c))
                                     (not= :before-departure (:consular/timing c)))]
                      (step :days :consular
                            (or (:consular/label-ja c) (:consular/id c))))
         ins-steps (for [i (insurance entities)
                         :when (= :on-site (:insurance/timing i))]
                     (step :hours :insurance (:insurance/label-ja i)))
         all (concat local from-pb cons-steps ins-steps)]
     {:checklist/crime-type crime-type
      :checklist/jurisdiction code
      :checklist/nationality nationality
      :checklist/steps (vec (mapcat (fn [p] (filter #(= p (:step/phase %)) all)) phase-order))
      :checklist/phases (mapv (fn [p] {:phase p :label (phase-label p)}) phase-order)
      :checklist/gaps gaps
      :checklist/refs {:playbook (:playbook/id pb)
                       :jurisdiction (:jurisdiction/iso3166-alpha3 j)
                       :evidence (:playbook/evidence pb)
                       :pitfalls (:playbook/pitfalls pb)}})))

;; ---------------------------------------------------------------------------
;; 誠実さの検査 — corpus 自身に対する assertion

(defn unverified
  "一次情報での確認がまだの entity。数を隠さないための監査用。"
  [entities]
  (filter #(= :pending-primary-source (:fact/verification %)) entities))

(defn unsourced
  "確信度も根拠も持たない entity。corpus に入ってはいけない形。"
  [entities]
  (remove (fn [e]
            (or (= "coverage" (:kakekomi/kind e))
                (:fact/confidence e)
                (:fact/basis e)))
          entities))

(defn coverage-report
  "coverage entity を plane で引ける map にして返す。"
  [entities]
  (into {} (map (juxt :coverage/plane identity)) (coverage entities)))
