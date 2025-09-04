package lexer

// TokenType はトークンの種類を表す文字列です。
type TokenType string

// Token は字句解析器がソースコードから切り出したトークンを表します。
type Token struct {
	Type    TokenType
	Literal string
}

const (
	ILLEGAL = "ILLEGAL"
	EOF     = "EOF"

	// 識別子
	NOUN = "NOUN" // 名詞
	VERB = "VERB" // 動詞
	ADJ  = "ADJ"  // 形容詞

	// 助詞・助動詞
	CASE_PARTICLE = "CASE_PARTICLE" // 格助詞
	AUX           = "AUX"           // 助動詞
	COPULA        = "COPULA"        // コピュラ

	// 区切り文字など
	GA = "が"
	WO = "を"
	NI = "に"
	KI = "き"
	MU = "む"
	ZU = "ず"
	// 現代日本語拡張
	HA   = "は"
	NO   = "の"
	DESU = "です"
	MASU = "ます"
	TA   = "た"
)

var keywords = map[string]TokenType{
	"が":  CASE_PARTICLE,
	"を":  CASE_PARTICLE,
	"に":  CASE_PARTICLE,
	"は":  CASE_PARTICLE,
	"の":  CASE_PARTICLE,
	"き":  AUX,
	"む":  AUX,
	"ず":  AUX,
	"ます": AUX,
	"た":  AUX,
	"です": COPULA,
}
