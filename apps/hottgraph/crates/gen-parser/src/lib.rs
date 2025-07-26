use nom::{
    branch::alt,
    bytes::complete::{tag, take_while, take_while1},
    character::complete::{alpha1, alphanumeric1, char, multispace0, multispace1},
    combinator::recognize,
    sequence::{delimited, pair},
    IResult,
};

/// `gen`言語の文を表すAST（抽象構文木）
#[derive(Debug, PartialEq)]
pub enum Statement {
    /// `let <variable>: <type> = <expression>;`
    Let {
        variable_name: String,
        type_name: String,
        value: Expression,
    },
}

/// `gen`言語の式を表すAST
#[derive(Debug, PartialEq)]
pub enum Expression {
    /// `create "<id>"`
    NodeCreation {
        id: String,
    },
}

fn parse_identifier(input: &str) -> IResult<&str, &str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        take_while1(|c: char| c.is_alphanumeric() || c == '_'),
    ))(input)
}

fn parse_quoted_string(input: &str) -> IResult<&str, &str> {
    delimited(
        char('"'),
        take_while(|c: char| c != '"'),
        char('"')
    )(input)
}

pub fn parse_statement(input: &str) -> IResult<&str, Statement> {
    let (input, _) = tag("let")(input)?;
    let (input, _) = multispace1(input)?;
    let (input, variable_name) = parse_identifier(input)?;
    let (input, _) = delimited(multispace0, char(':'), multispace0)(input)?;
    let (input, type_name) = alpha1(input)?;
    let (input, _) = delimited(multispace0, char('='), multispace0)(input)?;
    let (input, expr) = parse_expression(input)?;

    Ok((input, Statement::Let { 
        variable_name: variable_name.to_string(), 
        type_name: type_name.to_string(),
        value: expr
    }))
}

pub fn parse_expression(input: &str) -> IResult<&str, Expression> {
    let (input, _) = tag("create")(input)?;
    let (input, _) = multispace1(input)?;
    let (input, id) = parse_quoted_string(input)?;
    Ok((input, Expression::NodeCreation{ id: id.to_string() }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_let_statement() {
        let input = "let my_node: Node = create \"system/timer\"";
        let result = parse_statement(input);
        assert_eq!(
            result,
            Ok(("", Statement::Let {
                variable_name: "my_node".to_string(),
                type_name: "Node".to_string(),
                value: Expression::NodeCreation {
                    id: "system/timer".to_string(),
                }
            }))
        );
    }
}
