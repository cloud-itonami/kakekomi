# ADR-0001: kakekomi のアーキテクチャ — 判定しない corpus

**Status**: accepted
**Date**: 2026-08-03
**Superproject ADR**: `90-docs/adr/2608032000-overseas-crime-victim-first-response-corpus.edn`

## Context

国外で犯罪被害に遭った渡航者向けの情報を、この workspace はどこにも持って
いなかった。隣接するものは全てサイバー・暗号資産犯罪側（`cloud-itonami/tasuke`
`tadori` `malak` `crypto-asset-freeze`、front は `gftdcojp/cloud-kyusai`）か、
旅行の予約側ビジネス（`cloud-itonami-isic-7911/7912/7990`）だった。

置き場所は superproject ADR が決めている（org = f(起動主体, 事業主体)、
ADR-2607289700）。本 ADR が決めるのは repo の内側の形。

## Decision

### D1. 判定しない

この corpus は手続きの順序と参照先しか持たない。個別の事案について何が正しいかを
判定しない。`resources/repository-rules.edn` の `:must-not` に `:decide-a-case` を
入れて明示している。

理由は下流にある。`cloud-kyusai` は「intake → referral draft → `:pending-approval`
→ 人間が submit」という、**自分では何も実行しない**形を持っている。その形が
成立するのは、参照する corpus 側も決めないからである。corpus が「あなたのケースは
こうすべき」を返し始めると、人間の承認は形式になる。

### D2. 純粋 — fs も clock も持たない

`kakekomi.core` は状態を持たず、時計を読まず、ファイルを読まない。`data/*.edn` を
読むのは呼び出し側（`run-tests.cljk` と下流の consumer）。`kotoba-lang/ao` と同じ
runner-free / clock-free の形で、`{:prefix nil :role :library :execution :none}` を
満たす。

### D3. 法域を層として分ける

法域に依らない層（`playbook.edn`）と法域ごとに変わる層（`jurisdiction.edn`）を
分け、`:jurisdiction/iso3166-alpha3` で join する。犯罪類型の初動（安全確保 →
停止 → 届出 → 証拠保全）は法域に依らず、緊急番号と被害届の呼称だけが変わる。

この分割により、法域を 1 つ足す作業が「entity を 1 つ足す」に縮む。コードは
変わらない。

### D4. 欠落を沈黙させない

`checklist` は、corpus が持たない法域・犯罪類型について、項目を黙って落とさず
`:checklist/gaps` を返す。`data/coverage.edn` は seeded / universe / 
`:coverage/absent-means :not-yet-collected` を明示的に持ち、整合性検査が
**宣言された件数と実データの件数の一致を強制する**。

黙って部分的な corpus は、下流にとって完全な corpus と見分けがつかない。42 か国
しか持たないものが「載っていない国は該当なし」と読まれると、それは誤りより悪い
（誤りは気付けるが、沈黙は気付けない）。

### D5. 検証状態を fact ごとに持つ

`:fact/verification` は `:verified-primary-source` と `:pending-primary-source` を
区別する。前者だけが「一次情報を実際に参照した」を意味し、整合性検査が出典 URL と
確認日の存在を強制する。46 件が後者であることを README に数字で書いている。

「たぶん正しい」を「確認済み」として下流に流さないための境界。

### D6. .cljc であって .kotoba ではない（現時点）

superproject の runtime 優先順位は `kotoba wasm` > `clojurewasm` > `ClojureScript` >
`nbb` である。この repo の中身はデータ → データの純関数群で、本来 `kotoba/pure` に
収まる形をしている。それでも今 `.cljc` にしているのは、実装上の制約が 2 つある
ため:

1. **fs capability が無い** — corpus は EDN ファイル群で、それを読む経路が
   Kotoba 側に存在しない（superproject CLAUDE.md「今日の既知ブロッカー」4）。
2. **再帰的な値型が無い** — `playbook` の入れ子（vector of strings を持つ map の
   vector）は現在の Kotoba の値では表現できない。migration plan の W4 が計画して
   いるが未着地。

どちらも計画側で解消予定の**現在地の制約**であって、恒久的な設計判断ではない。
W4 の recursive logical value が着地したら再評価する。それまで flat/handle 設計へ
逃げない（計画側が「handles are not the application programming model」と明示して
いる）。

## Consequences

- (+) 法域の追加がデータ 1 行の作業になる。コードレビューが要らない。
- (+) 下流が「決めない」形を保てる。
- (+) 部分カバレッジであることが機械検査で担保される（宣言と実データの一致）。
- (−) 46 件が一次情報未確認のまま。緊急番号を実際の緊急時に使う場合、この corpus
  だけを根拠にできない。解消には各国当局または ITU の一次情報の取り込みが要る。
- (−) 犯罪類型に既知の穴がある（誘拐・拘束、逮捕された場合、交通事故、drink
  spiking、同行者の被害）。`data/coverage.edn` の `:coverage/known-gaps` に列挙済み。
- (−) 日本国籍者以外の領事手続きを持たない。`:consular/nationality` で分岐できる
  形にはしてあるが、データが無い。
