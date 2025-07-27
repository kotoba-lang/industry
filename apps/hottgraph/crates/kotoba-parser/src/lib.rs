use nom::{
    branch::alt,
    bytes::complete::{tag, take_while1},
    character::complete::{alpha1, char, multispace0, multispace1},
    combinator::{cut, map, opt, recognize, verify},
    multi::{separated_list0, separated_list1},
    sequence::{delimited, pair, preceded, terminated},
    IResult, Parser,
};

/// 型を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    /// The interval type, `ku`.
    Ku,
    /// A path type `ze(T, U)`, representing `T ≡ U`.
    Ze(Box<Type>, Box<Type>),
    /// A simple, named type like `ma` or `i64`.
    Simple(String),
    /// A glued type `en<A, B>`.
    En(Box<Type>, Box<Type>),
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
pub struct Constructor {
    pub name: String,
    pub fields: Vec<Type>,
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
        constructors: Vec<Constructor>,
    },
    /// `rin <function_name>(<params>): <return_type> = <body>`
    Rin {
        name: String,
        params: Vec<Parameter>,
        return_type: Type,
        body: Expression,
    },
}

// --- Parsers ---

fn parse_identifier_str(input: &str) -> IResult<&str, &str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        opt(take_while1(|c: char| c.is_alphanumeric() || c == '_')),
    ))
    .parse(input)
}

fn parse_identifier(input: &str) -> IResult<&str, &str> {
    verify(parse_identifier_str, |s: &str| {
        !matches!(
            s,
            "shiki" | "kan" | "ku" | "ou" | "ba" | "en" | "ma" | "ze" | "i0" | "i1" | "gyo" | "rin"
        )
    })
    .parse(input)
}

fn parse_type_name(input: &str) -> IResult<&str, &str> {
    parse_identifier_str(input) // alpha1ではi64などをパースできないため修正
}

fn parse_type(input: &str) -> IResult<&str, Type> {
    let (input, name) = parse_type_name(input)?;
    let (input, _) = multispace0(input)?;

    // `ku` type
    if name == "ku" {
        return Ok((input, Type::Ku));
    }

    // `ze` or `en` type
    if name == "ze" || name == "en" {
        let (input, generics) = delimited(
            char('<'),
            separated_list1(delimited(multispace0, char(','), multispace0), parse_type),
            char('>'),
        )
        .parse(input)?;

        if generics.len() != 2 {
            // ze and en must have exactly two type parameters.
            return Err(nom::Err::Error(nom::error::Error::new(
                input,
                nom::error::ErrorKind::Verify,
            )));
        }

        let mut iter = generics.into_iter();
        let type1 = iter.next().unwrap();
        let type2 = iter.next().unwrap();

        if name == "ze" {
            return Ok((
                input,
                Type::Ze(Box::new(type1), Box::new(type2)),
            ));
        } else {
            return Ok((
                input,
                Type::En(Box::new(type1), Box::new(type2)),
            ));
        }
    }

    Ok((input, Type::Simple(name.to_string())))
}

fn parse_parameter(input: &str) -> IResult<&str, Parameter> {
    map(
        (
            parse_identifier,
            delimited(multispace0, char(':'), multispace0),
            parse_type,
        ),
        |(name, _, type_annotation)| Parameter {
            name: name.to_string(),
            type_annotation,
        },
    )
    .parse(input)
}

fn parse_pattern(input: &str) -> IResult<&str, Pattern> {
    // Tries to parse a constructor pattern like `Succ(n)` or `Cons(h, t)`.
    // If that fails, it tries the other, simpler patterns.
    let constructor_with_args_parser = map(
        pair(
            parse_identifier,
            delimited(
                char('('),
                separated_list1(delimited(multispace0, char(','), multispace0), parse_pattern),
                char(')'),
            ),
        ),
        |(name, patterns)| Pattern::Constructor(name.to_string(), patterns),
    );

    alt((
        map(nom::character::complete::i64, Pattern::IntegerLiteral),
        map(alt((tag("i0"), tag("i1"))), |s: &str| {
            Pattern::IntervalLiteral(s.to_string())
        }),
        map(tag("_"), |_| Pattern::Wildcard),
        constructor_with_args_parser,
        // An identifier can be a variable or a constructor with no arguments.
        map(parse_identifier, |s| Pattern::Identifier(s.to_string())),
    ))
    .parse(input)
}

fn parse_ou_arm(input: &str) -> IResult<&str, OuArm> {
    map(
        preceded(
            multispace0,
            (
                parse_pattern,
                delimited(multispace0, tag("=>"), multispace0),
                parse_expression,
            ),
        ),
        |(pattern, _, body)| OuArm { pattern, body },
    )
    .parse(input)
}

fn parse_primary_expression(input: &str) -> IResult<&str, Expression> {
    let refl_parser = map(
        preceded(
            tag("refl"),
            delimited(char('('), parse_expression, char(')')),
        ),
        |expr| Expression::Refl(Box::new(expr)),
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

    let (mut remaining, mut expr) =
        alt((zo_parser, refl_parser, ident_expr_parser, integer_literal_parser)).parse(input)?;

    loop {
        let (next_remaining, method_call) = opt(preceded(
            char('.'),
            map(
                (
                    parse_identifier_str,
                    delimited(
                        char('('),
                        separated_list0(
                            delimited(multispace0, char(','), multispace0),
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

fn parse_constructor(input: &str) -> IResult<&str, Constructor> {
    map(
        pair(
            parse_identifier,
            opt(preceded(
                delimited(multispace0, char(':'), multispace0),
                // This part is simplified for now. A full implementation would
                // parse a list of types for the constructor fields.
                parse_type,
            )),
        ),
        |(name, opt_type)| Constructor {
            name: name.to_string(),
            fields: opt_type.map_or(vec![], |t| vec![t]),
        },
    )
    .parse(input)
}

pub fn parse_expression(input: &str) -> IResult<&str, Expression> {
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

    alt((ou_parser, kan_parser, |i| {
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

pub fn parse_statement(input: &str) -> IResult<&str, Statement> {
    let shiki_parser = map(
        (
            tag("shiki"),
            multispace1,
            parse_identifier,
            delimited(multispace0, char(':'), multispace0),
            parse_type,
            delimited(multispace0, char('='), multispace0),
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
            delimited(multispace0, char('='), multispace0),
            delimited(
                char('{'),
                terminated(
                    separated_list1(char(','), preceded(multispace0, parse_constructor)),
                    opt(preceded(multispace0, char(','))),
                ),
                preceded(multispace0, char('}')),
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
            delimited(
                char('('),
                separated_list1(delimited(multispace0, char(','), multispace0), parse_parameter),
                char(')'),
            ),
            delimited(multispace0, char(':'), multispace0),
            parse_type, // return type
            delimited(multispace0, char('='), multispace0),
            parse_expression, // body
        ),
        |(_, _, name, params, _, return_type, _, body)| Statement::Rin {
            name: name.to_string(),
            params,
            return_type,
            body,
        },
    );

    alt((shiki_parser, gyo_parser, rin_parser)).parse(input)
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
        let input = "shiki ticks: en<ma, i64> = timer_ba.as_en()";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "ticks".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("ma".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
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
        let input = "shiki pipeline: en<ma, i64> = ticks |> doubler";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "pipeline".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("ma".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
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
        let input = "shiki doubler: en<i64, i64> = kan(x: i64) => x";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "doubler".to_string(),
                    type_annotation: Type::En(
                        Box::new(Type::Simple("i64".to_string())),
                        Box::new(Type::Simple("i64".to_string()))
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
        let input = "gyo N = { zero: N, succ: en<N, N> }";
        let result = parse_statement(input);
        assert!(result.is_ok());
        let (remaining, statement) = result.unwrap();
        assert_eq!(remaining, "");
        if let Statement::Gyo { name, constructors } = statement {
            assert_eq!(name, "N");
            assert_eq!(constructors.len(), 2);
            assert_eq!(constructors[0].name, "zero");
            assert_eq!(constructors[0].fields.len(), 1);
            assert_eq!(
                constructors[1].name,
                "succ"
            );
            assert_eq!(constructors[1].fields.len(), 1);
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
