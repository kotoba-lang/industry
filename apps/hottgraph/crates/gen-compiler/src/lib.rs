use gen_parser::{Expression, Statement, Type};

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
        // use文などをここに追加する余地
        rust_code.push_str("use gen_core::Ba;\n\n");

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
            Expression::Ku { id } => Ok(format!("Ba::new(\"{}\")", id)),
            Expression::Identifier(name) => Ok(name),
            Expression::MethodCall { variable, method } => {
                let var_code = self.compile_expression(*variable)?;
                Ok(format!("{}.{}()", var_code, method))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gen_parser::parse_statement;

    #[test]
    fn test_compile_shiki_ku() {
        let input = "しき timer_ba: 場 = く \"system/timer\"";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "use gen_core::Ba;\n\nlet timer_ba: 場 = Ba::new(\"system/timer\");\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_shiki_method_call() {
        let input = "しき ticks: 縁<間, i64> = timer_ba.as_en()";
        let (_, statement) = parse_statement(input).unwrap();
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "use gen_core::Ba;\n\nlet ticks: 縁<間, i64> = timer_ba.as_en();\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }
}
