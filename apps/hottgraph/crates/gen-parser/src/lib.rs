use nom::{
    IResult,
    branch::alt,
    bytes::complete::{tag, take_while1},
    character::complete::{alpha1, char, multispace0, multispace1},
    combinator::{map, opt, recognize},
    multi::separated_list1,
    sequence::{delimited, pair, preceded, tuple},
};

/// 型を表すAST
#[derive(Debug, PartialEq, Clone)]
pub enum Type {
    /// `場`, `縁`, `間`, `i64` などのシンプルな型
    Simple(String),
    /// `縁<T1, T2>` のようなジェネリック型
    Generic(String, Vec<Type>),
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

fn parse_identifier(input: &str) -> IResult<&str, &str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        nom::bytes::complete::take_while(|c: char| c.is_alphanumeric() || c == '_'),
    ))(input)
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

fn parse_expression(input: &str) -> IResult<&str, Expression> {
    // `く` 式
    let ku_parser = map(
        preceded(tuple((tag("く"), multispace1)), parse_quoted_string),
        |id| Expression::Ku { id },
    );

    // 変数 or メソッド呼び出し
    let ident_parser = map(parse_identifier, |name| {
        Expression::Identifier(name.to_string())
    });

    // まずは変数としてパースを試みる
    let (mut remaining, mut expr) = alt((ku_parser, ident_parser))(input)?;

    // 後続の `.method()` をループでパース
    loop {
        let (next_remaining, method_call) = opt(preceded(
            char('.'),
            map(tuple((parse_identifier, tag("()"))), |(method, _)| {
                method.to_string()
            }),
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
}
