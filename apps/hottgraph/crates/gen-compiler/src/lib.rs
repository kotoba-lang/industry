use gen_parser::{Expression, Statement};

pub struct Compiler;

impl Compiler {
    pub fn new() -> Self {
        Compiler
    }

    pub fn compile(&self, program: Vec<Statement>) -> Result<String, String> {
        let mut rust_code = String::new();
        for statement in program {
            rust_code.push_str(&self.compile_statement(statement)?);
            rust_code.push('\n');
        }
        Ok(rust_code)
    }

    fn compile_statement(&self, statement: Statement) -> Result<String, String> {
        match statement {
            Statement::Let {
                variable_name,
                type_name,
                value,
            } => {
                let expr_code = self.compile_expression(value)?;
                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_name, expr_code
                ))
            }
        }
    }

    fn compile_expression(&self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::InCreation { id } => {
                Ok(format!("gen_core::In::new(\"{}\")", id))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gen_parser::parse_statement;

    #[test]
    fn test_compile_let_in_creation() {
        let input = "let my_in: In = create \"system/timer\"";
        let (_, statement) = parse_statement(input).unwrap();
        
        let compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);

        let expected_code = "let my_in: In = gen_core::In::new(\"system/timer\");\n";
        assert_eq!(result, Ok(expected_code.to_string()));
    }
}
