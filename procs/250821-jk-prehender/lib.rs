//! Densha - Next.js-like Web Application Framework
//!
//! Built on top of Kotoba, providing a familiar developer experience
//! with file-based routing, SSR, and API routes.

use std::convert::Infallible;

pub mod config;
pub mod router;
pub mod server;
pub mod ssr;
pub mod middleware;
pub mod static_files;
pub mod error;
pub mod ui;
pub mod transpiler;

pub use config::Config;
pub use router::Router;
pub use server::Server;
pub use ssr::SSREngine;
pub use middleware::{Middleware, MiddlewareChain};
pub use static_files::StaticFileHandler;
pub use ui::*;
pub use transpiler::KotobaTranspiler;

/// Kotoba Web Server - Following Kotoba's component-based architecture
/// This implementation provides a bridge between Densha and Kotoba patterns
pub struct KotobaWebServer {
    config: Config,
}

impl KotobaWebServer {
    /// Create a new Kotoba web server following Kotoba patterns
    pub async fn new(config: Config) -> Result<Self> {
        println!("🔧 Creating Kotoba-compatible web server instance...");

        // Validate and create necessary directories following Kotoba conventions
        Self::ensure_project_structure().await?;

        println!("✅ Kotoba web server instance created");
        Ok(Self { config })
    }

    /// Ensure project structure follows Kotoba conventions
    async fn ensure_project_structure() -> Result<()> {
        let dirs = ["app", "api", "public"];

        for dir in &dirs {
            if !std::path::Path::new(dir).exists() {
                println!("📁 Creating directory: {}", dir);
                std::fs::create_dir_all(dir)?;
            }
        }

        // Create basic app/page.kdl if it doesn't exist
        let page_path = "app/page.kdl";
        if !std::path::Path::new(page_path).exists() {
            println!("📄 Creating default page component: {}", page_path);
            let default_content = r#"// Default page component - Kotoba DSL
metadata {
    title "Welcome to Densha"
    layout "layout"
}

template {
    div class="container mx-auto py-8" {
        h1 class="text-4xl font-bold mb-4" { "Welcome to Densha!" }
        p class="text-gray-600" {
            "This is a Kotoba-compatible web application built with Densha."
        }
    }
}"#;
            tokio::fs::write(page_path, default_content).await?;
        }

        // Create basic layout.kdl if it doesn't exist
        let layout_path = "app/layout.kdl";
        if !std::path::Path::new(layout_path).exists() {
            println!("📄 Creating default layout component: {}", layout_path);
            let layout_content = r#"// Default layout component - Kotoba DSL
metadata {
    title "Densha App"
}

template {
    html lang="en" {
        head {
            meta charset="UTF-8"
            meta name="viewport" content="width=device-width, initial-scale=1.0"
            title "{{title}}"
            link rel="stylesheet" href="/public/globals.css"
        }
        body class="bg-gray-50" {
            {{{children}}}
        }
    }
}"#;
            tokio::fs::write(layout_path, layout_content).await?;
        }

        Ok(())
    }

    /// Run the server with Kotoba's component system
    pub async fn run(self) -> Result<()> {
        println!("🚀 Starting Kotoba-compatible web server...");

        // Load Kotoba components
        self.load_components().await?;

        // Start HTTP server
        self.start_server().await
    }

    /// Load components following Kotoba architecture
    async fn load_components(&self) -> Result<()> {
        println!("📦 Loading Kotoba components...");

        // Load page components from app directory
        if std::path::Path::new("app").exists() {
            println!("📄 Loading page components from ./app");
            self.load_page_components().await?;
        }

        // Load API routes from api directory
        if std::path::Path::new("api").exists() {
            println!("🔌 Loading API routes from ./api");
            self.load_api_components().await?;
        }

        // Load static files from public directory
        if std::path::Path::new("public").exists() {
            println!("📄 Loading static files from ./public");
        }

        println!("✅ Kotoba components loaded");
        Ok(())
    }

    /// Load page components from app directory
    async fn load_page_components(&self) -> Result<()> {
        use walkdir::WalkDir;

        for entry in WalkDir::new("app").into_iter().filter_map(|e| e.ok()) {
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("kdl") {
                println!("  📄 Found page component: {}", path.display());
            }
        }

        Ok(())
    }

    /// Load API components from api directory
    async fn load_api_components(&self) -> Result<()> {
        use walkdir::WalkDir;

        for entry in WalkDir::new("api").into_iter().filter_map(|e| e.ok()) {
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("rs") {
                println!("  🔌 Found API component: {}", path.display());
            }
        }

        Ok(())
    }

    /// Start HTTP server
    async fn start_server(self) -> Result<()> {
        use hyper::service::{make_service_fn, service_fn};
        use std::convert::Infallible;

        let addr = std::net::SocketAddr::from((
            self.config.server.host.parse::<std::net::IpAddr>()?,
            self.config.server.port
        ));

        println!("🌐 Server listening on http://{}", addr);

        let make_svc = make_service_fn(|_conn| async {
            Ok::<_, Infallible>(service_fn(|req| async {
                Self::handle_request(req).await
            }))
        });

        let server = hyper::Server::bind(&addr).serve(make_svc);
        println!("✅ Server ready to accept connections");

        server.await?;
        Ok(())
    }

                /// Handle HTTP requests following Kotoba patterns
                async fn handle_request(req: hyper::Request<hyper::Body>) -> std::result::Result<hyper::Response<hyper::Body>, Infallible> {
        let path = req.uri().path();
        println!("📨 Request: {} {}", req.method(), path);

        // Handle static files first
        if path.starts_with("/static/") || path.starts_with("/public/") {
            return Self::handle_static_file(path).await;
        }

        // Handle API routes
        if path.starts_with("/api/") {
            return Self::handle_api_request(req).await;
        }

        // Handle page requests
        Self::handle_page_request(path).await
    }

                /// Handle static file requests
                async fn handle_static_file(path: &str) -> std::result::Result<hyper::Response<hyper::Body>, Infallible> {
        let file_path = path.trim_start_matches("/static/").trim_start_matches("/public/");
        let full_path = std::path::Path::new("public").join(file_path);

        if full_path.exists() {
            match tokio::fs::read(&full_path).await {
                Ok(content) => {
                    let mime_type = mime_guess::from_path(&full_path).first_or_octet_stream();
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::OK)
                        .header("content-type", mime_type.as_ref())
                        .body(hyper::Body::from(content))
                        .unwrap())
                }
                Err(_) => Self::not_found_response(),
            }
        } else {
            Self::not_found_response()
        }
    }

                /// Handle API requests
                async fn handle_api_request(req: hyper::Request<hyper::Body>) -> std::result::Result<hyper::Response<hyper::Body>, Infallible> {
        let path = req.uri().path().trim_start_matches("/api/");

        // For now, return a simple JSON response
        let json_response = format!(r#"{{"message": "API endpoint: {}", "method": "{}"}}"#, path, req.method());
        Ok(hyper::Response::builder()
            .status(hyper::StatusCode::OK)
            .header("content-type", "application/json")
            .body(hyper::Body::from(json_response))
            .unwrap())
    }

                /// Handle page requests
                async fn handle_page_request(path: &str) -> std::result::Result<hyper::Response<hyper::Body>, Infallible> {
        // Try to find corresponding .kdl file
        let kdl_path = if path == "/" {
            "app/page.kdl"
        } else {
            &format!("app{}/page.kdl", path)
        };

        if std::path::Path::new(kdl_path).exists() {
            // For now, return a simple HTML response indicating Kotoba component
            let html = format!(r#"<!DOCTYPE html>
<html>
<head>
    <title>Densha - Kotoba Component</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link href="/public/globals.css" rel="stylesheet">
</head>
<body class="bg-gray-50">
    <div class="container mx-auto py-8">
        <div class="max-w-4xl mx-auto bg-white rounded-lg shadow-lg p-8">
            <h1 class="text-4xl font-bold text-center mb-8 text-blue-600">
                🚀 Kotoba Component: {}
            </h1>
            <div class="text-center space-y-4">
                <p class="text-gray-600 text-lg">
                    This page is rendered from Kotoba component:
                </p>
                <code class="bg-gray-100 px-4 py-2 rounded text-sm">
                    {}
                </code>
                <div class="mt-8 space-x-4">
                    <a href="/" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors">
                        Home
                    </a>
                    <a href="/about" class="bg-green-600 text-white px-6 py-3 rounded-lg hover:bg-green-700 transition-colors">
                        About
                    </a>
                </div>
            </div>
        </div>
    </div>
</body>
</html>"#, path, kdl_path);

            Ok(hyper::Response::builder()
                .status(hyper::StatusCode::OK)
                .header("content-type", "text/html")
                .body(hyper::Body::from(html))
                .unwrap())
        } else {
            Self::not_found_response()
        }
    }

                /// Return 404 Not Found response
                fn not_found_response() -> std::result::Result<hyper::Response<hyper::Body>, Infallible> {
        Ok(hyper::Response::builder()
            .status(hyper::StatusCode::NOT_FOUND)
            .header("content-type", "text/html")
            .body(hyper::Body::from(r#"<!DOCTYPE html>
<html>
<head>
    <title>Not Found - Densha</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link href="/public/globals.css" rel="stylesheet">
</head>
<body class="bg-gray-50">
    <div class="container mx-auto py-8">
        <div class="max-w-4xl mx-auto bg-white rounded-lg shadow-lg p-8 text-center">
            <h1 class="text-6xl font-bold text-gray-400 mb-4">404</h1>
            <h2 class="text-2xl font-bold text-gray-900 mb-4">Page Not Found</h2>
            <p class="text-gray-600 mb-8">
                The requested page could not be found.
            </p>
            <a href="/" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors">
                Go Home
            </a>
        </div>
    </div>
</body>
</html>"#))
            .unwrap())
    }
}

/// Result type alias for Densha operations
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;

/// Initialize the Densha framework with default settings
pub async fn init() -> Result<Server> {
    let config = Config::default();
    Server::new(config).await
}

/// Initialize the Densha framework with custom configuration
pub async fn init_with_config(config: Config) -> Result<Server> {
    Server::new(config).await
}

/// Initialize Densha with Kotoba transpiler
pub async fn init_with_kotoba() -> Result<(Server, KotobaTranspiler)> {
    let config = Config::default();
    let mut transpiler = KotobaTranspiler::new();

    println!("🔧 Initializing Kotoba transpiler...");
    println!("📂 Loading templates from: app/");

    // Load Kotoba templates from app directory
    match transpiler.load_templates_from_dir(std::path::Path::new("app")).await {
        Ok(_) => println!("✅ Templates loaded successfully"),
        Err(e) => println!("❌ Failed to load templates: {}", e),
    }

    println!("📊 Template count: {}", transpiler.templates().len());

    let server = Server::new_with_transpiler(config, Some(transpiler)).await?;
    Ok((server, KotobaTranspiler::new()))
}
