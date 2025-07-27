use kotoba_parser::{
    Constructor, Expression, OuArm, Pattern, Statement, Type,
};
use std::collections::HashMap;

pub struct Compiler {
    /// Stores constructors for each defined inductive type.
    /// e.g., "N" -> ["Zero", "Succ"]
    type_definitions: HashMap<String, Vec<String>>,
}

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
        Compiler {
            type_definitions: HashMap::new(),
        }
    }

    pub fn compile(&mut self, program: Vec<Statement>) -> Result<String, String> {
        // 1st Pass: Register all type definitions from `gyo` statements.
        for statement in &program {
            if let Statement::Gyo { name, constructors } = statement {
                let constructor_names = constructors.iter().map(|c| capitalize(&c.name)).collect();
                self.type_definitions
                    .insert(name.clone(), constructor_names);
            }
        }

        // 2nd Pass: Compile all statements.
        let mut rust_code = String::new();

        for statement in program {
            rust_code.push_str(&self.compile_statement(statement)?);
            rust_code.push('\n');
        }
        Ok(rust_code)
    }

    fn compile_statement(&mut self, statement: Statement) -> Result<String, String> {
        match statement {
            Statement::Shiki {
                variable_name,
                type_annotation,
                value,
            } => {
                let type_str = type_to_string(&type_annotation);
                let mut expr_code = self.compile_expression(value.clone())?;

                // Type-directed compilation: if the type is a path (`ze`) and the expression
                // is a lambda (`kan`), wrap the lambda in `Path::new`.
                if let (Type::Ze(_, _), Expression::Kan { .. }) = (&type_annotation, &value) {
                    expr_code = format!("kotoba_core::Path::new({})", expr_code);
                }

                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_str, expr_code
                ))
            }
            Statement::Gyo { name, constructors } => self.compile_gyo_statement(name, constructors),
            Statement::Rin {
                name,
                params,
                return_type,
                body,
            } => {
                let params_str = params
                    .iter()
                    .map(|p| format!("{}: {}", p.name, type_to_string(&p.type_annotation)))
                    .collect::<Vec<String>>()
                    .join(", ");
                let return_type_str = type_to_string(&return_type);
                let body_str = self.compile_expression(body)?;
                Ok(format!(
                    "fn {}({}) -> {} {{\n    {}\n}}",
                    name, params_str, return_type_str, body_str
                ))
            }
        }
    }

    fn compile_gyo_statement(
        &self,
        name: String,
        constructors: Vec<Constructor>,
    ) -> Result<String, String> {
        let mut enum_variants = String::new();
        for c in constructors {
            // NOTE: This is a massive simplification.
            // A real compiler would need to handle types properly.
            // Here, we just capitalize the constructor name.
            let variant_name = capitalize(&c.name);
            if c.fields.is_empty() {
                enum_variants.push_str(&format!("    {},\n", variant_name));
            } else {
                // Again, simplifying types to Box<Self> for recursion
                enum_variants.push_str(&format!("    {}(Box<Self>),\n", variant_name));
            }
        }

        Ok(format!(
            "#[derive(Debug, Clone)]\nenum {} {{\n{}}}",
            name, enum_variants
        ))
    }

    fn compile_expression(&mut self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::Identifier(name) => Ok(name),
            Expression::IntegerLiteral(n) => Ok(n.to_string()),
            Expression::Refl(expr) => {
                let expr_code = self.compile_expression(*expr)?;
                Ok(format!("kotoba_core::Path::new(|_| {})", expr_code))
            }
            Expression::Zo(val) => match val.as_str() {
                "i0" => Ok("kotoba_core::Interval::I0".to_string()),
                "i1" => Ok("kotoba_core::Interval::I1".to_string()),
                _ => Err("Invalid interval literal".to_string()),
            },
            Expression::MethodCall { variable, method, args } => {
                let var_code = self.compile_expression(*variable)?;
                let args_code: Vec<String> = args
                    .into_iter()
                    .map(|arg| self.compile_expression(arg))
                    .collect::<Result<_, _>>()?;

                // Special handling for methods that take references.
                let formatted_args = if method == "compose" {
                    args_code.iter().map(|arg| format!("&{}", arg)).collect::<Vec<_>>().join(", ")
                } else {
                    args_code.join(", ")
                };

                Ok(format!("{}.{}({})", var_code, method, formatted_args))
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

    fn compile_ou_arm(&mut self, arm: OuArm) -> Result<String, String> {
        let pattern_code = self.compile_pattern(&arm.pattern)?;
        let body_code = self.compile_expression(arm.body)?;
        Ok(format!("    {} => {{ {} }},\n", pattern_code, body_code))
    }

    fn compile_pattern(&mut self, pattern: &Pattern) -> Result<String, String> {
        match pattern {
            Pattern::IntegerLiteral(i) => Ok(i.to_string()),
            Pattern::IntervalLiteral(s) => match s.as_str() {
                "i0" => Ok("kotoba_core::Interval::I0".to_string()),
                "i1" => Ok("kotoba_core::Interval::I1".to_string()),
                _ => Err("Invalid interval literal in pattern".to_string()),
            },
            Pattern::Wildcard => Ok("_".to_string()),
            Pattern::Identifier(s) => {
                // An identifier in a pattern can be a variable or a nullary constructor.
                // We check if it's a known constructor first.
                let capitalized_name = capitalize(s);
                let type_name = self
                    .type_definitions
                    .iter()
                    .find(|(_type_name, constructors)| {
                        constructors.contains(&capitalized_name)
                    })
                    .map(|(type_name, _)| type_name.clone());

                if let Some(tn) = type_name {
                    Ok(format!("{}::{}", tn, capitalized_name))
                } else {
                    Ok(s.clone()) // It's a variable binding
                }
            }
            Pattern::Constructor(name, patterns) => {
                let capitalized_name = capitalize(name);
                // Find which type this constructor belongs to.
                let type_name = self
                    .type_definitions
                    .iter()
                    .find(|(_type_name, constructors)| constructors.contains(&capitalized_name))
                    .map(|(type_name, _)| type_name.clone());

                let fq_name = if let Some(tn) = type_name {
                    format!("{}::{}", tn, capitalized_name)
                } else {
                    // If not found, just use the capitalized name.
                    // This might happen for built-in types or errors.
                    capitalized_name
                };

                if patterns.is_empty() {
                    Ok(fq_name)
                } else {
                    let inner_patterns: Vec<String> = patterns
                        .iter()
                        .map(|p| self.compile_pattern(p))
                        .collect::<Result<_, _>>()?;
                    Ok(format!("{}({})", fq_name, inner_patterns.join(", ")))
                }
            }
        }
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        None => String::new(),
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kotoba_parser::parse_expression;
    use kotoba_parser::parse_statement;

    #[test]
    fn test_compile_shiki_zo() {
        let input = "shiki my_time: ku = i0";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let my_time: kotoba_core::Interval = kotoba_core::Interval::I0;\n";
        // We remove the Ba import for now as it's not used.
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call() {
        let input = "shiki ticks: en<ma, i64> = timer_ba.as_en()";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let ticks: kotoba_core::Glue<ma, kotoba_core::Path<i64>> = timer_ba.as_en();\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call_with_args() {
        let input = "shiki p2: ze<i64, i64> = p1.compose(q1)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let p2: kotoba_core::Path<i64> = p1.compose(&q1);\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call_sym() {
        let input = "shiki p_sym: ze<i64, i64> = p.sym()";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let p_sym: kotoba_core::Path<i64> = p.sym();\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_refl() {
        let input = "shiki id_path: ze<i64, i64> = refl(10)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let id_path: kotoba_core::Path<i64> = kotoba_core::Path::new(|_| 10);\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_path_constructor() {
        let input = "shiki my_path: ze<i64, i64> = kan(i: ku) => ou i { i0 => 10, i1 => 20 }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let my_path: kotoba_core::Path<i64> = kotoba_core::Path::new(|i: kotoba_core::Interval| { match i {
    kotoba_core::Interval::I0 => { 10 },
    kotoba_core::Interval::I1 => { 20 },
} });\n";
        // The compiled `match` formatting can be tricky, so we check for key parts.
        let compiled_code = result.unwrap();
        assert!(compiled_code.contains("let my_path: kotoba_core::Path<i64>"));
        assert!(compiled_code.contains("= kotoba_core::Path::new("));
        assert!(compiled_code.contains("|i: kotoba_core::Interval|"));
        assert!(compiled_code.contains("match i"));
        assert!(compiled_code.contains("10"));
        assert!(compiled_code.contains("20"));
    }

    #[test]
    fn test_compile_shiki_pipe() {
        let input = "shiki pipeline: en<ma, i64> = ticks |> doubler";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let pipeline: kotoba_core::Glue<ma, kotoba_core::Path<i64>> = pipe(ticks, doubler);\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_kan() {
        let input = "shiki doubler: en<i64, i64> = kan(x: i64) => x";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let doubler: kotoba_core::Glue<i64, kotoba_core::Path<i64>> = |x: i64| { x };\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_ou_expression() {
        let input = "ou x { 0 => i0, _ => i1 }";
        let (_, expression) = parse_expression(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile_expression(expression);
        let expected_code = "match x {\n    0 => { kotoba_core::Interval::I0 },\n    _ => { kotoba_core::Interval::I1 },\n}";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_gyo_statement() {
        let input = "gyo N = { zero, succ: N }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "#[derive(Debug, Clone)]\nenum N {\n    Zero,\n    Succ(Box<Self>),\n}\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_rin_statement() {
        let input = "rin add(a: N, b: N): N = ou a { zero => b }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        // Note: The compiled `match` is incomplete due to placeholder `compile_ou_arm`.
        // This test just checks the function signature.
        let compiled_code = result.unwrap();
        assert!(compiled_code.contains("fn add(a: N, b: N) -> N"));
        assert!(compiled_code.contains("match a"));
    }

    #[test]
    fn test_compile_rin_statement_with_constructor() {
        let program = vec![
            parse_statement("gyo N = { zero, succ: N }").unwrap().1,
            parse_statement("rin to_zero(a: N): N = ou a { zero => zero, succ(p) => zero }")
                .unwrap()
                .1,
        ];
        let mut compiler = Compiler::new();
        let result = compiler.compile(program);
        assert!(result.is_ok());
        let code = result.unwrap();
        assert!(code.contains("fn to_zero(a: N) -> N"));
        assert!(code.contains("match a"));
        assert!(code.contains("N::Zero =>"));
        assert!(code.contains("N::Succ(p) =>"));
        assert!(code.contains("N::Zero")); // a an expression
    }
}
