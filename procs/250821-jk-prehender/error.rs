//! Error types for Densha framework

use std::fmt;

/// Main error type for Densha operations
#[derive(Debug)]
pub enum DenshaError {
    /// IO-related errors
    Io(std::io::Error),
    /// HTTP-related errors
    Http(hyper::Error),
    /// Template rendering errors
    Template(String),
    /// Routing errors
    Routing(String),
    /// Configuration errors
    Config(String),
    /// Build errors
    Build(String),
    /// Runtime errors
    Runtime(String),
}

impl fmt::Display for DenshaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DenshaError::Io(err) => write!(f, "IO error: {}", err),
            DenshaError::Http(err) => write!(f, "HTTP error: {}", err),
            DenshaError::Template(msg) => write!(f, "Template error: {}", msg),
            DenshaError::Routing(msg) => write!(f, "Routing error: {}", msg),
            DenshaError::Config(msg) => write!(f, "Configuration error: {}", msg),
            DenshaError::Build(msg) => write!(f, "Build error: {}", msg),
            DenshaError::Runtime(msg) => write!(f, "Runtime error: {}", msg),
        }
    }
}

impl std::error::Error for DenshaError {}

impl From<std::io::Error> for DenshaError {
    fn from(err: std::io::Error) -> Self {
        DenshaError::Io(err)
    }
}

impl From<hyper::Error> for DenshaError {
    fn from(err: hyper::Error) -> Self {
        DenshaError::Http(err)
    }
}
