use kotoba_parser::{Expression, OuArm, Pattern, Statement, Type};

pub struct Compiler;

fn type_to_string(t: &Type) -> String {
    match t {
        Type::Ku => "kotoba_core::Interval".to_string(),
        Type::Ze(t1, _t2) => {
            // This is a placeholder. A real implementation would need to handle
            // the fact that Path is generic over a single type, but the
            // ze type represents equality between two values *of the same type*.
            // For now, we just represent the type of the path's content.
            format!("kotoba_core::Path<{}>", type_to_string(t1))
        }
        Type::En(t1, t2) => {
            // Placeholder for Glue type compilation
            format!(
                "kotoba_core::Glue<{}, kotoba_core::Path<{}>>",
                type_to_string(t1),
                type_to_string(t2)
            )
        }
        Type::Simple(name) => name.clone(),
    }
}

impl Compiler {
    pub fn new() -> Self {
        Compiler
    }

    pub fn compile(&self, program: Vec<Statement>) -> Result<String, String> {
        let mut rust_code = String::new();
        rust_code.push_str("use kotoba_core::Ba;\n\n");

        for statement in program {
            rust_code.push_str(&self.compile_statement(statement)?);
            rust_code.push('\n');
        }
        Ok(rust_code)
    }

    fn compile_statement(&self, statement: Statement) -> Result<String, String> {
        match statement {
            Statement::Shiki {
                variable_name,
                type_annotation,
                value,
            } => {
                let type_str = type_to_string(&type_annotation);
                let expr_code = self.compile_expression(value)?;
                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_str, expr_code
                ))
            }
        }
    }

    fn compile_expression(&self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::Ku { id } => Ok(format!("kotoba_core::Ba::new(\"{}\")", id)),
            Expression::Identifier(name) => Ok(name),
            Expression::Zo(val) => match val.as_str() {
                "i0" => Ok("kotoba_core::Interval::I0".to_string()),
                "i1" => Ok("kotoba_core::Interval::I1".to_string()),
                _ => Err("Invalid interval literal".to_string()),
            },
            Expression::MethodCall { variable, method } => {
                let var_code = self.compile_expression(*variable)?;
                Ok(format!("{}.{}()", var_code, method))
            }
            Expression::Pipe { lhs, rhs } => {
                let lhs_code = self.compile_expression(*lhs)?;
                let rhs_code = self.compile_expression(*rhs)?;
                Ok(format!("pipe({}, {})", lhs_code, rhs_code))
            }
            Expression::Kan { params, body } => {
                let params_str = params
                    .iter()
                    .map(|p| format!("{}: {}", p.name, type_to_string(&p.type_annotation)))
                    .collect::<Vec<String>>()
                    .join(", ");

                let body_code = self.compile_expression(*body)?;

                Ok(format!("|{}| {{ {} }}", params_str, body_code))
            }
            Expression::Ou { expression, arms } => {
                let expr_code = self.compile_expression(*expression)?;
                let mut arms_code = String::new();
                for arm in arms {
                    arms_code.push_str(&self.compile_ou_arm(arm)?);
                }
                Ok(format!("match {} {{\n{}}}", expr_code, arms_code))
            }
        }
    }

    fn compile_ou_arm(&self, arm: OuArm) -> Result<String, String> {
        let pattern_code = match arm.pattern {
            Pattern::IntegerLiteral(i) => i.to_string(),
            Pattern::Wildcard => "_".to_string(),
        };
        let body_code = self.compile_expression(arm.body)?;
        Ok(format!("    {} => {{ {} }},\n", pattern_code, body_code))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kotoba_parser::parse_expression;
    use kotoba_parser::parse_statement;

    #[test]
    fn test_compile_shiki_ku_and_zo() {
        let input = "shiki my_time: ku = i0";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet my_time: kotoba_core::Interval = kotoba_core::Interval::I0;\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_method_call() {
        let input = "shiki ticks: en<ma, i64> = timer_ba.as_en()";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "use kotoba_core::Ba;\n\nlet ticks: kotoba_core::Glue<ma, kotoba_core::Path<i64>> = timer_ba.as_en();\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_pipe() {
        let input = "shiki pipeline: en<ma, i64> = ticks |> doubler";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet pipeline: kotoba_core::Glue<ma, kotoba_core::Path<i64>> = pipe(ticks, doubler);\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_kan() {
        let input = "shiki doubler: en<i64, i64> = kan(x: i64) => x";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet doubler: kotoba_core::Glue<i64, kotoba_core::Path<i64>> = |x: i64| { x };\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_ou_expression() {
        let input = "ou x { 0 => ku \"zero\", _ => ku \"other\" }";
        let (_, expression) = parse_expression(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile_expression(expression);
        let expected_code = "match x {\n    0 => { kotoba_core::Ba::new(\"zero\") },\n    _ => { kotoba_core::Ba::new(\"other\") },\n}";
        assert_eq!(result, Ok(expected_code.to_string()));
    }
}
