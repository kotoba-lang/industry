以下は、大和言葉（古日本語）期の文法を題材に、LL(1)／手書き再帰下降パーサ用に設計した簡易文法の例です。対象とする現象を限定しつつ、
	1.	左再帰を除去
	2.	左ファクタリングを施し
	3.	各非終端記号が先読み１トークンで分岐可能

となるよう構成しています。

⸻

1. 対象とする構造
	•	主節：SOV 順序
	•	格助詞：主格〈が〉、対格〈を〉、与格〈に〉
	•	助動詞：完了〈き〉、推量〈む〉、打消〈ず〉
	•	連体修飾節：名詞を前置修飾
	•	形容詞活用：ク活用（例：『あら–しく』）
	•	動詞活用：四段活用のみ（例：『書く–書か–書き』）

※ 連用・未然・終止形の扱いは最小限に絞っています。

⸻

2. 高レベル文法（左再帰あり）

〈Sentence〉   → 〈Clause〉+
〈Clause〉     → 〈NP〉 〈VP〉
〈NP〉         → 〈NP〉 〈RelClause〉        // 左再帰！
                 | 〈BaseNP〉
〈BaseNP〉     → 〈Noun〉 〈CaseParticle〉?
〈RelClause〉  → 〈AdjPhrase〉 | 〈Clause〉
〈VP〉         → 〈Verb〉 〈AuxList〉  〈CaseParticle〉? 
〈AuxList〉    → 〈Aux〉 〈AuxList〉      // 左再帰
                 | ε
〈AdjPhrase〉  → 〈Adjective〉 〈Aux〉?
〈CaseParticle〉 → 'が' | 'を' | 'に'
〈Aux〉        → 'き' | 'む' | 'ず'
〈Noun〉       → [語幹]
〈Verb〉       → [語幹]
〈Adjective〉  → [語幹]


⸻

3. 左再帰除去＋左ファクタリング

上記の〈NP〉と〈AuxList〉に含まれる左再帰を次のように除去します。

〈NP〉   → 〈BaseNP〉 〈NP′〉
〈NP′〉  → 〈RelClause〉 〈NP′〉 | ε

〈AuxList〉 → 〈Aux〉 〈AuxList〉 | ε
※ ここは既に右再帰なので変更不要

さらに、〈RelClause〉内で〈AdjPhrase〉と〈Clause〉を先頭が識別可能となるように分け、左ファクタリングを行います。

〈RelClause〉 → 〈AdjPhrase〉 | 〈Clause〉
※ 〈AdjPhrase〉は形容詞系で始まり、〈Clause〉は名詞または連体節なので先読み１つで判別可


⸻

4. 最終版：ANTLR風 LL(1) 文法

grammar ClassicalJapanese;

options { k=1; }   // 先読み 1 トークン

// --- トップレベル ---
sentence
  : clause+ EOF
  ;

// --- 節・句構造 ---
clause
  : np vp
  ;

np
  : baseNP npTail
  ;

npTail
  : relClause npTail
  | /* ε */
  ;

baseNP
  : NOUN caseParticle?
  ;

relClause
  : adjPhrase
  | clause
  ;

// --- 動詞句・助動詞列 ---
vp
  : VERB auxList caseParticle?
  ;

auxList
  : AUX auxList
  | /* ε */
  ;

// --- 形容詞句 ---
adjPhrase
  : ADJ AUX?  // 例: あらしく、あらしき
  ;

// --- 形態素 ---
caseParticle
  : 'が'
  | 'を'
  | 'に'
  ;

AUX
  : 'き'    // 完了
  | 'む'    // 推量
  | 'ず'    // 打消
  ;

NOUN
  : /* 古日本語の名詞語幹 */
    [ぁ-ん一-龥]+
  ;

VERB
  : /* 古日本語の動詞語幹 */
    [ぁ-ん一-龥]+
  ;

// 古形容詞（ク活用）の語幹も ADJ としてまとめる
ADJ
  : [ぁ-ん一-龥]+
  ;

WS
  : [ \t\r\n]+ -> skip
  ;


⸻

解説
	•	npTail：連体修飾節の繰り返しを右再帰的に実装し、左再帰を完全に除去。
	•	auxList：助動詞の連鎖は右再帰で問題なく、LL(1) のまま維持。
	•	relClause：形容詞修飾と名詞句の節修飾を分離し、先読み1文字で判別可能に。
	•	options { k=1; }：LL(1) であることを明示。

この文法なら、トークン先読み1で常に適用すべき規則が一意に決まり、手書き再帰下降パーサ／ANTLR 等でそのまま実装できます。

---

5. 現代日本語への拡張

古典日本語パーサーをベースに、以下の現代日本語の要素を扱えるよう文法を拡張する。

-   **助詞**: 主題「は」、所有「の」
-   **丁寧形**: 助動詞「ます」、コピュラ「です」
-   **時制**: 過去形助動詞「た」

拡張後のLL(1)文法は以下の通り。

```antlr
grammar ModernJapanese;

options { k=1; }

// --- トップレベル ---
sentence
  : clause+ EOF
  ;

// --- 節・句構造 ---
clause
  : np vp
  ;

np
  : baseNP npTail
  ;

npTail
  : relClause npTail
  | /* ε */
  ;

baseNP
  : NOUN caseParticle?
  ;

relClause
  : adjPhrase
  | clause
  ;

// --- 動詞句・助動詞列 ---
vp
  : (np)? (verbPhrase | copulaPhrase)
  ;

verbPhrase
  : VERB auxList
  ;

copulaPhrase
  : (NOUN | ADJ) COPULA
  ;

auxList
  : AUX auxList
  | /* ε */
  ;

// --- 形容詞句 ---
adjPhrase
  : ADJ
  ;

// --- 形態素 ---
caseParticle
  : 'が' | 'を' | 'に' | 'は' | 'の'
  ;

AUX
  : 'き' | 'む' | 'ず' // 古典
  | 'ます' | 'た'      // 現代
  ;

COPULA
  : 'です'
  ;

NOUN      : [ぁ-ん一-龥]+ ;
VERB      : [ぁ-ん一-龥]+ ;
ADJ       : [ぁ-ん一-龥]+ ;
```

この文法に基づき、レキサー、AST、パーサーを段階的に拡張していく。