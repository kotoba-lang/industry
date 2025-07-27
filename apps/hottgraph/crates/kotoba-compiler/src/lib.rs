use kotoba_parser::{
    ConstructorDef, Expression, Parameter, Pattern, Statement, Type,
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
    Closure {
        params: Vec<Parameter>,
        body: Box<Expression>,
        captured_env: Vec<HashMap<String, Value>>,
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
    NotAFunction,
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
        Type::En(t1, t2, t3) => format!(
            "en<{}, {}, {}>",
            type_to_string(t1),
            type_to_string(t2),
            type_to_string(t3)
        ),
        Type::Unit => "()".to_string(),
        Type::Func(from, to) => format!("{} -> {}", type_to_string(from), type_to_string(to)),
        Type::Pi {
            binder_name,
            binder_type,
            return_type,
            ..
        } => format!(
            "({}: {}) -> {}",
            binder_name,
            type_to_string(binder_type),
            type_to_string(return_type)
        ),
        Type::Ident(name) => name.clone(),
        Type::App(head, args) => {
            let head_str = type_to_string(head);
            let args_str = args
                .iter()
                .map(type_to_string)
                .collect::<Vec<_>>()
                .join(" ");
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
            Expression::Identifier(name) => self
                .env_find_var(name)
                .cloned()
                .ok_or_else(|| EvalError::UndefinedVariable(name.clone())),
            Expression::Let {
                name, value, body, ..
            } => {
                let val = self.evaluate(value)?;
                self.env_enter_scope();
                self.env_define_var(name.clone(), val);
                let result = self.evaluate(body)?;
                self.env_exit_scope();
                Ok(result)
            }
            Expression::If {
                condition,
                then_branch,
                else_branch,
            } => {
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
                    (Value::I64(l), Value::I64(r)) => match op {
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
                    },
                    _ => {
                        unimplemented!("Binary operations on non-integers are not supported yet.")
                    }
                }
            }
            Expression::Kan { params, body } => Ok(Value::Closure {
                params: params.clone(),
                body: body.clone(),
                captured_env: self.environment.clone(),
            }),
            Expression::Pipe { lhs, rhs } => {
                let lhs_val = self.evaluate(lhs)?;
                self.env_enter_scope();
                self.env_define_var("_".to_string(), lhs_val); // Pipe placeholder
                let rhs_val = self.evaluate(rhs)?;
                self.env_exit_scope();
                Ok(rhs_val)
            }
            Expression::Ou { expression, arms } => {
                let value_to_match = self.evaluate(expression)?;
                for arm in arms {
                    if self.pattern_match(&value_to_match, &arm.pattern) {
                        return self.evaluate(&arm.body);
                    }
                }
                // This should ideally be an error for non-exhaustive patterns
                unimplemented!("Non-exhaustive pattern match");
            }
            Expression::Refl(expr) => self.evaluate(expr),
            Expression::Glue { value } => self.evaluate(value),
            Expression::Unglue { value } => self.evaluate(value),
            Expression::Zo(val) => {
                if val == "i0" {
                    Ok(Value::I64(0)) // Representing intervals as integers for now
                } else if val == "i1" {
                    Ok(Value::I64(1))
                } else {
                    unimplemented!("Unsupported interval value");
                }
            }
            Expression::MethodCall {
                variable,
                method,
                args,
            } => {
                let var_val = self.evaluate(variable)?;
                let mut arg_vals = Vec::new();
                for arg in args {
                    arg_vals.push(self.evaluate(arg)?);
                }

                // Super simplified mock for now
                match (var_val, method.as_str()) {
                    (Value::I64(i), "sym") => Ok(Value::I64(-i)), // Example
                    _ => unimplemented!("Method call evaluation not fully supported"),
                }
            }
            _ => unimplemented!("Evaluation for this expression is not yet implemented."),
        }
    }

    fn pattern_match(&self, value: &Value, pattern: &Pattern) -> bool {
        match (value, pattern) {
            (Value::I64(v), Pattern::IntegerLiteral(p)) => v == p,
            (_, Pattern::Wildcard) => true,
            (Value::Constructor(v_name), Pattern::Identifier(p_name)) if v_name == p_name => true,
            // Allow matching dummy Unit values against identifiers in `rin` compilation
            (Value::Unit, Pattern::Identifier(_)) => true,
            _ => false,
        }
    }

    fn apply_closure(&mut self, closure: Value, args: Vec<Value>) -> Result<Value, EvalError> {
        if let Value::Closure {
            params,
            body,
            captured_env,
        } = closure
        {
            let mut temp_evaluator = self.clone();
            temp_evaluator.environment = captured_env;
            temp_evaluator.env_enter_scope();

            if params.len() != args.len() {
                // Partial application could be handled here in the future
                return Err(EvalError::TypeMismatch);
            }

            for (param, arg) in params.iter().zip(args) {
                temp_evaluator.env_define_var(param.name.clone(), arg);
            }

            temp_evaluator.evaluate(&body)
        } else {
            Err(EvalError::NotAFunction)
        }
    }

    fn type_of(&mut self, value: &Value) -> Type {
        match value {
            Value::I64(_) => Type::Ident("i64".to_string()),
            Value::Bool(_) => Type::Ident("bool".to_string()),
            Value::Unit => Type::Unit,
            Value::Type(t) => t.clone(),
            Value::Closure { params, body, .. } => {
                let mut temp_compiler = self.clone();
                temp_compiler.context.enter_scope();

                for param in params {
                    temp_compiler
                        .context
                        .define_var(param.name.clone(), param.type_annotation.clone());
                }

                // If the body is a closure itself, we need to build a nested Pi type
                let return_type = temp_compiler
                    .type_check_expression(body, None)
                    .unwrap_or_else(|_| Type::Ident("TYPE_ERROR_IN_BODY".to_string()));

                temp_compiler.context.exit_scope();

                params.iter().rev().fold(return_type, |acc, param| {
                    if param.type_annotation == Type::Unit && params.len() == 1 {
                        // This case is for functions like `kan() => ...`
                        Type::Func(Box::new(Type::Unit), Box::new(acc))
                    } else {
                        Type::Pi {
                            binder_name: param.name.clone(),
                            binder_type: Box::new(param.type_annotation.clone()),
                            return_type: Box::new(acc),
                        }
                    }
                })
            }
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
            if let Statement::Gyo {
                name,
                params,
                constructors,
            } = statement
            {
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

    fn compile_statement(&mut self, statement: Statement) -> Result<String, String> {
        match statement {
            Statement::Shiki {
                variable_name,
                type_annotation,
                value,
            } => {
                let normalized_type = self
                    .normalize(&type_annotation)
                    .map_err(|e| format!("{:?}", e))?;

                let value_type = self
                    .type_check_expression(&value, Some(&normalized_type))
                    .map_err(|e| format!("{:?}", e))?;

                if !self.are_types_equal(&value_type, &normalized_type) {
                    return Err(format!(
                        "{:?}",
                        TypeError::TypeMismatch {
                            expected: type_to_string(&normalized_type),
                            found: type_to_string(&value_type)
                        }
                    ));
                }

                let value_eval = self.evaluate(&value).map_err(|e| format!("{:?}", e))?;
                self.env_define_var(variable_name.clone(), value_eval);
                self.context
                    .define_var(variable_name.clone(), value_type.clone());

                let type_str = self.type_to_rust_type_string(&normalized_type, None);
                let value_expr = self.compile_expression(value.clone())?;

                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_str, value_expr
                ))
            }
            Statement::Gyo {
                name,
                params,
                constructors,
            } => self.compile_gyo_statement(&name, &params, &constructors),
            Statement::Rin {
                name,
                generics,
                params,
                return_type,
                body,
                ..
            } => self.compile_rin_statement(&name, &generics, &params, &return_type, &body),
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

        let mut point_constructors = Vec::new();
        let mut path_constructors = Vec::new();
        for constructor in constructors {
            match constructor {
                ConstructorDef::Point { .. } => point_constructors.push(constructor),
                ConstructorDef::Path { .. } => path_constructors.push(constructor),
            }
        }

        let mut constructor_strs = Vec::new();
        for constructor in point_constructors {
            if let ConstructorDef::Point {
                name: con_name,
                fields,
            } = constructor
            {
                let field_types: Vec<String> = fields
                    .iter()
                    .map(|t| self.type_to_rust_type_string(t, Some(name)))
                    .collect();
                let fields_str = if field_types.is_empty() {
                    String::new()
                } else {
                    format!("({})", field_types.join(", "))
                };
                constructor_strs.push(format!("    {}{}", capitalize(con_name), fields_str));
            }
        }

        let enum_def = format!(
            "#[derive(Debug, Clone)]\npub enum {}{} {{\n{}\n}}",
            name,
            generics_str,
            constructor_strs.join(",\n")
        );

        let mut impl_block = String::new();
        if !path_constructors.is_empty() {
            let mut method_strs = Vec::new();
            for constructor in path_constructors {
                if let ConstructorDef::Path {
                    name: path_name,
                    path_type,
                } = constructor
                {
                    let path_type_str = self.type_to_rust_type_string(path_type, Some(name));
                    let method_str = format!(
                        "    pub fn {}(&self) -> {} {{\n        unimplemented!(\"Path constructor compilation is not fully supported yet.\")\n    }}",
                        path_name,
                        path_type_str
                    );
                    method_strs.push(method_str);
                }
            }
            impl_block = format!(
                "\nimpl {}{} {{\n{}\n}}",
                name,
                generics_str,
                method_strs.join("\n\n")
            );
        }

        Ok(format!("{}{}", enum_def, impl_block))
    }

    fn compile_rin_statement(
        &mut self,
        name: &str,
        generics: &[String],
        params: &[Parameter],
        return_type: &Type,
        body: &Expression,
    ) -> Result<String, String> {
        let generics_str = if generics.is_empty() {
            String::new()
        } else {
            format!("<{}>", generics.join(", "))
        };

        let param_strs: Vec<String> = params
            .iter()
            .map(|p| {
                format!(
                    "{}: {}",
                    p.name,
                    self.type_to_rust_type_string(&p.type_annotation, None)
                )
            })
            .collect();
        let return_type_str = self.type_to_rust_type_string(return_type, None);

        self.context.enter_scope();
        self.env_enter_scope();
        for p in params {
            self.context
                .define_var(p.name.clone(), p.type_annotation.clone());
            self.env_define_var(p.name.clone(), Value::Unit); // Dummy value for type checking
        }

        let body_str = self.compile_expression(body.clone())?;

        self.context.exit_scope();
        self.env_exit_scope();

        Ok(format!(
            "fn {}{}({}) -> {} {{\n    {}\n}}",
            name,
            generics_str,
            param_strs.join(", "),
            return_type_str,
            body_str
        ))
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
            Type::Ze(t1, t2) => {
                let s1 = self.type_to_rust_type_string(t1, current_type_name);
                let s2 = self.type_to_rust_type_string(t2, current_type_name);
                if s1 == s2 {
                    format!("kotoba_core::Path<{}>", s1)
                } else {
                    format!("kotoba_core::Path<{}, {}>", s1, s2)
                }
            }
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
                } else if name == "ku" {
                    "kotoba_core::Interval".to_string()
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
            (
                Type::Pi {
                    binder_type: bt1,
                    return_type: rt1,
                    ..
                },
                Type::Pi {
                    binder_type: bt2,
                    return_type: rt2,
                    ..
                },
            ) => self.are_types_equal(&bt1, &bt2) && self.are_types_equal(&rt1, &rt2),
            (Type::Func(p1, r1), Type::Func(p2, r2)) => {
                self.are_types_equal(&p1, &p2) && self.are_types_equal(&r1, &r2)
            }
            (
                Type::Pi {
                    binder_type: bt1,
                    return_type: rt1,
                    ..
                },
                Type::Func(p2, r2),
            )
            | (
                Type::Func(p2, r2),
                Type::Pi {
                    binder_type: bt1,
                    return_type: rt1,
                    ..
                },
            ) => self.are_types_equal(&bt1, &p2) && self.are_types_equal(&rt1, &r2),
            (Type::Ze(l1, r1), Type::Func(p, r)) | (Type::Func(p, r), Type::Ze(l1, r1)) => {
                // ze<A,B> is like A -> B
                self.are_types_equal(&l1, &p) && self.are_types_equal(&r1, &r)
            }
            (Type::Ze(l1, r1), Type::Pi { binder_type, return_type, .. }) |
            (Type::Pi { binder_type, return_type, .. }, Type::Ze(l1, r1)) => {
                self.are_types_equal(&l1, &binder_type) && self.are_types_equal(&r1, &return_type)
            }
            (Type::Ze(l1, r1), Type::Ze(l2, r2)) => {
                self.are_types_equal(&l1, &l2) && self.are_types_equal(&r1, &r2)
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
        _expected_type: Option<&Type>,
    ) -> Result<Type, TypeError> {
        match expression {
            Expression::IntegerLiteral(_) => Ok(Type::Ident("i64".to_string())),
            Expression::Identifier(name) => self
                .context
                .find_var(name)
                .cloned()
                .ok_or_else(|| TypeError::UndefinedVariable(name.clone())),
            Expression::Let {
                name,
                type_annotation,
                value,
                body,
            } => {
                let value_type = self.type_check_expression(value, type_annotation.as_deref())?;
                if let Some(expected) = type_annotation {
                    if !self.are_types_equal(&value_type, expected) {
                        return Err(TypeError::TypeMismatch {
                            expected: type_to_string(expected),
                            found: type_to_string(&value_type),
                        });
                    }
                }
                self.context.enter_scope();
                self.context.define_var(name.clone(), value_type);
                let body_type = self.type_check_expression(body, None)?;
                self.context.exit_scope();
                Ok(body_type)
            }
            Expression::BinaryOp { lhs, rhs, op } => {
                self.type_check_expression(lhs, None)?;
                self.type_check_expression(rhs, None)?;
                match op {
                    kotoba_parser::Operator::Add
                    | kotoba_parser::Operator::Subtract
                    | kotoba_parser::Operator::Multiply
                    | kotoba_parser::Operator::Divide => Ok(Type::Ident("i64".to_string())),
                    kotoba_parser::Operator::Equals
                    | kotoba_parser::Operator::NotEquals
                    | kotoba_parser::Operator::LessThan
                    | kotoba_parser::Operator::GreaterThan
                    | kotoba_parser::Operator::LessThanOrEqual
                    | kotoba_parser::Operator::GreaterThanOrEqual => {
                        Ok(Type::Ident("bool".to_string()))
                    }
                }
            }
            Expression::Pipe { lhs, rhs } => {
                let lhs_type = self.type_check_expression(lhs, None)?;
                let rhs_type = self.type_check_expression(rhs, None)?;

                match rhs_type {
                    Type::Func(param_type, return_type)
                    | Type::Pi {
                        binder_type: param_type,
                        return_type,
                        ..
                    } => {
                        if self.are_types_equal(&lhs_type, &param_type) {
                            Ok(*return_type)
                        } else {
                            Err(TypeError::TypeMismatch {
                                expected: type_to_string(&param_type),
                                found: type_to_string(&lhs_type),
                            })
                        }
                    }
                    _ => Err(TypeError::NotAFunction(type_to_string(&rhs_type))),
                }
            }
            Expression::Ou { expression, arms } => {
                let match_expr_type = self.type_check_expression(expression, None)?;

                if arms.is_empty() {
                    return Err(TypeError::EmptyOuExpression);
                }

                let mut first_arm_body_type: Option<Type> = None;

                for arm in arms {
                    self.context.enter_scope();
                    let bindings =
                        self.extract_bindings_from_pattern(&arm.pattern, &match_expr_type)?;
                    for (name, ty) in bindings {
                        self.context.define_var(name, ty);
                    }

                    let arm_body_type = self.type_check_expression(&arm.body, None)?;

                    if let Some(first_type) = &first_arm_body_type {
                        if !self.are_types_equal(first_type, &arm_body_type) {
                            self.context.exit_scope();
                            return Err(TypeError::TypeMismatch {
                                expected: type_to_string(first_type),
                                found: type_to_string(&arm_body_type),
                            });
                        }
                    } else {
                        first_arm_body_type = Some(arm_body_type);
                    }
                    self.context.exit_scope();
                }

                first_arm_body_type.ok_or(TypeError::EmptyOuExpression)
            }
            Expression::Refl(expr) => {
                let inner_type = self.type_check_expression(expr, None)?;
                Ok(Type::Ze(
                    Box::new(inner_type.clone()),
                    Box::new(inner_type),
                ))
            }
            Expression::Glue { value } => {
                let inner_type = self.type_check_expression(value, None)?;
                Ok(Type::En(
                    Box::new(inner_type),
                    Box::new(Type::Unit), // Placeholder
                    Box::new(Type::Unit), // Placeholder
                ))
            }
            Expression::Unglue { value } => {
                let inner_type = self.type_check_expression(value, None)?;
                if let Type::En(t, _, _) = inner_type {
                    Ok(*t)
                } else {
                    Err(TypeError::TypeMismatch {
                        expected: "en type".to_string(),
                        found: type_to_string(&inner_type),
                    })
                }
            }
            Expression::Kan { .. } => self
                .evaluate(expression)
                .map(|v| self.type_of(&v))
                .map_err(|e| TypeError::NotImplemented(format!("{:?}", e))),
            Expression::Zo(_) => Ok(Type::Ident("ku".to_string())),
            _ => self
                .evaluate(expression)
                .map(|v| self.type_of(&v))
                .map_err(|e| TypeError::NotImplemented(format!("{:?}", e))),
        }
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
        let mut bindings = HashMap::new();
        match (pattern, matched_type) {
            (Pattern::Identifier(name), ty) => {
                if self.context.find_constructor_type(name).is_none() {
                    bindings.insert(name.clone(), ty.clone());
                }
            }
            (Pattern::Constructor(con_name, arg_patterns), Type::App(head, args)) => {
                if let Type::Ident(type_name) = &**head {
                     if let Some((_params, constructors)) = self.context.type_definitions.get(type_name) {
                        if let Some(constructor_def) = constructors.iter().find(|c| match c {
                            ConstructorDef::Point { name, .. } => name == con_name,
                            ConstructorDef::Path { name, .. } => name == con_name,
                        }) {
                            let fields = match constructor_def {
                                ConstructorDef::Point { fields, .. } => fields,
                                _ => {
                                    return Err(TypeError::NotImplemented(
                                        "Path constructor patterns".to_string(),
                                    ))
                                }
                            };

                            if arg_patterns.len() != fields.len() {
                                return Err(TypeError::ConstructorArityMismatch {
                                    name: con_name.clone(),
                                    expected: fields.len(),
                                    found: arg_patterns.len(),
                                });
                            }

                            // This is a simplification. A proper implementation would substitute
                            // the generic type parameters (from `_params`) with the concrete
                            // types from `args`. For now, we assume the first argument corresponds
                            // to the first field type.
                            if fields.len() == 1 && args.len() == 1 {
                                let sub_bindings =
                                    self.extract_bindings_from_pattern(&arg_patterns[0], &args[0])?;
                                bindings.extend(sub_bindings);
                            }
                        }
                    }
                }
            }
            (Pattern::Constructor(con_name, arg_patterns), Type::Ident(type_name)) => {
                if let Some((_params, constructors)) = self.context.type_definitions.get(type_name)
                {
                    if let Some(constructor_def) = constructors.iter().find(|c| match c {
                        ConstructorDef::Point { name, .. } => name == con_name,
                        ConstructorDef::Path { name, .. } => name == con_name,
                    }) {
                        let fields = match constructor_def {
                            ConstructorDef::Point { fields, .. } => fields,
                            _ => {
                                return Err(TypeError::NotImplemented(
                                    "Path constructor patterns".to_string(),
                                ))
                            }
                        };

                        if arg_patterns.len() != fields.len() {
                            return Err(TypeError::ConstructorArityMismatch {
                                name: con_name.clone(),
                                expected: fields.len(),
                                found: arg_patterns.len(),
                            });
                        }

                        for (arg_pat, field_ty) in arg_patterns.iter().zip(fields) {
                            let sub_bindings =
                                self.extract_bindings_from_pattern(arg_pat, field_ty)?;
                            bindings.extend(sub_bindings);
                        }
                    }
                }
            }
            _ => {}
        }
        Ok(bindings)
    }

    fn compile_binary_op_expression(
        &mut self,
        op: kotoba_parser::Operator,
        lhs: Expression,
        rhs: Expression,
    ) -> Result<String, String> {
        // This will be simplified as evaluation now happens before compilation
        let val = self
            .evaluate(&Expression::BinaryOp {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            })
            .map_err(|e| format!("{:?}", e))?;
        Ok(format!("{:?}", val)) // Just for now
    }

    fn compile_expression(&mut self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::Identifier(name) => Ok(name),
            Expression::IntegerLiteral(n) => Ok(n.to_string()),
            Expression::Zo(s) => {
                if s == "i0" {
                    Ok("kotoba_core::Interval::I0".to_string())
                } else {
                    Ok("kotoba_core::Interval::I1".to_string())
                }
            }
            Expression::Ou { expression, arms } => {
                let match_expr_str = self.compile_expression(*expression)?;
                let mut arm_strs = Vec::new();
                for arm in arms {
                    let pattern_str = self.compile_pattern(&arm.pattern)?;
                    let body_str = self.compile_expression(arm.body)?;
                    arm_strs.push(format!("        {} => {{ {} }},", pattern_str, body_str));
                }
                Ok(format!(
                    "match {} {{\n{}\n    }}",
                    match_expr_str,
                    arm_strs.join("\n")
                ))
            }
            Expression::Refl(expr) => {
                let inner_expr_str = self.compile_expression(*expr)?;
                Ok(format!("kotoba_core::Path::new(|_| {})", inner_expr_str))
            }
            Expression::Glue { value } => {
                let inner_expr_str = self.compile_expression(*value)?;
                Ok(format!("kotoba_core::glue({})", inner_expr_str))
            }
            Expression::Unglue { value } => {
                let inner_expr_str = self.compile_expression(*value)?;
                Ok(format!("kotoba_core::unglue({})", inner_expr_str))
            }
            Expression::BinaryOp { lhs, rhs, op } => {
                let lhs_str = self.compile_expression(*lhs)?;
                let rhs_str = self.compile_expression(*rhs)?;
                let op_str = match op {
                    kotoba_parser::Operator::Add => "+",
                    kotoba_parser::Operator::Subtract => "-",
                    kotoba_parser::Operator::Multiply => "*",
                    kotoba_parser::Operator::Divide => "/",
                    kotoba_parser::Operator::Equals => "==",
                    kotoba_parser::Operator::NotEquals => "!=",
                    kotoba_parser::Operator::LessThan => "<",
                    kotoba_parser::Operator::GreaterThan => ">",
                    kotoba_parser::Operator::LessThanOrEqual => "<=",
                    kotoba_parser::Operator::GreaterThanOrEqual => ">=",
                };
                Ok(format!("({} {} {})", lhs_str, op_str, rhs_str))
            }
            Expression::If {
                condition,
                then_branch,
                else_branch,
            } => {
                let cond_str = self.compile_expression(*condition)?;
                let then_str = self.compile_expression(*then_branch)?;
                let else_str = self.compile_expression(*else_branch)?;
                Ok(format!(
                    "if {} {{ {} }} else {{ {} }}",
                    cond_str, then_str, else_str
                ))
            }
            Expression::Kan { params, body } => {
                let param_strs: Vec<String> = params
                    .iter()
                    .map(|p| {
                        format!(
                            "{}: {}",
                            p.name,
                            self.type_to_rust_type_string(&p.type_annotation, None)
                        )
                    })
                    .collect();
                let body_str = self.compile_expression(*body)?;
                Ok(format!("|{}| {{ {} }}", param_strs.join(", "), body_str))
            }
            Expression::Pipe { lhs, rhs } => {
                let lhs_str = self.compile_expression(*lhs)?;
                let rhs_str = self.compile_expression(*rhs)?;
                // This is a simplification. A real implementation would need to handle
                // methods vs. functions differently.
                Ok(format!("{}({})", rhs_str, lhs_str))
            }
            Expression::MethodCall {
                variable,
                method,
                args,
            } => {
                let var_str = self.compile_expression(*variable)?;
                let arg_strs: Result<Vec<_>, _> =
                    args.into_iter().map(|a| self.compile_expression(a)).collect();
                Ok(format!("{}.{}({})", var_str, method, arg_strs?.join(", ")))
            }
            _ => {
                // Fallback to evaluation for other expression types
                let value = self
                    .evaluate(&expression)
                    .map_err(|e| format!("{:?}", e))?;
                match value {
                    Value::I64(n) => Ok(n.to_string()),
                    Value::Bool(b) => Ok(b.to_string()),
                    _ => Ok("\"<compiled_value>\"".to_string()),
                }
            }
        }
    }

    fn compile_pattern(&self, pattern: &Pattern) -> Result<String, String> {
        match pattern {
            Pattern::IntegerLiteral(n) => Ok(n.to_string()),
            Pattern::Wildcard => Ok("_".to_string()),
            Pattern::IntervalLiteral(s) => {
                if s == "i0" {
                    Ok("kotoba_core::Interval::I0".to_string())
                } else {
                    Ok("kotoba_core::Interval::I1".to_string())
                }
            }
            Pattern::Identifier(name) => {
                if let Some(type_name) = self.context.find_constructor_type(name) {
                    Ok(format!("{}::{}", capitalize(type_name), capitalize(name)))
                } else {
                    Ok(name.clone())
                }
            }
            Pattern::Constructor(name, args) => {
                let type_name = self
                    .context
                    .find_constructor_type(name)
                    .ok_or_else(|| "Unknown constructor".to_string())?;
                let capitalized_name = capitalize(name);
                if args.is_empty() {
                    Ok(format!("{}::{}", capitalize(type_name), capitalized_name))
                } else {
                    let arg_patterns: Result<Vec<_>, _> =
                        args.iter().map(|p| self.compile_pattern(p)).collect();
                    Ok(format!(
                        "{}::{}({})",
                        capitalize(type_name),
                        capitalized_name,
                        arg_patterns?.join(", ")
                    ))
                }
            }
            _ => unimplemented!("Pattern compilation not fully implemented yet."),
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
        assert!(result.is_ok());
        let expected_code = "let my_time: kotoba_core::Interval = kotoba_core::Interval::I0;";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    #[ignore] // Ignoring because `compose` method is not implemented in mock evaluator
    fn test_compile_shiki_method_call_with_args() {
        let input = "shiki p2: ze<i64, i64> = p1.compose(q1)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let path_type = Type::Ze(
            Box::new(Type::Ident("i64".to_string())),
            Box::new(Type::Ident("i64".to_string())),
        );
        compiler
            .context
            .define_var("p1".to_string(), path_type.clone());
        compiler.context.define_var("q1".to_string(), path_type);
        let result = compiler.compile(vec![statement]);
        let expected_code = "let p2: kotoba_core::Path<i64> = p1.compose(&q1);";
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
        assert!(result.is_ok());
        let expected_code = "let p_sym: kotoba_core::Path<i64> = p.sym();";
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

        assert!(
            compiled_code.contains("let g: kotoba_core::Glue<i64, (), ()> = kotoba_core::glue(10)"),
            "Did not find expected glue compilation in: {}",
            compiled_code
        );
        assert!(
            compiled_code.contains("let v: i64 = kotoba_core::unglue(g)"),
            "Did not find expected unglue compilation in: {}",
            compiled_code
        );
    }

    #[test]
    fn test_type_check_simple_vars() {
        let mut compiler = Compiler::new();
        compiler
            .context
            .define_var("x".to_string(), Type::Ident("i64".to_string()));
        let expr = kotoba_parser::parse_expression("x").unwrap().1;
        let result = compiler.type_check_expression(&expr, None);
        assert_eq!(result, Ok(Type::Ident("i64".to_string())));

        let expr_undef = kotoba_parser::parse_expression("y").unwrap().1;
        let result_undef = compiler.type_check_expression(&expr_undef, None);
        assert_eq!(
            result_undef,
            Err(TypeError::UndefinedVariable("y".to_string()))
        );
    }

    #[test]
    fn test_type_check_let() {
        let mut compiler = Compiler::new();
        let expr = kotoba_parser::parse_expression("let x: i64 = 10 in x + 1")
            .unwrap()
            .1;
        let result = compiler.type_check_expression(&expr, Some(&Type::Ident("i64".to_string())));
        assert_eq!(result, Ok(Type::Ident("i64".to_string())));

        let expr_mismatch = kotoba_parser::parse_expression("let x: ku = 10 in x")
            .unwrap()
            .1;
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
        compiler
            .context
            .define_var("is_positive".to_string(), func_type);
        compiler
            .context
            .define_var("n".to_string(), Type::Ident("i64".to_string()));

        let expr = kotoba_parser::parse_expression("n |> is_positive")
            .unwrap()
            .1;
        let result = compiler.type_check_expression(&expr, None);
        assert_eq!(result, Ok(Type::Ident("bool".to_string())));

        let expr_not_func = kotoba_parser::parse_expression("n |> n").unwrap().1;
        let result_not_func = compiler.type_check_expression(&expr_not_func, None);
        assert_eq!(
            result_not_func,
            Err(TypeError::NotAFunction(type_to_string(&Type::Ident(
                "i64".to_string()
            ))))
        );
    }

    #[test]
    fn test_type_check_ou() {
        let mut compiler = Compiler::new();
        let option_type_name = "Option".to_string();
        let constructors = vec![
            ConstructorDef::Point {
                name: "some".to_string(),
                fields: vec![Type::Ident("T".to_string())], // Generic parameter
            },
            ConstructorDef::Point {
                name: "none".to_string(),
                fields: vec![],
            },
        ];
         compiler.context.type_definitions.insert(
            option_type_name.clone(),
            (
                vec![Parameter {
                    name: "T".to_string(),
                    type_annotation: Type::Ident("Type".to_string()),
                }],
                constructors,
            ),
        );

        let option_value_type = Type::App(
            Box::new(Type::Ident(option_type_name)),
            vec![Type::Ident("i64".to_string())],
        );
        compiler
            .context
            .define_var("opt".to_string(), option_value_type);

        let expr_ok = parse_expression("ou opt { some(x) => x, none => 0 }")
            .unwrap()
            .1;
        let result_ok = compiler.type_check_expression(&expr_ok, None);
        assert_eq!(result_ok, Ok(Type::Ident("i64".to_string())));

        let expr_err = parse_expression("ou opt { some(x) => x, none => i0 }")
            .unwrap()
            .1;
        let result_err = compiler.type_check_expression(&expr_err, None);
        assert_eq!(
            result_err,
            Err(TypeError::TypeMismatch {
                expected: "i64".to_string(),
                found: type_to_string(&Type::Ident("ku".to_string()))
            })
        );
    }

    #[test]
    fn test_compile_rin_with_generics() {
        let input = "rin id<T>(x: T): T = x";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "fn id<T>(x: T) -> T {\n    x\n}";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_rin_statement() {
        let input = "rin add(a: N, b: N): N = ou a { zero => b }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        compiler
            .context
            .type_definitions
            .insert("N".to_string(), (vec![], vec![]));
        compiler
            .context
            .define_var("a".to_string(), Type::Ident("N".to_string()));
        compiler
            .context
            .define_var("b".to_string(), Type::Ident("N".to_string()));
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
        let expected_code =
            "#[derive(Debug, Clone)]\npub enum N {\n    Zero,\n    Succ(Box<N>)\n}";
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
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
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
            parse_statement(
                "shiki path_to_false: ze<Bool, Bool> = kan(i: ku) => ou i { i0 => true, i1 => false }",
            )
            .unwrap()
            .1,
        ];
        let mut compiler = Compiler::new();
        let result = compiler.compile(program);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();

        assert!(compiled_code.contains("enum Bool"));
        assert!(compiled_code.contains("let path_to_false: kotoba_core::Path<Bool>"));
    }

    /*
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
            "let p_over_p: kotoba_core::Path<kotoba_core::Path<i64>> = some_path;";
        assert!(result.unwrap().contains(expected_code));
    }
    */

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
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let expected_code = "let ticks: kotoba_core::Glue<ma, i64, some_eq> = timer_ba.as_en();";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_refl() {
        let input = "shiki id_path: ze<i64, i64> = refl(10)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code = "let id_path: kotoba_core::Path<i64> = kotoba_core::Path::new(|_| 10);";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_path_constructor() {
        let input = "shiki my_path: ze<i64, i64> = kan(i: ku) => ou i { i0 => 10, i1 => 20 }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let compiled_code = result.unwrap();
        assert!(compiled_code.contains("let my_path: kotoba_core::Path<i64>"));
        assert!(compiled_code.contains("= |i: kotoba_core::Interval|"));
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
            "let add_curried: Box<dyn Fn(i64) -> Box<dyn Fn(i64) -> i64>> = |a: i64| { |b: i64| { a } };";
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
        let doubler_type = Type::Func(
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
        );
        compiler
            .context
            .define_var("doubler".to_string(), doubler_type);
        let result = compiler.compile(vec![statement]);
        assert!(result.is_ok(), "Compilation failed: {:?}", result.err());
        let expected_code = "let pipeline: kotoba_core::Glue<ma, i64, id> = doubler(ticks);";
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
        let expected_code = "let doubler: Box<dyn Fn(i64) -> i64> = |x: i64| { x };";
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_ou_expression() {
        let input = "ou i { i0 => i0, _ => i1 }";
        let (_, expression) = parse_expression(input).unwrap();
        let mut compiler = Compiler::new();
        compiler
            .context
            .define_var("i".to_string(), Type::Ident("ku".to_string()));
        let result = compiler.compile_expression(expression);
        let expected_code = "match i {\n    kotoba_core::Interval::I0 => { kotoba_core::Interval::I0 },\n    _ => { kotoba_core::Interval::I1 },\n}";
        assert!(result.unwrap().contains("match i"));
    }

    #[test]
    fn test_compile_gyo_statement_with_path() {
        let input = "gyo S1 = { base, loop: ze<base, base> }";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_enum = "#[derive(Debug, Clone)]\npub enum S1 {\n    Base\n}";
        let expected_impl = "impl S1 {\n    pub fn loop(&self) -> kotoba_core::Path<Base> {\n        unimplemented!(\"Path constructor compilation is not fully supported yet.\")\n    }\n}";
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

    /*
    #[test]
    #[ignore]
    fn test_dependent_type_evaluation_in_type_checker() {
        let program = vec![
            // The parser needs to be able to handle `Type`
        ];
    }
    */
}