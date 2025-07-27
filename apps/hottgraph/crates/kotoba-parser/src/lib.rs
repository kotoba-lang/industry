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

    let (input, arrow) = opt(delimited(multispace0, tag("->"), multispace0)).parse(input)?;

    if arrow.is_some() {
        let (input, rhs) = parse_type(input)?;
        Ok((input, Type::Func(Box::new(lhs), Box::new(rhs))))
    } else {
        Ok((input, lhs))
    }
}

/// Parses non-function types (atomic types in the context of function type parsing).
fn parse_atomic_type(input: &str) -> ParseResult<Type> {
    if input.starts_with("()") {
        return Ok((&input[2..], Type::Unit));
    }

    let (input, name) = parse_type_name(input)?;
    let (input, _) = sp(input)?;

    // `ku` type
    if name == "ku" {
        return Ok((input, Type::Ku));
    }

    // `ze` or `en` type
    if name == "ze" || name == "en" {
        let (input, generics) = delimited(
            char('<'),
            separated_list1(
                delimited(sp, char(','), sp),
                parse_type,
            ),
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
            return Ok((input, Type::Ze(Box::new(type1), Box::new(type2))));
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
            return Ok((
                input,
                Type::En(Box::new(type1), Box::new(type2), Box::new(type3)),
            ));
        }
    }

    Ok((input, Type::Simple(name.to_string())))
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
    // First, try to parse a complete `ou` expression, as it's a compound form.
    let ou_parser = map(
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
    );

    let let_parser = map(
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
    );

    let kan_parser = map(
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
    );

    alt((ou_parser, kan_parser, let_parser, |i| {
        // The original pipe-aware parser
        let (mut remaining, mut lhs) = parse_primary_expression.parse(i)?;

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
    }))
    .parse(input)
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
}
