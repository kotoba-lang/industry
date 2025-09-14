//! Kotoba Transpiler
//!
//! This module handles the transpilation of Kotoba DSL templates
//! to HTML/CSS that can be served by the web application.

use crate::error::DenshaError;
use crate::Result;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use std::collections::HashMap;
use std::path::Path;

// JSONnet support for DSL processing

/// Kotoba template structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KotobaTemplate {
    pub name: String,
    pub content: String,
    pub metadata: TemplateMetadata,
    pub file_path: std::path::PathBuf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TemplateMetadata {
    pub layout: Option<String>,
    pub styles: Vec<String>,
    pub scripts: Vec<String>,
    pub title: Option<String>,
    pub description: Option<String>,
}

/// Transpiler for converting Kotoba DSL to HTML/CSS
pub struct KotobaTranspiler {
    templates: HashMap<String, KotobaTemplate>,
    global_styles: String,
    component_registry: HashMap<String, String>,
}

impl KotobaTranspiler {
    pub fn new() -> Self {
        Self {
            templates: HashMap::new(),
            global_styles: Self::default_global_styles(),
            component_registry: Self::default_components(),
        }
    }

    /// Load a Kotoba template from file
    pub async fn load_template(&mut self, path: &Path) -> Result<()> {
        let content = tokio::fs::read_to_string(path).await?;
        let name = path.file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("unnamed")
            .to_string();

        let template = KotobaTemplate {
            name: name.clone(),
            content,
            metadata: TemplateMetadata {
                layout: None,
                styles: vec![],
                scripts: vec![],
                title: None,
                description: None,
            },
            file_path: path.to_path_buf(),
        };

        self.templates.insert(name, template);
        Ok(())
    }

    /// Load templates from directory
    pub async fn load_templates_from_dir(&mut self, dir_path: &Path) -> Result<()> {
        if !dir_path.exists() {
            return Ok(());
        }

        self.load_templates_recursive(dir_path).await
    }

    /// Recursively load templates from directory
    async fn load_templates_recursive(&mut self, dir_path: &Path) -> Result<()> {
        let mut entries = tokio::fs::read_dir(dir_path).await?;
        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            if path.is_dir() {
                // Recursively process subdirectories
                Box::pin(self.load_templates_recursive(&path)).await?;
            } else if path.extension().and_then(|s| s.to_str()) == Some("kdl") {
                println!("📄 Loading template: {}", path.display());
                self.load_template(&path).await?;
            }
        }

        Ok(())
    }

    /// Transpile a template to HTML
    pub async fn transpile_to_html(&self, template_name: &str, data: &serde_json::Value) -> Result<String> {
        let template = self.templates.get(template_name)
            .ok_or_else(|| DenshaError::Runtime(format!("Template '{}' not found", template_name)))?;

        let mut html = self.process_kotoba_dsl(&template.content, data).await?;

        // Apply layout if specified
        if let Some(layout_name) = &template.metadata.layout {
            if let Some(layout) = self.templates.get(layout_name) {
                let layout_data = serde_json::json!({
                    "children": html,
                    "title": template.metadata.title.as_deref().unwrap_or("Densha App"),
                    "description": template.metadata.description.as_deref().unwrap_or(""),
                    "styles": template.metadata.styles,
                    "scripts": template.metadata.scripts,
                });
                html = self.process_kotoba_dsl(&layout.content, &layout_data).await?;
            }
        }

        Ok(html)
    }

    /// Process Kotoba DSL content
    async fn process_kotoba_dsl(&self, dsl: &str, data: &serde_json::Value) -> Result<String> {
        let mut result = dsl.to_string();

        // Process component directives
        result = self.process_component_directives(&result, data).await?;

        // Process conditional directives
        result = self.process_conditionals(&result, data)?;

        // Process loops
        result = self.process_loops(&result, data)?;

        // Process data bindings
        result = self.process_data_bindings(&result, data)?;

        // Process includes
        result = self.process_includes(&result).await?;

        Ok(result)
    }

    /// Process component directives like @button, @card, etc.
    async fn process_component_directives(&self, content: &str, data: &serde_json::Value) -> Result<String> {
        let mut result = content.to_string();

        // Button component: @button(label="Click me", variant="primary")
        let button_regex = Regex::new(r"@button\(([^)]+)\)")?;
        result = button_regex.replace_all(&result, |caps: &regex::Captures| {
            let args = Self::parse_component_args(&caps[1]);
            let default_label = "Button".to_string();
            let default_variant = "primary".to_string();
            let label = args.get("label").unwrap_or(&default_label);
            let variant = args.get("variant").unwrap_or(&default_variant);
            let size = args.get("size").unwrap_or(&"md".to_string());

            format!("<button class=\"btn btn-{}\">{}</button>", variant, label)
        }).to_string();

        // Card component: @card(header="Title", content="Content")
        let card_regex = Regex::new(r"@card\(([^)]+)\)")?;
        result = card_regex.replace_all(&result, |caps: &regex::Captures| {
            let args = Self::parse_component_args(&caps[1]);
            let default_content = "".to_string();
            let default_variant = "default".to_string();
            let header = args.get("header");
            let content = args.get("content").unwrap_or(&default_content);
            let variant = args.get("variant").unwrap_or(&default_variant);

            let mut html = format!("<div class=\"card card-{}\">", variant);
            if let Some(h) = header {
                html.push_str(&format!("<div class=\"card-header\"><h3>{}</h3></div>", h));
            }
            html.push_str(&format!("<div class=\"card-content\">{}</div></div>", content));
            html
        }).to_string();

        // Container component: @container(size="lg")...@endcontainer
        let container_regex = Regex::new(r"@container\(([^)]*)\)(.*?)@endcontainer")?;
        result = container_regex.replace_all(&result, |caps: &regex::Captures| {
            let args = Self::parse_component_args(&caps[1]);
            let default_size = "lg".to_string();
            let size = args.get("size").unwrap_or(&default_size);
            let content = &caps[2];
            format!("<div class=\"container container-{}\">{}</div>", size, content)
        }).to_string();

        // Heading components: @h1, @h2, etc.
        for level in 1..=6 {
            let heading_regex = Regex::new(&format!(r"@h{}(.*?)@endh{}", level, level))?;
            result = heading_regex.replace_all(&result, |caps: &regex::Captures| {
                let content = &caps[1];
                format!("<h{} class=\"heading h{}\">{}</h{}>", level, level, content, level)
            }).to_string();
        }

        Ok(result)
    }

    /// Process conditional directives
    fn process_conditionals(&self, content: &str, data: &serde_json::Value) -> Result<String> {
        let mut result = content.to_string();

        // @if(condition)...@endif
        let if_regex = Regex::new(r"@if\(([^)]+)\)(.*?)@endif")?;
        result = if_regex.replace_all(&result, |caps: &regex::Captures| {
            let condition = &caps[1];
            let content = &caps[2];

            if self.evaluate_condition(condition, data) {
                content.to_string()
            } else {
                String::new()
            }
        }).to_string();

        // @unless(condition)...@endunless
        let unless_regex = Regex::new(r"@unless\(([^)]+)\)(.*?)@endunless")?;
        result = unless_regex.replace_all(&result, |caps: &regex::Captures| {
            let condition = &caps[1];
            let content = &caps[2];

            if !self.evaluate_condition(condition, data) {
                content.to_string()
            } else {
                String::new()
            }
        }).to_string();

        Ok(result)
    }

    /// Process loop directives
    fn process_loops(&self, content: &str, data: &serde_json::Value) -> Result<String> {
        let mut result = content.to_string();

        // @each(items as item)...@endeach
        let each_regex = Regex::new(r"@each\(([^)]+)\)(.*?)@endeach")?;
        result = each_regex.replace_all(&result, |caps: &regex::Captures| {
            let params = &caps[1];
            let template = &caps[2];

            let parts: Vec<&str> = params.split(" as ").collect();
            if parts.len() == 2 {
                let collection_path = parts[0].trim();
                let item_var = parts[1].trim();

                if let Some(collection) = self.get_value_from_path(data, collection_path) {
                    if let Some(array) = collection.as_array() {
                        let mut output = String::new();
                        for item in array {
                            let mut item_template = template.to_string();
                            item_template = item_template.replace(&format!("{{{{{}}}}}", item_var), &item.to_string());
                            output.push_str(&item_template);
                        }
                        return output;
                    }
                }
            }

            String::new()
        }).to_string();

        Ok(result)
    }

    /// Process data bindings like {{variable}}
    fn process_data_bindings(&self, content: &str, data: &serde_json::Value) -> Result<String> {
        let mut result = content.to_string();

        // Simple variable binding: {{variable}}
        let binding_regex = Regex::new(r"\{\{([^}]+)\}\}")?;
        result = binding_regex.replace_all(&result, |caps: &regex::Captures| {
            let path = caps[1].trim();
            if let Some(value) = self.get_value_from_path(data, path) {
                value.to_string().trim_matches('"').to_string()
            } else {
                format!("{{{{{}}}}}", path)
            }
        }).to_string();

        Ok(result)
    }

    /// Process include directives
    async fn process_includes(&self, content: &str) -> Result<String> {
        let mut result = content.to_string();

        // @include(template_name)
        let include_regex = Regex::new(r"@include\(([^)]+)\)")?;
        result = include_regex.replace_all(&result, |caps: &regex::Captures| {
            let template_name = caps[1].trim().trim_matches('"').trim_matches('\'');
            // In a real implementation, you'd recursively process the included template
            format!("<!-- Include: {} -->", template_name)
        }).to_string();

        Ok(result)
    }

    /// Parse component arguments like label="Click me", variant="primary"
    fn parse_component_args(args_str: &str) -> HashMap<String, String> {
        let mut args = HashMap::new();

        for arg in args_str.split(',') {
            let arg = arg.trim();
            if let Some((key, value)) = arg.split_once('=') {
                let key = key.trim();
                let value = value.trim().trim_matches('"').trim_matches('\'');
                args.insert(key.to_string(), value.to_string());
            }
        }

        args
    }

    /// Evaluate a condition against the data
    fn evaluate_condition(&self, condition: &str, data: &serde_json::Value) -> bool {
        // Simple condition evaluation
        if let Some(value) = self.get_value_from_path(data, condition) {
            match value {
                serde_json::Value::Bool(b) => *b,
                serde_json::Value::String(s) => !s.is_empty(),
                serde_json::Value::Array(a) => !a.is_empty(),
                serde_json::Value::Object(o) => !o.is_empty(),
                _ => true,
            }
        } else {
            false
        }
    }

    /// Get value from JSON path like "user.name"
    fn get_value_from_path<'a>(&self, data: &'a serde_json::Value, path: &str) -> Option<&'a serde_json::Value> {
        let mut current = data;

        for part in path.split('.') {
            match current {
                serde_json::Value::Object(obj) => {
                    current = obj.get(part)?;
                }
                _ => return None,
            }
        }

        Some(current)
    }

    /// Default global styles (Tailwind-like)
    fn default_global_styles() -> String {
        r#"@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  html { @apply scroll-smooth; }
  body { @apply font-sans text-gray-900 bg-white antialiased; }
}

@layer components {
  .btn { @apply inline-flex items-center justify-center px-4 py-2 text-sm font-medium rounded-md shadow-sm transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed; }
  .btn-primary { @apply btn bg-primary-600 text-white hover:bg-primary-700 focus:ring-primary-500; }
  .btn-secondary { @apply btn bg-secondary-600 text-white hover:bg-secondary-700 focus:ring-secondary-500; }
  .btn-outline { @apply btn bg-transparent border border-gray-300 text-gray-700 hover:bg-gray-50 focus:ring-primary-500; }
  .btn-ghost { @apply btn bg-transparent text-gray-600 hover:bg-gray-50 hover:text-gray-900 focus:ring-gray-500; }
  .card { @apply bg-white rounded-lg shadow-sm border border-gray-200 p-6; }
  .container { @apply mx-auto max-w-7xl px-4 sm:px-6 lg:px-8; }
  .heading { @apply font-bold text-gray-900; }
  .h1 { @apply text-4xl mb-4; }
  .h2 { @apply text-3xl mb-3; }
  .h3 { @apply text-2xl mb-2; }
  .h4 { @apply text-xl mb-2; }
  .h5 { @apply text-lg mb-1; }
  .h6 { @apply text-base mb-1; }
}"#.to_string()
    }

    /// Default component definitions
    fn default_components() -> HashMap<String, String> {
        let mut components = HashMap::new();

        components.insert(
            "button".to_string(),
            r#"<button class="btn btn-primary">{{label}}</button>"#.to_string(),
        );

        components.insert(
            "card".to_string(),
            r#"<div class="card">
  {{#if header}}<div class="card-header"><h3>{{header}}</h3></div>{{/if}}
  <div class="card-content">{{content}}</div>
  {{#if footer}}<div class="card-footer">{{footer}}</div>{{/if}}
</div>"#.to_string(),
        );

        components
    }

    /// Generate CSS from templates
    pub fn generate_css(&self) -> String {
        self.global_styles.clone()
    }

    /// Get available templates
    pub fn templates(&self) -> &HashMap<String, KotobaTemplate> {
        &self.templates
    }

    /// Process Kotoba DSL templates (JSONnet-based, KDL format)
    pub async fn process_kotoba_template(&self, content: &str, data: Option<&serde_json::Value>) -> Result<String> {
        // Kotoba DSL is JSONnet-based, so we can process it as JSONnet
        // Convert KDL-style Kotoba DSL to JSONnet format if needed
        let jsonnet_content = self.kdl_to_jsonnet(content)?;
        self.process_jsonnet_template(&jsonnet_content, data).await
    }

    /// Process KDL templates (JSONnet-based)
    pub async fn process_kdl_template(&self, content: &str, data: Option<&serde_json::Value>) -> Result<String> {
        // KDL templates are also JSONnet-based in the latest Kotoba design
        // Convert KDL-style content to JSONnet format
        let jsonnet_content = self.kdl_to_jsonnet(content)?;
        self.process_jsonnet_template(&jsonnet_content, data).await
    }

    /// Convert KDL-style DSL to JSONnet format
    fn kdl_to_jsonnet(&self, kdl_content: &str) -> Result<String> {
        // Basic KDL to JSONnet conversion
        // This is a simplified implementation - can be enhanced for full KDL support

        // If the content already looks like JSON, return as-is
        if kdl_content.trim().starts_with('{') {
            return Ok(kdl_content.to_string());
        }

        // Simple KDL-like structure to JSONnet conversion
        // For now, wrap the content in a basic JSONnet structure
        let jsonnet = format!(r#"
// Converted from KDL format
{{
  metadata: {{
    title: "Kotoba Template",
    description: "Converted from KDL format",
    layout: "layout",
    styles: ["/globals.css"],
    scripts: []
  }},

  template: {{
    type: "div",
    class: "container mx-auto py-8",
    children: [
      {{
        type: "div",
        class: "max-w-4xl mx-auto bg-white rounded-lg shadow-lg p-8",
        children: [
          {{
            type: "h1",
            class: "text-4xl font-bold text-center mb-8 text-blue-600",
            text: "🚀 Kotoba Template"
          }},
          {{
            type: "p",
            class: "text-center text-gray-600",
            text: "This template was converted from KDL format. Length: {} characters"
          }}
        ]
      }}
    ]
  }}
}}"#, kdl_content.len());

        Ok(jsonnet)
    }

    /// Process JSONnet DSL templates using external jsonnet command
    pub async fn process_jsonnet_template(&self, content: &str, data: Option<&serde_json::Value>) -> Result<String> {
        use tokio::process::Command;

        // Write content to a temporary file
        let temp_file_path = std::env::temp_dir().join(format!("densha_template_{}.jsonnet", std::process::id()));
        tokio::fs::write(&temp_file_path, content).await.map_err(|e| {
            Box::new(DenshaError::Template(format!(
                "Failed to write temporary JSONnet file: {}", e
            )))
        })?;

        // Prepare jsonnet command
        let mut cmd = Command::new("jsonnet");
        cmd.arg(temp_file_path.to_str().unwrap());

        // Execute jsonnet command
        let output = cmd.output().await.map_err(|e| {
            Box::new(DenshaError::Template(format!(
                "Failed to execute jsonnet command: {}. Make sure jsonnet is installed.", e
            )))
        })?;

        // Cleanup temp file
        let _ = tokio::fs::remove_file(&temp_file_path).await;

        if output.status.success() {
            let result = String::from_utf8_lossy(&output.stdout).to_string();

            // Parse the JSONnet result as JSON and convert to HTML
            match serde_json::from_str::<JsonValue>(&result) {
                Ok(json_value) => self.json_to_html(&json_value),
                Err(e) => Err(Box::new(DenshaError::Template(format!(
                    "JSONnet result parsing failed: {}", e
                ))))
            }
        } else {
            let error_msg = String::from_utf8_lossy(&output.stderr).to_string();
            Err(Box::new(DenshaError::Template(format!(
                "JSONnet evaluation failed: {}", error_msg
            ))))
        }
    }

    /// Convert JSON result to HTML
    fn json_to_html(&self, json_value: &JsonValue) -> Result<String> {
        match json_value {
            JsonValue::Object(obj) => {
                let template = obj.get("template");
                let metadata = obj.get("metadata");

                let mut head_html = String::new();
                let mut body_html = String::new();

                // Process metadata for head
                if let Some(JsonValue::Object(meta)) = metadata {
                    if let Some(JsonValue::String(title)) = meta.get("title") {
                        head_html.push_str(&format!("<title>{}</title>", title));
                    }
                }

                // Process template for body
                if let Some(template_obj) = template {
                    body_html.push_str(&self.render_json_element(template_obj)?);
                }

                Ok(format!("{}{}", head_html, body_html))
            }
            _ => Err(Box::new(DenshaError::Template(
                "JSON template must be an object with 'template' field".to_string()
            ))),
        }
    }

    /// Render a JSON element to HTML
    fn render_json_element(&self, element: &JsonValue) -> Result<String> {
        match element {
            JsonValue::Object(obj) => {
                let element_type = obj.get("type");
                let class = obj.get("class");
                let text = obj.get("text");
                let children = obj.get("children");
                let href = obj.get("href");

                let mut html = String::new();

                if let Some(JsonValue::String(tag)) = element_type {
                    html.push_str(&format!("<{}", tag));

                    // Add class attribute
                    if let Some(JsonValue::String(class_val)) = class {
                        html.push_str(&format!(" class=\"{}\"", class_val));
                    }

                    // Add href attribute for links
                    if let Some(JsonValue::String(href_val)) = href {
                        html.push_str(&format!(" href=\"{}\"", href_val));
                    }

                    html.push_str(">");

                    // Add text content
                    if let Some(JsonValue::String(text_val)) = text {
                        html.push_str(text_val);
                    }

                    // Render children
                    if let Some(JsonValue::Array(child_array)) = children {
                        for child in child_array {
                            html.push_str(&self.render_json_element(child)?);
                        }
                    }

                    html.push_str(&format!("</{}>", tag));
                }

                Ok(html)
            }
            JsonValue::Array(arr) => {
                let mut html = String::new();
                for item in arr {
                    html.push_str(&self.render_json_element(item)?);
                }
                Ok(html)
            }
            JsonValue::String(s) => Ok(s.clone()),
            _ => Ok(String::new()),
        }
    }
}

impl Default for KotobaTranspiler {
    fn default() -> Self {
        Self::new()
    }
}

/// Template builder for creating Kotoba templates programmatically
pub struct TemplateBuilder {
    name: String,
    content: String,
    metadata: TemplateMetadata,
}

impl TemplateBuilder {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            content: String::new(),
            metadata: TemplateMetadata {
                layout: None,
                styles: vec![],
                scripts: vec![],
                title: None,
                description: None,
            },
        }
    }

    pub fn content(mut self, content: impl Into<String>) -> Self {
        self.content = content.into();
        self
    }

    pub fn layout(mut self, layout: impl Into<String>) -> Self {
        self.metadata.layout = Some(layout.into());
        self
    }

    pub fn title(mut self, title: impl Into<String>) -> Self {
        self.metadata.title = Some(title.into());
        self
    }

    pub fn description(mut self, description: impl Into<String>) -> Self {
        self.metadata.description = Some(description.into());
        self
    }

    pub fn style(mut self, style: impl Into<String>) -> Self {
        self.metadata.styles.push(style.into());
        self
    }

    pub fn script(mut self, script: impl Into<String>) -> Self {
        self.metadata.scripts.push(script.into());
        self
    }

    pub fn build(self) -> KotobaTemplate {
        let name = self.name.clone();
        KotobaTemplate {
            name: self.name,
            content: self.content,
            metadata: self.metadata,
            file_path: std::path::PathBuf::from(format!("{}.kdl", name)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_transpile_simple_template() {
        let mut transpiler = KotobaTranspiler::new();

        // Create a simple template
        let template = TemplateBuilder::new("test")
            .content("Hello {{name}}!")
            .build();

        transpiler.templates.insert("test".to_string(), template);

        let data = serde_json::json!({"name": "World"});
        let result = transpiler.transpile_to_html("test", &data).await.unwrap();

        assert_eq!(result, "Hello World!");
    }

    #[tokio::test]
    async fn test_transpile_with_component() {
        let mut transpiler = KotobaTranspiler::new();

        let template = TemplateBuilder::new("test")
            .content("@button(label=\"Click me\", variant=\"primary\")")
            .build();

        transpiler.templates.insert("test".to_string(), template);

        let data = serde_json::json!({});
        let result = transpiler.transpile_to_html("test", &data).await.unwrap();

        assert!(result.contains("<button class=\"btn btn-primary\">Click me</button>"));
    }

    #[test]
    fn test_parse_component_args() {
        let args_str = "label=\"Click me\", variant=\"primary\", size=\"lg\"";
        let args = KotobaTranspiler::parse_component_args(args_str);

        assert_eq!(args.get("label"), Some(&"Click me".to_string()));
        assert_eq!(args.get("variant"), Some(&"primary".to_string()));
        assert_eq!(args.get("size"), Some(&"lg".to_string()));
    }

    #[test]
    fn test_evaluate_condition() {
        let transpiler = KotobaTranspiler::new();
        let data = serde_json::json!({"user": {"logged_in": true}, "items": [1, 2, 3]});

        assert!(transpiler.evaluate_condition("user.logged_in", &data));
        assert!(transpiler.evaluate_condition("items", &data));
        assert!(!transpiler.evaluate_condition("user.admin", &data));
    }

}
