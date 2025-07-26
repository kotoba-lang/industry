use nom::{
    branch::alt,
    bytes::complete::{tag, take_while1},
    character::complete::{alpha1, char, multispace0, multispace1},
    combinator::{map, not, opt, peek, recognize},
    multi::separated_list1,
    sequence::{delimited, pair, preceded, tuple},
    IResult,
};

/// 型を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    /// `場`, `縁`, `間`, `i64` などのシンプルな型
    Simple(String),
    /// `縁<T1, T2>` のようなジェネリック型
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
    /// `く "<id>"`
    Ku { id: String },
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
    /// `かん(<params>) => <body>`
    Kan {
        params: Vec<Parameter>,
        body: Box<Expression>,
    },
}

/// 文を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Statement {
    /// `しき <variable>: <type> = <expression>`
    Shiki {
        variable_name: String,
        type_annotation: Type,
        value: Expression,
    },
}

// --- Parsers ---

fn parse_identifier_str(input: &str) -> IResult<&str, &str> {
    take_while1(|c: char| c.is_alphabetic() || c == '_')(input)
}

fn parse_identifier(input: &str) -> IResult<&str, &str> {
    let (next, ident) = parse_identifier_str(input)?;
    // キーワードと一致する場合はエラー
    match ident {
        "しき" | "かん" | "く" | "おう" | "場" | "縁" | "間" => Err(nom::Err::Error(nom::error::Error::new(
            input,
            nom::error::ErrorKind::Tag,
        ))),
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
    take_while1(|c: char| !"<>,".contains(c) && !c.is_whitespace() && c != ':')(input)
}

fn parse_type(input: &str) -> IResult<&str, Type> {
    let (input, name) = parse_type_name(input)?;
    let (input, generics) = opt(delimited(
        char('<'),
        separated_list1(delimited(multispace0, char(','), multispace0), parse_type),
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
    // `く` 式
    let ku_parser = map(
        preceded(tuple((tag("く"), multispace1)), parse_quoted_string),
        |id| Expression::Ku { id },
    );

    // `かん` 式
    let kan_parser = map(
        tuple((
            tag("かん"),
            delimited(
                char('('),
                separated_list1(delimited(multispace0, char(','), multispace0), parse_parameter),
                char(')'),
            ),
            delimited(multispace0, tag("=>"), multispace0),
            parse_primary_expression, // 左再帰を避けるため、primary_expression をパース
        )),
        |(_, params, _, body)| Expression::Kan {
            params,
            body: Box::new(body),
        },
    );

    // 変数 or メソッド呼び出し
    let ident_expr_parser = map(parse_identifier, |name| {
        Expression::Identifier(name.to_string())
    });

    let (mut remaining, mut expr) = alt((ku_parser, kan_parser, ident_expr_parser))(input)?;

    // 後続の `.method()` をループでパース
    loop {
        let (next_remaining, method_call) = opt(preceded(
            char('.'),
            map(
                tuple((parse_identifier, tag("()"))),
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
    let (input, _) = tag("しき")(input)?;
    let (input, _) = multispace1(input)?;
    let (input, variable_name) = parse_identifier(input)?;
    let (input, _) = nom::sequence::delimited(multispace0, char(':'), multispace0)(input)?;
    let (input, type_annotation) = parse_type(input)?;
    let (input, _) = nom::sequence::delimited(multispace0, char('='), multispace0)(input)?;
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
        let input = "しき timer_ba: 場 = く \"system/timer\"";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "timer_ba".to_string(),
                    type_annotation: Type::Simple("場".to_string()),
                    value: Expression::Ku {
                        id: "system/timer".to_string()
                    }
                }
            ))
        );
    }

    #[test]
    fn test_parse_shiki_method_call() {
        let input = "しき ticks: 縁<間, i64> = timer_ba.as_en()";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "ticks".to_string(),
                    type_annotation: Type::Generic(
                        "縁".to_string(),
                        vec![
                            Type::Simple("間".to_string()),
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
        let input = "しき pipeline: 縁<間, i64> = ticks |> doubler";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "pipeline".to_string(),
                    type_annotation: Type::Generic(
                        "縁".to_string(),
                        vec![
                            Type::Simple("間".to_string()),
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
        let input = "しき doubler: 縁<i64, i64> = かん(x: i64) => x"; // bodyは簡単のためただの変数
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok((
                "",
                Statement::Shiki {
                    variable_name: "doubler".to_string(),
                    type_annotation: Type::Generic(
                        "縁".to_string(),
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
