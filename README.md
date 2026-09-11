# kakekomi（駆け込み）

国外で犯罪被害に遭った渡航者の**初動 corpus**。犯罪類型ごとの手順、法域ごとの
緊急番号と被害届の呼称、日本の領事手続き、海外旅行保険の請求要件を、
join できる 1 つの EDN 面として持つ。

**この repo は判定しない。** 手続きの順序と参照先を持つだけで、個別の事案に
ついて何が正しいかは決めない。決めないことは制約ではなく設計で、下流
（`gftdcojp/cloud-kyusai` の referral draft）が「提案するが決めない」形を
保てるのは、この層が決めないからである。

**法的助言ではない。** 実際の事案では在外公館・現地当局・保険会社・弁護士の
指示が優先する。

## 何が入っているか

| ファイル | 面 | 件数 |
|---|---|---:|
| `data/playbook.edn` | 犯罪類型ごとの初動（法域に依らない層） | 9 |
| `data/jurisdiction.edn` | 緊急番号・被害届の現地名称 | 42 |
| `data/consular-jp.edn` | 日本国籍者の領事手続き（旅券紛失・帰国のための渡航書ほか） | 8 |
| `data/insurance.edn` | 海外旅行保険の請求要件（現地で取らないと後で取れないもの） | 5 |
| `data/coverage.edn` | カバレッジの明示的記録 | 3 |

結合キーは `:jurisdiction/iso3166-alpha3`。`cloud-itonami-iso3166-<alpha3>` repo 群、
および superproject の統合クエリ面（`manifest/edn-query.cljs`、`:source/dataset
"kakekomi"`）とはこのキーで join する。

## カバレッジ — 網羅していない

法域は **42 / 249**（ISO 3166-1 の符号数）。**ここに無い国は「対象外」ではなく
「まだ収集していない」**（`:coverage/absent-means :not-yet-collected`）。この差を
黙って持つと、下流が「載っていない＝該当なし」と読む。

検証状態も分けて記録している:

- `:verified-primary-source` — 2026-08-03 に一次情報を実際に参照して確認した。
  `consular-jp.edn` の 5 件（外務省）と `insurance.edn` の 4 件（国内損保 FAQ・
  国民生活センター）。
- `:pending-primary-source` — 広く確立した事実として記載したが、この corpus と
  しては一次情報での確認をまだ行っていない。**46 件がこれに当たる**（緊急番号・
  被害届の現地名称のすべて）。

緊急番号は変更頻度が低いが、変更されないわけではない。実際に使う場面では
この corpus を唯一の根拠にせず、外務省 海外安全ホームページの国別安全情報で
確認する経路を残しておくこと。

## 使う

```clojure
(require '[kakekomi.core :as k])

;; 呼び出し側が data/*.edn を読んで渡す（core は fs も clock も持たない）
(k/checklist corpus :pickpocket "FRA")
;; => {:checklist/steps [{:step/phase :immediate :step/source :jurisdiction
;;                        :step/text "緊急通報: police 17 / universal 112"} ...]
;;     :checklist/gaps []
;;     :checklist/refs {:playbook "pickpocket" :evidence [...] :pitfalls [...]}}

;; 持っていない法域は黙って落とさず gap として返る
(:checklist/gaps (k/checklist corpus :pickpocket "TUV"))
;; => [{:gap/kind :jurisdiction-missing :gap/means :not-yet-collected ...}]

(k/emergency-numbers corpus "NOR")  ;; => {:police "112" :medical "113" :fire "110"}
(k/unverified corpus)               ;; 一次情報未確認の entity を数える
```

## テスト

script host は **nbb**（`bb` は使わない — ADR-2607173000）。

```sh
nbb --classpath src:test run-tests.cljk
```

15 tests / 205 assertions。単体テスト（`test/kakekomi/core_test.cljk`、インライン
fixture）と実データの整合性検査（`run-tests.cljk`）を分けている——後者が落ちるのは
ロジックではなくデータの不備で、直す場所が違うため。

整合性検査が固定していること: 全 entity が `:kakekomi/kind` と根拠を持つ /
法域コードが一意で緊急番号を 1 つ以上持つ / playbook が 3 フェーズと evidence を
持つ / **`coverage.edn` の宣言値が実データの件数と一致する** /
`:verified-primary-source` を名乗る entity が出典 URL と確認日を持つ。

## 足す

`data/jurisdiction.edn` に entity を 1 つ足すだけでよい。コードの変更は要らない。

```clojure
{:kakekomi/kind "jurisdiction"
 :jurisdiction/iso3166-alpha3 "XXX" :jurisdiction/iso3166-alpha2 "XX"
 :jurisdiction/name-ja "..." :jurisdiction/emergency-police "..."
 :fact/confidence :well-established :fact/verification :pending-primary-source}
```

足したら `data/coverage.edn` の `:coverage/seeded` も更新する（整合性検査が
一致を強制する）。**確認できない項目は書かない——空欄のまま残す方が、推測で
埋めるより下流にとって安全である。**

## この repo の位置

- **org**: `cloud-itonami`（on-demand × 産業 actor。ADR-2607289700 の 2 軸判定）
- **role**: `{:prefix nil :role :library :execution :none}`（`resources/repository-rules.edn`）
- **姉妹**: `cloud-itonami/tasuke`（国内向け被害届起草）/ `tadori`（on-chain trace）/
  `malak`（越境 LE referral）— いずれもサイバー・暗号資産犯罪側。kakekomi は
  物理犯罪・渡航者側を担う。
- **下流**: `gftdcojp/cloud-kyusai`（被害者 intake → referral draft）がこの corpus を
  読む。

設計の経緯は `docs/adr/0001-architecture.md` と superproject の
`90-docs/adr/2608032000-overseas-crime-victim-first-response-corpus.edn`。
