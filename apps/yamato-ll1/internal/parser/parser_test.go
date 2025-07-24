package parser

import (
	"testing"

	"github.com/junkawasaki/yamato-ll1/internal/ast"
	"github.com/junkawasaki/yamato-ll1/internal/lexer"
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

	verbP, ok := vp.VerbPhrase.(*ast.VerbPhrase)
	if !ok {
		t.Fatalf("vp.VerbPhrase not *ast.VerbPhrase. got=%T", vp.VerbPhrase)
	}

	if verbP.Verb.TokenLiteral() != "見" {
		t.Errorf("verbP.Verb.TokenLiteral() not '見'. got=%s", verbP.Verb.TokenLiteral())
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

func TestModernJapaneseParse(t *testing.T) {
	tests := []struct {
		input             string
		expectedVPSubject string
		expectedVerb      string
		isCopula          bool
	}{
		{"私 は 学生 です", "学生", "です", true},
		{"彼 が ご飯 を 食べ ます", "ご飯", "食べ", false},
	}

	for _, tt := range tests {
		l := lexer.New(tt.input)
		p := New(l)
		sentence := p.ParseSentence()
		checkParserErrors(t, p)

		if len(sentence.Clauses) != 1 {
			t.Fatalf("len(Clauses) not 1. got=%d", len(sentence.Clauses))
		}

		clause, ok := sentence.Clauses[0].(*ast.Clause)
		if !ok {
			t.Fatalf("Clauses[0] not *ast.Clause.")
		}

		vp, ok := clause.VP.(*ast.VP)
		if !ok {
			t.Fatalf("clause.VP not *ast.VP.")
		}

		if tt.isCopula {
			cp, ok := vp.VerbPhrase.(*ast.CopulaPhrase)
			if !ok {
				t.Fatalf("vp.VerbPhrase not *ast.CopulaPhrase.")
			}
			if cp.Subject.String() != tt.expectedVPSubject {
				t.Errorf("cp.Subject wrong. want=%q, got=%q", tt.expectedVPSubject, cp.Subject.String())
			}
		} else {
			// verb phrase test
			verbP, ok := vp.VerbPhrase.(*ast.VerbPhrase)
			if !ok {
				t.Fatalf("vp.VerbPhrase not *ast.VerbPhrase.")
			}
			if verbP.Verb.String() != tt.expectedVerb {
				t.Errorf("verbP.Verb.String() wrong. want=%q, got=%q", tt.expectedVerb, verbP.Verb.String())
			}
		}
	}
}
