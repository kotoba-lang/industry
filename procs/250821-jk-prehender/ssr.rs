//! Server-Side Rendering engine for Densha

use crate::error::DenshaError;
use crate::Result;
use handlebars::Handlebars;
use serde::Serialize;
use std::collections::HashMap;
use std::path::Path;

/// SSR engine for rendering pages on the server
pub struct SSREngine {
    /// Handlebars template engine
    templates: Handlebars<'static>,
    /// Base layout template
    layout_template: String,
}

impl SSREngine {
    /// Create a new SSR engine
    pub fn new() -> Self {
        let mut templates = Handlebars::new();
        templates.set_strict_mode(true);

        Self {
            templates,
            layout_template: Self::default_layout(),
        }
    }

    /// Load templates from directory
    pub fn load_templates(&mut self, template_dir: &Path) -> Result<()> {
        if !template_dir.exists() {
            return Ok(());
        }

        // Load all .hbs files as templates
        for entry in walkdir::WalkDir::new(template_dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension().and_then(|s| s.to_str()) == Some("hbs"))
        {
            let path = entry.path();
            let template_name = path.strip_prefix(template_dir)
                .unwrap_or(path)
                .with_extension("")
                .to_string_lossy()
                .replace('\\', "/");

            self.templates.register_template_file(&template_name, path)?;
        }

        Ok(())
    }

    /// Render a page with data
    pub fn render_page(&self, template_name: &str, data: &impl Serialize) -> Result<String> {
        // Render the page content
        let content = self.templates.render(template_name, data)?;

        // Render with layout
        let layout_data = LayoutData {
            content: &content,
            title: "Densha App", // You could extract this from data
            styles: vec![],
            scripts: vec![],
        };

        let html = self.templates.render_template(&self.layout_template, &layout_data)?;
        Ok(html)
    }

    /// Render a page with custom layout
    pub fn render_with_layout(&self, template_name: &str, layout_name: &str, data: &impl Serialize) -> Result<String> {
        let content = self.templates.render(template_name, data)?;
        let layout = self.templates.render(layout_name, &serde_json::json!({
            "content": content
        }))?;
        Ok(layout)
    }

    /// Pre-render static HTML for build
    pub fn prerender_page(&self, route: &str, data: &impl Serialize) -> Result<String> {
        // For static generation, you might want to include hydration data
        let mut prerender_data = serde_json::to_value(data)?;
        if let Some(obj) = prerender_data.as_object_mut() {
            obj.insert("__prerender".to_string(), serde_json::json!(true));
            obj.insert("__route".to_string(), serde_json::json!(route));
        }

        self.render_page(route, &prerender_data)
    }

    /// Default HTML layout template
    fn default_layout() -> String {
        r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{{title}}</title>
    {{#each styles}}
    <link rel="stylesheet" href="{{this}}">
    {{/each}}
</head>
<body>
    <div id="root">
        {{{content}}}
    </div>

    <!-- Hydration data -->
    <script>
        window.__DENSHA_DATA__ = {{{json_data}}};
    </script>

    {{#each scripts}}
    <script src="{{this}}"></script>
    {{/each}}
</body>
</html>"#.to_string()
    }

    /// Set custom layout template
    pub fn set_layout(&mut self, layout: String) {
        self.layout_template = layout;
    }
}

impl Default for SSREngine {
    fn default() -> Self {
        Self::new()
    }
}

/// Data structure for layout rendering
#[derive(Serialize)]
struct LayoutData<'a> {
    content: &'a str,
    title: &'a str,
    styles: Vec<String>,
    scripts: Vec<String>,
}

/// Page component trait for server-side rendering
#[async_trait::async_trait]
pub trait PageComponent: Send + Sync {
    /// Get initial props for the page
    async fn get_initial_props(&self, params: &HashMap<String, String>) -> Result<serde_json::Value> {
        Ok(serde_json::json!({}))
    }

    /// Render the page to HTML
    async fn render(&self, props: &serde_json::Value) -> Result<String>;
}

/// API route handler trait
#[async_trait::async_trait]
pub trait ApiHandler: Send + Sync {
    /// Handle GET requests
    async fn get(&self, params: &HashMap<String, String>) -> Result<serde_json::Value> {
        Err("Method not implemented".into())
    }

    /// Handle POST requests
    async fn post(&self, params: &HashMap<String, String>, body: serde_json::Value) -> Result<serde_json::Value> {
        Err("Method not implemented".into())
    }

    /// Handle PUT requests
    async fn put(&self, params: &HashMap<String, String>, body: serde_json::Value) -> Result<serde_json::Value> {
        Err("Method not implemented".into())
    }

    /// Handle DELETE requests
    async fn delete(&self, params: &HashMap<String, String>) -> Result<serde_json::Value> {
        Err("Method not implemented".into())
    }
}

/// Static generation utilities
pub struct StaticGenerator<'a> {
    ssr_engine: &'a SSREngine,
}

impl<'a> StaticGenerator<'a> {
    pub fn new(ssr_engine: &'a SSREngine) -> Self {
        Self { ssr_engine }
    }

    /// Generate static HTML for a route
    pub async fn generate_route(&self, route: &str, data: &impl Serialize, output_dir: &Path) -> Result<()> {
        let html = self.ssr_engine.prerender_page(route, data)?;

        let output_path = output_dir.join(format!("{}.html", route.trim_start_matches('/').replace('/', "_")));
        std::fs::write(output_path, html)?;
        Ok(())
    }

    /// Generate static site from routes
    pub async fn generate_site(&self, routes: &[String], output_dir: &Path) -> Result<()> {
        for route in routes {
            let data = serde_json::json!({
                "route": route,
                "title": format!("Page: {}", route)
            });
            self.generate_route(route, &data, output_dir).await?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_ssr_engine_creation() {
        let engine = SSREngine::new();
        assert!(engine.templates.get_templates().is_empty());
    }

    #[test]
    fn test_default_layout_rendering() {
        let engine = SSREngine::new();

        // Create a simple template
        engine.templates.register_template_string("test", "<h1>Hello {{name}}</h1>").unwrap();

        let data = serde_json::json!({"name": "World"});
        let result = engine.render_page("test", &data).unwrap();

        assert!(result.contains("Hello World"));
        assert!(result.contains("<!DOCTYPE html>"));
        assert!(result.contains("<div id=\"root\">"));
    }

    #[tokio::test]
    async fn test_static_generation() {
        let engine = SSREngine::new();
        engine.templates.register_template_string("page", "<div>{{content}}</div>").unwrap();

        let generator = StaticGenerator::new(&engine);
        let temp_dir = TempDir::new().unwrap();

        let data = serde_json::json!({"content": "Static content"});
        generator.generate_route("/test", &data, temp_dir.path()).await.unwrap();

        let output_file = temp_dir.path().join("test.html");
        assert!(output_file.exists());

        let content = std::fs::read_to_string(output_file).unwrap();
        assert!(content.contains("Static content"));
    }
}
