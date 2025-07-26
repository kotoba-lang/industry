use kotoba_parser::{Expression, Parameter, Statement, Type};

pub struct Compiler;

fn type_to_string(t: &Type) -> String {
    match t {
        Type::Simple(name) => name.clone(),
        Type::Generic(name, params) => {
            let params_str: Vec<String> = params.iter().map(type_to_string).collect();
            format!("{}<{}>", name, params_str.join(", "))
        }
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
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kotoba_parser::parse_statement;

    #[test]
    fn test_compile_shiki_ku() {
        let input = "shiki timer_ba: ba = ku \"system/timer\"";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet timer_ba: ba = kotoba_core::Ba::new(\"system/timer\");\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_method_call() {
        let input = "shiki ticks: en<ma, i64> = timer_ba.as_en()";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "use kotoba_core::Ba;\n\nlet ticks: en<ma, i64> = timer_ba.as_en();\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_pipe() {
        let input = "shiki pipeline: en<ma, i64> = ticks |> doubler";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet pipeline: en<ma, i64> = pipe(ticks, doubler);\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_kan() {
        let input = "shiki doubler: en<i64, i64> = kan(x: i64) => x";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "use kotoba_core::Ba;\n\nlet doubler: en<i64, i64> = |x: i64| { x };\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }
}
