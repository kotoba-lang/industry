//! The parser for the `kotoba` language.
//! It implements a handwritten recursive descent parser.

// The AST definitions are kept as they are, as they correctly represent the language structure.
// The main change is to replace the `nom` based parsing functions with a manual implementation.

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Location {
    pub line: usize,
    pub column: usize,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Span {
    pub start: Location,
    pub end: Location,
}

#[derive(Debug, PartialEq, Clone)]
pub struct Expression {
    pub kind: ExpressionKind,
    pub span: Span,
}

#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    Ku,
    Ze(Box<Type>, Box<Type>),
    Ident(String),
    App(Box<Type>, Vec<Type>),
    Expr(Box<Expression>),
    En(Box<Type>, Box<Type>, Box<Type>),
    Unit,
    Func(Box<Type>, Box<Type>),
    Pi {
        binder_name: String,
        binder_type: Box<Type>,
        return_type: Box<Type>,
    },
}

#[derive(Debug, PartialEq, Clone)]
pub struct Parameter {
    pub name: String,
    pub type_annotation: Type,
}

#[derive(Debug, PartialEq, Clone)]
pub enum Pattern {
    IntegerLiteral(i64),
    IntervalLiteral(String),
    Wildcard,
    Identifier(String),
    Constructor(String, Vec<Pattern>),
}

#[derive(Debug, PartialEq, Clone)]
pub struct OuArm {
    pub pattern: Pattern,
    pub body: Expression,
}

#[derive(Debug, PartialEq, Clone)]
pub enum ConstructorDef {
    Point {
        name: String,
        fields: Vec<Type>,
    },
    Path {
        name: String,
        path_type: Type,
        body: Expression,
    },
}

#[derive(Debug, PartialEq, Clone)]
pub enum ExpressionKind {
    Kan {
        params: Vec<Parameter>,
        body: Box<Expression>,
    },
    BinaryOp {
        op: Operator,
        lhs: Box<Expression>,
        rhs: Box<Expression>,
    },
    Refl(Box<Expression>),
    Glue {
        base: Box<Expression>,
        boundary: Box<Expression>,
        equivalence: Box<Expression>,
    },
    Unglue {
        value: Box<Expression>,
    },
    Let {
        name: String,
        type_annotation: Option<Box<Type>>,
        value: Box<Expression>,
        body: Box<Expression>,
    },
    If {
        condition: Box<Expression>,
        then_branch: Box<Expression>,
        else_branch: Box<Expression>,
    },
    Identifier(String),
    IntegerLiteral(i64),
    MethodCall {
        variable: Box<Expression>,
        method: String,
        args: Vec<Expression>,
    },
    Pipe {
        lhs: Box<Expression>,
        rhs: Box<Expression>,
    },
    Ou {
        expression: Box<Expression>,
        arms: Vec<OuArm>,
    },
    Zo(String),
    Unit,
}

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Operator {
    Add,
    Subtract,
    Multiply,
    Divide,
    Equals,
    NotEquals,
    LessThan,
    GreaterThan,
    LessThanOrEqual,
    GreaterThanOrEqual,
}

#[derive(Debug, PartialEq, Clone)]
pub enum Statement {
    Shiki {
        variable_name: String,
        type_annotation: Type,
        value: Expression,
    },
    Gyo {
        name: String,
        params: Vec<Parameter>,
        constructors: Vec<ConstructorDef>,
    },
    Rin {
        name: String,
        generics: Vec<String>,
        params: Vec<Parameter>,
        return_type: Type,
        body: Expression,
    },
}

// ---- Hand-written Parser Implementation ----

// 1. Lexer (Tokenizer)
// We will skip a separate lexer for now and do lexical analysis on the fly
// in the parser for simplicity, as is common in recursive descent parsers.

// 2. Parser
pub type ParseResult<T> = Result<T, String>;

#[derive(Clone)]
pub struct Parser<'a> {
    chars: std::iter::Peekable<std::str::Chars<'a>>,
    line: usize,
    column: usize,
}

impl<'a> Parser<'a> {
    pub fn new(input: &'a str) -> Self {
        Parser {
            chars: input.chars().peekable(),
            line: 1,
            column: 1,
        }
    }

    fn next_char(&mut self) -> Option<char> {
        let next = self.chars.next();
        if let Some(c) = next {
            if c == '\n' {
                self.line += 1;
                self.column = 1;
            } else {
                self.column += 1;
            }
        }
        next
    }

    fn peek(&mut self) -> Option<&char> {
        self.chars.peek()
    }

    fn current_location(&self) -> Location {
        Location {
            line: self.line,
            column: self.column,
        }
    }

    fn consume_whitespace(&mut self) {
        while let Some(&c) = self.peek() {
            if c.is_whitespace() {
                self.next_char();
            } else {
                break;
            }
        }
    }

    fn parse_identifier(&mut self) -> ParseResult<String> {
        self.consume_whitespace();
        let mut ident = String::new();
        while let Some(&c) = self.peek() {
            if c.is_alphanumeric() || c == '_' {
                ident.push(self.next_char().unwrap());
            } else {
                break;
            }
        }
        if ident.is_empty() {
            Err("Expected an identifier".to_string())
        } else {
            Ok(ident)
        }
    }
    
    fn parse_integer(&mut self) -> ParseResult<(i64, Span)> {
        self.consume_whitespace();
        let start = self.current_location();
        let mut num_str = String::new();
        while let Some(&c) = self.peek() {
            if c.is_digit(10) {
                num_str.push(self.next_char().unwrap());
            } else {
                break;
            }
        }
        let end = self.current_location();
        if num_str.is_empty() {
            Err("Expected an integer".to_string())
        } else {
            num_str
                .parse::<i64>()
                .map_err(|_| "Invalid integer".to_string())
                .map(|val| (val, Span { start, end }))
        }
    }

    fn parse_primary_expression(&mut self) -> ParseResult<Expression> {
        self.consume_whitespace();
        let start = self.current_location();

        let next_char = self.peek().ok_or("Unexpected end of input")?;

        if next_char.is_alphabetic() {
            let ident = self.parse_identifier()?;
            let end = self.current_location();
            let span = Span { start, end };

            return match ident.as_str() {
                "i0" | "i1" => Ok(Expression {
                    kind: ExpressionKind::Zo(ident),
                    span,
                }),
                "refl" => self.parse_refl_expression(),
                "glue" => self.parse_glue_expression(),
                "unglue" => self.parse_unglue_expression(),
                "kan" => self.parse_kan_expression(),
                "ou" => self.parse_ou_expression(),
                "let" => self.parse_let_expression(),
                "if" => self.parse_if_expression(),
                _ => Ok(Expression {
                    kind: ExpressionKind::Identifier(ident),
                    span,
                }),
            };
        }

        if next_char.is_digit(10) {
            return self.parse_integer().map(|(lit, span)| {
                Expression {
                    kind: ExpressionKind::IntegerLiteral(lit),
                    span,
                }
            });
        }

        if *next_char == '(' {
            self.next_char();
            self.consume_whitespace();
            if self.peek() == Some(&')') {
                self.next_char();
                let end = self.current_location();
                return Ok(Expression {
                    kind: ExpressionKind::Unit,
                    span: Span { start, end },
                });
            }
            let expr = self.parse_expression()?;
            self.expect_token(')')?;
            return Ok(expr);
        }

        Err(format!("Unexpected character: {}", next_char))
    }

    fn parse_postfix_expression(&mut self) -> ParseResult<Expression> {
        let mut expr = self.parse_primary_expression()?;
        loop {
            self.consume_whitespace();
            if self.peek() == Some(&'.') {
                let start_span = expr.span;
                self.next_char(); // consume '.'
                let method = self.parse_identifier()?;
                self.expect_token('(')?;
                let mut args = Vec::new();
                self.consume_whitespace();
                if self.peek() != Some(&')') {
                    loop {
                        let arg = self.parse_expression()?;
                        args.push(arg);
                        self.consume_whitespace();
                        if self.peek() == Some(&')') {
                            break;
                        }
                        self.expect_token(',')?;
                    }
                }
                self.expect_token(')')?;
                let end_location = self.current_location();
                expr = Expression {
                    kind: ExpressionKind::MethodCall {
                        variable: Box::new(expr),
                        method,
                        args,
                    },
                    span: Span {
                        start: start_span.start,
                        end: end_location,
                    },
                };
            } else {
                break;
            }
        }
        Ok(expr)
    }

    fn parse_refl_expression(&mut self) -> ParseResult<Expression> {
        // "refl" is consumed in primary, start should be passed in or recalculated.
        // For now, let's make a rough approximation.
        let start = self.current_location();
        self.expect_token('(')?;
        let expr = self.parse_expression()?;
        self.expect_token(')')?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Refl(Box::new(expr)),
            span: Span { start, end },
        })
    }

    fn parse_glue_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        self.expect_token('(')?;
        let base = self.parse_expression()?;
        self.expect_token(',')?;
        let boundary = self.parse_expression()?;
        self.expect_token(',')?;
        let equivalence = self.parse_expression()?;
        self.expect_token(')')?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Glue {
                base: Box::new(base),
                boundary: Box::new(boundary),
                equivalence: Box::new(equivalence),
            },
            span: Span { start, end },
        })
    }
    fn parse_unglue_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        self.expect_token('(')?;
        let value = self.parse_expression()?;
        self.expect_token(')')?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Unglue {
                value: Box::new(value),
            },
            span: Span { start, end },
        })
    }
    fn parse_kan_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        self.expect_token('(')?;
        let mut params = Vec::new();
        if self.peek() != Some(&')') {
            loop {
                params.push(self.parse_parameter()?);
                if self.peek() == Some(&')') {
                    break;
                }
                self.expect_token(',')?;
            }
        }
        self.expect_token(')')?;
        self.expect_token('=')?;
        self.expect_token('>')?;
        let body = self.parse_expression()?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Kan {
                params,
                body: Box::new(body),
            },
            span: Span { start, end },
        })
    }
    fn parse_ou_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        let expression = self.parse_expression()?;
        self.expect_token('{')?;
        let mut arms = Vec::new();
        if self.peek() != Some(&'}') {
            loop {
                arms.push(self.parse_ou_arm()?);
                if self.peek() == Some(&'}') {
                    break;
                }
                self.expect_token(',')?;
            }
        }
        self.expect_token('}')?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Ou {
                expression: Box::new(expression),
                arms,
            },
            span: Span { start, end },
        })
    }
    fn parse_let_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        let name = self.parse_identifier()?;
        let mut type_annotation = None;
        self.consume_whitespace();
        if self.peek() == Some(&':') {
            self.next_char();
            type_annotation = Some(Box::new(self.parse_type()?));
        }
        self.expect_token('=')?;
        let value = self.parse_expression()?;
        let in_kw = self.parse_identifier()?;
        if in_kw != "in" {
            return Err("Expected 'in' keyword".to_string());
        }
        let body = self.parse_expression()?;
        let end = self.current_location();
        Ok(Expression {
            kind: ExpressionKind::Let {
                name,
                type_annotation,
                value: Box::new(value),
                body: Box::new(body),
            },
            span: Span { start, end },
        })
    }

    fn parse_ou_arm(&mut self) -> ParseResult<OuArm> {
        let pattern = self.parse_pattern()?;
        self.expect_token('=')?;
        self.expect_token('>')?;
        let body = self.parse_expression()?;
        Ok(OuArm { pattern, body })
    }

    fn parse_pattern(&mut self) -> ParseResult<Pattern> {
        let ident = self.parse_identifier()?;
        if ident == "_" {
            return Ok(Pattern::Wildcard);
        }

        self.consume_whitespace();
        if self.peek() == Some(&'(') {
            self.next_char(); // consume '('
            let mut args = Vec::new();
            if self.peek() != Some(&')') {
                loop {
                    args.push(self.parse_pattern()?);
                    if self.peek() == Some(&')') {
                        break;
                    }
                    self.expect_token(',')?;
                }
            }
            self.expect_token(')')?;
            return Ok(Pattern::Constructor(ident, args));
        }

        Ok(Pattern::Identifier(ident))
    }

    fn parse_if_expression(&mut self) -> ParseResult<Expression> {
        let start = self.current_location();
        let condition = self.parse_expression()?;
        let then_kw = self.parse_identifier()?;
        if then_kw != "then" {
            return Err("Expected 'then' keyword".to_string());
        }
        let then_branch = self.parse_expression()?;
        let else_kw = self.parse_identifier()?;
        if else_kw != "else" {
            return Err("Expected 'else' keyword".to_string());
        }
        let else_branch = self.parse_expression()?;
        let end = self.current_location();

        Ok(Expression {
            kind: ExpressionKind::If {
                condition: Box::new(condition),
                then_branch: Box::new(then_branch),
                else_branch: Box::new(else_branch),
            },
            span: Span { start, end },
        })
    }

    fn parse_term(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_postfix_expression()?;
        loop {
            self.consume_whitespace();
            let op = match self.peek() {
                Some('*') => Operator::Multiply,
                Some('/') => Operator::Divide,
                _ => break,
            };
            self.next_char();

            let rhs = self.parse_primary_expression()?;
            let span = Span {
                start: lhs.span.start,
                end: rhs.span.end,
            };
            lhs = Expression {
                kind: ExpressionKind::BinaryOp {
                    op,
                    lhs: Box::new(lhs),
                    rhs: Box::new(rhs),
                },
                span,
            };
        }
        Ok(lhs)
    }

    // This is now the additive parser
    pub fn parse_additive_expression(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_term()?;
        loop {
            self.consume_whitespace();
            let op = match self.peek() {
                Some('+') => Operator::Add,
                Some('-') => Operator::Subtract,
                _ => break,
            };
            self.next_char();

            let rhs = self.parse_term()?;
            let span = Span {
                start: lhs.span.start,
                end: rhs.span.end,
            };
            lhs = Expression {
                kind: ExpressionKind::BinaryOp {
                    op,
                    lhs: Box::new(lhs),
                    rhs: Box::new(rhs),
                },
                span,
            };
        }
        Ok(lhs)
    }

    fn parse_pipe(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_comparison()?;
        loop {
            self.consume_whitespace();
            if self.peek() == Some(&'|') {
                self.next_char();
                self.expect_token('>')?;
                let rhs = self.parse_comparison()?;
                let span = Span {
                    start: lhs.span.start,
                    end: rhs.span.end,
                };
                lhs = Expression {
                    kind: ExpressionKind::Pipe {
                        lhs: Box::new(lhs),
                        rhs: Box::new(rhs),
                    },
                    span,
                };
            } else {
                break;
            }
        }
        Ok(lhs)
    }

    // Public entry point for parsing any expression
    pub fn parse_expression(&mut self) -> ParseResult<Expression> {
        self.parse_pipe()
    }

    fn parse_comparison(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_additive_expression()?; // Lower precedence
        loop {
            self.consume_whitespace();
            let op = match self.peek() {
                Some('=') => {
                    self.next_char();
                    if self.peek() == Some(&'=') {
                        self.next_char();
                        Operator::Equals
                    } else {
                        // This case is tricky, might be single '=' assignment
                        break;
                    }
                }
                Some('!') => {
                    self.next_char();
                    self.expect_token('=')?;
                    Operator::NotEquals
                }
                Some('<') => {
                    self.next_char();
                    if self.peek() == Some(&'=') {
                        self.next_char();
                        Operator::LessThanOrEqual
                    } else {
                        Operator::LessThan
                    }
                }
                Some('>') => {
                    self.next_char();
                    if self.peek() == Some(&'=') {
                        self.next_char();
                        Operator::GreaterThanOrEqual
                    } else {
                        Operator::GreaterThan
                    }
                }
                _ => break,
            };

            let rhs = self.parse_additive_expression()?;
            let span = Span {
                start: lhs.span.start,
                end: rhs.span.end,
            };
            lhs = Expression {
                kind: ExpressionKind::BinaryOp {
                    op,
                    lhs: Box::new(lhs),
                    rhs: Box::new(rhs),
                },
                span,
            };
        }
        Ok(lhs)
    }
    
    fn expect_token(&mut self, expected: char) -> ParseResult<()> {
        self.consume_whitespace();
        if self.peek() == Some(&expected) {
            self.next_char();
            Ok(())
        } else {
            Err(format!("Expected '{}', but found {:?}", expected, self.peek()))
        }
    }

    // This function will handle the full type grammar including function types.
    fn parse_type(&mut self) -> ParseResult<Type> {
        let mut base_type = self.parse_app_type()?;
        self.consume_whitespace();
        if self.peek() == Some(&'-') {
            self.next_char(); // consume '-'
            self.expect_token('>')?; // consume '>'
            let return_type = self.parse_type()?; // Right-recursive call
            base_type = Type::Func(Box::new(base_type), Box::new(return_type));
        }
        Ok(base_type)
    }
    
    // Parses type applications like `Vec i64`
    fn parse_app_type(&mut self) -> ParseResult<Type> {
        let mut head = self.parse_atomic_type()?;
        loop {
            self.consume_whitespace();
            // Try to parse another atomic type, if it fails, we're done.
            if let Ok(arg) = self.parse_atomic_type() {
                 head = Type::App(Box::new(head), vec![arg]);
            } else {
                break;
            }
        }
        Ok(head)
    }

    // Parses atomic types like identifiers, `ku`, `()`, or `(A -> B)`
    fn parse_atomic_type(&mut self) -> ParseResult<Type> {
        self.consume_whitespace();
        if self.peek() == Some(&'(') {
            self.next_char(); // consume '('
            self.consume_whitespace();

            if self.peek() == Some(&')') {
                self.next_char(); // consume ')'
                return Ok(Type::Unit);
            }

            // Lookahead for Pi-type like `(a: T) -> U`
            let mut snapshot = self.clone();
            if let Ok(_ident) = snapshot.parse_identifier() {
                snapshot.consume_whitespace();
                if snapshot.peek() == Some(&':') {
                    // It's a Pi type. Let's parse it for real.
                    let binder_name = self.parse_identifier()?;
                    self.expect_token(':')?;
                    let binder_type = self.parse_type()?;
                    self.expect_token(')')?;
                    self.consume_whitespace();
                    self.expect_token('-')?;
                    self.expect_token('>')?;
                    let return_type = self.parse_type()?;

                    return Ok(Type::Pi {
                        binder_name,
                        binder_type: Box::new(binder_type),
                        return_type: Box::new(return_type),
                    });
                }
            }

            // If it wasn't a Pi-type, parse it as a grouped type
            let inner_type = self.parse_type()?;
            self.expect_token(')')?;
            return Ok(inner_type);
        }

        let ident = self.parse_identifier()?;
        self.consume_whitespace();
        if self.peek() == Some(&'<') {
            self.next_char(); // consume '<'
            let mut args = Vec::new();
            loop {
                args.push(self.parse_type()?);
                self.consume_whitespace();
                if self.peek() == Some(&'>') {
                    self.next_char(); // consume '>'
                    break;
                }
                self.expect_token(',')?;
            }

            if ident == "ze" {
                if args.len() == 2 {
                    return Ok(Type::Ze(Box::new(args[0].clone()), Box::new(args[1].clone())));
                } else {
                    return Err("ze type constructor expects 2 arguments".to_string());
                }
            }

            return Ok(Type::App(Box::new(Type::Ident(ident)), args));
        }

        if ident == "ku" {
            Ok(Type::Ku)
        } else if ident == "Unit" { // Assuming Unit is parsed as an identifier
            Ok(Type::Unit)
        }
        else {
             Ok(Type::Ident(ident))
        }
    }

    // Placeholder for the statement parser
    pub fn parse_statement(&mut self) -> ParseResult<Statement> {
        let ident = self.parse_identifier()?;
        match ident.as_str() {
            "shiki" => {
                let var_name = self.parse_identifier()?;
                self.expect_token(':')?;
                let type_ann = self.parse_type()?;
                self.expect_token('=')?;
                let value = self.parse_expression()?;
                Ok(Statement::Shiki {
                    variable_name: var_name,
                    type_annotation: type_ann,
                    value,
                })
            }
            "gyo" => {
                let name = self.parse_identifier()?;
                self.expect_token('=')?;
                self.expect_token('{')?;
                
                let mut constructors = Vec::new();
                self.consume_whitespace();
                if self.peek() != Some(&'}') {
                    loop {
                        constructors.push(self.parse_constructor_def()?);
                        self.consume_whitespace();
                        if self.peek() == Some(&'}') {
                            break;
                        }
                        self.expect_token(',')?;
                    }
                }

                self.expect_token('}')?;

                Ok(Statement::Gyo{ 
                    name, 
                    params: vec![], // simplified for now
                    constructors,
                })
            }
            "rin" => self.parse_rin_statement(),
            _ => Err(format!("Unsupported statement type: {}", ident))
        }
    }

    fn parse_rin_statement(&mut self) -> ParseResult<Statement> {
        let name = self.parse_identifier()?;
        // Generics parsing (simplified)
        let generics = if self.peek() == Some(&'<') {
            self.next_char(); // consume '<'
            let mut g = Vec::new();
            loop {
                g.push(self.parse_identifier()?);
                if self.peek() == Some(&'>') {
                    self.next_char();
                    break;
                }
                self.expect_token(',')?;
            }
            g
        } else {
            vec![]
        };

        self.expect_token('(')?;
        // Params parsing (simplified)
        let mut params = Vec::new();
        if self.peek() != Some(&')') {
            loop {
                params.push(self.parse_parameter()?);
                if self.peek() == Some(&')') {
                    break;
                }
                self.expect_token(',')?;
            }
        }
        self.expect_token(')')?;
        self.expect_token(':')?;
        let return_type = self.parse_type()?;
        self.expect_token('=')?;
        let body = self.parse_expression()?;

        Ok(Statement::Rin {
            name,
            generics,
            params,
            return_type,
            body,
        })
    }

    fn parse_parameter(&mut self) -> ParseResult<Parameter> {
        let name = self.parse_identifier()?;
        self.expect_token(':')?;
        let type_annotation = self.parse_type()?;
        Ok(Parameter { name, type_annotation })
    }

    fn parse_constructor_def(&mut self) -> ParseResult<ConstructorDef> {
        let name = self.parse_identifier()?;
        self.consume_whitespace();
        if self.peek() == Some(&':') {
            // Path constructor: loop: ze<base, base>
            self.next_char(); // consume ':'
            let path_type = self.parse_type()?;
            self.expect_token('=')?;
            let body = self.parse_expression()?;
            Ok(ConstructorDef::Path { name, path_type, body })
        } else {
            // Point constructor: base or succ(N)
            let mut fields = Vec::new();
            if self.peek() == Some(&'(') {
                self.next_char(); // consume '('
                self.consume_whitespace();
                if self.peek() != Some(&')') {
                     loop {
                        fields.push(self.parse_type()?);
                        self.consume_whitespace();
                        if self.peek() == Some(&')') {
                            break;
                        }
                        self.expect_token(',')?;
                    }
                }
                self.expect_token(')')?;
            }
            Ok(ConstructorDef::Point { name, fields })
        }
    }


    pub fn parse_program(&mut self) -> ParseResult<Vec<Statement>> {
        let mut statements = Vec::new();
        self.consume_whitespace();
        while self.peek().is_some() {
            let stmt = self.parse_statement()?;
            statements.push(stmt);
            self.consume_whitespace();
        }
        Ok(statements)
    }
}


pub fn parse_program(input: &str) -> Result<Vec<Statement>, String> {
    Parser::new(input).parse_program()
}

// Main entry point for expressions is now parse_pipe
pub fn parse_expression(input: &str) -> Result<Expression, String> {
    Parser::new(input).parse_pipe()
}

pub fn parse_statement(input: &str) -> Result<Statement, String> {
    Parser::new(input).parse_statement()
} 