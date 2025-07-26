use nom::{
    branch::alt,
    bytes::complete::{tag, take_while1},
    character::complete::{alpha1, char, multispace0, multispace1},
    combinator::{map, opt, recognize},
    multi::separated_list1,
    sequence::{delimited, pair, preceded, tuple},
    IResult,
};

/// 型を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    /// `ba`, `en`, `ma`, `i64` などのシンプルな型
    Simple(String),
    /// `en<T1, T2>` のようなジェネリック型
    Generic(String, Vec<Type>),
}

#[derive(Debug, PartialEq, Clone)]
pub struct Parameter {
    pub name: String,
    pub type_annotation: Type,
}

/// 式を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Expression {
    /// `ku "<id>"`
    Ku { id: String },
    /// `kan(<params>) => <body>`
    Kan {
        params: Vec<Parameter>,
        body: Box<Expression>,
    },
    /// 変数名
    Identifier(String),
    /// `var.method()`
    MethodCall {
        variable: Box<Expression>,
        method: String,
    },
    /// `lhs |> rhs`
    Pipe {
        lhs: Box<Expression>,
        rhs: Box<Expression>,
    },
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
}

// --- Parsers ---

fn parse_identifier_str(input: &str) -> IResult<&str, &str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        opt(take_while1(|c: char| c.is_alphanumeric() || c == '_')),
    ))(input)
}

fn parse_identifier(input: &str) -> IResult<&str, &str> {
    let (next, ident) = parse_identifier_str(input)?;
    // キーワードと一致する場合はエラー
    match ident {
        "shiki" | "kan" | "ku" | "ou" | "ba" | "en" | "ma" => Err(nom::Err::Error(
            nom::error::Error::new(input, nom::error::ErrorKind::Tag),
        )),
        _ => Ok((next, ident)),
    }
}

fn parse_quoted_string(input: &str) -> IResult<&str, String> {
    map(
        delimited(
            char('"'),
            nom::bytes::complete::take_while(|c: char| c != '"'),
            char('"'),
        ),
        |s: &str| s.to_string(),
    )(input)
}

fn parse_type_name(input: &str) -> IResult<&str, &str> {
    alpha1(input)
}

fn parse_type(input: &str) -> IResult<&str, Type> {
    let (input, name) = parse_type_name(input)?;
    let (input, generics) = opt(delimited(
        char('<'),
        separated_list1(
            delimited(multispace0, char(','), multispace0),
            parse_type,
        ),
        char('>'),
    ))(input)?;

    Ok((
        input,
        match generics {
            Some(types) => Type::Generic(name.to_string(), types),
            None => Type::Simple(name.to_string()),
        },
    ))
}

fn parse_parameter(input: &str) -> IResult<&str, Parameter> {
    map(
        tuple((
            parse_identifier,
            delimited(multispace0, char(':'), multispace0),
            parse_type,
        )),
        |(name, _, type_annotation)| Parameter {
            name: name.to_string(),
            type_annotation,
        },
    )(input)
}

fn parse_primary_expression(input: &str) -> IResult<&str, Expression> {
    let ku_parser = map(
        preceded(tuple((tag("ku"), multispace1)), parse_quoted_string),
        |id| Expression::Ku { id },
    );

    let kan_parser = map(
        tuple((
            tag("kan"),
            delimited(
                char('('),
                separated_list1(delimited(multispace0, char(','), multispace0), parse_parameter),
                char(')'),
            ),
            delimited(multispace0, tag("=>"), multispace0),
            parse_primary_expression, // 左再帰を避ける
        )),
        |(_, params, _, body)| Expression::Kan {
            params,
            body: Box::new(body),
        },
    );

    let ident_expr_parser = map(parse_identifier, |name| {
        Expression::Identifier(name.to_string())
    });

    let (mut remaining, mut expr) = alt((ku_parser, kan_parser, ident_expr_parser))(input)?;

    loop {
        let (next_remaining, method_call) = opt(preceded(
            char('.'),
            map(
                tuple((parse_identifier_str, tag("()"))),
                |(method, _)| method.to_string(),
            ),
        ))(&remaining)?;

        if let Some(method) = method_call {
            expr = Expression::MethodCall {
                variable: Box::new(expr),
                method,
            };
            remaining = next_remaining;
        } else {
            break;
        }
    }

    Ok((remaining, expr))
}

fn parse_expression(input: &str) -> IResult<&str, Expression> {
    let (mut remaining, mut lhs) = parse_primary_expression(input)?;

    loop {
        let (next_remaining, pipe) = opt(preceded(
            delimited(multispace0, tag("|>"), multispace0),
            parse_primary_expression,
        ))(&remaining)?;

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

pub fn parse_statement(input: &str) -> IResult<&str, Statement> {
    let (input, _) = tag("shiki")(input)?;
    let (input, _) = multispace1(input)?;
    let (input, variable_name) = parse_identifier(input)?;
    let (input, _) = delimited(multispace0, char(':'), multispace0)(input)?;
    let (input, type_annotation) = parse_type(input)?;
    let (input, _) = delimited(multispace0, char('='), multispace0)(input)?;
    let (input, value) = parse_expression(input)?;

    Ok((
        input,
        Statement::Shiki {
            variable_name: variable_name.to_string(),
            type_annotation,
            value,
        },
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_shiki_ku() {
        let input = "shiki timer_ba: ba = ku \"system/timer\"";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "timer_ba".to_string(),
                    type_annotation: Type::Simple("ba".to_string()),
                    value: Expression::Ku {
                        id: "system/timer".to_string()
                    }
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
                    type_annotation: Type::Generic(
                        "en".to_string(),
                        vec![
                            Type::Simple("ma".to_string()),
                            Type::Simple("i64".to_string())
                        ]
                    ),
                    value: Expression::MethodCall {
                        variable: Box::new(Expression::Identifier("timer_ba".to_string())),
                        method: "as_en".to_string()
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
                    type_annotation: Type::Generic(
                        "en".to_string(),
                        vec![
                            Type::Simple("ma".to_string()),
                            Type::Simple("i64".to_string())
                        ]
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
                    type_annotation: Type::Generic(
                        "en".to_string(),
                        vec![
                            Type::Simple("i64".to_string()),
                            Type::Simple("i64".to_string())
                        ]
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
}
