package lexer

import (
	"testing"
)

func TestNextToken(t *testing.T) {
	input := `人 が 馬 を 見 き`

	tests := []struct {
		expectedType    TokenType
		expectedLiteral string
	}{
		{NOUN, "人"},
		{CASE_PARTICLE, "が"},
		{NOUN, "馬"},
		{CASE_PARTICLE, "を"},
		{NOUN, "見"},
		{AUX, "き"},
		{EOF, ""},
	}

	l := New(input)

	for i, tt := range tests {
		tok := l.NextToken()

		if tok.Type != tt.expectedType {
			t.Fatalf("tests[%d] - tokentype wrong. expected=%q, got=%q",
				i, tt.expectedType, tok.Type)
		}

		if tok.Literal != tt.expectedLiteral {
			t.Fatalf("tests[%d] - literal wrong. expected=%q, got=%q",
				i, tt.expectedLiteral, tok.Literal)
		}
	}
}

func TestNextTokenModern(t *testing.T) {
	// 「。」を分離
	input := `私 は 学生 です 。 彼 が ご飯 を 食べ ます た`

	tests := []struct {
		expectedType    TokenType
		expectedLiteral string
	}{
		{NOUN, "私"},
		{CASE_PARTICLE, "は"},
		{NOUN, "学生"},
		{COPULA, "です"},
		{NOUN, "。"},
		{NOUN, "彼"},
		{CASE_PARTICLE, "が"},
		{NOUN, "ご飯"},
		{CASE_PARTICLE, "を"},
		{NOUN, "食べ"},
		{AUX, "ます"},
		{AUX, "た"},
		{EOF, ""},
	}

	l := New(input)

	for i, tt := range tests {
		tok := l.NextToken()

		if tok.Type != tt.expectedType {
			t.Fatalf("tests[%d] - tokentype wrong. expected=%q, got=%q",
				i, tt.expectedType, tok.Type)
		}

		if tok.Literal != tt.expectedLiteral {
			t.Fatalf("tests[%d] - literal wrong. expected=%q, got=%q",
				i, tt.expectedLiteral, tok.Literal)
		}
	}
}
