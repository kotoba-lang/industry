package ast

import (
	"bytes"

	"github.com/junkawasaki/yamato-ll1/internal/lexer"
)

// Node はASTのすべてのノードが実装するインターフェースです。
type Node interface {
	TokenLiteral() string // デバッグやテストのためにノードに関連するトークンのリテラルを返す
	String() string       // ASTノードを文字列として表現する
}

// Statement は文を表すノードです。
type Statement interface {
	Node
	statementNode()
}

// Expression は式を表すノードです。
type Expression interface {
	Node
	expressionNode()
}

// Sentence はプログラム（一連の文）全体を表すASTのルートノードです。
type Sentence struct {
	Clauses []Statement
}

func (s *Sentence) TokenLiteral() string {
	if len(s.Clauses) > 0 {
		return s.Clauses[0].TokenLiteral()
	}
	return ""
}

func (s *Sentence) String() string {
	var out bytes.Buffer
	for _, stmt := range s.Clauses {
		out.WriteString(stmt.String())
	}
	return out.String()
}

// Clause は節を表します。
type Clause struct {
	Token lexer.Token // その節の最初のトークン（デバッグ用）
	NP    Expression
	VP    Expression
}

func (c *Clause) statementNode()       {}
func (c *Clause) expressionNode()      {} // relClauseとして扱われるために追加
func (c *Clause) TokenLiteral() string { return c.Token.Literal }
func (c *Clause) String() string {
	var out bytes.Buffer
	out.WriteString("(")
	out.WriteString(c.NP.String())
	out.WriteString(" ")
	out.WriteString(c.VP.String())
	out.WriteString(")")
	return out.String()
}

// Noun は名詞を表す式です。
type Noun struct {
	Token lexer.Token // a lexer.NOUN token
	Value string
}

func (n *Noun) expressionNode()      {}
func (n *Noun) TokenLiteral() string { return n.Token.Literal }
func (n *Noun) String() string       { return n.Token.Literal }

// Verb は動詞を表す式です。
type Verb struct {
	Token lexer.Token // a lexer.VERB token
	Value string
}

func (v *Verb) expressionNode()      {}
func (v *Verb) TokenLiteral() string { return v.Token.Literal }
func (v *Verb) String() string       { return v.Token.Literal }

// Adjective は形容詞を表す式です。
type Adjective struct {
	Token lexer.Token // a lexer.ADJ token
	Value string
}

func (a *Adjective) expressionNode()      {}
func (a *Adjective) TokenLiteral() string { return a.Token.Literal }
func (a *Adjective) String() string       { return a.Token.Literal }

// CaseParticle は格助詞を表す式です。
type CaseParticle struct {
	Token lexer.Token // 'が', 'を', 'に'
	Value string
}

func (cp *CaseParticle) expressionNode()      {}
func (cp *CaseParticle) TokenLiteral() string { return cp.Token.Literal }
func (cp *CaseParticle) String() string       { return cp.Token.Literal }

// Aux は助動詞を表す式です。
type Aux struct {
	Token lexer.Token // 'き', 'む', 'ず'
	Value string
}

func (a *Aux) expressionNode()      {}
func (a *Aux) TokenLiteral() string { return a.Token.Literal }
func (a *Aux) String() string       { return a.Token.Literal }

// NP は名詞句(Noun Phrase)を表します。 baseNP npTail に対応します。
type NP struct {
	Token lexer.Token  // 名詞句の最初のトークン
	Base  Expression   // BaseNP (Noun or a more complex one in future)
	Tail  []Expression // NPTail, which consists of RelClauses
}

func (np *NP) expressionNode()      {}
func (np *NP) TokenLiteral() string { return np.Token.Literal }
func (np *NP) String() string {
	var out bytes.Buffer
	out.WriteString(np.Base.String())
	for _, rel := range np.Tail {
		out.WriteString(" " + rel.String())
	}
	return out.String()
}

// VP は動詞句(Verb Phrase)を表します。
type VP struct {
	Token       lexer.Token // 動詞句の最初のトークン (the verb or noun)
	Object      *NP         // 目的語などのNP (optional)
	Verb        Expression
	Auxiliaries []Expression  // 助動詞のリスト
	Particle    *CaseParticle // 末尾の格助詞 (optional)
}

func (vp *VP) expressionNode()      {}
func (vp *VP) TokenLiteral() string { return vp.Token.Literal }
func (vp *VP) String() string {
	var out bytes.Buffer
	out.WriteString("(")
	if vp.Object != nil {
		out.WriteString(vp.Object.String() + " ")
	}
	out.WriteString(vp.Verb.String())
	for _, aux := range vp.Auxiliaries {
		out.WriteString(" " + aux.String())
	}
	if vp.Particle != nil {
		out.WriteString(" " + vp.Particle.String())
	}
	out.WriteString(")")
	return out.String()
}

// AdjPhrase は形容詞句を表します。
type AdjPhrase struct {
	Token     lexer.Token
	Adjective Expression
	Auxiliary *Aux // Optional
}

func (ap *AdjPhrase) expressionNode()      {}
func (ap *AdjPhrase) TokenLiteral() string { return ap.Token.Literal }
func (ap *AdjPhrase) String() string {
	var out bytes.Buffer
	out.WriteString(ap.Adjective.String())
	if ap.Auxiliary != nil {
		out.WriteString(ap.Auxiliary.String())
	}
	return out.String()
}

// RelClause は連体修飾節を表します。
// 今回の文法では adjPhrase | clause なので、Expressionとして扱うことができます。
// そのため、特定の構造体は不要で、パーサがどちらかのExpressionを返すようにします。
