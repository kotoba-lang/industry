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