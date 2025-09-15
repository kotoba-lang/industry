//! File-based routing system inspired by Next.js

use crate::error::DenshaError;
use crate::Result;
use regex::Regex;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Route represents a single route in the application
#[derive(Debug, Clone)]
pub struct Route {
    /// The file path that defines this route
    pub file_path: PathBuf,
    /// The URL pattern this route matches
    pub pattern: String,
    /// Route parameters extracted from the path
    pub params: Vec<String>,
    /// The type of route (page, api, etc.)
    pub route_type: RouteType,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RouteType {
    Page,
    Api,
    Static,
}

/// Router manages all routes in the application
pub struct Router {
    /// All registered routes
    routes: HashMap<String, Route>,
    /// Compiled regex patterns for matching
    patterns: HashMap<String, Regex>,
}

impl Router {
    /// Create a new router
    pub fn new() -> Self {
        Self {
            routes: HashMap::new(),
            patterns: HashMap::new(),
        }
    }

    /// Load routes from the pages directory
    pub fn load_pages(&mut self, pages_dir: &Path) -> Result<()> {
        self.load_directory(pages_dir, RouteType::Page, "")?;
        Ok(())
    }

    /// Load API routes from the api directory
    pub fn load_api_routes(&mut self, api_dir: &Path) -> Result<()> {
        self.load_directory(api_dir, RouteType::Api, "/api")?;
        Ok(())
    }

    /// Load routes from a directory recursively
    fn load_directory(&mut self, dir: &Path, route_type: RouteType, prefix: &str) -> Result<()> {
        if !dir.exists() {
            return Ok(());
        }

        self.walk_directory(dir, &route_type, prefix, dir)?;
        Ok(())
    }

    /// Recursively walk directory and register routes
    fn walk_directory(&mut self, dir: &Path, route_type: &RouteType, prefix: &str, base_dir: &Path) -> Result<()> {
        let entries = std::fs::read_dir(dir)?;

        for entry in entries {
            let entry = entry?;
            let path = entry.path();

            if path.is_dir() {
                // Handle dynamic routes like [id]
                let dir_name = path.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("");

                let new_prefix = if dir_name.starts_with('[') && dir_name.ends_with(']') {
                    format!("{}/{}", prefix, dir_name)
                } else {
                    format!("{}/{}", prefix, dir_name)
                };

                self.walk_directory(&path, &route_type, &new_prefix, base_dir)?;
            } else if let Some(ext) = path.extension() {
                if matches!(ext.to_str(), Some("rs") | Some("js") | Some("ts")) {
                    self.register_route(&path, &route_type, prefix, base_dir)?;
                }
            }
        }

        Ok(())
    }

    /// Register a single route
    fn register_route(&mut self, file_path: &Path, route_type: &RouteType, prefix: &str, base_dir: &Path) -> Result<()> {
        let relative_path = file_path.strip_prefix(base_dir).unwrap_or(file_path);
        let route_path = self.file_path_to_route_path(relative_path, prefix);

        let (pattern, params) = self.build_pattern_and_params(&route_path);

        let route = Route {
            file_path: file_path.to_path_buf(),
            pattern: route_path.clone(),
            params,
            route_type: route_type.clone(),
        };

        self.routes.insert(route_path.clone(), route);

        // Compile regex for pattern matching
        let regex_pattern = self.pattern_to_regex(&pattern);
        self.patterns.insert(route_path, Regex::new(&regex_pattern)?);

        Ok(())
    }

    /// Convert file path to route path
    fn file_path_to_route_path(&self, relative_path: &Path, prefix: &str) -> String {
        let mut route_path = prefix.to_string();

        for component in relative_path.iter() {
            let comp_str = component.to_str().unwrap_or("");

            if let Some(stem) = Path::new(comp_str).file_stem().and_then(|s| s.to_str()) {
                if stem == "index" {
                    // index.rs -> no additional path
                    continue;
                } else if stem.starts_with('[') && stem.ends_with(']') {
                    // [id].rs -> :id
                    let param = &stem[1..stem.len() - 1];
                    route_path.push_str(&format!("/{}", param));
                } else {
                    route_path.push_str(&format!("/{}", stem));
                }
            }
        }

        if route_path.is_empty() {
            "/".to_string()
        } else {
            route_path
        }
    }

    /// Build regex pattern and extract parameters
    fn build_pattern_and_params(&self, route_path: &str) -> (String, Vec<String>) {
        let mut pattern = String::new();
        let mut params = Vec::new();

        for segment in route_path.split('/') {
            if segment.is_empty() {
                continue;
            }

            pattern.push('/');

            if segment.starts_with(':') {
                // Parameter segment like :id
                let param_name = &segment[1..];
                params.push(param_name.to_string());
                pattern.push_str(r"([^/]+)");
            } else {
                pattern.push_str(segment);
            }
        }

        if pattern.is_empty() {
            pattern = "/".to_string();
        }

        (pattern, params)
    }

    /// Convert pattern to regex
    fn pattern_to_regex(&self, pattern: &str) -> String {
        let mut regex = String::new();

        for segment in pattern.split('/') {
            if segment.is_empty() {
                continue;
            }

            regex.push_str("/");

            if segment.starts_with('[') && segment.ends_with(']') {
                // Dynamic segment like [id]
                regex.push_str(r"([^/]+)");
            } else {
                regex.push_str(segment);
            }
        }

        if regex.is_empty() {
            "^/$".to_string()
        } else {
            format!("^{}", regex)
        }
    }

    /// Match a URL path against registered routes
    pub fn match_route(&self, path: &str) -> Option<(&Route, HashMap<String, String>)> {
        // First try exact match
        if let Some(route) = self.routes.get(path) {
            return Some((route, HashMap::new()));
        }

        // Then try pattern matching
        for (route_path, route) in &self.routes {
            if let Some(regex) = self.patterns.get(route_path) {
                if let Some(captures) = regex.captures(path) {
                    let mut params = HashMap::new();

                    for (i, param_name) in route.params.iter().enumerate() {
                        if let Some(capture) = captures.get(i + 1) {
                            params.insert(param_name.clone(), capture.as_str().to_string());
                        }
                    }

                    return Some((route, params));
                }
            }
        }

        None
    }

    /// Get all registered routes
    pub fn routes(&self) -> &HashMap<String, Route> {
        &self.routes
    }
}

impl Default for Router {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn test_file_path_to_route_path() {
        let router = Router::new();

        // Test basic page
        let path = Path::new("pages/index.rs");
        assert_eq!(router.file_path_to_route_path(path, ""), "/");

        // Test nested page
        let path = Path::new("pages/about.rs");
        assert_eq!(router.file_path_to_route_path(path, ""), "/about");

        // Test dynamic route
        let path = Path::new("pages/posts/[id].rs");
        assert_eq!(router.file_path_to_route_path(path, ""), "/posts/:id");

        // Test API route
        let path = Path::new("pages/users.rs");
        assert_eq!(router.file_path_to_route_path(path, "/api"), "/api/users");
    }

    #[test]
    fn test_route_matching() {
        let mut router = Router::new();

        // Simulate registering a route
        let route = Route {
            file_path: "pages/posts/[id].rs".into(),
            pattern: "/posts/:id".to_string(),
            params: vec!["id".to_string()],
            route_type: RouteType::Page,
        };

        router.routes.insert("/posts/:id".to_string(), route);
        router.patterns.insert("/posts/:id".to_string(), Regex::new(r"^/posts/([^/]+)$").unwrap());

        // Test matching
        let (matched_route, params) = router.match_route("/posts/123").unwrap();
        assert_eq!(matched_route.pattern, "/posts/:id");
        assert_eq!(params.get("id"), Some(&"123".to_string()));
    }
}
