package lexer

import (
	"bufio"
	"strings"
)

type Lexer struct {
	scanner *bufio.Scanner
	done    bool
}

func New(input string) *Lexer {
	scanner := bufio.NewScanner(strings.NewReader(input))
	scanner.Split(bufio.ScanWords)
	return &Lexer{scanner: scanner}
}

func (l *Lexer) NextToken() Token {
	if l.done {
		return Token{Type: EOF, Literal: ""}
	}

	if l.scanner.Scan() {
		word := l.scanner.Text()
		return Token{
			Type:    LookupIdent(word),
			Literal: word,
		}
	}

	if err := l.scanner.Err(); err != nil {
		// Handle error, for now, we'll just signal EOF
		l.done = true
		return Token{Type: EOF, Literal: ""}
	}

	l.done = true
	return Token{Type: EOF, Literal: ""}
}

func LookupIdent(ident string) TokenType {
	if tok, ok := keywords[ident]; ok {
		return tok
	}
	return NOUN
}
