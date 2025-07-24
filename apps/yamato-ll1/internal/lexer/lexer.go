package lexer

import "unicode/utf8"

type Lexer struct {
	input        string
	position     int  // 現在の文字の位置
	readPosition int  // 次の文字の位置
	ch           rune // 現在検査中の文字 (rune に変更)
}

func New(input string) *Lexer {
	l := &Lexer{input: input}
	l.readChar()
	return l
}

func (l *Lexer) readChar() {
	if l.readPosition >= len(l.input) {
		l.ch = 0 // 0はNUL文字を表し、ファイルの終端を示す
	} else {
		// UTF-8文字をデコードしてruneとして読み込む
		r, size := utf8.DecodeRuneInString(l.input[l.readPosition:])
		l.ch = r
		l.position = l.readPosition
		l.readPosition += size
	}
}

func (l *Lexer) NextToken() Token {
	var tok Token

	l.skipWhitespace()

	// 'が', 'を', 'に', 'き', 'む', 'ず' は1文字のトークンなので、ここで処理する
	switch l.ch {
	case 'が', 'を', 'に', 'き', 'む', 'ず':
		// keywordsマップを使ってTokenTypeを決定する
		if tokenType, ok := keywords[string(l.ch)]; ok {
			tok = Token{Type: tokenType, Literal: string(l.ch)}
		} else {
			// このパスは通常通らないはず
			tok = newToken(ILLEGAL, l.ch)
		}
	case 0:
		tok.Literal = ""
		tok.Type = EOF
	default:
		if isLetter(l.ch) {
			tok.Literal = l.readIdentifier()
			// IDENTトークンタイプを追加し、キーワードか判定する
			tok.Type = LookupIdent(tok.Literal)
			return tok
		} else {
			tok = newToken(ILLEGAL, l.ch)
		}
	}

	l.readChar()
	return tok
}

func (l *Lexer) skipWhitespace() {
	for l.ch == ' ' || l.ch == '\t' || l.ch == '\n' || l.ch == '\r' {
		l.readChar()
	}
}

func (l *Lexer) readIdentifier() string {
	position := l.position
	for isLetter(l.ch) {
		l.readChar()
	}
	return l.input[position:l.position]
}

func isLetter(ch rune) bool {
	// ひらがな、カタカナ、漢字の範囲を判定に加える
	return ('a' <= ch && ch <= 'z') || ('A' <= ch && ch <= 'Z') || ch == '_' ||
		(ch >= 'ぁ' && ch <= 'ん') || (ch >= 'ァ' && ch <= 'ン') || (ch >= '\u4e00' && ch <= '\u9faf')
}

func newToken(tokenType TokenType, ch rune) Token {
	return Token{Type: tokenType, Literal: string(ch)}
}

// LookupIdent は識別子がキーワードかどうかを判定します。
func LookupIdent(ident string) TokenType {
	if tok, ok := keywords[ident]; ok {
		return tok
	}
	// NOUN, VERB, ADJ の判定はパーサで行うため、ここでは汎用的なIDENTを返す
	// ただし、今回は簡単化のためにNOUNにフォールバックする
	return NOUN
} 