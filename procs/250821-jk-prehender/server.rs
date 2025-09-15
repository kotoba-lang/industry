//! Main server implementation for Densha

use crate::config::Config;
use crate::error::DenshaError;
use crate::middleware::{MiddlewareChain, CorsMiddleware, LoggerMiddleware};
use crate::router::{Router, RouteType};
use crate::ssr::SSREngine;
use crate::static_files::StaticFileHandler;
use crate::transpiler::KotobaTranspiler;
use crate::Result;
use hyper::service::{make_service_fn, service_fn};
use hyper::{Body, Request, Response, Server as HyperServer, StatusCode};
use std::convert::Infallible;
use std::net::SocketAddr;
use std::sync::Arc;

/// Main server struct
pub struct DenshaServer {
    /// Server configuration
    config: Config,
    /// Router for handling routes
    router: Router,
    /// SSR engine
    ssr_engine: SSREngine,
    /// Static file handler
    static_handler: StaticFileHandler,
    /// Middleware chain
    middleware_chain: MiddlewareChain,
    /// Kotoba transpiler for processing KDL templates
    transpiler: Option<KotobaTranspiler>,
}

impl DenshaServer {
    /// Create a new server with configuration
    pub async fn new(config: Config) -> Result<Self> {
        Self::new_with_transpiler(config, None).await
    }

    /// Create a new server with Kotoba transpiler
    pub async fn new_with_transpiler(config: Config, transpiler: Option<KotobaTranspiler>) -> Result<Self> {
        let mut router = Router::new();
        let mut ssr_engine = SSREngine::new();

        // Load routes from filesystem
        let _ = router.load_pages(&std::path::PathBuf::from("pages")); // Ignore error if pages dir doesn't exist
        let _ = router.load_api_routes(&std::path::PathBuf::from("api")); // Ignore error if api dir doesn't exist

        // Load templates (ignore error if templates dir doesn't exist)
        let _ = ssr_engine.load_templates(&std::path::PathBuf::from("templates"));

        // Create static file handler
        let static_handler = StaticFileHandler::new(std::path::PathBuf::from("public"))
            .with_prefix("".to_string()); // Serve files directly from root

        // Create default middleware chain
        let middleware_chain = MiddlewareChain::new()
            .add(LoggerMiddleware::new())
            .add(CorsMiddleware::new());

        Ok(Self {
            config,
            router,
            ssr_engine,
            static_handler,
            middleware_chain,
            transpiler,
        })
    }

    /// Start the server
    pub async fn start(self) -> Result<()> {
        let addr_str = format!("{}:{}", self.config.server.host, self.config.server.port);
        println!("🔍 Debug: Trying to parse address: {}", addr_str);
        let addr: SocketAddr = addr_str.parse()
            .map_err(|e| DenshaError::Config(format!("Invalid address: {} (tried to parse: {})", e, addr_str)))?;

        let server = Arc::new(self);

        println!("🚀 Densha server starting on http://{}", addr);
        println!("📁 Pages directory: ./pages");
        println!("🔌 API directory: ./api");
        println!("📄 Static files: ./public");

        let make_svc = make_service_fn(move |_conn| {
            let server = Arc::clone(&server);
            async move {
                Ok::<_, Infallible>(service_fn(move |req| {
                    let server = Arc::clone(&server);
                    async move { server.handle_request(req).await }
                }))
            }
        });

        let server = HyperServer::bind(&addr).serve(make_svc);

        println!("✅ Server ready");

        server.await.map_err(|e| DenshaError::Http(e))?;

        Ok(())
    }

    /// Handle incoming HTTP request
    async fn handle_request(&self, req: Request<Body>) -> std::result::Result<Response<Body>, Infallible> {
        let path = req.uri().path();
        println!("📨 Debug: Incoming request - {} {}", req.method(), path);

        // Handle static files first
        if self.static_handler.should_handle(&req) {
            println!("📄 Debug: Serving static file");
            match self.static_handler.serve(&req).await {
                Ok(response) => {
                    println!("✅ Debug: Static file served successfully");
                    return Ok(response);
                }
                Err(e) => {
                    println!("❌ Debug: Static file error: {}", e);
                    return Ok(self.error_response(StatusCode::NOT_FOUND, "File not found"));
                }
            }
        }

        // Route the request
        println!("🔀 Debug: Routing request for path: {}", path);
        let response = match self.router.match_route(path) {
            Some((route, params)) => {
                println!("✅ Debug: Route matched - Type: {:?}, Pattern: {}, Params: {:?}", route.route_type, route.pattern, params);
                match route.route_type {
                    RouteType::Page => self.handle_page_request(route, &params).await,
                    RouteType::Api => self.handle_api_request(&req, route, &params).await,
                    RouteType::Static => self.handle_static_request(route).await,
                }
            }
            None => {
                println!("❌ Debug: No route matched for path: {}", path);
                Ok(self.test_response(path, req.method()))
            }
        };

        // Process response
        let mut response = response.unwrap_or_else(|e| {
            println!("❌ Debug: Error handling request: {}", e);
            self.error_response(StatusCode::INTERNAL_SERVER_ERROR, &e.to_string())
        });

        println!("✅ Debug: Sending response");
        Ok(response)
    }

    /// Create a test response
    fn test_response(&self, path: &str, method: &hyper::Method) -> Response<Body> {
        let html = format!(r#"<!DOCTYPE html>
<html>
<head>
    <title>Densha Test</title>
    <link href="/globals.css" rel="stylesheet">
    <style>body{{font-family:Arial,sans-serif;text-align:center;padding:50px;}}</style>
</head>
<body>
    <h1>🚀 Densha Server is Working!</h1>
    <p>This is a test response from Densha.</p>
    <p>Path: {}</p>
    <p>Method: {}</p>
    <p>Request processing is working correctly.</p>
    <div class="mt-8">
        <a href="/" class="bg-blue-600 text-white px-4 py-2 rounded mr-4">Home</a>
        <a href="/about" class="bg-green-600 text-white px-4 py-2 rounded">About</a>
    </div>
</body>
</html>"#, path, method);

        Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "text/html")
            .body(Body::from(html))
            .unwrap()
    }

    /// Handle page requests (SSR)
    async fn handle_page_request(&self, route: &crate::router::Route, params: &std::collections::HashMap<String, String>) -> Result<Response<Body>> {
        // Simple test: always return a working HTML page
        println!("🌟 Debug: Processing route: {}", route.pattern);

        let test_html = format!(r#"<!DOCTYPE html>
<html>
<head>
    <title>Densha Test Page</title>
    <link href="/globals.css" rel="stylesheet">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body class="bg-gray-50">
    <div class="container mx-auto py-8">
        <div class="max-w-4xl mx-auto bg-white rounded-lg shadow-lg p-8">
            <h1 class="text-4xl font-bold text-center mb-8 text-blue-600">
                🚀 Densha Server is Running!
            </h1>
            <div class="text-center space-y-4">
                <p class="text-gray-600 text-lg">
                    Route: <code class="bg-gray-100 px-2 py-1 rounded">{}</code>
                </p>
                <p class="text-gray-600">
                    File: <code class="bg-gray-100 px-2 py-1 rounded">{}</code>
                </p>
                <div class="mt-8 space-x-4">
                    <a href="/" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors">
                        Home
                    </a>
                    <a href="/about" class="bg-green-600 text-white px-6 py-3 rounded-lg hover:bg-green-700 transition-colors">
                        About
                    </a>
                    <a href="/api/hello" class="bg-purple-600 text-white px-6 py-3 rounded-lg hover:bg-purple-700 transition-colors">
                        API Test
                    </a>
                </div>
            </div>
        </div>
    </div>

    <script>
        console.log('🎉 Densha server is working!');
        console.log('Route: {}');
    </script>
</body>
</html>"#, route.pattern, route.file_path.display(), route.pattern);

        let response = Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "text/html")
            .body(Body::from(test_html))
            .map_err(|e| DenshaError::Runtime(format!("Failed to build response: {}", e)))?;

        return Ok(response);

        // Fallback to regular SSR engine
        let props = serde_json::json!({
            "params": params,
            "route": route.pattern,
            "title": format!("Page: {}", route.pattern)
        });

        // Render page
        let html = self.ssr_engine.render_page("page", &props)?;

        let response = Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "text/html")
            .body(Body::from(html))
            .map_err(|e| DenshaError::Runtime(format!("Failed to build response: {}", e)))?;

        Ok(response)
    }

    /// Handle API requests
    async fn handle_api_request(&self, req: &Request<Body>, route: &crate::router::Route, params: &std::collections::HashMap<String, String>) -> Result<Response<Body>> {
        // For now, return a simple JSON response
        // In a real implementation, you'd load and execute the API handler
        let response_data = serde_json::json!({
            "method": req.method().to_string(),
            "route": route.pattern,
            "params": params,
            "message": "API route not yet implemented"
        });

        let response = Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "application/json")
            .body(Body::from(serde_json::to_string(&response_data)?))
            .map_err(|e| DenshaError::Runtime(format!("Failed to build response: {}", e)))?;

        Ok(response)
    }

    /// Handle static file requests
    async fn handle_static_request(&self, _route: &crate::router::Route) -> Result<Response<Body>> {
        // Static files are handled by the static file handler
        Ok(self.error_response(StatusCode::NOT_FOUND, "Static route not found"))
    }

    /// Handle SPA routing (serve index.html)
    async fn handle_spa_request(&self) -> Result<Response<Body>> {
        let html = r#"<!DOCTYPE html>
<html>
<head>
    <title>Densha App</title>
</head>
<body>
    <div id="root">Loading...</div>
    <script>
        // Simple client-side routing
        console.log('Densha SPA mode');
    </script>
</body>
</html>"#;

        let response = Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "text/html")
            .body(Body::from(html))
            .map_err(|e| DenshaError::Runtime(format!("Failed to build response: {}", e)))?;

        Ok(response)
    }

    /// Create error response
    fn error_response(&self, status: StatusCode, message: &str) -> Response<Body> {
        let error_html = format!(
            r#"<!DOCTYPE html>
<html>
<head>
    <title>Error {}</title>
    <style>
        body {{ font-family: Arial, sans-serif; margin: 40px; }}
        .error {{ color: #d32f2f; }}
    </style>
</head>
<body>
    <h1 class="error">Error {}</h1>
    <p>{}</p>
    <a href="/">Go back home</a>
</body>
</html>"#,
            status.as_u16(),
            status.as_u16(),
            message
        );

        Response::builder()
            .status(status)
            .header("content-type", "text/html")
            .body(Body::from(error_html))
            .unwrap()
    }

    /// Get server configuration
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Get router
    pub fn router(&self) -> &Router {
        &self.router
    }
}

/// Convenience type alias
pub type Server = DenshaServer;

#[cfg(test)]
mod tests {
    use super::*;
    use hyper::Method;

    #[tokio::test]
    async fn test_server_creation() {
        let config = Config::default();
        let server = DenshaServer::new(config).await.unwrap();
        assert_eq!(server.config.server.port, 3000);
    }

    #[test]
    fn test_error_response() {
        let config = Config::default();
        let server = tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async {
                DenshaServer::new(config).await.unwrap()
            })
        });

        let response = server.error_response(StatusCode::NOT_FOUND, "Page not found");
        assert_eq!(response.status(), StatusCode::NOT_FOUND);

        let body = response.into_body();
        let bytes = tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async {
                hyper::body::to_bytes(body).await.unwrap()
            })
        });

        let body_str = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(body_str.contains("Error 404"));
        assert!(body_str.contains("Page not found"));
    }
}
