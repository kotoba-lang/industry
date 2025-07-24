package parser

import (
	"fmt"

	"github.com/junkawasaki/yamato-ll1/internal/ast"
	"github.com/junkawasaki/yamato-ll1/internal/lexer"
)

type Parser struct {
	l      *lexer.Lexer
	errors []string

	curToken  lexer.Token
	peekToken lexer.Token
}

func New(l *lexer.Lexer) *Parser {
	p := &Parser{
		l:      l,
		errors: []string{},
	}

	// 2つのトークンを読み込み、curTokenとpeekTokenの両方をセットする
	p.nextToken()
	p.nextToken()

	return p
}

func (p *Parser) Errors() []string {
	return p.errors
}

func (p *Parser) nextToken() {
	p.curToken = p.peekToken
	p.peekToken = p.l.NextToken()
}

func (p *Parser) ParseSentence() *ast.Sentence {
	sentence := &ast.Sentence{}
	sentence.Clauses = []ast.Statement{}

	for !p.curTokenIs(lexer.EOF) {
		stmt := p.parseClause()
		if stmt != nil {
			sentence.Clauses = append(sentence.Clauses, stmt)
		}
		p.nextToken()
	}

	return sentence
}

func (p *Parser) parseClause() *ast.Clause {
	// ログを削除
	// fmt.Printf("parseClause start: curToken=%+v\n", p.curToken)

	// 1. NPを解析
	clause := &ast.Clause{Token: p.curToken}
	clause.NP = p.parseNP()
	if clause.NP == nil {
		return nil
	}

	// 2. VPの開始（動詞）へトークンを進める
	p.nextToken()

	// 3. VPを解析
	clause.VP = p.parseVP()
	if clause.VP == nil {
		return nil
	}

	// Let the caller (ParseSentence) advance the token.
	// No nextToken() call at the end of this function.
	return clause
}

func (p *Parser) parseNP() ast.Expression {
	if !p.curTokenIs(lexer.NOUN) {
		p.errors = append(p.errors, fmt.Sprintf("expected noun but got %s", p.curToken.Literal))
		return nil
	}

	np := &ast.NP{Token: p.curToken}
	np.Base = &ast.Noun{Token: p.curToken, Value: p.curToken.Literal}

	// Optional: caseParticle
	if p.peekTokenIs(lexer.CASE_PARTICLE) {
		p.nextToken()
		// ここで格助詞をNPノードに保存すべきだが、一旦スキップ
	}

	// relClauseのループは一旦削除

	return np
}

func (p *Parser) parseRelClause() ast.Expression {
	switch p.curToken.Type {
	case lexer.ADJ:
		return p.parseAdjPhrase()
	case lexer.NOUN:
		// NOUNで始まる場合は、それが内包する節の主語であるため、
		// clause を再帰的に解析する
		return p.parseClause()
	default:
		p.errors = append(p.errors, fmt.Sprintf("unexpected token for relClause: %s", p.curToken.Literal))
		return nil
	}
}

func (p *Parser) parseAdjPhrase() ast.Expression {
	adjPhrase := &ast.AdjPhrase{Token: p.curToken}
	adjPhrase.Adjective = &ast.Adjective{Token: p.curToken, Value: p.curToken.Literal}

	if p.peekTokenIs(lexer.AUX) {
		p.nextToken()
		adjPhrase.Auxiliary = &ast.Aux{Token: p.curToken, Value: p.curToken.Literal}
	}

	return adjPhrase
}

func (p *Parser) parseVP() ast.Expression {
	vp := &ast.VP{Token: p.curToken}

	// Optional object NP
	if p.curTokenIs(lexer.NOUN) && p.peekTokenIs(lexer.CASE_PARTICLE) {
		vp.Object = p.parseNP().(*ast.NP)
		p.nextToken()
	}

	// verbPhrase or copulaPhrase
	if p.peekTokenIs(lexer.COPULA) {
		vp.VerbPhrase = p.parseCopulaPhrase()
	} else {
		vp.VerbPhrase = p.parseVerbPhrase()
	}

	return vp
}

func (p *Parser) parseVerbPhrase() ast.Expression {
	phrase := &ast.VerbPhrase{Token: p.curToken}

	if !p.curTokenIs(lexer.NOUN) { // Verbs are tokenized as NOUNs
		return nil
	}
	phrase.Verb = &ast.Verb{Token: p.curToken, Value: p.curToken.Literal}

	for p.peekTokenIs(lexer.AUX) {
		p.nextToken()
		aux := &ast.Aux{Token: p.curToken, Value: p.curToken.Literal}
		phrase.Auxiliaries = append(phrase.Auxiliaries, aux)
	}
	return phrase
}

func (p *Parser) parseCopulaPhrase() ast.Expression {
	phrase := &ast.CopulaPhrase{Token: p.curToken}

	if !(p.curTokenIs(lexer.NOUN) || p.curTokenIs(lexer.ADJ)) {
		return nil
	}
	phrase.Subject = &ast.Noun{Token: p.curToken, Value: p.curToken.Literal} // Simplified

	p.nextToken() // move to COPULA
	if !p.curTokenIs(lexer.COPULA) {
		return nil
	}
	copulaToken := p.curToken
	phrase.Copula = &copulaToken

	return phrase
}

func (p *Parser) curTokenIs(t lexer.TokenType) bool {
	return p.curToken.Type == t
}

func (p *Parser) peekTokenIs(t lexer.TokenType) bool {
	return p.peekToken.Type == t
}
