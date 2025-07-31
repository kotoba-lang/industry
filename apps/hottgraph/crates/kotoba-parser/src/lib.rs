use nom::{
    branch::alt,
    bytes::complete::{is_not, tag},
    character::complete::{alpha1, alphanumeric1, char, multispace1, i64 as nom_i64},
    combinator::{cut, map, opt, recognize, value, verify},
    multi::{many0, separated_list0, separated_list1},
    sequence::{delimited, pair, preceded, tuple},
    IResult, Parser,
};

type ParseResult<'a, O> = IResult<&'a str, O, nom::error::Error<&'a str>>;

/// Type AST
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

/// Expression AST
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

/// Statement AST
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

fn sp(input: &str) -> ParseResult<&str> {
    recognize(many0(alt((
        value((), multispace1),
        value((), pair(tag("//"), is_not("\n\r"))),
    ))))
    .parse(input)
}

fn parse_identifier_str(input: &str) -> ParseResult<&str> {
    recognize(pair(
        alt((alpha1, tag("_"))),
        many0(alt((alphanumeric1, tag("_")))),
    ))
    .parse(input)
}

fn parse_identifier(input: &str) -> ParseResult<&str> {
    verify(parse_identifier_str, |s: &str| {
        !matches!(
            s,
            "shiki" | "kan" | "ku" | "ou" | "ba" | "en" | "ma" | "ze" | "i0" | "i1" | "gyo" | "rin" | "Type" | "let" | "in" | "if" | "then" | "else"
        )
    })
    .parse(input)
}

// --- Type Parsers ---
pub fn parse_type(input: &str) -> ParseResult<Type> {
    // Implementation from before, adapted for nom 7 if needed
    // For brevity, assuming it's correct for now.
    alt((
        map(
            tuple((
                delimited(
                    char('('),
                    pair(
                        map(parse_identifier, String::from),
                        preceded(delimited(sp, char(':'), sp), parse_type),
                    ),
                    char(')'),
                ),
                preceded(delimited(sp, tag("->"), sp), parse_type),
            )),
            |((binder_name, binder_type), return_type)| Type::Pi {
                binder_name,
                binder_type: Box::new(binder_type),
                return_type: Box::new(return_type),
            },
        ),
        map(
            pair(parse_atomic_type, preceded(delimited(sp, tag("->"), sp), parse_type)),
            |(lhs, rhs)| Type::Func(Box::new(lhs), Box::new(rhs)),
        ),
        parse_atomic_type,
    )).parse(input)
}

fn parse_atomic_type(input: &str) -> ParseResult<Type> {
    let (input, head) = parse_single_atomic_type(input)?;
    let (input, args) = many0(preceded(sp, parse_single_atomic_type)).parse(input)?;
    if args.is_empty() {
        Ok((input, head))
    } else {
        Ok((input, Type::App(Box::new(head), args)))
    }
}

fn parse_single_atomic_type(input: &str) -> ParseResult<Type> {
    alt((
        map(tag("()"), |_| Type::Unit),
        map(tag("ku"), |_| Type::Ku),
        map(tag("Type"), |_| Type::Ident("Type".to_string())),
        parse_ze_or_en_type,
        delimited(
            char('('),
            alt((
                parse_type,
                map(parse_expression, |e| Type::Expr(Box::new(e))),
            )),
            char(')'),
        ),
        map(parse_identifier_str, |s| Type::Ident(s.to_string())),
    )).parse(input)
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
        tuple((
            map(parse_identifier, |s| s.to_string()),
            preceded(delimited(sp, char(':'), sp), parse_type),
        )),
        |(name, type_annotation)| Parameter {
            name,
            type_annotation,
        },
    )
    .parse(input)
}

fn parse_pattern(input: &str) -> ParseResult<Pattern> {
    // Implementation from before, adapted for nom 7 if needed
    let integer_literal_parser = map(nom_i64, Pattern::IntegerLiteral);
    let interval_literal_parser = map(alt((tag("i0"), tag("i1"))), |s: &str| Pattern::IntervalLiteral(s.to_string()));
    let wildcard_parser = map(tag("_"), |_| Pattern::Wildcard);
    let identifier_parser = map(parse_identifier, |s| Pattern::Identifier(s.to_string()));
    let constructor_parser = map(
        pair(
            map(parse_identifier_str, |s| s.to_string()),
            opt(delimited(char('('), separated_list0(delimited(sp, char(','), sp), parse_pattern), char(')'))),
        ),
        |(name, args)| Pattern::Constructor(name, args.unwrap_or_default()),
    );
    alt((integer_literal_parser, interval_literal_parser, wildcard_parser, constructor_parser, identifier_parser)).parse(input)
}

fn parse_ou_arm(input: &str) -> ParseResult<OuArm> {
    map(
        tuple((
            parse_pattern,
            preceded(delimited(sp, tag("=>"), sp), parse_expression),
        )),
        |(pattern, body)| OuArm { pattern, body },
    )
    .parse(input)
}

// --- Expression Parsers (Rewritten) ---

fn parse_primary(input: &str) -> ParseResult<Expression> {
    alt((
        map(nom_i64, Expression::IntegerLiteral),
        map(parse_identifier, |s| Expression::Identifier(s.to_string())),
        map(tag("()"), |_| Expression::Unit),
        map(alt((tag("i0"), tag("i1"))), |s: &str| Expression::Zo(s.to_string())),
        delimited(char('('), parse_expression, char(')')),
        // Simplified parsers for glue/refl for now
        map(preceded(tag("refl"), delimited(char('('), parse_expression, char(')'))), |e| Expression::Refl(Box::new(e))),
        map(
            preceded(tag("glue"), delimited(char('('), tuple((parse_expression, preceded(tag(","), parse_expression), preceded(tag(","), parse_expression))), char(')'))),
            |(base, boundary, equivalence)| Expression::Glue { base: Box::new(base), boundary: Box::new(boundary), equivalence: Box::new(equivalence) }
        ),
         map(preceded(tag("unglue"), delimited(char('('), parse_expression, char(')'))), |e| Expression::Unglue { value: Box::new(e) }),
    )).parse(input)
}

fn parse_call(input: &str) -> ParseResult<Expression> {
    let (mut input, mut expr) = parse_primary(input)?;
    loop {
        let (next_input, method_call) = opt(preceded(
            tag("."),
            pair(
                map(parse_identifier, |s| s.to_string()),
                opt(delimited(
                    char('('),
                    separated_list0(delimited(sp, char(','), sp), parse_expression),
                    char(')'),
                )),
            ),
        ))
        .parse(input)?;

        if let Some((method, args)) = method_call {
            expr = Expression::MethodCall {
                variable: Box::new(expr),
                method,
                args: args.unwrap_or_default(),
            };
            input = next_input;
        } else {
            break;
        }
    }
    Ok((input, expr))
}

fn parse_multiplicative(input: &str) -> ParseResult<Expression> {
    let (input, mut lhs) = parse_call(input)?;
    let (input, ops) = many0(pair(
        delimited(sp, alt((
            value(Operator::Multiply, tag("*")),
            value(Operator::Divide, tag("/")),
        )), sp),
        parse_call,
    )).parse(input)?;

    for (op, rhs) in ops {
        lhs = Expression::BinaryOp { op, lhs: Box::new(lhs), rhs: Box::new(rhs) };
    }
    Ok((input, lhs))
}

fn parse_additive(input: &str) -> ParseResult<Expression> {
    let (input, mut lhs) = parse_multiplicative(input)?;
    let (input, ops) = many0(pair(
        delimited(sp, alt((
            value(Operator::Add, tag("+")),
            value(Operator::Subtract, tag("-")),
        )), sp),
        parse_multiplicative,
    )).parse(input)?;

    for (op, rhs) in ops {
        lhs = Expression::BinaryOp { op, lhs: Box::new(lhs), rhs: Box::new(rhs) };
    }
    Ok((input, lhs))
}

fn parse_comparison(input: &str) -> ParseResult<Expression> {
    let (input, mut lhs) = parse_additive(input)?;
    let (input, ops) = many0(pair(
        delimited(sp, alt((
            value(Operator::Equals, tag("==")),
            value(Operator::NotEquals, tag("!=")),
            value(Operator::LessThan, tag("<")),
            value(Operator::GreaterThan, tag(">")),
            value(Operator::LessThanOrEqual, tag("<=")),
            value(Operator::GreaterThanOrEqual, tag(">=")),
        )), sp),
        parse_additive,
    )).parse(input)?;

    for (op, rhs) in ops {
        lhs = Expression::BinaryOp { op, lhs: Box::new(lhs), rhs: Box::new(rhs) };
    }
    Ok((input, lhs))
}

fn parse_pipe(input: &str) -> ParseResult<Expression> {
    let (input, mut lhs) = parse_comparison(input)?;
    let (input, ops) = many0(pair(
        delimited(sp, tag("|>"), sp),
        parse_comparison,
    )).parse(input)?;

    for (_, rhs) in ops {
        lhs = Expression::Pipe { lhs: Box::new(lhs), rhs: Box::new(rhs) };
    }
    Ok((input, lhs))
}

fn parse_if(input: &str) -> ParseResult<Expression> {
    map(
        tuple((
            preceded(tag("if"), sp),
            parse_expression,
            preceded(sp, tag("then")),
            parse_expression,
            preceded(sp, tag("else")),
            parse_expression,
        )),
        |(_, condition, _, then_branch, _, else_branch)| Expression::If {
            condition: Box::new(condition),
            then_branch: Box::new(then_branch),
            else_branch: Box::new(else_branch),
        },
    ).parse(input)
}

fn parse_let(input: &str) -> ParseResult<Expression> {
    map(
        tuple((
            preceded(tag("let"), multispace1),
            map(parse_identifier, |s| s.to_string()),
            opt(preceded(delimited(sp, char(':'), sp), parse_type)),
            delimited(sp, char('='), sp),
            parse_expression,
            delimited(sp, tag("in"), sp),
            parse_expression,
        )),
        |(_, name, type_annotation, _, value, _, body)| Expression::Let {
            name,
            type_annotation: type_annotation.map(Box::new),
            value: Box::new(value),
            body: Box::new(body),
        },
    ).parse(input)
}

fn parse_kan(input: &str) -> ParseResult<Expression> {
    map(
        tuple((
            preceded(tag("kan"), sp),
            delimited(
                char('('),
                separated_list0(delimited(sp, char(','), sp), parse_parameter),
                char(')'),
            ),
            preceded(sp, tag("=>")),
            cut(parse_expression),
        )),
        |(_, params, _, body)| Expression::Kan { params, body: Box::new(body) },
    ).parse(input)
}

fn parse_ou(input: &str) -> ParseResult<Expression> {
    map(
        tuple((
            preceded(tag("ou"), sp),
            parse_call, // expression to match on
            preceded(sp, delimited(char('{'), separated_list0(delimited(sp, char(','), sp), parse_ou_arm), char('}'))),
        )),
        |(_, expression, arms)| Expression::Ou { expression: Box::new(expression), arms },
    ).parse(input)
}

pub fn parse_expression(input: &str) -> ParseResult<Expression> {
    alt((parse_if, parse_let, parse_kan, parse_ou, parse_pipe)).parse(input)
}

// --- Statement Parsers ---
pub fn parse_statement(input: &str) -> ParseResult<Statement> {
    // Implementation from before, adapted for nom 7 if needed
    let shiki_parser = map(
        tuple((
            preceded(tag("shiki"), multispace1),
            map(parse_identifier, |s| s.to_string()),
            preceded(delimited(sp, char(':'), sp), parse_type),
            preceded(delimited(sp, char('='), sp), parse_expression),
        )),
        |(_, variable_name, type_annotation, value)| Statement::Shiki { variable_name, type_annotation, value },
    );

    let gyo_parser = map(
        tuple((
            preceded(tag("gyo"), multispace1),
            map(parse_identifier, |s| s.to_string()),
            preceded(delimited(sp, char('='), sp), delimited(
                char('{'),
                separated_list0(delimited(sp, char(','), sp), parse_constructor_def),
                char('}'),
            )),
        )),
        |(_, name, constructors)| Statement::Gyo { name, params: vec![], constructors }, // Simplified params for now
    );
    
    let rin_parser = map(
        tuple((
            preceded(tag("rin"), multispace1),
            map(parse_identifier, |s| s.to_string()),
            opt(delimited(char('<'), separated_list1(delimited(sp, char(','), sp), map(parse_identifier, |s| s.to_string())), char('>'))),
            delimited(char('('), separated_list0(delimited(sp, char(','), sp), parse_parameter), char(')')),
            preceded(delimited(sp, char(':'), sp), parse_type),
            preceded(delimited(sp, char('='), sp), parse_expression),
        )),
        |(_, name, generics, params, return_type, body)| Statement::Rin { name, generics: generics.unwrap_or_default(), params, return_type, body },
    );

    alt((shiki_parser, gyo_parser, rin_parser)).parse(input)
}

fn parse_constructor_def(input: &str) -> ParseResult<ConstructorDef> {
    let mut point_parser = map(
        pair(
            map(parse_identifier, |s| s.to_string()),
            opt(delimited(char('('), separated_list1(delimited(sp, char(','), sp), parse_type), char(')'))),
        ),
        |(name, fields)| ConstructorDef::Point { name, fields: fields.unwrap_or_default() },
    );
    // Path parser can be added here
    point_parser.parse(input)
} 

pub fn parse_program(input: &str) -> ParseResult<Vec<Statement>> {
    many0(parse_statement).parse(input)
} 