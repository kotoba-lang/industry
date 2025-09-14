//! Static file serving for Densha

use crate::error::DenshaError;
use crate::Result;
use hyper::{Body, Request, Response, StatusCode};
use mime_guess::from_path;
use std::path::{Path, PathBuf};
use tokio::fs;

/// Static file handler
pub struct StaticFileHandler {
    /// Root directory for static files
    root_dir: PathBuf,
    /// URL prefix for static files
    url_prefix: String,
    /// Cache control max age in seconds
    cache_max_age: u32,
}

impl StaticFileHandler {
    /// Create a new static file handler
    pub fn new(root_dir: PathBuf) -> Self {
        Self {
            root_dir,
            url_prefix: "/static".to_string(),
            cache_max_age: 86400, // 24 hours
        }
    }

    /// Create with custom URL prefix
    pub fn with_prefix(mut self, prefix: String) -> Self {
        self.url_prefix = prefix;
        self
    }

    /// Create with custom cache settings
    pub fn with_cache(mut self, max_age: u32) -> Self {
        self.cache_max_age = max_age;
        self
    }

    /// Check if request should be handled by static file handler
    pub fn should_handle(&self, req: &Request<Body>) -> bool {
        req.uri().path().starts_with(&self.url_prefix)
    }

    /// Serve static file
    pub async fn serve(&self, req: &Request<Body>) -> Result<Response<Body>> {
        let path = req.uri().path();

        // Remove the URL prefix to get the file path
        let file_path = if let Some(stripped) = path.strip_prefix(&self.url_prefix) {
            stripped.trim_start_matches('/')
        } else {
            path.trim_start_matches('/')
        };

        let full_path = self.root_dir.join(file_path);

        // Security check: prevent directory traversal
        if !self.is_path_safe(&full_path)? {
            return self.not_found_response();
        }

        // Check if file exists
        if !full_path.exists() {
            return self.not_found_response();
        }

        // Check if it's a file (not a directory)
        if !full_path.is_file() {
            return self.not_found_response();
        }

        // Read file
        let content = fs::read(&full_path).await?;
        let mime_type = from_path(&full_path).first_or_octet_stream();

        // Create response
        let mut response = Response::new(Body::from(content));
        response.headers_mut().insert(
            "content-type",
            mime_type.to_string().parse().unwrap(),
        );

        // Add cache headers
        response.headers_mut().insert(
            "cache-control",
            format!("max-age={}", self.cache_max_age).parse().unwrap(),
        );

        Ok(response)
    }

    /// Get URL for a static file
    pub fn get_url(&self, file_path: &str) -> String {
        format!("{}/{}", self.url_prefix, file_path.trim_start_matches('/'))
    }

    /// Check if the path is safe (prevents directory traversal)
    fn is_path_safe(&self, requested_path: &Path) -> Result<bool> {
        let canonical_requested = requested_path.canonicalize()?;
        let canonical_root = self.root_dir.canonicalize()?;

        Ok(canonical_requested.starts_with(canonical_root))
    }

    /// Create a 404 response
    fn not_found_response(&self) -> Result<Response<Body>> {
        let mut response = Response::new(Body::from("File not found"));
        *response.status_mut() = StatusCode::NOT_FOUND;
        Ok(response)
    }
}

impl Default for StaticFileHandler {
    fn default() -> Self {
        Self::new(PathBuf::from("public"))
    }
}

/// Asset pipeline for processing static files
pub struct AssetPipeline {
    /// Source directory
    source_dir: PathBuf,
    /// Output directory
    output_dir: PathBuf,
    /// Whether to minify assets
    minify: bool,
}

impl AssetPipeline {
    /// Create a new asset pipeline
    pub fn new(source_dir: PathBuf, output_dir: PathBuf) -> Self {
        Self {
            source_dir,
            output_dir,
            minify: false,
        }
    }

    /// Enable minification
    pub fn with_minification(mut self) -> Self {
        self.minify = true;
        self
    }

    /// Process all assets
    pub async fn process_assets(&self) -> Result<()> {
        if !self.source_dir.exists() {
            return Ok(());
        }

        // Create output directory
        fs::create_dir_all(&self.output_dir).await?;

        // Process all files
        self.process_directory(&self.source_dir, &self.output_dir).await?;
        Ok(())
    }

    /// Recursively process directory
    async fn process_directory(&self, source: &Path, output: &Path) -> Result<()> {
        use std::pin::Pin;
        use std::future::Future;
        let mut entries = fs::read_dir(source).await?;

        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();

            if path.is_dir() {
                let dir_name = path.file_name().unwrap();
                let output_subdir = output.join(dir_name);
                fs::create_dir_all(&output_subdir).await?;
                Box::pin(self.process_directory(&path, &output_subdir)).await?;
            } else {
                self.process_file(&path, output).await?;
            }
        }

        Ok(())
    }

    /// Process a single file
    async fn process_file(&self, source: &Path, output_dir: &Path) -> Result<()> {
        let file_name = source.file_name().unwrap();
        let output_path = output_dir.join(file_name);

        if let Some(ext) = source.extension().and_then(|s| s.to_str()) {
            match ext {
                "css" if self.minify => {
                    // Minify CSS (simplified)
                    let content = fs::read_to_string(source).await?;
                    let minified = self.minify_css(&content);
                    fs::write(output_path, minified).await?;
                }
                "js" if self.minify => {
                    // Minify JS (simplified)
                    let content = fs::read_to_string(source).await?;
                    let minified = self.minify_js(&content);
                    fs::write(output_path, minified).await?;
                }
                _ => {
                    // Copy file as-is
                    fs::copy(source, output_path).await?;
                }
            }
        }

        Ok(())
    }

    /// Simple CSS minification
    fn minify_css(&self, content: &str) -> String {
        content
            .lines()
            .map(|line| line.trim())
            .filter(|line| !line.is_empty())
            .collect::<Vec<_>>()
            .join("")
            .replace(" {", "{")
            .replace(": ", ":")
            .replace("; ", ";")
    }

    /// Simple JS minification
    fn minify_js(&self, content: &str) -> String {
        content
            .lines()
            .map(|line| line.trim())
            .filter(|line| !line.is_empty())
            .collect::<Vec<_>>()
            .join("")
    }
}

/// Image optimization utilities
pub struct ImageOptimizer {
    /// Quality setting (0-100)
    quality: u8,
    /// Output formats to generate
    formats: Vec<String>,
}

impl ImageOptimizer {
    pub fn new() -> Self {
        Self {
            quality: 80,
            formats: vec!["webp".to_string(), "avif".to_string()],
        }
    }

    pub fn with_quality(mut self, quality: u8) -> Self {
        self.quality = quality;
        self
    }

    /// Optimize an image file
    pub async fn optimize_image(&self, input_path: &Path, output_dir: &Path) -> Result<Vec<PathBuf>> {
        // In a real implementation, you would use libraries like image crate
        // For now, just copy the file
        let file_name = input_path.file_name().unwrap().to_str().unwrap();
        let output_path = output_dir.join(file_name);

        fs::copy(input_path, &output_path).await?;

        Ok(vec![output_path])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    use tokio::fs as async_fs;

    #[tokio::test]
    async fn test_static_file_handler() {
        let temp_dir = TempDir::new().unwrap();
        let test_file = temp_dir.path().join("test.txt");

        // Create test file
        async_fs::write(&test_file, b"Hello World").await.unwrap();

        let handler = StaticFileHandler::new(temp_dir.path().to_path_buf());

        // Test file serving
        let req = Request::get("/static/test.txt")
            .body(Body::empty())
            .unwrap();

        let response = handler.serve(&req).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);

        let body_bytes = hyper::body::to_bytes(response.into_body()).await.unwrap();
        assert_eq!(body_bytes.as_ref(), b"Hello World");
    }

    #[tokio::test]
    async fn test_asset_pipeline() {
        let source_dir = TempDir::new().unwrap();
        let output_dir = TempDir::new().unwrap();

        // Create test CSS file
        let css_file = source_dir.path().join("style.css");
        async_fs::write(&css_file, "body { color: red; }").await.unwrap();

        let pipeline = AssetPipeline::new(source_dir.path().to_path_buf(), output_dir.path().to_path_buf())
            .with_minification();

        pipeline.process_assets().await.unwrap();

        let output_file = output_dir.path().join("style.css");
        assert!(output_file.exists());

        let content = async_fs::read_to_string(output_file).await.unwrap();
        assert_eq!(content, "body{color:red;}");
    }
}
