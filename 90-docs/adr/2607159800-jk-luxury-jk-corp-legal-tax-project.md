# ADR-2607159800: JK株式会社の法務・税務管理を jk-luxury org の新規 project `jk-corp` として scaffold

## Status
Accepted

## Context

2026-07-15、JK株式会社(日本法人、京橋税務署管轄、代表河崎純真、旧COMMONS株式会社。
`orgs/personal/facts/orgs.edn` `:org/id "jk-corp"`)宛てに京橋税務署から**源泉所得税及び
復興特別所得税の加算税賦課決定通知書及び納税告知書**(整理番号00256104、告知額計
74,825円〈本税68,825円+不納付加算税6,000円〉、納期限R8.7.27)が届いた。内容を精査した
結果、これは**顧問税理士(税理士法人TOTAL)に支払った報酬(令和7年7-12月分、
741,510円)にかかる源泉徴収漏れ**であり、給与の源泉徴収とは無関係(別紙の
「俸給・給料等(賞与)」欄は完全に空欄)。オーナーから当初示された「社員がいないのに
納税を要求されるのは不適切」という懸念は事実誤認で、専門家報酬への源泉徴収義務
(所得税法204条1項2号)は従業員の有無と無関係に生じる、という整理を行った。

この検討結果とスキャン書類一式は、いったん superproject 内の `orgs/luxury-jk/`
(非west管理の一時フォルダ)に保管した。その後オーナーから「この検討を jk-luxury に
projects としてまとめて整理して」との指示があった。

`jk-luxury` は ADR-2607062300 で登録済みの実在GitHub org(`club-shinshi`・
`net-babiniku`、いずれもprivate)。ただし同ADRの2026-07-06付Amendmentにより、
`club-shinshi`/`net-babiniku` の運営主体は**JK Inc.(BVI法人)**であり、
**JK株式会社(日本法人)ではない**と明記済み——つまり今回の税務通知の当事者
(JK株式会社・日本法人)と、jk-luxury orgの既存2projectの法的運営主体
(JK Inc.・BVI法人)は**別法人**という記録が既にある。

このため、jk-luxury org に本件を project として追加してよいか(法人が異なる資料が
同一org・同一アクセス制御境界に混在することになる)をオーナーに確認した。オーナーは
「jk-luxury org に新規GitHub project(private repo)として scaffold」を選択
(2026-07-15、この法人相違点を提示した上での意思決定)。

## Decision

1. **jk-luxury org 配下に新規private repo `jk-corp` を作成する。** JK株式会社
   (日本法人)の法務・税務・登記等のコーポレート管理資料を格納する場所とし、
   `club-shinshi`/`net-babiniku`(製品コードリポジトリ)とは独立させる。コードは無く、
   ドキュメント(スキャン書類 + 対応方針メモ)のみのリポジトリ。
2. **初回content**は2026-06-26付源泉所得税納税告知書一式(スキャン6点)と対応方針
   README。`orgs/luxury-jk/` に一時保管していた内容をここに移設し、
   `orgs/luxury-jk/` は削除する(superprojectには未コミットのため履歴に残らない)。
3. **`manifest/repos.edn`** に `jk-corp` を `jk-luxury` org の `:extra-projects`
   として登録(`orgs/jk-luxury/jk-corp`、path override無し)。
   `nbb scripts/gen-west-manifest.cljs --entry jk-corp` で `west.yml` に反映
   (当該entryのみの最小diff、ADR-2607022900の標準手順)。
4. **リポジトリのREADME/CLAUDE.mdに、jk-luxury org内での法人相違点を明記する**:
   `club-shinshi`/`net-babiniku` = JK Inc.(BVI)、`jk-corp` = JK株式会社(日本)。
   将来の混同(アクセス権限設計・コンプライアンス監査・契約書の当事者取り違え等)を
   防ぐための恒久的な注記とする。

## Consequences

- jk-luxury orgは今後「JK」ブランドを冠する**2つの異なる法人**(BVIのJK Inc. /
  日本のJK株式会社)のprojectが混在するorgになる。ADR-2607062300が前提としていた
  「GitHub orgの境界=法人の境界」という設計原則は、本ADRにより部分的に後退する。
  アクセス制御・PSP契約・コンプライアンス監査のscopeを検討する際は、**org単位でなく
  repo単位**(`jk-corp` vs `club-shinshi`/`net-babiniku`)で法人を区別する必要がある。
- 実務上は、オーナー個人が実質的に全法人の代表者であり、かつ「JK」関連資料を1か所に
  集約したいという実利的な要請を優先した。この差異はドキュメント(本ADR + repo README)
  で明示する運用でカバーする。
- 将来、日本法人側の資料が増える(登記・他の税務対応・契約書等)場合、`jk-corp` は
  それらの標準的な格納先になる。

## Alternatives Considered

1. **JK株式会社専用の新規GitHub orgを別途作成。** ADRの法人分離原則には最も忠実だが、
   オーナーは今回「jk-luxury に project として」を明示的に選択したため不採用。
   将来、日本法人側の資料/アクセス制御が本格的に増える場合は改めてADR化して分離を
   検討する。
2. **superproject内の `orgs/luxury-jk/`(west非管理の素のフォルダ)のまま整理を続ける。**
   `orgs/kawasakijun`・`orgs/personal` と同様の軽量な運用も可能だったが、オーナーは
   正式なGitHub project化(west登録・独立repo)を選択した。

## References

- `90-docs/adr/2607062300-jk-luxury-org-club-shinshi-net-babiniku-relocation.md`
  (jk-luxury org登録・Amendment: 運営主体はJK Inc. BVI、JK株式会社ではない)
- `orgs/personal/facts/orgs.edn` `:org/id "jk-corp"`(JK株式会社の組織レジストリ
  エントリ。顧問税理士=税理士法人TOTAL 等)
- `orgs/kawasakijun/jk_state.edn`(JK株式会社/旧COMMONS株式会社の経営状態スナップショット)
- `manifest/repos.edn` `:manifest-workflow`(child repos = plain-git、west.yml再生成の
  最小diff原則、ADR-2607022900)
