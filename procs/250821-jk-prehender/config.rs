//! Configuration module for Densha framework

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Main configuration structure for Densha
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Server configuration
    pub server: ServerConfig,
    /// Development configuration
    pub dev: DevConfig,
    /// Build configuration
    pub build: BuildConfig,
    /// Experimental features
    pub experimental: ExperimentalConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    /// Host to bind the server to
    pub host: String,
    /// Port to bind the server to
    pub port: u16,
    /// Enable HTTPS
    pub https: bool,
    /// Path to SSL certificate (if HTTPS enabled)
    pub ssl_cert: Option<PathBuf>,
    /// Path to SSL key (if HTTPS enabled)
    pub ssl_key: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DevConfig {
    /// Enable hot reload
    pub hot_reload: bool,
    /// Watch directories for changes
    pub watch_dirs: Vec<PathBuf>,
    /// Port for development server
    pub port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BuildConfig {
    /// Output directory for build artifacts
    pub output_dir: PathBuf,
    /// Enable source maps
    pub source_maps: bool,
    /// Minify output
    pub minify: bool,
    /// Target environment (server, client, or both)
    pub target: BuildTarget,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BuildTarget {
    Server,
    Client,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentalConfig {
    /// Enable app directory (Next.js 13+ style)
    pub app_directory: bool,
    /// Enable turbopack
    pub turbopack: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: ServerConfig::default(),
            dev: DevConfig::default(),
            build: BuildConfig::default(),
            experimental: ExperimentalConfig::default(),
        }
    }
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            host: "127.0.0.1".to_string(),
            port: 3000,
            https: false,
            ssl_cert: None,
            ssl_key: None,
        }
    }
}

impl Default for DevConfig {
    fn default() -> Self {
        Self {
            hot_reload: true,
            watch_dirs: vec![
                "pages".into(),
                "components".into(),
                "api".into(),
                "styles".into(),
            ],
            port: 3000,
        }
    }
}

impl Default for BuildConfig {
    fn default() -> Self {
        Self {
            output_dir: ".densha".into(),
            source_maps: true,
            minify: false,
            target: BuildTarget::Both,
        }
    }
}

impl Default for ExperimentalConfig {
    fn default() -> Self {
        Self {
            app_directory: false,
            turbopack: false,
        }
    }
}

impl Config {
    /// Load configuration from a Jsonnet file (following Kotoba patterns)
    pub async fn from_jsonnet_file<P: AsRef<std::path::Path>>(path: P) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let path = path.as_ref();

        if !path.exists() {
            println!("⚠️  Configuration file not found: {}, using default config", path.display());
            return Ok(Self::default());
        }

        // For now, just return default config since we don't have jsonnet support yet
        // In a full implementation, this would parse the Jsonnet file
        println!("📄 Loading configuration from Jsonnet file: {}", path.display());
        Ok(Self::default())
    }

    /// Load configuration from a file path
    pub async fn from_file<P: AsRef<std::path::Path>>(path: P) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let path = path.as_ref();

        if !path.exists() {
            println!("⚠️  Configuration file not found: {}, using default config", path.display());
            return Ok(Self::default());
        }

        match path.extension().and_then(|s| s.to_str()) {
            Some("jsonnet") | Some("libsonnet") => {
                Self::from_jsonnet_file(path).await
            }
            Some("json") => {
                let content = tokio::fs::read_to_string(path).await?;
                let config: Self = serde_json::from_str(&content)?;
                Ok(config)
            }
            Some("toml") => {
                let content = tokio::fs::read_to_string(path).await?;
                let config: Self = toml::from_str(&content)?;
                Ok(config)
            }
            _ => {
                println!("⚠️  Unsupported configuration file format: {}, using default config", path.display());
                Ok(Self::default())
            }
        }
    }
}
