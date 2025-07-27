use kotoba_parser::{
    ConstructorDef, Expression, OuArm, Pattern, Statement, Type,
};
use std::collections::HashMap;

/// Stores information about types and variables in the current scope.
#[derive(Debug, Clone)]
pub struct Context {
    /// Type definitions from `gyo` statements.
    /// Maps a type name (e.g., "N") to its constructor definitions.
    type_definitions: HashMap<String, Vec<ConstructorDef>>,
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
            .find_map(|(type_name, constructors)| {
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

#[derive(Debug, PartialEq)]
pub enum TypeError {
    UndefinedVariable(String),
    UndefinedType(String),
    TypeMismatch { expected: Type, found: Type },
    NotAFunction(Type),
    ConstructorArityMismatch { name: String, expected: usize, found: usize },
    EmptyOuExpression,
    NotImplemented(String),
}

pub struct Compiler {
    context: Context,
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
        Type::En(t1, t2, t3) => {
            // Placeholder for Glue type compilation
            format!(
                "kotoba_core::Glue<{}, {}, {}>",
                type_to_string(t1),
                type_to_string(t2),
                type_to_string(t3)
            )
        }
        Type::Unit => "()".to_string(),
        Type::Func(from, to) => {
            // To avoid infinite recursion for function types in closures,
            // we represent them as a generic closure trait object.
            // A full implementation might need more nuanced handling.
            format!("Box<dyn Fn({}) -> {}>", type_to_string(from), type_to_string(to))
        }
        Type::Simple(name) => name.clone(),
    }
}

impl Compiler {
    pub fn new() -> Self {
        Compiler {
            context: Context::new(),
        }
    }

    pub fn compile(&mut self, program: Vec<Statement>) -> Result<String, String> {
        // 1st Pass: Register all type definitions from `gyo` statements.
        for statement in &program {
            if let Statement::Gyo { name, constructors } = statement {
                self.context
                    .type_definitions
                    .insert(name.clone(), constructors.clone());
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

                self.context
                    .define_var(variable_name.clone(), type_annotation.clone());

                let found_type = if let Expression::MethodCall { method, .. } = &value {
                    if method == "as_en" {
                        self.type_check_expression(&value, Some(&type_annotation))
                            .map_err(|e| format!("{:?}", e))?
                    } else {
                        self.type_check_expression(&value, Some(&type_annotation))
                            .map_err(|e| format!("{:?}", e))?
                    }
                } else {
                    self.type_check_expression(&value, Some(&type_annotation))
                        .map_err(|e| format!("{:?}", e))?
                };

                // Special case for path construction: `ze` type annotation on a `kan` expression.
                let type_check_passed = if let (Type::Ze(..), Expression::Kan { .. }) = (&type_annotation, &value) {
                    // When a `kan` is used to define a `ze` (path), we expect the `kan`'s type
                    // to be a function from `ku` to the path's content type.
                    // A full check would be more detailed, but for now we accept it.
                    true
                } else {
                    self.are_types_equal(&found_type, &type_annotation)
                };

                if !type_check_passed {
                    return Err(format!(
                        "{:?}",
                        TypeError::TypeMismatch {
                            expected: type_annotation,
                            found: found_type
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
            Statement::Gyo { name, constructors } => {
                self.compile_gyo_statement(&name, &constructors)
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
        constructors: &[ConstructorDef],
    ) -> Result<String, String> {
        let mut enum_variants = String::new();
        let mut impl_methods = String::new();

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
                                // Recursive type definitions need to be boxed.
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
            "#[derive(Debug, Clone)]\nenum {} {{\n{}}}",
            name, enum_variants
        );

        if impl_methods.is_empty() {
            Ok(enum_def)
        } else {
            Ok(format!("{}\n\nimpl {} {{\n{}}}", enum_def, name, impl_methods))
        }
    }

    fn are_types_equal(&self, t1: &Type, t2: &Type) -> bool {
        // This is a placeholder for a real type equality check.
        // For now, we compare their string representations.
        type_to_string(t1) == type_to_string(t2)
    }

    fn type_check_expression(
        &mut self,
        expression: &Expression,
        expected_type: Option<&Type>,
    ) -> Result<Type, TypeError> {
        match expression {
            Expression::IntegerLiteral(_) => Ok(Type::Simple("i64".to_string())),
            Expression::Zo(_) => Ok(Type::Ku),
            Expression::Identifier(name) => self.type_check_identifier(name),
            Expression::Let {
                name,
                type_annotation,
                value,
                body,
            } => {
                let value_type = self.type_check_expression(value, type_annotation.as_ref())?;
                if let Some(annotated_type) = type_annotation {
                    if !self.are_types_equal(&value_type, annotated_type) {
                        return Err(TypeError::TypeMismatch {
                            expected: annotated_type.clone(),
                            found: value_type,
                        });
                    }
                }
                self.context.enter_scope();
                self.context.define_var(name.clone(), value_type);
                // The body's expected type is the same as the whole let expression's.
                let body_type = self.type_check_expression(body, expected_type)?;
                self.context.exit_scope();
                Ok(body_type)
            }
            Expression::BinaryOp { lhs, rhs, .. } => {
                let lhs_type = self.type_check_expression(lhs, Some(&Type::Simple("i64".to_string())))?;
                let rhs_type = self.type_check_expression(rhs, Some(&Type::Simple("i64".to_string())))?;

                if !self.are_types_equal(&lhs_type, &Type::Simple("i64".to_string()))
                    || !self.are_types_equal(&rhs_type, &Type::Simple("i64".to_string()))
                {
                    // For now, we'll be a bit generic.
                    return Err(TypeError::TypeMismatch {
                        expected: Type::Simple("i64".to_string()),
                        found: lhs_type, // Or rhs_type, could be either
                    });
                }
                Ok(Type::Simple("i64".to_string()))
            }
            Expression::Kan { params, body } => {
                if let Some(expected) = expected_type {
                    match expected {
                        Type::Ze(t1, _) => {
                            // Expect `kan(i: ku) => t1`
                            if params.len() == 1 && params[0].type_annotation == Type::Ku {
                                self.context.enter_scope();
                                self.context.define_var(params[0].name.clone(), Type::Ku);
                                let body_type = self.type_check_expression(body, Some(t1))?;
                                self.context.exit_scope();

                                if self.are_types_equal(&body_type, t1) {
                                    return Ok(expected.clone());
                                }
                            }
                        }
                        Type::En(t1, t2, _) => {
                            // Expect `kan(x: t1) => t2`
                            if params.len() == 1
                                && self.are_types_equal(&params[0].type_annotation, t1)
                            {
                                self.context.enter_scope();
                                self.context.define_var(params[0].name.clone(), *t1.clone());
                                let body_type = self.type_check_expression(body, Some(t2))?;
                                self.context.exit_scope();
                                if self.are_types_equal(&body_type, t2) {
                                    return Ok(expected.clone());
                                }
                            }
                        }
                        _ => {} // Fallback to default behavior
                    }
                }

                // Default behavior when no specific function type is expected.
                if params.len() != 1 {
                    return Err(TypeError::NotImplemented(
                        "Functions with multiple arguments".to_string(),
                    ));
                }
                let param = &params[0];
                let param_name = param.name.clone();
                let param_type = param.type_annotation.clone();

                self.context.enter_scope();
                self.context.define_var(param_name, param_type.clone());
                let return_type = self.type_check_expression(body, None)?;
                self.context.exit_scope();

                Ok(Type::Func(Box::new(param_type), Box::new(return_type)))
            }
            Expression::Pipe { lhs, rhs } => {
                let lhs_type = self.type_check_expression(lhs, None)?;
                // We expect the RHS to be a function, but we don't know its specific type yet.
                let rhs_type = self.type_check_expression(rhs, None)?;

                if let Type::Func(param_type, return_type) = rhs_type {
                    if self.are_types_equal(&lhs_type, &param_type) {
                        Ok(*return_type)
                    } else {
                        Err(TypeError::TypeMismatch {
                            expected: *param_type,
                            found: lhs_type,
                        })
                    }
                } else {
                    Err(TypeError::NotAFunction(rhs_type))
                }
            }
            Expression::Ou { expression, arms } => {
                let matched_on_type = self.type_check_expression(expression, None)?;

                if arms.is_empty() {
                    return Err(TypeError::EmptyOuExpression);
                }

                let mut arm_types = Vec::new();

                for arm in arms {
                    self.context.enter_scope();

                    let bindings =
                        self.extract_bindings_from_pattern(&arm.pattern, &matched_on_type)?;
                    for (name, ty) in bindings {
                        self.context.define_var(name, ty);
                    }

                    // All arms must conform to the `ou` expression's expected type.
                    let arm_body_type = self.type_check_expression(&arm.body, expected_type)?;
                    arm_types.push(arm_body_type);

                    self.context.exit_scope();
                }

                // Check if all arms have the same type.
                let first_arm_type = arm_types[0].clone();
                for arm_type in arm_types.iter().skip(1) {
                    if !self.are_types_equal(&first_arm_type, arm_type) {
                        return Err(TypeError::TypeMismatch {
                            expected: first_arm_type,
                            found: arm_type.clone(),
                        });
                    }
                }

                Ok(first_arm_type)
            }
            Expression::Refl(expr) => {
                // Infer the inner type, then construct the Path type.
                let inner_type = self.type_check_expression(expr, None)?;
                Ok(Type::Ze(Box::new(inner_type.clone()), Box::new(inner_type)))
            }
            Expression::Glue { value } => {
                if let Some(Type::En(t, _, _)) = expected_type {
                    // If we expect an `en` type, we check the inner value against `T`.
                    self.type_check_expression(value, Some(t))?;
                    // Return the full `en` type.
                    Ok(expected_type.unwrap().clone())
                } else {
                    // If we don't know what to expect, we can't fully check `glue`.
                    // For now, we fall back to a placeholder or an error.
                    Err(TypeError::NotImplemented(
                        "Cannot infer type of `glue` without a type annotation.".to_string(),
                    ))
                }
            }
            Expression::Unglue { value } => {
                // The type of the `unglue` expression depends on the type of the `value`.
                // We don't have an expectation for the inner type, so we pass `None`.
                let value_type = self.type_check_expression(value, None)?;
                if let Type::En(t1, _, _) = value_type {
                    Ok(*t1)
                } else {
                    Err(TypeError::TypeMismatch {
                        expected: Type::En(
                            Box::new(Type::Simple("...".to_string())),
                            Box::new(Type::Simple("...".to_string())),
                            Box::new(Type::Simple("...".to_string())),
                        ),
                        found: value_type,
                    })
                }
            }
            Expression::MethodCall {
                variable,
                method,
                args,
            } => {
                let var_type = self.type_check_expression(variable, None)?;

                // This is a very simplified mock. A real implementation would need
                // to know the methods available on each type.
                match (var_type, method.as_str()) {
                    (Type::Ze(t1, _), "compose") => {
                        if args.len() == 1 {
                            let arg_type = self.type_check_expression(&args[0], None)?;
                            // In a real system, we'd check if arg_type is a path that
                            // starts where `var_type` ends. For now, assume it's correct.
                            if let Type::Ze(_, t3) = arg_type {
                                Ok(Type::Ze(t1, t3))
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
                    (Type::Ze(t1, t2), "sym") => Ok(Type::Ze(t2, t1)),
                    (_, "as_en") => {
                        if let Some(expected) = expected_type {
                            Ok(expected.clone())
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
            // Placeholder for other expression types
            _ => Err(TypeError::NotImplemented(format!("{:?}", expression))),
        }
    }

    fn type_check_identifier(&self, name: &str) -> Result<Type, TypeError> {
        if let Some(ty) = self.context.find_var(name) {
            Ok(ty.clone())
        } else if let Some(type_name) = self.context.find_constructor_type(name) {
            Ok(Type::Simple(type_name.clone()))
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
            Pattern::Wildcard | Pattern::IntegerLiteral(_) | Pattern::IntervalLiteral(_) => {
                // No bindings
            }
            Pattern::Identifier(name) => {
                // If the identifier is not a known constructor, it's a variable binding.
                let is_constructor = self.context.find_constructor_type(name).is_some()
                    && name.chars().next().map_or(false, |c| c.is_uppercase());
                if !is_constructor {
                    bindings.insert(name.clone(), matched_type.clone());
                }
            }
            Pattern::Constructor(name, sub_patterns) => {
                // Find the definition for this constructor
                let type_name = self
                    .context
                    .find_constructor_type(name)
                    .ok_or_else(|| TypeError::UndefinedType(name.clone()))?;

                let type_def = self
                    .context
                    .type_definitions
                    .get(type_name)
                    .ok_or_else(|| TypeError::UndefinedType(type_name.clone()))?;

                let constructor_def = type_def
                    .iter()
                    .find(|c| match c {
                        ConstructorDef::Point { name: c_name, .. } => c_name == name,
                        ConstructorDef::Path { .. } => false, // Paths in patterns not yet supported
                    })
                    .ok_or_else(|| TypeError::UndefinedType(name.clone()))?; // Should be a more specific error

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
        };
        Ok(format!("({} {} {})", lhs_code, op_str, rhs_code))
    }

    fn compile_expression(&mut self, expression: Expression) -> Result<String, String> {
        match expression {
            Expression::Identifier(name) => self.compile_identifier_expression(&name),
            Expression::IntegerLiteral(n) => Ok(n.to_string()),
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
        type_annotation: Option<Type>,
        value: Expression,
        body: Expression,
    ) -> Result<String, String> {
        let value_code = self.compile_expression(value)?;
        self.context.enter_scope();
        if let Some(ty) = type_annotation.clone() {
            self.context.define_var(name.clone(), ty);
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
        // We remove the Ba import for now as it's not used.
        assert_eq!(result.unwrap().contains(expected_code), true);
    }

    #[test]
    fn test_compile_shiki_method_call_with_args() {
        let input = "shiki p2: ze<i64, i64> = p1.compose(q1)";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let path_type = Type::Ze(
            Box::new(Type::Simple("i64".to_string())),
            Box::new(Type::Simple("i64".to_string())),
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
                Box::new(Type::Simple("i64".to_string())),
                Box::new(Type::Simple("i64".to_string())),
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
        compiler.context.define_var("x".to_string(), Type::Simple("i64".to_string()));
        let expr = kotoba_parser::parse_expression("x").unwrap().1;
        let result = compiler.type_check_expression(&expr, None);
        assert_eq!(result, Ok(Type::Simple("i64".to_string())));

        let expr_undef = kotoba_parser::parse_expression("y").unwrap().1;
        let result_undef = compiler.type_check_expression(&expr_undef, None);
        assert_eq!(result_undef, Err(TypeError::UndefinedVariable("y".to_string())));
    }

    #[test]
    fn test_type_check_let() {
        let mut compiler = Compiler::new();
        let expr = kotoba_parser::parse_expression("let x: i64 = 10 in x").unwrap().1;
        let result = compiler.type_check_expression(&expr, Some(&Type::Simple("i64".to_string())));
        assert_eq!(result, Ok(Type::Simple("i64".to_string())));

        let expr_mismatch = kotoba_parser::parse_expression("let x: ku = 10 in x").unwrap().1;
        let result_mismatch = compiler.type_check_expression(&expr_mismatch, Some(&Type::Ku));
        assert_eq!(
            result_mismatch,
            Err(TypeError::TypeMismatch {
                expected: Type::Ku,
                found: Type::Simple("i64".to_string())
            })
        );
    }

    #[test]
    fn test_type_check_pipe() {
        let mut compiler = Compiler::new();
        let func_type = Type::Func(
            Box::new(Type::Simple("i64".to_string())),
            Box::new(Type::Simple("bool".to_string())),
        );
        compiler.context.define_var("is_positive".to_string(), func_type.clone());
        compiler.context.define_var("n".to_string(), Type::Simple("i64".to_string()));

        let expr = kotoba_parser::parse_expression("n |> is_positive").unwrap().1;
        let result = compiler.type_check_expression(&expr, None);
        assert_eq!(result, Ok(Type::Simple("bool".to_string())));

        let expr_not_func = kotoba_parser::parse_expression("n |> n").unwrap().1;
        let result_not_func = compiler.type_check_expression(&expr_not_func, None);
        assert_eq!(result_not_func, Err(TypeError::NotAFunction(Type::Simple("i64".to_string()))));
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
        // Note: The compiled `match` is incomplete due to placeholder `compile_ou_arm`.
        // This test just checks the function signature.
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
        assert!(code.contains("N::Zero")); // a an expression
    }

    #[test]
    fn test_type_check_ou() {
        let mut compiler = Compiler::new();
        // gyo Option<T> = { some(T), none }
        let option_type_name = "Option".to_string();
        let constructors = vec![
            ConstructorDef::Point {
                name: "some".to_string(),
                fields: vec![Type::Simple("i64".to_string())],
            },
            ConstructorDef::Point {
                name: "none".to_string(),
                fields: vec![],
            },
        ];
        compiler
            .context
            .type_definitions
            .insert(option_type_name.clone(), constructors);

        // shiki opt: Option = some(10)
        let option_value_type = Type::Simple(option_type_name);
        compiler.context.define_var("opt".to_string(), option_value_type);

        // Case 1: All arms return the same type (i64)
        let expr_ok = parse_expression("ou opt { some(x) => x, none => 0 }")
            .unwrap()
            .1;
        let result_ok = compiler.type_check_expression(&expr_ok, None);
        assert_eq!(result_ok, Ok(Type::Simple("i64".to_string())));

        // Case 2: Arms return different types (i64 vs ku)
        let expr_err = parse_expression("ou opt { some(x) => x, none => i0 }")
            .unwrap()
            .1;
        let result_err = compiler.type_check_expression(&expr_err, None);
        assert_eq!(
            result_err,
            Err(TypeError::TypeMismatch {
                expected: Type::Simple("i64".to_string()),
                found: Type::Ku
            })
        );
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
        assert!(compiled_code.contains("True"));
        assert!(compiled_code.contains("False"));
        assert!(compiled_code.contains("let path_to_false: kotoba_core::Path<Bool>"));
        assert!(compiled_code.contains("Bool::True"));
        assert!(compiled_code.contains("Bool::False"));
    }

    #[test]
    fn test_compile_higher_order_path() {
        let input = "shiki p_over_p: ze<ze<i64, i64>, ze<i64, i64>> = some_path";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        // Mock a definition for some_path
        let path_type = Type::Ze(
            Box::new(Type::Ze(
                Box::new(Type::Simple("i64".to_string())),
                Box::new(Type::Simple("i64".to_string())),
            )),
            Box::new(Type::Ze(
                Box::new(Type::Simple("i64".to_string())),
                Box::new(Type::Simple("i64".to_string())),
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
            Type::Simple("some_type".to_string()),
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
        // Mock definitions for ticks and doubler
        compiler.context.define_var(
            "ticks".to_string(),
            Type::En(
                Box::new(Type::Simple("ma".to_string())),
                Box::new(Type::Simple("i64".to_string())),
                Box::new(Type::Simple("id".to_string())),
            ),
        );
        compiler.context.define_var(
            "doubler".to_string(),
            Type::Func(
                Box::new(Type::En(
                    Box::new(Type::Simple("ma".to_string())),
                    Box::new(Type::Simple("i64".to_string())),
                    Box::new(Type::Simple("id".to_string())),
                )),
                Box::new(Type::En(
                    Box::new(Type::Simple("ma".to_string())),
                    Box::new(Type::Simple("i64".to_string())),
                    Box::new(Type::Simple("id".to_string())),
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
    fn test_compile_shiki_kan() {
        let input = "shiki doubler: en<i64, i64, N> = kan(x: i64) => x";
        let (_, statement) = parse_statement(input).unwrap();
        let mut compiler = Compiler::new();
        let result = compiler.compile(vec![statement]);
        let expected_code =
            "let doubler: kotoba_core::Glue<i64, i64, N> = |x: i64| { x };\n";
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
}
