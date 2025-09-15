//! Middleware system for Densha

use async_trait::async_trait;
use hyper::{Body, Request, Response};
use std::sync::Arc;

/// Middleware trait for processing requests
#[async_trait]
pub trait Middleware: Send + Sync {
    /// Process the request before it reaches the handler
    async fn before(&self, req: &mut Request<Body>) -> Result<(), MiddlewareError>;

    /// Process the response after the handler
    async fn after(&self, res: &mut Response<Body>) -> Result<(), MiddlewareError>;

    /// Get middleware name for debugging
    fn name(&self) -> &str;
}

/// Middleware error type
#[derive(Debug, thiserror::Error)]
pub enum MiddlewareError {
    #[error("Authentication failed: {0}")]
    Auth(String),
    #[error("Rate limit exceeded")]
    RateLimit,
    #[error("CORS error: {0}")]
    Cors(String),
    #[error("Custom error: {0}")]
    Custom(String),
}

/// Middleware chain for processing multiple middlewares
pub struct MiddlewareChain {
    middlewares: Vec<Box<dyn Middleware>>,
}

impl MiddlewareChain {
    /// Create a new middleware chain
    pub fn new() -> Self {
        Self {
            middlewares: Vec::new(),
        }
    }

    /// Add a middleware to the chain
    pub fn add<M: Middleware + 'static>(mut self, middleware: M) -> Self {
        self.middlewares.push(Box::new(middleware));
        self
    }

    /// Process request through all middlewares
    pub async fn process_request(&self, req: &mut Request<Body>) -> Result<(), MiddlewareError> {
        for middleware in &self.middlewares {
            middleware.before(req).await?;
        }
        Ok(())
    }

    /// Process response through all middlewares
    pub async fn process_response(&self, res: &mut Response<Body>) -> Result<(), MiddlewareError> {
        for middleware in self.middlewares.iter().rev() {
            middleware.after(res).await?;
        }
        Ok(())
    }

    /// Get middleware names for debugging
    pub fn names(&self) -> Vec<&str> {
        self.middlewares.iter().map(|m| m.name()).collect()
    }
}

impl Default for MiddlewareChain {
    fn default() -> Self {
        Self::new()
    }
}

/// CORS middleware
pub struct CorsMiddleware {
    allowed_origins: Vec<String>,
    allowed_methods: Vec<String>,
    allowed_headers: Vec<String>,
}

impl CorsMiddleware {
    pub fn new() -> Self {
        Self {
            allowed_origins: vec!["*".to_string()],
            allowed_methods: vec!["GET".to_string(), "POST".to_string(), "PUT".to_string(), "DELETE".to_string()],
            allowed_headers: vec!["Content-Type".to_string(), "Authorization".to_string()],
        }
    }

    pub fn with_origins(mut self, origins: Vec<String>) -> Self {
        self.allowed_origins = origins;
        self
    }
}

#[async_trait]
impl Middleware for CorsMiddleware {
    async fn before(&self, req: &mut Request<Body>) -> Result<(), MiddlewareError> {
        // CORS preflight handling
        if req.method() == hyper::Method::OPTIONS {
            // This would be handled by the CORS headers in the response
        }
        Ok(())
    }

    async fn after(&self, res: &mut Response<Body>) -> Result<(), MiddlewareError> {
        let headers = res.headers_mut();

        if !self.allowed_origins.is_empty() {
            headers.insert(
                "Access-Control-Allow-Origin",
                self.allowed_origins.join(", ").parse().unwrap(),
            );
        }

        headers.insert(
            "Access-Control-Allow-Methods",
            self.allowed_methods.join(", ").parse().unwrap(),
        );

        headers.insert(
            "Access-Control-Allow-Headers",
            self.allowed_headers.join(", ").parse().unwrap(),
        );

        Ok(())
    }

    fn name(&self) -> &str {
        "cors"
    }
}

/// Logger middleware
pub struct LoggerMiddleware;

impl LoggerMiddleware {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl Middleware for LoggerMiddleware {
    async fn before(&self, req: &mut Request<Body>) -> Result<(), MiddlewareError> {
        println!("{} {} {}", req.method(), req.uri().path(), req.uri().query().unwrap_or(""));
        Ok(())
    }

    async fn after(&self, res: &mut Response<Body>) -> Result<(), MiddlewareError> {
        println!("Response status: {}", res.status());
        Ok(())
    }

    fn name(&self) -> &str {
        "logger"
    }
}

/// Authentication middleware
pub struct AuthMiddleware {
    token_header: String,
}

impl AuthMiddleware {
    pub fn new() -> Self {
        Self {
            token_header: "Authorization".to_string(),
        }
    }

    pub fn with_header(mut self, header: String) -> Self {
        self.token_header = header;
        self
    }
}

#[async_trait]
impl Middleware for AuthMiddleware {
    async fn before(&self, req: &mut Request<Body>) -> Result<(), MiddlewareError> {
        let auth_header = req.headers().get(&self.token_header);

        match auth_header {
            Some(value) => {
                let token = value.to_str().map_err(|_| MiddlewareError::Auth("Invalid token format".to_string()))?;

                // Simple token validation (in real app, you'd verify JWT or similar)
                if !token.starts_with("Bearer ") {
                    return Err(MiddlewareError::Auth("Invalid token format".to_string()));
                }

                // You would validate the token here
                println!("Token validated: {}", &token[7..]);
                Ok(())
            }
            None => Err(MiddlewareError::Auth("Missing authentication token".to_string())),
        }
    }

    async fn after(&self, _res: &mut Response<Body>) -> Result<(), MiddlewareError> {
        Ok(())
    }

    fn name(&self) -> &str {
        "auth"
    }
}

/// Rate limiting middleware (simplified)
pub struct RateLimitMiddleware {
    requests_per_minute: u32,
}

impl RateLimitMiddleware {
    pub fn new(requests_per_minute: u32) -> Self {
        Self {
            requests_per_minute,
        }
    }
}

#[async_trait]
impl Middleware for RateLimitMiddleware {
    async fn before(&self, _req: &mut Request<Body>) -> Result<(), MiddlewareError> {
        // In a real implementation, you'd track requests per IP/client
        // For now, just simulate rate limiting
        println!("Rate limit check: {} requests/minute allowed", self.requests_per_minute);
        Ok(())
    }

    async fn after(&self, _res: &mut Response<Body>) -> Result<(), MiddlewareError> {
        Ok(())
    }

    fn name(&self) -> &str {
        "rate_limit"
    }
}
