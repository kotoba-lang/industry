use kotoba_parser::{
    ConstructorDef, Expression, OuArm, Pattern, Statement, Type, Parameter
};
use std::collections::HashMap;

/// Stores information about types and variables in the current scope.
#[derive(Debug, Clone, PartialEq)]
pub struct Context {
    /// Maps a type name (e.g., "N") to its parameters and constructor definitions.
    type_definitions: HashMap<String, (Vec<Parameter>, Vec<ConstructorDef>)>,
    /// Scoped variables. Each element in the vector represents a new scope.
    scopes: Vec<HashMap<String, Type>>,
}

impl Context {
    fn new() -> Self {
        Context {
            type_definitions: HashMap::new(),
            scopes: vec![HashMap::new()],
        }
    }

    fn enter_scope(&mut self) {
        self.scopes.push(HashMap::new());
    }

    fn exit_scope(&mut self) {
        self.scopes.pop();
    }

    fn define_var(&mut self, name: String, ty: Type) {
        self.scopes.last_mut().unwrap().insert(name, ty);
    }

    fn find_var(&self, name: &str) -> Option<&Type> {
        self.scopes.iter().rev().find_map(|scope| scope.get(name))
    }

    fn find_constructor_type(&self, constructor_name: &str) -> Option<&String> {
        let capitalized_name = capitalize(constructor_name);
        self.type_definitions
            .iter()
            .find_map(|(type_name, (_params, constructors))| {
                if constructors.iter().any(|c| match c {
                    ConstructorDef::Point { name, .. } => capitalize(name) == capitalized_name,
                    ConstructorDef::Path { name, .. } => capitalize(&name) == capitalized_name,
                }) {
                    Some(type_name)
                } else {
                    None
                }
            })
    }
}


/// Represents a runtime value during type checking and interpretation.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    I64(i64),
    Bool(bool),
    Unit,
    Type(Type),
    Pi {
        binder_name: String,
        binder_type: Box<Value>,
        body: Box<Expression>,
        captured_context: Context,
    },
    Constructor(String),
}

#[derive(Debug, PartialEq)]
pub enum TypeError {
    UndefinedVariable(String),
    UndefinedType(String),
    TypeMismatch { expected: String, found: String },
    NotAFunction(String),
    ConstructorArityMismatch { name: String, expected: usize, found: usize },
    EmptyOuExpression,
    NotImplemented(String),
}

#[derive(Debug, PartialEq)]
pub enum EvalError {
    UndefinedVariable(String),
    TypeMismatch,
}

#[derive(Debug, Clone)]
pub struct Compiler {
    context: Context,
    environment: Vec<HashMap<String, Value>>,
}

fn type_to_string(t: &Type) -> String {
    match t {
        Type::Ku => "ku".to_string(),
        Type::Ze(t1, t2) => format!("ze<{}, {}>", type_to_string(t1), type_to_string(t2)),
        Type::En(t1, t2, t3) => format!("en<{}, {}, {}>", type_to_string(t1), type_to_string(t2), type_to_string(t3)),
        Type::Unit => "()".to_string(),
        Type::Func(from, to) => format!("{} -> {}", type_to_string(from), type_to_string(to)),
        Type::Pi { binder_name, binder_type, return_type, .. } => {
            format!("({}: {}) -> {}", binder_name, type_to_string(binder_type), type_to_string(return_type))
        }
        Type::Ident(name) => name.clone(),
        Type::App(head, args) => {
            let head_str = type_to_string(head);
            let args_str = args.iter().map(type_to_string).collect::<Vec<_>>().join(" ");
            format!("{} {}", head_str, args_str)
        }
        Type::Expr(expr) => format!("{:?}", expr),
    }
}


impl Compiler {
    fn env_enter_scope(&mut self) {
        self.environment.push(HashMap::new());
    }

    fn env_exit_scope(&mut self) {
        self.environment.pop();
    }

    fn env_define_var(&mut self, name: String, val: Value) {
        self.environment.last_mut().unwrap().insert(name, val);
    }

    fn env_find_var(&self, name: &str) -> Option<&Value> {
        self.environment.iter().rev().find_map(|scope| scope.get(name))
    }

    fn evaluate(&mut self, expr: &Expression) -> Result<Value, EvalError> {
        match expr {
            Expression::IntegerLiteral(n) => Ok(Value::I64(*n)),
            Expression::Identifier(name) => self.env_find_var(name).cloned().ok_or_else(|| EvalError::UndefinedVariable(name.clone())),
            Expression::Let { name, value, body, .. } => {
                let val = self.evaluate(value)?;
                self.env_enter_scope();
                self.env_define_var(name.clone(), val);
                let result = self.evaluate(body)?;
                self.env_exit_scope();
                Ok(result)
            }
            Expression::If { condition, then_branch, else_branch } => {
                let cond_val = self.evaluate(condition)?;
                match cond_val {
                    Value::Bool(b) => {
                        if b {
                            self.evaluate(then_branch)
                        } else {
                            self.evaluate(else_branch)
                        }
                    }
                    _ => Err(EvalError::TypeMismatch),
                }
            }
            Expression::BinaryOp { lhs, rhs, op } => {
                let lhs_val = self.evaluate(lhs)?;
                let rhs_val = self.evaluate(rhs)?;
                match (lhs_val, rhs_val) {
                    (Value::I64(l), Value::I64(r)) => {
                        match op {
                            kotoba_parser::Operator::Add => Ok(Value::I64(l + r)),
                            kotoba_parser::Operator::Subtract => Ok(Value::I64(l - r)),
                            kotoba_parser::Operator::Multiply => Ok(Value::I64(l * r)),
                            kotoba_parser::Operator::Divide => Ok(Value::I64(l / r)),
                            kotoba_parser::Operator::Equals => Ok(Value::Bool(l == r)),
                            kotoba_parser::Operator::NotEquals => Ok(Value::Bool(l != r)),
                            kotoba_parser::Operator::LessThan => Ok(Value::Bool(l < r)),
                            kotoba_parser::Operator::GreaterThan => Ok(Value::Bool(l > r)),
                            kotoba_parser::Operator::LessThanOrEqual => Ok(Value::Bool(l <= r)),
                            kotoba_parser::Operator::GreaterThanOrEqual => Ok(Value::Bool(l >= r)),
                        }
                    }
                    _ => unimplemented!("Binary operations on non-integers are not supported yet."),
                }
            }
            _ => unimplemented!("Evaluation for this expression is not yet implemented."),
        }
    }

    fn type_of(&mut self, value: &Value) -> Type {
        match value {
            Value::I64(_) => Type::Ident("i64".to_string()),
            Value::Bool(_) => Type::Ident("bool".to_string()),
            Value::Unit => Type::Unit,
            Value::Type(t) => t.clone(),
            Value::Pi { binder_name, binder_type, body, captured_context } => {
                let mut temp_compiler = self.clone();
                temp_compiler.context = captured_context.clone();
                temp_compiler.context.enter_scope();
                let binder_ty = temp_compiler.type_of(&*binder_type);
                temp_compiler.context.define_var(binder_name.clone(), binder_ty);
                let body_val = temp_compiler.type_check_expression(body, None);

                Type::Pi {
                    binder_name: binder_name.clone(),
                    binder_type: Box::new(self.type_of(&*binder_type)),
                    return_type: Box::new(body_val.map(|v| temp_compiler.type_of(&v)).unwrap_or(Type::Ident("ERROR".to_string()))),
                }
            },
            Value::Constructor(name) => Type::Ident(name.clone()),
        }
    }

    pub fn new() -> Self {
        Compiler {
            context: Context::new(),
            environment: vec![HashMap::new()],
        }
    }

    pub fn compile(&mut self, program: Vec<Statement>) -> Result<String, String> {
        for statement in &program {
            if let Statement::Gyo { name, params, constructors } = statement {
                self.context
                    .type_definitions
                    .insert(name.clone(), (params.clone(), constructors.clone()));
            }
        }

        let mut rust_code = String::new();

        for statement in program {
            rust_code.push_str(&self.compile_statement(statement)?);
            rust_code.push('\n');
        }
        Ok(rust_code)
    }

    fn compile_statement(
        &mut self,
        statement: Statement,
    ) -> Result<String, String> {
        match statement {
            Statement::Shiki {
                variable_name,
                type_annotation,
                value,
            } => {
                 let normalized_type = self.normalize(&type_annotation).map_err(|e| format!("{:?}",e))?;

                let value_checked = self
                    .type_check_expression(&value, Some(&normalized_type))
                    .map_err(|e| format!("{:?}", e))?;

                let found_type = self.type_of(&value_checked);

                self.context
                    .define_var(variable_name.clone(), found_type.clone());

                if !self.are_types_equal(&found_type, &normalized_type) {
                     return Err(format!(
                        "{:?}",
                        TypeError::TypeMismatch {
                            expected: type_to_string(&normalized_type),
                            found: type_to_string(&found_type)
                        }
                    ));
                }
                
                let value_eval = self.evaluate(&value).map_err(|e| format!("{:?}", e))?;
                self.env_define_var(variable_name.clone(), value_eval);


                let type_str = type_to_string(&normalized_type);
                let value_expr = self.compile_expression(value.clone())?;

                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_str, value_expr
                ))
            }
            Statement::Gyo { name, params, constructors } => {
                self.compile_gyo_statement(&name, &params, &constructors)
            }
            Statement::Rin { .. } => Ok(String::new()), // Simplified for now
        }
    }

    fn compile_gyo_statement(
        &self,
        name: &str,
        params: &[Parameter],
        constructors: &[ConstructorDef],
    ) -> Result<String, String> {
        let type_params: Vec<_> = params
            .iter()
            .filter(|p| self.is_type_parameter(p))
            .map(|p| p.name.clone())
            .collect();

        let generics_str = if type_params.is_empty() {
            String::new()
        } else {
            format!("<{}>", type_params.join(", "))
        };

        let mut constructor_strs = Vec::new();
        for constructor in constructors {
            let (con_name, fields_str) = match constructor {
                ConstructorDef::Point { name: con_name, fields } => {
                    let field_types: Vec<String> = fields
                        .iter()
                        .map(|t| self.type_to_rust_type_string(t, Some(name)))
                        .collect();
                    let fields_str = if field_types.is_empty() {
                        String::new()
                    } else {
                        format!("({})", field_types.join(", "))
                    };
                    (capitalize(con_name), fields_str)
                }
                ConstructorDef::Path { name: path_name, path_type } => {
                    // For now, path constructors are represented as points
                    // This will need a more sophisticated handling later
                    (
                        capitalize(path_name),
                        format!("({})", self.type_to_rust_type_string(path_type, Some(name))),
                    )
                }
            };
            constructor_strs.push(format!("    {}{}", con_name, fields_str));
        }

        let enum_def = format!(
            "#[derive(Debug, Clone)]\npub enum {}{} {{\n{}\n}}",
            name,
            generics_str,
            constructor_strs.join(",\n")
        );
        Ok(enum_def)
    }

    fn is_type_parameter(&self, p: &Parameter) -> bool {
        if let Type::Ident(name) = &p.type_annotation {
            name == "Type"
        } else {
            false
        }
    }

    fn type_to_rust_type_string(&self, t: &Type, current_type_name: Option<&str>) -> String {
        match t {
            Type::Ku => "kotoba_core::Interval".to_string(),
            Type::Ze(t1, t2) => format!(
                "kotoba_core::Path<{}, {}>",
                self.type_to_rust_type_string(t1, current_type_name),
                self.type_to_rust_type_string(t2, current_type_name)
            ),
            Type::En(t1, t2, t3) => format!(
                "kotoba_core::Glue<{}, {}, {}>",
                self.type_to_rust_type_string(t1, current_type_name),
                self.type_to_rust_type_string(t2, current_type_name),
                self.type_to_rust_type_string(t3, current_type_name)
            ),
            Type::Unit => "()".to_string(),
            Type::Func(from, to) => format!(
                "Box<dyn Fn({}) -> {}>",
                self.type_to_rust_type_string(from, current_type_name),
                self.type_to_rust_type_string(to, current_type_name)
            ),
            Type::Pi {
                binder_type,
                return_type,
                ..
            } => format!(
                "Box<dyn Fn({}) -> {}>",
                self.type_to_rust_type_string(binder_type, current_type_name),
                self.type_to_rust_type_string(return_type, current_type_name)
            ),
            Type::Ident(name) => {
                if current_type_name.map_or(false, |n| n == name) {
                    format!("Box<{}>", capitalize(name))
                } else if name == "i64" {
                    "i64".to_string()
                } else if name == "bool" {
                    "bool".to_string()
                } else {
                    capitalize(name)
                }
            }
            Type::App(head, args) => {
                let head_str = self.type_to_rust_type_string(head, current_type_name);
                let args_str = args
                    .iter()
                    .map(|arg| self.type_to_rust_type_string(arg, current_type_name))
                    .collect::<Vec<_>>()
                    .join(", ");
                format!("{}<{}>", head_str, args_str)
            }
            Type::Expr(_) => "i64".to_string(), // Assume expressions in types evaluate to i64 for now
        }
    }


    fn are_types_equal(&mut self, t1: &Type, t2: &Type) -> bool {
        let norm_t1 = self.normalize(t1).unwrap_or_else(|_| t1.clone());
        let norm_t2 = self.normalize(t2).unwrap_or_else(|_| t2.clone());
        
        match (norm_t1.clone(), norm_t2.clone()) {
            (Type::Pi { binder_type: bt1, return_type: rt1, .. }, Type::Func(p1, r1)) |
            (Type::Func(p1, r1), Type::Pi { binder_type: bt1, return_type: rt1, .. }) => {
                self.are_types_equal(&bt1, &p1) && self.are_types_equal(&rt1, &r1)
            }
            _ => type_to_string(&norm_t1) == type_to_string(&norm_t2),
        }
    }
    
    fn normalize(&mut self, ty: &Type) -> Result<Type, EvalError> {
        match ty {
            Type::App(head, args) => {
                let norm_head = self.normalize(head)?;
                let mut norm_args = Vec::new();
                for arg in args {
                    norm_args.push(self.normalize(arg)?);
                }
                Ok(Type::App(Box::new(norm_head), norm_args))
            }
            Type::Expr(expr) => {
                let value = self.evaluate(expr)?;
                match value {
                    Value::I64(n) => Ok(Type::Expr(Box::new(Expression::IntegerLiteral(n)))),
                    Value::Bool(b) => Ok(Type::Expr(Box::new(Expression::Identifier(b.to_string())))),
                    _ => Ok(Type::Expr(expr.clone())),
                }
            }
            _ => Ok(ty.clone()),
        }
    }


    fn type_check_expression(
        &mut self,
        expression: &Expression,
        expected_type: Option<&Type>,
    ) -> Result<Value, TypeError> {
        // This is a stub for now. The full implementation is complex.
        // We just evaluate the expression and assume the type is correct for this pass.
        self.evaluate(expression).map_err(|e| TypeError::NotImplemented(format!("{:?}", e)))
    }

    fn type_check_identifier(&self, name: &str) -> Result<Type, TypeError> {
        if let Some(ty) = self.context.find_var(name) {
            Ok(ty.clone())
        } else if let Some(type_name) = self.context.find_constructor_type(name) {
            Ok(Type::Ident(type_name.clone()))
        } else {
            Err(TypeError::UndefinedVariable(name.to_string()))
        }
    }

    fn extract_bindings_from_pattern(
        &self,
        pattern: &Pattern,
        matched_type: &Type,
    ) -> Result<HashMap<String, Type>, TypeError> {
        Ok(HashMap::new())
    }

    fn compile_binary_op_expression(
        &mut self,
        op: kotoba_parser::Operator,
        lhs: Expression,
        rhs: Expression,
    ) -> Result<String, String> {
        // This will be simplified as evaluation now happens before compilation
        let val = self.evaluate(&Expression::BinaryOp{ op, lhs: Box::new(lhs), rhs: Box::new(rhs) }).map_err(|e| format!("{:?}", e))?;
        Ok(format!("{:?}", val)) // Just for now
    }

    fn compile_expression(&mut self, expression: Expression) -> Result<String, String> {
        // Re-route compilation to be based on evaluation
        let value = self.evaluate(&expression).map_err(|e| format!("{:?}", e))?;
        match value {
            Value::I64(n) => Ok(n.to_string()),
            Value::Bool(b) => Ok(b.to_string()),
            _ => Ok("\"<compiled_value>\"".to_string())
        }
    }

    fn compile_identifier_expression(&self, name: &str) -> Result<String, String> {
        Ok(name.to_string())
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
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call_with_args() {
        let input = "shiki p2: ze<i64, i64> = p1.compose(q1)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let path_type = Type::Ze(
            Box::new(Type::Ident("i64".to_string())),
            Box::new(Type::Ident("i64".to_string())),
        );
        compiler.context.define_var("p1".to_string(), path_type.clone());
        compiler.context.define_var("q1".to_string(), path_type);
        let result = compiler.compile(vec![statement]);
        let expected_code = "let p2: kotoba_core::Path<i64> = p1.compose(&q1);\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call_sym() {
        let input = "shiki p_sym: ze<i64, i64> = p.sym()";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        compiler.context.define_var(
            "p".to_string(),
            Type::Ze(
                Box::new(Type::Ident("i64".to_string())),
                Box::new(Type::Ident("i64".to_string())),
            ),
        );
        let result = compiler.compile(vec![statement]);
        let expected_code = "let p_sym: kotoba_core::Path<i64> = p.sym();\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_glue_unglue() {
        let input_glue = "shiki g: en<i64, (), ()> = glue(10)";
        let (_, statement_glue) = parse_statement(input_glue).unwrap();
        let input_unglue = "shiki v: i64 = unglue(g)";
        let (_, statement_unglue) = parse_statement(input_unglue).unwrap();

        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement_glue, statement_unglue]);

        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();

        let expected_glue = "let g: kotoba_core::Glue<i64, (), ()> = kotoba_core::glue::<i64, (), ()>(10);\n";
        let expected_unglue = "let v: i64 = kotoba_core::unglue(g);\n";

        assert!(
            compiled_code.contains(expected_glue),
            "Did not find expected glue compilation in: {}",
            compiled_code
        );
        assert!(
            compiled_code.contains(expected_unglue),
            "Did not find expected unglue compilation in: {}",
            compiled_code
        );
    }

    #[test]
    fn test_type_check_simple_vars() {
        let mut compiler = Compiler::new();
        compiler.context.define_var("x".to_string(), Type::Ident("i64".to_string()));
        let expr = kotoba_parser::parse_expression("x").unwrap().1;
        let result = compiler.type_check_expression(&expr, None);
        assert_eq!(result, Ok(Value::Type(Type::Ident("i64".to_string()))));

        let expr_undef = kotoba_parser::parse_expression("y").unwrap().1;
        let result_undef = compiler.type_check_expression(&expr_undef, None);
        assert_eq!(result_undef, Err(TypeError::UndefinedVariable("y".to_string())));
    }

    #[test]
    fn test_type_check_let() {
        let mut compiler = Compiler::new();
        let expr = kotoba_parser::parse_expression("let x: i64 = 10 in x + 1").unwrap().1;
        let result = compiler.type_check_expression(&expr, Some(&Type::Ident("i64".to_string())));
        assert_eq!(result, Ok(Value::I64(0)));

        let expr_mismatch = kotoba_parser::parse_expression("let x: ku = 10 in x").unwrap().1;
        let result_mismatch = compiler.type_check_expression(&expr_mismatch, Some(&Type::Ku));
        assert_eq!(
            result_mismatch,
            Err(TypeError::TypeMismatch {
                expected: type_to_string(&Type::Ku),
                found: type_to_string(&Type::Ident("i64".to_string()))
            })
        );
    }

    #[test]
    fn test_type_check_pipe() {
        let mut compiler = Compiler::new();
        let func_type = Type::Func(
            Box::new(Type::Ident("i64".to_string())),
            Box::new(Type::Ident("bool".to_string())),
        );
        compiler.context.define_var("is_positive".to_string(), func_type.clone());
        compiler.context.define_var("n".to_string(), Type::Ident("i64".to_string()));

        let expr = kotoba_parser::parse_expression("n |> is_positive").unwrap().1;
        let result = compiler.type_check_expression(&expr, None);
        let result_type = compiler.type_of(&result.unwrap());
        assert_eq!(result_type, Type::Ident("bool".to_string()));

        let expr_not_func = kotoba_parser::parse_expression("n |> n").unwrap().1;
        let result_not_func = compiler.type_check_expression(&expr_not_func, None);
        assert_eq!(result_not_func, Err(TypeError::NotAFunction(type_to_string(&Type::Ident(
            "i64".to_string()
        )))));
    }

    #[test]
    fn test_type_check_ou() {
        let mut compiler = Compiler::new();
        let option_type_name = "Option".to_string();
        let constructors = vec![
            ConstructorDef::Point {
                name: "some".to_string(),
                fields: vec![Type::Ident("i64".to_string())],
            },
            ConstructorDef::Point {
                name: "none".to_string(),
                fields: vec![],
            },
        ];
        compiler
            .context
            .type_definitions
            .insert(option_type_name.clone(), (vec![], constructors));

        let option_value_type = Type::Ident(option_type_name);
        compiler.context.define_var("opt".to_string(), option_value_type);

        let expr_ok = parse_expression("ou opt { some(x) => x, none => 0 }")
            .unwrap()
            .1;
        let result_ok = compiler.type_check_expression(&expr_ok, None);
        assert_eq!(compiler.type_of(&result_ok.unwrap()), Type::Ident("i64".to_string()));

        let expr_err = parse_expression("ou opt { some(x) => x, none => i0 }")
            .unwrap()
            .1;
        let result_err = compiler.type_check_expression(&expr_err, None);
        assert_eq!(
            result_err,
            Err(TypeError::TypeMismatch {
                expected: "i64".to_string(),
                found: type_to_string(&Type::Ku)
            })
        );
    }

    #[test]
    fn test_compile_rin_with_generics() {
        let input = "rin id<T>(x: T): T = x";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "fn id<T>(x: T) -> T {\n    x\n}\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_rin_statement() {
        let input = "rin add(a: N, b: N): N = ou a { zero => b }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let compiled_code = result.unwrap();
        assert!(compiled_code.contains("fn add(a: N, b: N) -> N"));
        assert!(compiled_code.contains("match a"));
    }

    #[test]
    fn test_compile_gyo_statement() {
        let input = "gyo N = { zero, succ(N) }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "#[derive(Debug, Clone)]\nenum N {\n    Zero,\n    Succ(Box<N>),\n}\n";
        let actual_code = result.unwrap();
        assert!(
            actual_code.contains(expected_code),
            "Expected:\n{}\n\nGot:\n{}",
            expected_code,
            actual_code
        );
    }

    #[test]
    fn test_compile_rin_statement_with_constructor() {
        let program = vec![
            parse_statement("gyo N = { zero, succ(N) }").unwrap().1,
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
        assert!(code.contains("N::Zero"));
    }

    #[test]
    fn test_compile_path_with_inductive_type() {
        let program = vec![
            parse_statement("gyo Bool = { true, false }").unwrap().1,
            parse_statement("shiki path_to_false: ze<Bool, Bool> = kan(i: ku) => ou i { i0 => true, i1 => false }").unwrap().1,
        ];
        let mut compiler = Compiler::new();
        let result = compiler.compile(program);
        let compiled_code = result.unwrap();

        assert!(compiled_code.contains("enum Bool"));
        assert!(compiled_code.contains("let path_to_false: kotoba_core::Path<Bool>"));
        assert!(compiled_code.contains("Bool::True"));
        assert!(compiled_code.contains("Bool::False"));
    }

    #[test]
    fn test_compile_higher_order_path() {
        let input = "shiki p_over_p: ze<ze<i64, i64>, ze<i64, i64>> = some_path";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let path_type = Type::Ze(
            Box::new(Type::Ze(
                Box::new(Type::Ident("i64".to_string())),
                Box::new(Type::Ident("i64".to_string())),
            )),
            Box::new(Type::Ze(
                Box::new(Type::Ident("i64".to_string())),
                Box::new(Type::Ident("i64".to_string())),
            )),
        );
        compiler.context.define_var("some_path".to_string(), path_type);
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let p_over_p: kotoba_core::Path<kotoba_core::Path<i64>> = some_path;\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call() {
        let input = "shiki ticks: en<ma, i64, some_eq> = timer_ba.as_en()";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        compiler.context.define_var(
            "timer_ba".to_string(),
            Type::Ident("some_type".to_string()),
        );
        let result = compiler.compile(vec![statement]);
        let expected_code = "let ticks: kotoba_core::Glue<ma, i64, some_eq> = timer_ba.as_en();\n";
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
        let compiled_code = result.unwrap();
        assert!(compiled_code.contains("let my_path: kotoba_core::Path<i64>"));
        assert!(compiled_code.contains("= kotoba_core::Path::new("));
        assert!(compiled_code.contains("|i: kotoba_core::Interval|"));
        assert!(compiled_code.contains("match i"));
        assert!(compiled_code.contains("10"));
        assert!(compiled_code.contains("20"));
    }

    #[test]
    fn test_compile_nested_kan() {
        let input = "shiki add_curried: i64 -> i64 -> i64 = kan(a: i64) => kan(b: i64) => a";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let add_curried: Box<dyn Fn(i64) -> Box<dyn Fn(i64) -> i64>> = |a: i64| { |b: i64| { a } };\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_pipe() {
        let input = "shiki pipeline: en<ma, i64, id> = ticks |> doubler";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        compiler.context.define_var(
            "ticks".to_string(),
            Type::En(
                Box::new(Type::Ident("ma".to_string())),
                Box::new(Type::Ident("i64".to_string())),
                Box::new(Type::Ident("id".to_string())),
            ),
        );
        compiler.context.define_var(
            "doubler".to_string(),
            Type::Func(
                Box::new(Type::En(
                    Box::new(Type::Ident("ma".to_string())),
                    Box::new(Type::Ident("i64".to_string())),
                    Box::new(Type::Ident("id".to_string())),
                )),
                Box::new(Type::En(
                    Box::new(Type::Ident("ma".to_string())),
                    Box::new(Type::Ident("i64".to_string())),
                    Box::new(Type::Ident("id".to_string())),
                )),
            ),
        );
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let pipeline: kotoba_core::Glue<ma, i64, id> = doubler(ticks);\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_binary_op() {
        let input = "shiki result: i64 = 1 + 2";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();
        assert!(
            compiled_code.contains("let result: i64 = (1 + 2);"),
            "Did not find expected binary operation compilation in: {}",
            compiled_code
        );
    }

    #[test]
    fn test_compile_shiki_comparison_op() {
        let input = "shiki result: bool = 1 < 2";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();
        assert!(
            compiled_code.contains("let result: bool = (1 < 2);"),
            "Did not find expected comparison operation compilation in: {}",
            compiled_code
        );
    }

    #[test]
    fn test_compile_shiki_if_expression() {
        let input = "shiki result: i64 = if 1 < 2 then 10 else 20";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();
        assert!(
            compiled_code.contains("let result: i64 = if (1 < 2) { 10 } else { 20 };"),
            "Did not find expected if expression compilation in: {}",
            compiled_code
        );
    }

    #[test]
    fn test_compile_shiki_kan() {
        let input = "shiki doubler: i64 -> i64 = kan(x: i64) => x";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let expected_code =
            "let doubler: Box<dyn Fn(i64) -> i64> = |x: i64| { x };\n";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_ou_expression() {
        let input = "ou i { i0 => i0, _ => i1 }";
        let (_, expression) = parse_expression(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile_expression(expression);
        let expected_code = "match i {\n    kotoba_core::Interval::I0 => { kotoba_core::Interval::I0 },\n    _ => { kotoba_core::Interval::I1 },\n}";
        assert_eq!(result, Ok(expected_code.to_string()));
    }

    #[test]
    fn test_compile_gyo_statement_with_path() {
        let input = "gyo S1 = { base, loop: ze<base, base> }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_enum = "#[derive(Debug, Clone)]\nenum S1 {\n    Base,\n}";
        let expected_impl = "impl S1 {\n    pub fn loop(&self) -> kotoba_core::Path<base> {\n        unimplemented!(\"Path constructor compilation is not fully supported yet.\")\n    }\n}";
        let compiled_code = result.unwrap();
        assert!(
            compiled_code.contains(expected_enum),
            "Enum definition missing or incorrect."
        );
        assert!(
            compiled_code.contains(expected_impl),
            "Impl block for path constructor missing or incorrect."
        );
    }

    #[test]
    fn test_dependent_type_evaluation_in_type_checker() {
        let program = vec![
            // The parser needs to be able to handle `Type` as a parameter type.
            parse_statement("gyo Vec (A: Type, n: i64) = { nil, cons(A, Vec A (n-1)) }").unwrap().1,
            parse_statement("shiki my_vec: Vec i64 (1 + 1) = cons(10, cons(20, nil))").unwrap().1,
        ];
        let mut compiler = Compiler::new();
        let result = compiler.compile(program);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());

        // After compilation, the type of `my_vec` in the context should be `Vec i64 2`.
        let my_vec_type = compiler.context.find_var("my_vec").unwrap().clone();
        // This requires a way to represent evaluated types. For now, we'll check the string representation.
        // A real implementation would have a semantic equality check for types.
        let expected_type_str = "Vec i64 Expr(IntegerLiteral(2))"; // This is a simplified string representation
        let actual_type_str = type_to_string(&compiler.normalize(&my_vec_type).unwrap());

        // TODO: This test will fail until the type checker evaluates expressions within types.
        assert_eq!(actual_type_str, expected_type_str);
    }

    #[test]
    fn test_compile_shiki_pi_type() {
        let input = "shiki id_func: (x: i64) -> i64 = kan(y: i64) => y";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let expected_code =
            "let id_func: Box<dyn Fn(i64) -> i64> = |y: i64| { y };\n";
        assert!(
            result.unwrap().contains(expected_code),
            "Generated code did not match expectation."
        );
    }

    #[test]
    fn test_evaluator_variables_and_let() {
        let input = "let x = 10 in let y = 20 in x + y";
        let (_, expression) = parse_expression(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.evaluate(&expression);
        assert_eq!(result, Ok(Value::I64(30)));

        let input_undefined = "let x = 5 in y";
        let (_, expression_undef) = parse_expression(input_undefined).unwrap();
        let result_undef = compiler.evaluate(&expression_undef);
        assert_eq!(result_undef, Err(EvalError::UndefinedVariable("y".to_string())));
    }

    #[test]
    fn test_evaluator_if() {
        let input_true = "if 10 > 5 then 1 else 0";
        let (_, expr_true) = parse_expression(input_true).unwrap();
        let mut compiler_true = Compiler::new();
        let result_true = compiler_true.evaluate(&expr_true);
        assert_eq!(result_true, Ok(Value::I64(1)));

        let input_false = "if 10 < 5 then 1 else 0";
        let (_, expr_false) = parse_expression(input_false).unwrap();
        let mut compiler_false = Compiler::new();
        let result_false = compiler_false.evaluate(&expr_false);
        assert_eq!(result_false, Ok(Value::I64(0)));
    }
}
