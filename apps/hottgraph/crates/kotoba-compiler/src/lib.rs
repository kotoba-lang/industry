use kotoba_parser::{
    ConstructorDef, Expression, OuArm, Pattern, Statement, Type, Parameter
};
use std::collections::HashMap;

/// Stores information about types and variables in the current scope.
#[derive(Debug, Clone, PartialEq)]
pub struct Context {
    /// Type definitions from `gyo` statements.
    /// Maps a type name (e.g., "N") to its parameters and constructor definitions.
    type_definitions: HashMap<String, (Vec<Parameter>, Vec<ConstructorDef>)>,
    /// Scoped variables. Each element in the vector represents a new scope.
    scopes: Vec<HashMap<String, Type>>,
}

impl Context {
    fn new() -> Self {
        Context {
            type_definitions: HashMap::new(),
            scopes: vec![HashMap::new()], // Start with a global scope
        }
    }

    /// Enters a new scope.
    fn enter_scope(&mut self) {
        self.scopes.push(HashMap::new());
    }

    /// Exits the current scope.
    fn exit_scope(&mut self) {
        self.scopes.pop();
    }

    /// Defines a new variable in the current scope.
    fn define_var(&mut self, name: String, ty: Type) {
        self.scopes.last_mut().unwrap().insert(name, ty);
    }

    /// Finds a variable's type, searching from the innermost scope outwards.
    fn find_var(&self, name: &str) -> Option<&Type> {
        self.scopes.iter().rev().find_map(|scope| scope.get(name))
    }

    /// Finds which type a given constructor name belongs to.
    fn find_constructor_type(&self, constructor_name: &str) -> Option<&String> {
        let capitalized_name = capitalize(constructor_name);
        self.type_definitions
            .iter()
            .find_map(|(type_name, (_params, constructors))| {
                if constructors.iter().any(|c| match c {
                    ConstructorDef::Point { name, .. } => capitalize(name) == capitalized_name,
                    ConstructorDef::Path { name, .. } => capitalize(name) == capitalized_name,
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
    /// A 64-bit integer value.
    I64(i64),
    /// A boolean value.
    Bool(bool),
    /// The unit value `()`.
    Unit,
    /// A type itself, used when a type is treated as a first-class value.
    Type(Type),
    /// A dependent function type (Pi-type).
    Pi {
        binder_name: String,
        binder_type: Box<Value>,
        // The body is represented abstractly here. In a real interpreter,
        // this would be a closure capturing its environment.
        body: Box<Expression>,
        captured_context: Context,
    },
    /// A constructor of a `gyo` type.
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
        Type::Ku => "kotoba_core::Interval".to_string(),
        Type::Ze(t1, t2) => {
            format!("kotoba_core::Path<{}>", type_to_string(t1)) // Simplified
        }
        Type::En(t1, t2, t3) => {
            format!(
                "kotoba_core::Glue<{}, {}, {}>",
                type_to_string(t1),
                type_to_string(t2),
                type_to_string(t3)
            )
        }
        Type::Unit => "()".to_string(),
        Type::Func(from, to) => {
            format!("Box<dyn Fn({}) -> {}>", type_to_string(from), type_to_string(to))
        }
        Type::Pi { binder_type, return_type, .. } => {
            format!("Box<dyn Fn({}) -> {}>", type_to_string(binder_type), type_to_string(return_type))
        }
        Type::Ident(name) => name.clone(),
        Type::App(head, args) => {
            let head_str = type_to_string(head);
            let args_str = args.iter().map(type_to_string).collect::<Vec<_>>().join(" ");
            format!("{} {}", head_str, args_str)
        }
        Type::Expr(expr) => {
            // This is tricky. For now, we'll try a best-effort string representation
            // of the expression. A real compiler would need a pretty-printer.
            // This is mainly for debugging and test error messages.
            format!("{:?}", expr)
        }
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
        // 1st Pass: Register all type definitions from `gyo` statements.
        for statement in &program {
            if let Statement::Gyo { name, params, constructors } = statement {
                self.context
                    .type_definitions
                    .insert(name.clone(), (params.clone(), constructors.clone()));
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
                let type_str = type_to_string(&type_annotation);

                let value_checked = self
                    .type_check_expression(&value, Some(&type_annotation))
                    .map_err(|e| format!("{:?}", e))?;

                let found_type = self.type_of(&value_checked);

                self.context
                    .define_var(variable_name.clone(), found_type.clone());

                let type_check_passed = if let (Type::Ze(..), Expression::Kan { .. }) = (&type_annotation, &value) {
                    true
                } else {
                    self.are_types_equal(&found_type, &type_annotation)
                };

                if !type_check_passed {
                    return Err(format!(
                        "{:?}",
                        TypeError::TypeMismatch {
                            expected: type_to_string(&type_annotation),
                            found: type_to_string(&found_type)
                        }
                    ));
                }

                let value_expr = if let Expression::Glue { ref value } = value {
                    if let Type::En(t1, t2, t3) = &type_annotation {
                        let value_code = self.compile_expression(*value.clone())?;
                        format!(
                            "kotoba_core::glue::<{}, {}, {}>({})",
                            type_to_string(t1),
                            type_to_string(t2),
                            type_to_string(t3),
                            value_code
                        )
                    } else {
                        return Err("`glue` requires an `en` type annotation.".to_string());
                    }
                } else {
                    self.compile_expression(value.clone())?
                };

                let final_expr_code =
                    if let (Type::Ze(_, _), Expression::Kan { .. }) = (&type_annotation, &value) {
                        format!("kotoba_core::Path::new({})", value_expr)
                    } else {
                        value_expr
                    };

                Ok(format!(
                    "let {}: {} = {};",
                    variable_name, type_str, final_expr_code
                ))
            }
            Statement::Gyo { name, params, constructors } => {
                self.compile_gyo_statement(&name, &params, &constructors)
            }
            Statement::Rin {
                name,
                generics,
                params,
                return_type,
                body,
            } => {
                self.context.enter_scope();
                for p in &params {
                    self.context
                        .define_var(p.name.clone(), p.type_annotation.clone());
                }

                let generics_str = if generics.is_empty() {
                    String::new()
                } else {
                    format!("<{}>", generics.join(", "))
                };
                let params_str = params
                    .iter()
                    .map(|p| format!("{}: {}", p.name, type_to_string(&p.type_annotation)))
                    .collect::<Vec<String>>()
                    .join(", ");
                let return_type_str = type_to_string(&return_type);
                let body_str = self.compile_expression(body)?;

                self.context.exit_scope();

                Ok(format!(
                    "fn {}{}({}) -> {} {{\n    {}\n}}",
                    name, generics_str, params_str, return_type_str, body_str
                ))
            }
        }
    }

    fn compile_gyo_statement(
        &self,
        name: &str,
        params: &[Parameter],
        constructors: &[ConstructorDef],
    ) -> Result<String, String> {
        let mut enum_variants = String::new();
        let mut impl_methods = String::new();

        // For now, we only handle type parameters (like `A: Type`) for generics.
        // Value parameters (`n: i64`) are ignored in this step.
        let generics: Vec<_> = params.iter()
            .filter_map(|p| {
                if let Type::Ident(s) = &p.type_annotation {
                    if s == "Type" { // This is a convention for now.
                        return Some(p.name.clone());
                    }
                }
                None
            })
            .collect();

        let generics_str = if generics.is_empty() {
            String::new()
        } else {
            format!("<{}>", generics.join(", "))
        };


        for c in constructors {
            match c {
                ConstructorDef::Point {
                    name: constr_name,
                    fields,
                } => {
                    let variant_name = capitalize(constr_name);
                    if fields.is_empty() {
                        enum_variants.push_str(&format!("    {},\n", variant_name));
                    } else {
                        let fields_str = fields
                            .iter()
                            .map(|f| {
                                let type_str = type_to_string(f);
                                if type_str == name {
                                    format!("Box<{}>", name)
                                } else {
                                    type_str
                                }
                            })
                            .collect::<Vec<_>>()
                            .join(", ");
                        enum_variants.push_str(&format!("    {}({}),\n", variant_name, fields_str));
                    }
                }
                ConstructorDef::Path { name: path_name, path_type } => {
                    let path_type_str = type_to_string(path_type);
                    impl_methods.push_str(&format!(
                        "    pub fn {}(&self) -> {} {{\n        unimplemented!(\"Path constructor compilation is not fully supported yet.\")\n    }}\n",
                        path_name,
                        path_type_str
                    ));
                }
            }
        }

        let enum_def = format!(
            "#[derive(Debug, Clone)]\nenum {}{} {{\n{}}}",
            name, generics_str, enum_variants
        );

        if impl_methods.is_empty() {
            Ok(enum_def)
        } else {
            Ok(format!("{}\n\nimpl {} {{\n{}}}", enum_def, name, impl_methods))
        }
    }

    fn are_types_equal(&mut self, t1: &Type, t2: &Type) -> bool {
        match (t1, t2) {
            (Type::Pi { binder_type: bt1, return_type: rt1, .. }, Type::Func(p1, r1)) |
            (Type::Func(p1, r1), Type::Pi { binder_type: bt1, return_type: rt1, .. }) => {
                self.are_types_equal(bt1, p1) && self.are_types_equal(rt1, r1)
            }
            _ => {
                let norm_t1 = self.normalize(t1).unwrap_or_else(|_| t1.clone());
                let norm_t2 = self.normalize(t2).unwrap_or_else(|_| t2.clone());
                type_to_string(&norm_t1) == type_to_string(&norm_t2)
            }
        }
    }

    /// Evaluates expressions within a type to produce a normalized form.
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
                // Convert the value back into a type-level representation.
                // This is a crucial step for dependent types.
                match value {
                    Value::I64(n) => Ok(Type::Expr(Box::new(Expression::IntegerLiteral(n)))),
                    Value::Bool(b) => Ok(Type::Expr(Box::new(Expression::Identifier(b.to_string())))),
                    // For now, other values are returned as-is, wrapped in Type::Expr
                    _ => Ok(Type::Expr(expr.clone())),
                }
            }
            // Other types are returned as-is for now.
            _ => Ok(ty.clone()),
        }
    }

    fn type_check_expression(
        &mut self,
        expression: &Expression,
        expected_type: Option<&Type>,
    ) -> Result<Value, TypeError> {
        match expression {
            Expression::IntegerLiteral(n) => Ok(Value::I64(*n)),
            Expression::Zo(_) => Ok(Value::Type(Type::Ku)),
            Expression::Identifier(name) => {
                let ty = self.type_check_identifier(name)?;
                Ok(Value::Type(ty))
            }
            Expression::Let {
                name,
                type_annotation,
                value,
                body,
            } => {
                let value_checked = self.type_check_expression(value, type_annotation.as_deref())?;
                let value_type = self.type_of(&value_checked);
                if let Some(annotated_type) = type_annotation {
                    if !self.are_types_equal(&value_type, annotated_type) {
                        return Err(TypeError::TypeMismatch {
                            expected: type_to_string(annotated_type),
                            found: type_to_string(&value_type),
                        });
                    }
                }
                self.context.enter_scope();
                self.context.define_var(name.clone(), value_type);
                let body_value = self.type_check_expression(body, expected_type)?;
                self.context.exit_scope();
                Ok(body_value)
            }
            Expression::If {
                condition,
                then_branch,
                else_branch,
            } => {
                let condition_value = self.type_check_expression(condition, Some(&Type::Ident("bool".to_string())))?;
                if self.type_of(&condition_value) != Type::Ident("bool".to_string()) {
                    return Err(TypeError::TypeMismatch {
                        expected: "bool".to_string(),
                        found: type_to_string(&self.type_of(&condition_value)),
                    });
                }

                let then_value = self.type_check_expression(then_branch, expected_type)?;
                let else_value = self.type_check_expression(else_branch, expected_type)?;

                if self.type_of(&then_value) != self.type_of(&else_value) {
                    return Err(TypeError::TypeMismatch {
                        expected: type_to_string(&self.type_of(&then_value)),
                        found: type_to_string(&self.type_of(&else_value)),
                    });
                }
                Ok(then_value)
            }
            Expression::BinaryOp { lhs, rhs, op } => {
                let lhs_value = self.type_check_expression(lhs, Some(&Type::Ident("i64".to_string())))?;
                let rhs_value = self.type_check_expression(rhs, Some(&Type::Ident("i64".to_string())))?;

                if self.type_of(&lhs_value) != Type::Ident("i64".to_string())
                    || self.type_of(&rhs_value) != Type::Ident("i64".to_string())
                {
                    return Err(TypeError::TypeMismatch {
                        expected: "i64".to_string(),
                        found: type_to_string(&self.type_of(&lhs_value)),
                    });
                }
                
                match op {
                    kotoba_parser::Operator::Add
                    | kotoba_parser::Operator::Subtract
                    | kotoba_parser::Operator::Multiply
                    | kotoba_parser::Operator::Divide => Ok(Value::I64(0)),
                    _ => Ok(Value::Bool(true)),
                }
            }
            Expression::Kan { params, body } => {
                if params.len() != 1 {
                    return Err(TypeError::NotImplemented(
                        "Functions with multiple arguments".to_string(),
                    ));
                }
                let param = &params[0];
                let binder_type_value = Value::Type(param.type_annotation.clone());

                Ok(Value::Pi {
                    binder_name: param.name.clone(),
                    binder_type: Box::new(binder_type_value),
                    body: body.clone(),
                    captured_context: self.context.clone(),
                })
            }
            Expression::Pipe { lhs, rhs } => {
                let rhs_value = self.type_check_expression(rhs, None)?;
                match rhs_value {
                    Value::Pi { binder_name, binder_type, body, captured_context } => {
                        let lhs_value = self.type_check_expression(lhs, None)?;
                        let expected_lhs_type = self.type_of(&*binder_type);
                        let actual_lhs_type = self.type_of(&lhs_value);

                        if !self.are_types_equal(&actual_lhs_type, &expected_lhs_type) {
                            return Err(TypeError::TypeMismatch {
                                expected: type_to_string(&expected_lhs_type),
                                found: type_to_string(&actual_lhs_type),
                            });
                        }

                        let mut application_context = captured_context;
                        application_context.enter_scope();
                        application_context.define_var(binder_name, actual_lhs_type);

                        let original_context = self.context.clone();
                        self.context = application_context;
                        let result_value = self.type_check_expression(&body, expected_type)?;
                        self.context = original_context;
                        Ok(result_value)
                    }
                    Value::Type(Type::Func(param_type, return_type)) => {
                        let lhs_value = self.type_check_expression(lhs, None)?;
                        let actual_lhs_type = self.type_of(&lhs_value);
                        if !self.are_types_equal(&actual_lhs_type, &param_type) {
                            return Err(TypeError::TypeMismatch {
                                expected: type_to_string(&param_type),
                                found: type_to_string(&actual_lhs_type),
                            });
                        }
                        Ok(Value::Type(*return_type))
                    }
                    _ => Err(TypeError::NotAFunction(type_to_string(&self.type_of(&rhs_value)))),
                }
            }
            Expression::Ou { expression, arms } => {
                let matched_on_value = self.type_check_expression(expression, None)?;
                let matched_on_type = self.type_of(&matched_on_value);

                if arms.is_empty() {
                    return Err(TypeError::EmptyOuExpression);
                }

                let mut arm_values = Vec::new();

                for arm in arms {
                    self.context.enter_scope();

                    let bindings =
                        self.extract_bindings_from_pattern(&arm.pattern, &matched_on_type)?;
                    for (name, ty) in bindings {
                        self.context.define_var(name, ty);
                    }

                    let arm_body_value = self.type_check_expression(&arm.body, expected_type)?;
                    arm_values.push(arm_body_value);

                    self.context.exit_scope();
                }

                let first_arm_type = self.type_of(&arm_values[0]);
                for arm_value in arm_values.iter().skip(1) {
                    if self.type_of(arm_value) != first_arm_type {
                        return Err(TypeError::TypeMismatch {
                            expected: type_to_string(&first_arm_type),
                            found: type_to_string(&self.type_of(arm_value)),
                        });
                    }
                }

                Ok(arm_values.remove(0))
            }
            Expression::Refl(expr) => {
                let inner_value = self.type_check_expression(expr, None)?;
                let inner_type = self.type_of(&inner_value);
                Ok(Value::Type(Type::Ze(
                    Box::new(inner_type.clone()),
                    Box::new(inner_type),
                )))
            }
            Expression::Glue { value } => {
                if let Some(Type::En(t, _, _)) = expected_type {
                    let value_checked = self.type_check_expression(value, Some(t))?;
                    if self.type_of(&value_checked) != *t.clone() {
                        return Err(TypeError::TypeMismatch {
                            expected: type_to_string(t),
                            found: type_to_string(&self.type_of(&value_checked)),
                        });
                    }
                    Ok(Value::Type(expected_type.unwrap().clone()))
                } else {
                    Err(TypeError::NotImplemented(
                        "Cannot infer type of `glue` without a type annotation.".to_string(),
                    ))
                }
            }
            Expression::Unglue { value } => {
                let value_checked = self.type_check_expression(value, None)?;
                if let Type::En(t1, _, _) = self.type_of(&value_checked) {
                    Ok(Value::Type(*t1))
                } else {
                    Err(TypeError::TypeMismatch {
                        expected: "en<..._>".to_string(),
                        found: type_to_string(&self.type_of(&value_checked)),
                    })
                }
            }
            Expression::MethodCall {
                variable,
                method,
                args,
            } => {
                let var_value = self.type_check_expression(variable, None)?;
                let var_type = self.type_of(&var_value);

                match (var_type, method.as_str()) {
                    (Type::Ze(t1, t2), "sym") => Ok(Value::Type(Type::Ze(t2, t1))),
                    (Type::Ze(t1, _), "compose") => {
                         if args.len() == 1 {
                            let arg_value = self.type_check_expression(&args[0], None)?;
                            if let Type::Ze(_, t3) = self.type_of(&arg_value) {
                                Ok(Value::Type(Type::Ze(t1, t3)))
                            } else {
                                Err(TypeError::NotImplemented(
                                    "Path composition with non-path types".to_string(),
                                ))
                            }
                        } else {
                             Err(TypeError::NotImplemented(
                                "compose with wrong number of args".to_string(),
                            ))
                        }
                    }
                    (_, "as_en") => {
                        if let Some(expected) = expected_type {
                            Ok(Value::Type(expected.clone()))
                        } else {
                            Err(TypeError::NotImplemented(
                                "Cannot infer `as_en` type without annotation".to_string(),
                            ))
                        }
                    }
                    _ => Err(TypeError::NotImplemented(format!(
                        "Method call on {:?} with method {}",
                        variable, method
                    ))),
                }
            }
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
        match pattern {
            Pattern::Wildcard | Pattern::IntegerLiteral(_) | Pattern::IntervalLiteral(_) => {}
            Pattern::Identifier(name) => {
                let is_constructor = self.context.find_constructor_type(name).is_some()
                    && name.chars().next().map_or(false, |c| c.is_uppercase());
                if !is_constructor {
                    bindings.insert(name.clone(), matched_type.clone());
                }
            }
            Pattern::Constructor(name, sub_patterns) => {
                let type_name = self
                    .context
                    .find_constructor_type(name)
                    .ok_or_else(|| TypeError::UndefinedType(name.clone()))?;

                let type_def_tuple = self
                    .context
                    .type_definitions
                    .get(type_name)
                    .ok_or_else(|| TypeError::UndefinedType(type_name.clone()))?;

                let constructor_def = type_def_tuple
                    .1 // Access constructors from the tuple
                    .iter()
                    .find(|c| match c {
                        ConstructorDef::Point { name: c_name, .. } => c_name == name,
                        _ => false,
                    })
                    .ok_or_else(|| TypeError::UndefinedType(name.clone()))?;

                if let ConstructorDef::Point { fields, .. } = constructor_def {
                    if fields.len() != sub_patterns.len() {
                        return Err(TypeError::ConstructorArityMismatch {
                            name: name.clone(),
                            expected: fields.len(),
                            found: sub_patterns.len(),
                        });
                    }

                    for (sub_pattern, field_type) in sub_patterns.iter().zip(fields.iter()) {
                        let sub_bindings =
                            self.extract_bindings_from_pattern(sub_pattern, field_type)?;
                        bindings.extend(sub_bindings);
                    }
                }
            }
        }
        Ok(bindings)
    }

    fn compile_binary_op_expression(
        &mut self,
        op: kotoba_parser::Operator,
        lhs: Expression,
        rhs: Expression,
    ) -> Result<String, String> {
        let lhs_code = self.compile_expression(lhs)?;
        let rhs_code = self.compile_expression(rhs)?;
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
        Ok(format!("({} {} {})", lhs_code, op_str, rhs_code))
    }

    fn compile_expression(&mut self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::Identifier(name) => self.compile_identifier_expression(&name),
            Expression::IntegerLiteral(n) => Ok(n.to_string()),
            Expression::If {
                condition,
                then_branch,
                else_branch,
            } => {
                let cond_code = self.compile_expression(*condition)?;
                let then_code = self.compile_expression(*then_branch)?;
                let else_code = self.compile_expression(*else_branch)?;
                Ok(format!(
                    "if {} {{ {} }} else {{ {} }}",
                    cond_code, then_code, else_code
                ))
            }
            Expression::BinaryOp { op, lhs, rhs } => {
                self.compile_binary_op_expression(op, *lhs, *rhs)
            }
            Expression::Refl(expr) => self.compile_refl_expression(*expr),
            Expression::Glue { .. } => Err("`glue` must be used directly in a `shiki` statement with a type annotation.".to_string()),
            Expression::Unglue { value } => self.compile_unglue_expression(*value),
            Expression::Let { name, type_annotation, value, body } => self.compile_let_expression(name, type_annotation, *value, *body),
            Expression::Zo(val) => self.compile_zo_expression(&val),
            Expression::MethodCall { variable, method, args } => self.compile_method_call_expression(*variable, method, args),
            Expression::Pipe { lhs, rhs } => self.compile_pipe_expression(*lhs, *rhs),
            Expression::Kan { params, body } => self.compile_kan_expression(params, *body),
            Expression::Ou { expression, arms } => self.compile_ou_expression(*expression, arms),
        }
    }

    fn compile_identifier_expression(&self, name: &str) -> Result<String, String> {
        if let Some(type_name) = self.context.find_constructor_type(name) {
            Ok(format!("{}::{}", type_name, capitalize(name)))
        } else {
            Ok(name.to_string())
        }
    }

    fn compile_refl_expression(&mut self, expr: Expression) -> Result<String, String> {
        let expr_code = self.compile_expression(expr)?;
        Ok(format!("kotoba_core::Path::new(|_| {})", expr_code))
    }

    fn compile_unglue_expression(&mut self, value: Expression) -> Result<String, String> {
        let value_code = self.compile_expression(value)?;
        Ok(format!("kotoba_core::unglue({})", value_code))
    }

    fn compile_let_expression(
        &mut self,
        name: String,
        type_annotation: Option<Box<Type>>,
        value: Expression,
        body: Expression,
    ) -> Result<String, String> {
        let value_code = self.compile_expression(value)?;
        self.context.enter_scope();
        if let Some(ty) = type_annotation.clone() {
            self.context.define_var(name.clone(), *ty);
        }
        let body_code = self.compile_expression(body)?;
        self.context.exit_scope();

        let let_statement = if let Some(ty) = type_annotation {
            format!(
                "let {}: {} = {};",
                name,
                type_to_string(&ty),
                value_code
            )
        } else {
            format!("let {} = {};", name, value_code)
        };
        Ok(format!("{{\n    {}\n    {}\n}}", let_statement, body_code))
    }

    fn compile_zo_expression(&self, val: &str) -> Result<String, String> {
        match val {
            "i0" => Ok("kotoba_core::Interval::I0".to_string()),
            "i1" => Ok("kotoba_core::Interval::I1".to_string()),
            _ => Err("Invalid interval literal".to_string()),
        }
    }

    fn compile_method_call_expression(
        &mut self,
        variable: Expression,
        method: String,
        args: Vec<Expression>,
    ) -> Result<String, String> {
        let var_code = self.compile_expression(variable)?;
        let args_code: Vec<String> = args
            .into_iter()
            .map(|arg| self.compile_expression(arg))
            .collect::<Result<_, _>>()?;

        let formatted_args = if method == "compose" {
            args_code
                .iter()
                .map(|arg| format!("&{}", arg))
                .collect::<Vec<_>>()
                .join(", ")
        } else {
            args_code.join(", ")
        };

        Ok(format!("{}.{}({})", var_code, method, formatted_args))
    }

    fn compile_pipe_expression(&mut self, lhs: Expression, rhs: Expression) -> Result<String, String> {
        let lhs_code = self.compile_expression(lhs)?;
        let rhs_code = self.compile_expression(rhs)?;
        Ok(format!("{}({})", rhs_code, lhs_code))
    }

    fn compile_kan_expression(
        &mut self,
        params: Vec<kotoba_parser::Parameter>,
        body: Expression,
    ) -> Result<String, String> {
        self.context.enter_scope();
        for p in &params {
            self.context
                .define_var(p.name.clone(), p.type_annotation.clone());
        }

        let params_str = params
            .iter()
            .map(|p| format!("{}: {}", p.name, type_to_string(&p.type_annotation)))
            .collect::<Vec<String>>()
            .join(", ");

        let body_code = self.compile_expression(body)?;
        self.context.exit_scope();

        Ok(format!("|{}| {{ {} }}", params_str, body_code))
    }

    fn compile_ou_expression(
        &mut self,
        expression: Expression,
        arms: Vec<OuArm>,
    ) -> Result<String, String> {
        let expr_code = self.compile_expression(expression)?;
        let mut arms_code = String::new();
        for arm in arms {
            arms_code.push_str(&self.compile_ou_arm(arm)?);
        }
        Ok(format!("match {} {{\n{}}}", expr_code, arms_code))
    }

    fn compile_ou_arm(&mut self, arm: OuArm) -> Result<String, String> {
        let pattern_code = self.compile_pattern(&arm.pattern)?;
        let body_code = self.compile_expression(arm.body)?;
        Ok(format!("    {} => {{ {} }},\n", pattern_code, body_code))
    }

    fn compile_pattern(&self, pattern: &Pattern) -> Result<String, String> {
        match pattern {
            Pattern::IntegerLiteral(i) => Ok(i.to_string()),
            Pattern::IntervalLiteral(s) => match s.as_str() {
                "i0" => Ok("kotoba_core::Interval::I0".to_string()),
                "i1" => Ok("kotoba_core::Interval::I1".to_string()),
                _ => Err("Invalid interval literal in pattern".to_string()),
            },
            Pattern::Wildcard => Ok("_".to_string()),
            Pattern::Identifier(s) => {
                if let Some(tn) = self.context.find_constructor_type(s) {
                    Ok(format!("{}::{}", tn, capitalize(s)))
                } else {
                    Ok(s.clone())
                }
            }
            Pattern::Constructor(name, patterns) => {
                let capitalized_name = capitalize(name);
                let type_name = self.context.find_constructor_type(name);

                let fq_name = if let Some(tn) = type_name {
                    format!("{}::{}", tn, capitalized_name)
                } else {
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
