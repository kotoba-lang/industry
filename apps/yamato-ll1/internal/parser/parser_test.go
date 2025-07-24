package parser

import (
	"github.com/junkawasaki/yamato-ll1/internal/ast"
	"github.com/junkawasaki/yamato-ll1/internal/lexer"
	"testing"
)

func TestParseSentence(t *testing.T) {
	l := lexer.New("人 が 馬 を 見 き")
	p := New(l)

	sentence := p.ParseSentence()
	checkParserErrors(t, p)

	if sentence == nil {
		t.Fatalf("ParseSentence() returned nil")
	}
	if len(sentence.Clauses) != 1 {
		t.Fatalf("sentence.Clauses does not contain 1 statement. got=%d", len(sentence.Clauses))
	}

	stmt, ok := sentence.Clauses[0].(*ast.Clause)
	if !ok {
		t.Fatalf("sentence.Clauses[0] is not *ast.Clause. got=%T", sentence.Clauses[0])
	}

	// NPのテスト
	np, ok := stmt.NP.(*ast.NP)
	if !ok {
		t.Fatalf("stmt.NP is not *ast.NP. got=%T", stmt.NP)
	}
	if np.Base.TokenLiteral() != "人" {
		t.Errorf("np.Base.TokenLiteral() not '人'. got=%s", np.Base.TokenLiteral())
	}

	// VPのテスト
	vp, ok := stmt.VP.(*ast.VP)
	if !ok {
		t.Fatalf("stmt.VP is not *ast.VP. got=%T", stmt.VP)
	}
	if vp.Verb.TokenLiteral() != "見" {
		t.Errorf("vp.Verb.TokenLiteral() not '見'. got=%s", vp.Verb.TokenLiteral())
	}
}

func checkParserErrors(t *testing.T, p *Parser) {
	errors := p.Errors()
	if len(errors) == 0 {
		return
	}

	t.Errorf("parser has %d errors", len(errors))
	for _, msg := range errors {
		t.Errorf("parser error: %q", msg)
	}
	t.FailNow()
} 