//! The parser for the `kotoba` language.
//! It implements a handwritten recursive descent parser.

// The AST definitions are kept as they are, as they correctly represent the language structure.
// The main change is to replace the `nom` based parsing functions with a manual implementation.

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
    Path { name: String, path_type: Type },
}

#[derive(Debug, PartialEq, Clone)]
pub enum Expression {
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

pub struct Parser<'a> {
    chars: std::iter::Peekable<std::str::Chars<'a>>,
}

impl<'a> Parser<'a> {
    pub fn new(input: &'a str) -> Self {
        Parser {
            chars: input.chars().peekable(),
        }
    }

    fn next_char(&mut self) -> Option<char> {
        self.chars.next()
    }

    fn peek(&mut self) -> Option<&char> {
        self.chars.peek()
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
    
    fn parse_integer(&mut self) -> ParseResult<i64> {
        self.consume_whitespace();
        let mut num_str = String::new();
        while let Some(&c) = self.peek() {
            if c.is_digit(10) {
                num_str.push(self.next_char().unwrap());
            } else {
                break;
            }
        }
        if num_str.is_empty() {
            Err("Expected an integer".to_string())
        } else {
            num_str.parse::<i64>().map_err(|e| e.to_string())
        }
    }

    fn parse_primary_expression(&mut self) -> ParseResult<Expression> {
        self.consume_whitespace();
        if let Some(c) = self.peek() {
            if c.is_digit(10) {
                return self.parse_integer().map(Expression::IntegerLiteral);
            }
            if c.is_alphabetic() {
                let ident = self.parse_identifier()?;
                match ident.as_str() {
                    "i0" | "i1" => return Ok(Expression::Zo(ident)),
                    "refl" => return self.parse_refl_expression(),
                    "glue" => return self.parse_glue_expression(),
                    "unglue" => return self.parse_unglue_expression(),
                    "kan" => return self.parse_kan_expression(),
                    "ou" => return self.parse_ou_expression(),
                    "let" => return self.parse_let_expression(),
                    "if" => return self.parse_if_expression(),
                    _ => {
                        // It could be a variable, or it could be a type constructor in a type expression
                        // We will need to handle method calls here too.
                        return self.parse_identifier_or_method_call(ident);
                    }
                }
            }
            if *c == '(' {
                self.next_char(); // Consume '('
                let expr = self.parse_comparison()?; // Recursive call to the top-level expression parser
                self.consume_whitespace();
                if self.next_char() == Some(')') {
                    return Ok(expr);
                } else {
                    return Err("Expected ')'".to_string());
                }
            }
        }
        Err("Unexpected token in expression".to_string())
    }

    fn parse_identifier_or_method_call(&mut self, ident: String) -> ParseResult<Expression> {
        self.consume_whitespace();
        if self.peek() == Some(&'.') {
            self.next_char(); // consume '.'
            let method = self.parse_identifier()?;
            self.expect_token('(')?;
            // For now, assume no arguments for simplicity
            self.expect_token(')')?;
            Ok(Expression::MethodCall {
                variable: Box::new(Expression::Identifier(ident)),
                method,
                args: vec![],
            })
        } else {
            Ok(Expression::Identifier(ident))
        }
    }

    fn parse_refl_expression(&mut self) -> ParseResult<Expression> {
        self.expect_token('(')?;
        let expr = self.parse_expression()?;
        self.expect_token(')')?;
        Ok(Expression::Refl(Box::new(expr)))
    }

    fn parse_glue_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }
    fn parse_unglue_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }
    fn parse_kan_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }
    fn parse_ou_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }
    fn parse_let_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }
    fn parse_if_expression(&mut self) -> ParseResult<Expression> { Ok(Expression::Unit) }

    fn parse_term(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_primary_expression()?;
        loop {
            self.consume_whitespace();
            match self.peek() {
                Some('*') => {
                    self.next_char();
                    let rhs = self.parse_primary_expression()?;
                    lhs = Expression::BinaryOp {
                        op: Operator::Multiply,
                        lhs: Box::new(lhs),
                        rhs: Box::new(rhs),
                    };
                }
                Some('/') => {
                    self.next_char();
                    let rhs = self.parse_primary_expression()?;
                    lhs = Expression::BinaryOp {
                        op: Operator::Divide,
                        lhs: Box::new(lhs),
                        rhs: Box::new(rhs),
                    };
                }
                _ => break,
            }
        }
        Ok(lhs)
    }

    // This is now the additive parser
    pub fn parse_additive_expression(&mut self) -> ParseResult<Expression> {
        let mut lhs = self.parse_term()?;
        loop {
            self.consume_whitespace();
            match self.peek() {
                Some('+') => {
                    self.next_char();
                    let rhs = self.parse_term()?;
                    lhs = Expression::BinaryOp {
                        op: Operator::Add,
                        lhs: Box::new(lhs),
                        rhs: Box::new(rhs),
                    };
                }
                Some('-') => {
                    self.next_char();
                    let rhs = self.parse_term()?;
                    lhs = Expression::BinaryOp {
                        op: Operator::Subtract,
                        lhs: Box::new(lhs),
                        rhs: Box::new(rhs),
                    };
                }
                _ => break,
            }
        }
        Ok(lhs)
    }

    // Public entry point for parsing any expression
    pub fn parse_expression(&mut self) -> ParseResult<Expression> {
        self.parse_comparison()
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
            lhs = Expression::BinaryOp {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
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

    // Placeholder
    fn parse_type(&mut self) -> ParseResult<Type> {
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

            // Handle specific generic types like ze
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
        } else {
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
                 Ok(Statement::Gyo{ name: "dummy".to_string(), params: vec![], constructors: vec![]})
            }
            _ => Err(format!("Unsupported statement type: {}", ident))
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

// Main entry point for expressions is now parse_comparison
pub fn parse_expression(input: &str) -> Result<Expression, String> {
    Parser::new(input).parse_expression()
}

pub fn parse_statement(input: &str) -> Result<Statement, String> {
    Parser::new(input).parse_statement()
} 