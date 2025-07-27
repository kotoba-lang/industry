use nom::{
    branch::alt,
    bytes::complete::{is_not, tag, take_while1},
    character::complete::{alpha1, char, multispace0, multispace1},
    combinator::{cut, map, opt, recognize, value, verify},
    multi::{many0, many1, separated_list0, separated_list1},
    sequence::{delimited, pair, preceded, terminated},
    IResult, Parser,
};

type ParseResult<'a, O> = IResult<&'a str, O, nom::error::Error<&'a str>>;

/// 型を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    /// The interval type, `ku`.
    Ku,
    /// A path type `ze(T, U)`, representing `T ≡ U`.
    Ze(Box<Type>, Box<Type>),
    /// A simple, named type like `ma` or `i64`.
    Simple(String),
    /// A glued type `en<A, T, E>`.
    En(Box<Type>, Box<Type>, Box<Type>),
    /// The unit type `()`.
    Unit,
    /// A function type `A -> B`.
    Func(Box<Type>, Box<Type>),
    /// A dependent function type (Pi-type) `(x: A) -> B`.
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
    /// 整数リテラル (`123`)
    IntegerLiteral(i64),
    /// `i0` または `i1`
    IntervalLiteral(String),
    /// ワイルドカード (`_`)
    Wildcard,
    /// 識別子 (`x`)
    Identifier(String),
    /// コンストラクタ (`Succ(n)`)
    Constructor(String, Vec<Pattern>),
}

#[derive(Debug, PartialEq, Clone)]
pub struct OuArm {
    pub pattern: Pattern,
    pub body: Expression,
}

#[derive(Debug, PartialEq, Clone)]
pub enum ConstructorDef {
    /// A point constructor, like `true` or `succ(N)`.
    Point { name: String, fields: Vec<Type> },
    /// A path constructor, like `loop: ze<base, base>`.
    Path { name: String, path_type: Type },
}

/// 式を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Expression {
    /// `kan(<params>) => <body>`
    Kan {
        params: Vec<Parameter>,
        body: Box<Expression>,
    },
    /// A binary operation like `a + b`
    BinaryOp {
        op: Operator,
        lhs: Box<Expression>,
        rhs: Box<Expression>,
    },
    /// `refl(<expr>)`
    Refl(Box<Expression>),
    /// `glue(<expr>)`
    Glue { value: Box<Expression> },
    /// `unglue(<expr>)`
    Unglue { value: Box<Expression> },
    /// `let <name>: <type> = <value> in <body>`
    Let {
        name: String,
        type_annotation: Option<Type>,
        value: Box<Expression>,
        body: Box<Expression>,
    },
    /// `if <condition> then <then_branch> else <else_branch>`
    If {
        condition: Box<Expression>,
        then_branch: Box<Expression>,
        else_branch: Box<Expression>,
    },
    /// 変数名
    Identifier(String),
    /// 整数リテラル
    IntegerLiteral(i64),
    /// `var.method(args)`
    MethodCall {
        variable: Box<Expression>,
        method: String,
        args: Vec<Expression>,
    },
    /// `lhs |> rhs`
    Pipe {
        lhs: Box<Expression>,
        rhs: Box<Expression>,
    },
    /// `ou <expr> { <arms> }`
    Ou {
        expression: Box<Expression>,
        arms: Vec<OuArm>,
    },
    /// An interval literal, `zo` (`i0` or `i1`).
    Zo(String),
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

/// 文を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Statement {
    /// `shiki <variable>: <type> = <expression>`
    Shiki {
        variable_name: String,
        type_annotation: Type,
        value: Expression,
    },
    /// `gyo <TypeName> = { <constructors> }`
    Gyo {
        name: String,
        constructors: Vec<ConstructorDef>,
    },
    /// `rin <function_name><<generics>>(params): <return_type> = <body>`
    Rin {
        name: String,
        generics: Vec<String>,
        params: Vec<Parameter>,
        return_type: Type,
        body: Expression,
    },
}

/// Consumes whitespace and line comments.
fn sp(input: &str) -> ParseResult<&str> {
    recognize(many0(alt((
        value((), multispace1),
        value((), pair(tag("//"), is_not("\n\r"))),
    ))))
    .parse(input)
}

// --- Parsers ---

fn parse_identifier_str(input: &str) -> ParseResult<&str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        opt(take_while1(|c: char| c.is_alphanumeric() || c == '_')),
    ))
    .parse(input)
}

fn parse_identifier(input: &str) -> ParseResult<&str> {
    verify(parse_identifier_str, |s: &str| {
        !matches!(
            s,
            "shiki" | "kan" | "ku" | "ou" | "ba" | "en" | "ma" | "ze" | "i0" | "i1" | "gyo" | "rin"
        )
    })
    .parse(input)
}

fn parse_type_name(input: &str) -> ParseResult<&str> {
    parse_identifier_str(input) // alpha1ではi64などをパースできないため修正
}

/// Parses a type, handling right-associative function types `A -> B -> C`.
pub fn parse_type(input: &str) -> ParseResult<Type> {
    let (input, lhs) = parse_atomic_type(input)?;

    if let Ok((input, _)) = delimited(sp, tag("->"), sp).parse(input) {
        // After seeing `->`, we must parse the rest of the type.
        let (input, rhs) = cut(parse_type).parse(input)?;

        // Now, try to interpret `lhs` as a binder `(x: T)` for a Pi type.
        // This is a bit of a hack: `parse_atomic_type` returns a Simple type
        // containing the string `(x: T)`, which we re-parse here.
        if let Type::Simple(s) = &lhs {
            if let Ok((rest, (name, ty))) = delimited(
                char('('),
                pair(
                    map(parse_identifier, |s| s.to_string()),
                    preceded(delimited(sp, char(':'), sp), parse_type),
                ),
                char(')'),
            )
            .parse(s)
            {
                // Ensure the binder string was fully consumed.
                if rest.is_empty() {
                    return Ok((
                        input,
                        Type::Pi {
                            binder_name: name,
                            binder_type: Box::new(ty),
                            return_type: Box::new(rhs),
                        },
                    ));
                }
            }
        }
        // If it's not a valid binder, it's a regular function type.
        Ok((input, Type::Func(Box::new(lhs), Box::new(rhs))))
    } else {
        Ok((input, lhs))
    }
}

/// Parses non-function types (atomic types in the context of function type parsing).
fn parse_atomic_type(input: &str) -> ParseResult<Type> {
    alt((
        map(tag("()"), |_| Type::Unit),
        // A binder `(x: T)` is parsed as a "simple" type containing its own source string.
        // This is a hack to be resolved by `parse_type`.
        map(
            recognize(delimited(
                char('('),
                pair(
                    parse_identifier,
                    preceded(delimited(sp, char(':'), sp), parse_type),
                ),
                char(')'),
            )),
            |s: &str| Type::Simple(s.to_string()),
        ),
        // A regular parenthesized type `(T)`. This must come after the binder parser.
        delimited(char('('), parse_type, char(')')),
        map(tag("ku"), |_| Type::Ku),
        parse_ze_or_en_type,
        map(parse_identifier, |s| Type::Simple(s.to_string())),
    ))
    .parse(input)
}

fn parse_ze_or_en_type(input: &str) -> ParseResult<Type> {
    let (input, name) = alt((tag("ze"), tag("en"))).parse(input)?;
    let (input, _) = sp(input)?;
    let (input, generics) = delimited(
        char('<'),
        separated_list1(delimited(sp, char(','), sp), parse_type),
        char('>'),
    )
    .parse(input)?;

    if name == "ze" {
        if generics.len() != 2 {
            return Err(nom::Err::Error(nom::error::Error::new(
                input,
                nom::error::ErrorKind::Verify,
            )));
        }
        let mut iter = generics.into_iter();
        let type1 = iter.next().unwrap();
        let type2 = iter.next().unwrap();
        Ok((input, Type::Ze(Box::new(type1), Box::new(type2))))
    } else {
        // 'en'
        if generics.len() != 3 {
            return Err(nom::Err::Error(nom::error::Error::new(
                input,
                nom::error::ErrorKind::Verify,
            )));
        }
        let mut iter = generics.into_iter();
        let type1 = iter.next().unwrap();
        let type2 = iter.next().unwrap();
        let type3 = iter.next().unwrap();
        Ok((
            input,
            Type::En(Box::new(type1), Box::new(type2), Box::new(type3)),
        ))
    }
}

fn parse_parameter(input: &str) -> ParseResult<Parameter> {
    map(
        (
            parse_identifier,
            delimited(sp, char(':'), sp),
            parse_type,
        ),
        |(name, _, type_annotation)| Parameter {
            name: name.to_string(),
            type_annotation,
        },
    )
    .parse(input)
}

fn parse_pattern(input: &str) -> ParseResult<Pattern> {
    // Tries to parse a constructor pattern like `Succ(n)` or `Cons(h, t)`.
    // If that fails, it tries the other, simpler patterns.
    let constructor_with_args_parser = map(
        pair(
            parse_identifier,
            delimited(
                char('('),
                separated_list1(
                    delimited(sp, char(','), sp),
                    parse_pattern,
                ),
                char(')'),
            ),
        ),
        |(name, patterns)| Pattern::Constructor(name.to_string(), patterns),
    );

    alt((
        map(alt((tag("i0"), tag("i1"))), |s: &str| {
            Pattern::IntervalLiteral(s.to_string())
        }),
        map(nom::character::complete::i64, Pattern::IntegerLiteral),
        map(tag("_"), |_| Pattern::Wildcard),
        constructor_with_args_parser,
        // An identifier can be a variable or a constructor with no arguments.
        map(parse_identifier, |s| Pattern::Identifier(s.to_string())),
    ))
    .parse(input)
}

fn parse_ou_arm(input: &str) -> ParseResult<OuArm> {
    map(
        preceded(
            sp,
            (
                parse_pattern,
                delimited(sp, tag("=>"), sp),
                parse_expression,
            ),
        ),
        |(pattern, _, body)| OuArm { pattern, body },
    )
    .parse(input)
}

fn parse_primary_expression(input: &str) -> ParseResult<Expression> {
    let refl_parser = map(
        preceded(
            tag("refl"),
            delimited(char('('), parse_expression, char(')')),
        ),
        |expr| Expression::Refl(Box::new(expr)),
    );

    let glue_parser = map(
        preceded(
            tag("glue"),
            delimited(char('('), parse_expression, char(')')),
        ),
        |value| Expression::Glue {
            value: Box::new(value),
        },
    );

    let unglue_parser = map(
        preceded(
            tag("unglue"),
            delimited(char('('), parse_expression, char(')')),
        ),
        |value| Expression::Unglue {
            value: Box::new(value),
        },
    );

    let zo_parser = map(alt((tag("i0"), tag("i1"))), |s: &str| {
        Expression::Zo(s.to_string())
    });

    let ident_expr_parser = map(parse_identifier, |name| {
        Expression::Identifier(name.to_string())
    });

    let integer_literal_parser = map(nom::character::complete::i64, |n| {
        Expression::IntegerLiteral(n)
    });

    let (mut remaining, mut expr) = alt((
        zo_parser,
        refl_parser,
        glue_parser,
        unglue_parser,
        ident_expr_parser,
        integer_literal_parser,
    ))
    .parse(input)?;

    loop {
        let (next_remaining, method_call) = opt(preceded(
            char('.'),
            map(
                (
                    parse_identifier_str,
                    delimited(
                        char('('),
                        separated_list0(
                            delimited(sp, char(','), sp),
                            parse_expression,
                        ),
                        char(')'),
                    ),
                ),
                |(method, args)| (method.to_string(), args),
            ),
        ))
        .parse(remaining)?;

        if let Some((method, args)) = method_call {
            expr = Expression::MethodCall {
                variable: Box::new(expr),
                method,
                args,
            };
            remaining = next_remaining;
        } else {
            break;
        }
    }

    Ok((remaining, expr))
}

fn parse_constructor(input: &str) -> ParseResult<ConstructorDef> {
    // Tries to parse a path constructor like `loop: ze<base, base>` first.
    let path_parser = map(
        (
            parse_identifier,
            preceded(delimited(sp, char(':'), sp), parse_type),
        ),
        |(name, path_type)| ConstructorDef::Path {
            name: name.to_string(),
            path_type,
        },
    );

    // Then tries to parse a point constructor like `succ(N)`.
    let point_parser = map(
        pair(
            parse_identifier,
            opt(delimited(
                char('('),
                separated_list1(delimited(sp, char(','), sp), parse_type),
                char(')'),
            )),
        ),
        |(name, fields)| ConstructorDef::Point {
            name: name.to_string(),
            fields: fields.unwrap_or_default(),
        },
    );

    alt((path_parser, point_parser)).parse(input)
}

pub fn parse_expression(input: &str) -> ParseResult<Expression> {
    alt((
        parse_ou_expression,
        parse_kan_expression,
        parse_let_expression,
        parse_if_expression,
        parse_comparison_expression,
    ))
    .parse(input)
}

fn parse_if_expression(input: &str) -> ParseResult<Expression> {
    map(
        (
            preceded(tag("if"), multispace1),
            parse_expression,
            delimited(multispace1, tag("then"), multispace1),
            parse_expression,
            delimited(multispace1, tag("else"), multispace1),
            parse_expression,
        ),
        |(_, condition, _, then_branch, _, else_branch)| Expression::If {
            condition: Box::new(condition),
            then_branch: Box::new(then_branch),
            else_branch: Box::new(else_branch),
        },
    )
    .parse(input)
}

fn parse_ou_expression(input: &str) -> ParseResult<Expression> {
    map(
        preceded(
            tag("ou"),
            cut(preceded(
                multispace1,
                (
                    parse_primary_expression, // The expression to be matched
                    multispace0,
                    delimited(
                        char('{'),
                        terminated(
                            separated_list1(char(','), parse_ou_arm),
                            opt(preceded(multispace0, char(','))), // Allow optional trailing comma
                        ),
                        preceded(multispace0, char('}')),
                    ),
                ),
            )),
        ),
        |(expression, _, arms)| Expression::Ou {
            expression: Box::new(expression),
            arms,
        },
    )
    .parse(input)
}

fn parse_let_expression(input: &str) -> ParseResult<Expression> {
    map(
        (
            preceded(tag("let"), multispace1),
            parse_identifier,
            opt(preceded(
                delimited(multispace0, char(':'), multispace0),
                parse_type,
            )),
            delimited(multispace0, char('='), multispace0),
            parse_expression,
            delimited(multispace0, tag("in"), multispace1),
            parse_expression,
        ),
        |(_, name, type_annotation, _, value, _, body)| Expression::Let {
            name: name.to_string(),
            type_annotation,
            value: Box::new(value),
            body: Box::new(body),
        },
    )
    .parse(input)
}

fn parse_kan_expression(input: &str) -> ParseResult<Expression> {
    map(
        (
            tag("kan"),
            delimited(
                char('('),
                separated_list1(
                    delimited(multispace0, char(','), multispace0),
                    parse_parameter,
                ),
                char(')'),
            ),
            delimited(multispace0, tag("=>"), multispace0),
            parse_expression, // 左再帰を避ける -> より一般的な式を許可
        ),
        |(_, params, _, body)| Expression::Kan {
            params,
            body: Box::new(body),
        },
    )
    .parse(input)
}

fn parse_comparison_expression(input: &str) -> ParseResult<Expression> {
    let (mut input, mut lhs) = parse_additive_expression(input)?;
    loop {
        let (next_input, op) = opt(delimited(
            sp,
            alt((
                value(Operator::Equals, tag("==")),
                value(Operator::NotEquals, tag("!=")),
                value(Operator::LessThanOrEqual, tag("<=")),
                value(Operator::GreaterThanOrEqual, tag(">=")),
                value(Operator::LessThan, char('<')),
                value(Operator::GreaterThan, char('>')),
            )),
            sp,
        ))
        .parse(input)?;

        if let Some(op) = op {
            let (next_input, rhs) = parse_additive_expression(next_input)?;
            lhs = Expression::BinaryOp {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
            input = next_input;
        } else {
            break;
        }
    }
    Ok((input, lhs))
}

fn parse_additive_expression(input: &str) -> ParseResult<Expression> {
    let (mut input, mut lhs) = parse_multiplicative_expression(input)?;
    loop {
        let (next_input, op) = opt(delimited(
            sp,
            alt((
                value(Operator::Add, char('+')),
                value(Operator::Subtract, char('-')),
            )),
            sp,
        ))
        .parse(input)?;

        if let Some(op) = op {
            let (next_input, rhs) = parse_multiplicative_expression(next_input)?;
            lhs = Expression::BinaryOp {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
            input = next_input;
        } else {
            break;
        }
    }
    Ok((input, lhs))
}

fn parse_multiplicative_expression(input: &str) -> ParseResult<Expression> {
    let (mut input, mut lhs) = parse_pipe_expression(input)?;
    loop {
        let (next_input, op) = opt(delimited(
            sp,
            alt((
                value(Operator::Multiply, char('*')),
                value(Operator::Divide, char('/')),
            )),
            sp,
        ))
        .parse(input)?;

        if let Some(op) = op {
            let (next_input, rhs) = parse_pipe_expression(next_input)?;
            lhs = Expression::BinaryOp {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
            input = next_input;
        } else {
            break;
        }
    }
    Ok((input, lhs))
}

fn parse_pipe_expression(input: &str) -> ParseResult<Expression> {
    let (mut remaining, mut lhs) = parse_primary_expression(input)?;
    loop {
        let (next_remaining, pipe) = opt(preceded(
            delimited(multispace0, tag("|>"), multispace0),
            parse_primary_expression,
        ))
        .parse(remaining)?;

        if let Some(rhs) = pipe {
            lhs = Expression::Pipe {
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
            remaining = next_remaining;
        } else {
            break;
        }
    }
    Ok((remaining, lhs))
}

pub fn parse_statement(input: &str) -> ParseResult<Statement> {
    let shiki_parser = map(
        (
            tag("shiki"),
            multispace1,
            parse_identifier,
            delimited(sp, char(':'), sp),
            parse_type,
            delimited(sp, char('='), sp),
            parse_expression,
        ),
        |(_, _, variable_name, _, type_annotation, _, value)| Statement::Shiki {
            variable_name: variable_name.to_string(),
            type_annotation,
            value,
        },
    );

    let gyo_parser = map(
        (
            tag("gyo"),
            multispace1,
            parse_type_name,
            delimited(sp, char('='), sp),
            delimited(
                char('{'),
                terminated(
                    separated_list1(
                        char(','),
                        preceded(sp, parse_constructor),
                    ),
                    opt(preceded(sp, char(','))),
                ),
                preceded(sp, char('}')),
            ),
        ),
        |(_, _, name, _, constructors)| Statement::Gyo {
            name: name.to_string(),
            constructors,
        },
    );

    let rin_parser = map(
        (
            tag("rin"),
            multispace1,
            parse_identifier, // function name
            opt(delimited(
                char('<'),
                separated_list1(
                    delimited(sp, char(','), sp),
                    parse_type_name,
                ),
                char('>'),
            )),
            delimited(
                char('('),
                separated_list1(
                    delimited(sp, char(','), sp),
                    parse_parameter,
                ),
                char(')'),
            ),
            delimited(sp, char(':'), sp),
            parse_type, // return type
            delimited(sp, char('='), sp),
            parse_expression, // body
        ),
        |(_, _, name, generics, params, _, return_type, _, body)| Statement::Rin {
            name: name.to_string(),
            generics: generics.unwrap_or_default().into_iter().map(String::from).collect(),
            params,
            return_type,
            body,
        },
    );

    alt((shiki_parser, gyo_parser, rin_parser)).parse(input)
}

pub fn parse_program(input: &str) -> ParseResult<Vec<Statement>> {
    terminated(
        many1(preceded(sp, parse_statement)),
        sp,
    )
    .parse(input)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_toki_type() {
        let input = "shiki my_time: ku = i0";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "my_time".to_string(),
                    type_annotation: Type::Ku,
                    value: Expression::Zo("i0".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_method_call() {
        let input = "shiki ticks: en<ma, i64, some_eq> = timer_ba.as_en()";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "ticks".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("ma".to_string())),
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("some_eq".to_string()))
                    ),
                    value: Expression::MethodCall {
                        variable: Box::new(Expression::Identifier("timer_ba".to_string())),
                        method: "as_en".to_string(),
                        args: vec![]
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_method_call_with_args() {
        let input = "shiki p2: ze<i64, i64> = p1.compose(q1)";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "p2".to_string(),
                    type_annotation: Type::Ze(
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
                    ),
                    value: Expression::MethodCall {
                        variable: Box::new(Expression::Identifier("p1".to_string())),
                        method: "compose".to_string(),
                        args: vec![Expression::Identifier("q1".to_string())]
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_method_call_sym() {
        let input = "shiki p_sym: ze<i64, i64> = p.sym()";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "p_sym".to_string(),
                    type_annotation: Type::Ze(
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
                    ),
                    value: Expression::MethodCall {
                        variable: Box::new(Expression::Identifier("p".to_string())),
                        method: "sym".to_string(),
                        args: vec![]
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_pipe() {
        let input = "shiki pipeline: en<ma, i64, id> = ticks |> doubler";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "pipeline".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("ma".to_string())),
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("id".to_string()))
                    ),
                    value: Expression::Pipe {
                        lhs: Box::new(Expression::Identifier("ticks".to_string())),
                        rhs: Box::new(Expression::Identifier("doubler".to_string()))
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_kan() {
        let input = "shiki doubler: en<i64, i64, N> = kan(x: i64) => x";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "doubler".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("N".to_string()))
                    ),
                    value: Expression::Kan {
                        params: vec![Parameter {
                            name: "x".to_string(),
                            type_annotation: Type::Simple("i64".to_string())
                        }],
                        body: Box::new(Expression::Identifier("x".to_string()))
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_rin_statement_with_generics() {
        let input = "rin id<T>(x: T): T = x";
        let result = parse_statement(input);
        assert!(result.is_ok());
        let (remaining, statement) = result.unwrap();
        assert_eq!(remaining, "");
        if let Statement::Rin {
            name,
            generics,
            params,
            return_type,
            ..
        } = statement
        {
            assert_eq!(name, "id");
            assert_eq!(generics, vec!["T"]);
            assert_eq!(params.len(), 1);
            assert_eq!(params[0].name, "x");
            if let Type::Simple(type_name) = &params[0].type_annotation {
                assert_eq!(type_name, "T");
            } else {
                panic!("Expected simple type for param x");
            }
            if let Type::Simple(type_name) = &return_type {
                assert_eq!(type_name, "T");
            } else {
                panic!("Expected simple type for return type");
            }
        } else {
            panic!("Expected Rin statement");
        }
    }

    #[test]
    fn test_parse_nested_kan() {
        let input = "kan(a: A) => kan(b: B) => c";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::Kan {
                    params: vec![Parameter {
                        name: "a".to_string(),
                        type_annotation: Type::Simple("A".to_string())
                    }],
                    body: Box::new(Expression::Kan {
                        params: vec![Parameter {
                            name: "b".to_string(),
                            type_annotation: Type::Simple("B".to_string())
                        }],
                        body: Box::new(Expression::Identifier("c".to_string()))
                    })
                }
            ))
        );
    }

    #[test]
    fn test_parse_ze_type() {
        let input = "shiki my_path: ze<i64, i64> = some_path";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "my_path".to_string(),
                    type_annotation: Type::Ze(
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
                    ),
                    value: Expression::Identifier("some_path".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_parse_higher_order_path_type() {
        let input = "shiki p_over_p: ze<ze<i64, i64>, ze<i64, i64>> = some_path";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "p_over_p".to_string(),
                    type_annotation: Type::Ze(
                        Box::new(Type::Ze(
                            Box::new(Type::Simple("i64".to_string())),
                            Box::new(Type::Simple("i64".to_string()))
                        )),
                        Box::new(Type::Ze(
                            Box::new(Type::Simple("i64".to_string())),
                            Box::new(Type::Simple("i64".to_string()))
                        ))
                    ),
                    value: Expression::Identifier("some_path".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_parse_ou_expression() {
        let input = "ou x { 0 => i0, _ => i1 }";
        let result = parse_expression(input); // 式として直接パース
        assert_eq!(
            result,
            Ok((
                "",
                Expression::Ou {
                    expression: Box::new(Expression::Identifier("x".to_string())),
                    arms: vec![
                        OuArm {
                            pattern: Pattern::IntegerLiteral(0),
                            body: Expression::Zo("i0".to_string())
                        },
                        OuArm {
                            pattern: Pattern::Wildcard,
                            body: Expression::Zo("i1".to_string())
                        }
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_parse_gyo_statement() {
        let input = "gyo N = { zero, succ(N) }";
        let result = parse_statement(input);
        assert!(result.is_ok());
        let (remaining, statement) = result.unwrap();
        assert_eq!(remaining, "");
        if let Statement::Gyo { name, constructors } = statement {
            assert_eq!(name, "N");
            assert_eq!(constructors.len(), 2);
            assert_eq!(
                constructors[0],
                ConstructorDef::Point {
                    name: "zero".to_string(),
                    fields: vec![]
                }
            );
            assert_eq!(
                constructors[1],
                ConstructorDef::Point {
                    name: "succ".to_string(),
                    fields: vec![Type::Simple("N".to_string())]
                }
            );
        } else {
            panic!("Expected Gyo statement");
        }
    }

    #[test]
    fn test_parse_gyo_with_path_constructor() {
        let input = "gyo S1 = { base, loop: ze<base, base> }";
        let result = parse_statement(input);
        assert!(result.is_ok());
        let (remaining, statement) = result.unwrap();
        assert_eq!(remaining, "");
        if let Statement::Gyo { name, constructors } = statement {
            assert_eq!(name, "S1");
            assert_eq!(constructors.len(), 2);
            assert_eq!(
                constructors[0],
                ConstructorDef::Point {
                    name: "base".to_string(),
                    fields: vec![]
                }
            );
            assert_eq!(
                constructors[1],
                ConstructorDef::Path {
                    name: "loop".to_string(),
                    path_type: Type::Ze(
                        Box::new(Type::Simple("base".to_string())),
                        Box::new(Type::Simple("base".to_string()))
                    )
                }
            );
        } else {
            panic!("Expected Gyo statement");
        }
    }

    #[test]
    fn test_parse_rin_statement() {
        let input = "rin add(a: N, b: N): N = ou a { zero => b }";
        let result = parse_statement(input);
        assert!(result.is_ok());
        let (remaining, statement) = result.unwrap();
        assert_eq!(remaining, "");
        if let Statement::Rin {
            name,
            params,
            return_type,
            ..
        } = statement
        {
            assert_eq!(name, "add");
            assert_eq!(params.len(), 2);
            assert_eq!(params[0].name, "a");
            if let Type::Simple(type_name) = &params[0].type_annotation {
                assert_eq!(type_name, "N");
            } else {
                panic!("Expected simple type for param a");
            }
            if let Type::Simple(type_name) = &return_type {
                assert_eq!(type_name, "N");
            } else {
                panic!("Expected simple type for return type");
            }
        } else {
            panic!("Expected Rin statement");
        }
    }

    #[test]
    fn test_parse_constructor_pattern() {
        let input = "Succ(n)";
        let result = parse_pattern(input);
        assert!(result.is_ok());
        let (remaining, pattern) = result.unwrap();
        assert_eq!(remaining, "");
        if let Pattern::Constructor(name, patterns) = pattern {
            assert_eq!(name, "Succ");
            assert_eq!(patterns.len(), 1);
            if let Pattern::Identifier(inner_name) = &patterns[0] {
                assert_eq!(inner_name, "n");
            } else {
                panic!("Expected inner pattern to be an identifier");
            }
        } else {
            panic!("Expected Constructor pattern");
        }
    }

    #[test]
    fn test_parse_unit_type() {
        let input = "()";
        let result = parse_type(input);
        assert_eq!(result, Ok(("", Type::Unit)));
    }

    #[test]
    fn test_parse_function_type() {
        let input = "i64 -> i64";
        let result = parse_type(input);
        assert_eq!(
            result,
            Ok((
                "",
                Type::Func(
                    Box::new(Type::Simple("i64".to_string())),
                    Box::new(Type::Simple("i64".to_string()))
                )
            ))
        );

        let input_nested = "A -> B -> C";
        let result_nested = parse_type(input_nested);
        assert_eq!(
            result_nested,
            Ok((
                "",
                Type::Func(
                    Box::new(Type::Simple("A".to_string())),
                    Box::new(Type::Func(
                        Box::new(Type::Simple("B".to_string())),
                        Box::new(Type::Simple("C".to_string()))
                    ))
                )
            ))
        );
    }

    #[test]
    fn test_parse_let_in() {
        let input_with_type = "let x: i64 = 10 in x";
        let result_with_type = parse_expression(input_with_type);
        assert_eq!(
            result_with_type,
            Ok((
                "",
                Expression::Let {
                    name: "x".to_string(),
                    type_annotation: Some(Type::Simple("i64".to_string())),
                    value: Box::new(Expression::IntegerLiteral(10)),
                    body: Box::new(Expression::Identifier("x".to_string()))
                }
            ))
        );

        let input_without_type = "let y = true in y";
        let result_without_type = parse_expression(input_without_type);
        assert_eq!(
            result_without_type,
            Ok((
                "",
                Expression::Let {
                    name: "y".to_string(),
                    type_annotation: None,
                    value: Box::new(Expression::Identifier("true".to_string())),
                    body: Box::new(Expression::Identifier("y".to_string()))
                }
            ))
        );
    }

    #[test]
    fn test_parse_inductive_type_expression() {
        let input = "shiki my_bool: Bool = true";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "my_bool".to_string(),
                    type_annotation: Type::Simple("Bool".to_string()),
                    value: Expression::Identifier("true".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_parse_glue_unglue() {
        let input = "glue(10)";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::Glue {
                    value: Box::new(Expression::IntegerLiteral(10))
                }
            ))
        );

        let input = "unglue(x)";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::Unglue {
                    value: Box::new(Expression::Identifier("x".to_string()))
                }
            ))
        );
    }

    #[test]
    fn test_parse_refl() {
        let input = "refl(10)";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::Refl(Box::new(Expression::IntegerLiteral(10)))
            ))
        );
    }

    #[test]
    fn test_parse_binary_operations() {
        // Simple addition
        let input_add = "1 + 2";
        let result_add = parse_expression(input_add);
        assert_eq!(
            result_add,
            Ok((
                "",
                Expression::BinaryOp {
                    op: Operator::Add,
                    lhs: Box::new(Expression::IntegerLiteral(1)),
                    rhs: Box::new(Expression::IntegerLiteral(2)),
                }
            ))
        );

        // Operator precedence
        let input_prec = "1 + 2 * 3";
        let result_prec = parse_expression(input_prec);
        assert_eq!(
            result_prec,
            Ok((
                "",
                Expression::BinaryOp {
                    op: Operator::Add,
                    lhs: Box::new(Expression::IntegerLiteral(1)),
                    rhs: Box::new(Expression::BinaryOp {
                        op: Operator::Multiply,
                        lhs: Box::new(Expression::IntegerLiteral(2)),
                        rhs: Box::new(Expression::IntegerLiteral(3)),
                    }),
                }
            ))
        );
    }

    #[test]
    fn test_parse_comparison_operations() {
        let input = "1 < 2";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::BinaryOp {
                    op: Operator::LessThan,
                    lhs: Box::new(Expression::IntegerLiteral(1)),
                    rhs: Box::new(Expression::IntegerLiteral(2)),
                }
            ))
        );

        // Precedence: 1 + 2 == 3
        let input_prec = "1 + 2 == 3";
        let result_prec = parse_expression(input_prec);
        assert_eq!(
            result_prec,
            Ok((
                "",
                Expression::BinaryOp {
                    op: Operator::Equals,
                    lhs: Box::new(Expression::BinaryOp {
                        op: Operator::Add,
                        lhs: Box::new(Expression::IntegerLiteral(1)),
                        rhs: Box::new(Expression::IntegerLiteral(2)),
                    }),
                    rhs: Box::new(Expression::IntegerLiteral(3)),
                }
            ))
        );
    }

    #[test]
    fn test_parse_if_expression() {
        let input = "if true then 1 else 2";
        let result = parse_expression(input);
        assert_eq!(
            result,
            Ok((
                "",
                Expression::If {
                    condition: Box::new(Expression::Identifier("true".to_string())),
                    then_branch: Box::new(Expression::IntegerLiteral(1)),
                    else_branch: Box::new(Expression::IntegerLiteral(2)),
                }
            ))
        );

        let input_nested = "if a > b then (if c then d else e) else f";
        let result_nested = parse_expression(input_nested);
        assert!(result_nested.is_ok());
    }

    #[test]
    fn test_parse_pi_type() {
        // Dependent function type
        let input_pi = "(x: i64) -> i64";
        let result_pi = parse_type(input_pi);
        assert_eq!(
            result_pi,
            Ok((
                "",
                Type::Pi {
                    binder_name: "x".to_string(),
                    binder_type: Box::new(Type::Simple("i64".to_string())),
                    return_type: Box::new(Type::Simple("i64".to_string())),
                }
            ))
        );

        // Parenthesized function type
        let input_paren = "(A -> B) -> C";
        let result_paren = parse_type(input_paren);
        assert_eq!(
            result_paren,
            Ok((
                "",
                Type::Func(
                    Box::new(Type::Func(
                        Box::new(Type::Simple("A".to_string())),
                        Box::new(Type::Simple("B".to_string()))
                    )),
                    Box::new(Type::Simple("C".to_string()))
                )
            ))
        );

        // Simple function type (no parens)
        let input_simple = "A -> B";
        let result_simple = parse_type(input_simple);
        assert_eq!(
            result_simple,
            Ok((
                "",
                Type::Func(
                    Box::new(Type::Simple("A".to_string())),
                    Box::new(Type::Simple("B".to_string()))
                )
            ))
        );
    }
}
