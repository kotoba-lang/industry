//! Densha - Next.js-like Web Application Framework
//!
//! This is the main entry point for the Densha framework.
//! It provides a development server and build tools similar to Next.js.

use clap::{Parser, Subcommand};
use densha::config::Config;
use densha::server::Server;
use densha::transpiler::KotobaTranspiler;
use std::path::PathBuf;

// Import streams API
mod api;
use crate::api::{STREAMS_API, Stream, StreamUpdate, StreamReference};

// Import chrono for timestamps
use chrono;

#[derive(Parser)]
#[command(name = "densha")]
#[command(version, about = "Next.js-like web framework built with Rust", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Start development server with hot reload
    Dev {
        /// Port to run the development server on
        #[arg(short, long, default_value_t = 3000)]
        port: u16,

        /// Host to bind the development server to
        #[arg(long, default_value = "127.0.0.1")]
        host: String,

        /// Enable open browser automatically
        #[arg(long)]
        open: bool,
    },

    /// Build the application for production
    Build {
        /// Output directory for build artifacts
        #[arg(short, long, default_value = ".densha")]
        output: String,

        /// Enable verbose output
        #[arg(short, long)]
        verbose: bool,
    },

    /// Start production server
    Start {
        /// Port to run the production server on
        #[arg(short, long, default_value_t = 3000)]
        port: u16,

        /// Host to bind the production server to
        #[arg(long, default_value = "0.0.0.0")]
        host: String,

        /// Directory containing built application
        #[arg(long, default_value = ".densha")]
        dir: String,
    },

    /// Initialize a new Densha project
    Init {
        /// Project name
        name: Option<String>,

        /// Template to use
        #[arg(short, long, default_value = "default")]
        template: String,

        /// Skip installing dependencies
        #[arg(long)]
        skip_install: bool,
    },

    /// Export the application to static files
    Export {
        /// Output directory for static export
        #[arg(short, long, default_value = "out")]
        output: String,

        /// Enable verbose output
        #[arg(short, long)]
        verbose: bool,
    },

    /// Run linting on the codebase
    Lint {
        /// Fix linting issues automatically
        #[arg(long)]
        fix: bool,

        /// File or directory to lint
        #[arg(default_value = ".")]
        path: String,
    },

    /// Manage telemetry settings
    Telemetry {
        /// Enable telemetry
        #[arg(long)]
        enable: bool,

        /// Disable telemetry
        #[arg(long)]
        disable: bool,

        /// Show current telemetry status
        #[arg(long)]
        status: bool,
    },

    /// Show information about the project
    Info,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    match cli.command {
        Commands::Dev { port, host, open } => {
            run_dev_server(port, &host, open).await?;
        }
        Commands::Build { output, verbose } => {
            run_build(&output, verbose).await?;
        }
        Commands::Start { port, host, dir } => {
            run_production_server(port, &host, &dir).await?;
        }
        Commands::Init { name, template, skip_install } => {
            run_init(name, &template, skip_install).await?;
        }
        Commands::Export { output, verbose } => {
            run_export(&output, verbose).await?;
        }
        Commands::Lint { fix, path } => {
            run_lint(fix, &path).await?;
        }
        Commands::Telemetry { enable, disable, status } => {
            run_telemetry(enable, disable, status).await?;
        }
        Commands::Info => {
            run_info().await?;
        }
    }

    Ok(())
}

/// Run development server with hot reload
async fn run_dev_server(port: u16, host: &str, open: bool) -> anyhow::Result<()> {
    println!("🚀 Starting Densha development server...");

    if open {
        println!("🔗 Opening browser automatically...");
        // TODO: Implement browser opening functionality
    }

    println!("🔧 Initializing server with Kotoba-compatible architecture...");
    println!("📄 Using default configuration");
    println!("📂 App directory: ./app");
    println!("🔌 API directory: ./api");
    println!("📄 Public directory: ./public");

    // Start simple HTTP server
    let addr = format!("{}:{}", host, port).parse()?;
    println!("🚀 Starting server on http://{}", addr);

    let make_svc = hyper::service::make_service_fn(|_conn| async {
        Ok::<_, std::convert::Infallible>(hyper::service::service_fn(|req| async {
            handle_kotoba_request(req).await
        }))
    });

    let server = hyper::Server::bind(&addr).serve(make_svc);
    println!("✅ Server ready to accept connections");
    println!("📝 Ready - started server on http://{}", addr);

    server.await?;
    Ok(())
}

/// Handle requests following Kotoba patterns
async fn handle_kotoba_request(req: hyper::Request<hyper::Body>) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    let path = req.uri().path();
    println!("📨 Request: {} {}", req.method(), path);

    // Handle static files first
    if path.starts_with("/static/") || path.starts_with("/public/") {
        return handle_static_file(path).await;
    }

    // Handle API routes
    if path.starts_with("/api/") {
        return handle_api_request(req).await;
    }

    // Handle page requests
    handle_page_request(path).await
}

/// Handle static file requests
async fn handle_static_file(path: &str) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
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
            Err(_) => not_found_response(),
        }
    } else {
        not_found_response()
    }
}

/// Handle API requests
async fn handle_api_request(req: hyper::Request<hyper::Body>) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    let path = req.uri().path().trim_start_matches("/api/");
    let method = req.method();

    match (method, path) {
        // GET /api/streams - Get all streams
        (&hyper::Method::GET, "streams") => {
            let streams = STREAMS_API.get_streams().await;
            let json_response = serde_json::to_string(&streams).unwrap_or_else(|_| "[]".to_string());
            Ok(hyper::Response::builder()
                .status(hyper::StatusCode::OK)
                .header("content-type", "application/json")
                .body(hyper::Body::from(json_response))
                .unwrap())
        }

        // POST /api/streams - Create a new stream
        (&hyper::Method::POST, "streams") => {
            let body_bytes = hyper::body::to_bytes(req.into_body()).await.unwrap_or_default();
            let body_str = String::from_utf8(body_bytes.to_vec()).unwrap_or_default();

            match serde_json::from_str::<serde_json::Value>(&body_str) {
                Ok(data) => {
                    let stream = Stream {
                        id: "".to_string(),
                        stream_id: "".to_string(),
                        title: data.get("title").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        content: data.get("content").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        tags: data.get("tags").and_then(|v| v.as_array())
                            .map(|arr| arr.iter().filter_map(|v| v.as_str()).map(|s| s.to_string()).collect())
                            .unwrap_or_default(),
                        created_at: "".to_string(),
                        updated_at: "".to_string(),
                        references: vec![],
                        subscribers: vec![],
                        stream_type: data.get("streamType").and_then(|v| v.as_str()).unwrap_or("note").to_string(),
                        stream_state: "draft".to_string(),
                        user_id: "local-user".to_string(),
                        device_id: "local-device".to_string(),
                        version: 1,
                        is_deleted: false,
                        sync_status: "pending".to_string(),
                    };

                    let created_stream = STREAMS_API.create_stream(stream).await;
                    let json_response = serde_json::to_string(&created_stream).unwrap_or_else(|_| "{}".to_string());
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::CREATED)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(json_response))
                        .unwrap())
                }
                Err(_) => {
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::BAD_REQUEST)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(r#"{"error": "Invalid JSON"}"#))
                        .unwrap())
                }
            }
        }

        // GET /api/streams/{id} - Get a specific stream
        (&hyper::Method::GET, path) if path.starts_with("streams/") => {
            let id = path.trim_start_matches("streams/");
            match STREAMS_API.get_stream(id).await {
                Some(stream) => {
                    let json_response = serde_json::to_string(&stream).unwrap_or_else(|_| "{}".to_string());
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::OK)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(json_response))
                        .unwrap())
                }
                None => {
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::NOT_FOUND)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(r#"{"error": "Stream not found"}"#))
                        .unwrap())
                }
            }
        }

        // PUT /api/streams/{id} - Update a stream
        (&hyper::Method::PUT, path) if path.starts_with("streams/") => {
            let id = path.trim_start_matches("streams/").to_string();
            let body_bytes = hyper::body::to_bytes(req.into_body()).await.unwrap_or_default();
            let body_str = String::from_utf8(body_bytes.to_vec()).unwrap_or_default();

            match serde_json::from_str::<StreamUpdate>(&body_str) {
                Ok(updates) => {
                    match STREAMS_API.update_stream(&id, updates).await {
                        Some(updated_stream) => {
                            let json_response = serde_json::to_string(&updated_stream).unwrap_or_else(|_| "{}".to_string());
                            Ok(hyper::Response::builder()
                                .status(hyper::StatusCode::OK)
                                .header("content-type", "application/json")
                                .body(hyper::Body::from(json_response))
                                .unwrap())
                        }
                        None => {
                            Ok(hyper::Response::builder()
                                .status(hyper::StatusCode::NOT_FOUND)
                                .header("content-type", "application/json")
                                .body(hyper::Body::from(r#"{"error": "Stream not found"}"#))
                                .unwrap())
                        }
                    }
                }
                Err(_) => {
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::BAD_REQUEST)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(r#"{"error": "Invalid JSON"}"#))
                        .unwrap())
                }
            }
        }

        // DELETE /api/streams/{id} - Delete a stream
        (&hyper::Method::DELETE, path) if path.starts_with("streams/") => {
            let id = path.trim_start_matches("streams/");
            let deleted = STREAMS_API.delete_stream(id).await;

            if deleted {
                Ok(hyper::Response::builder()
                    .status(hyper::StatusCode::NO_CONTENT)
                    .body(hyper::Body::empty())
                    .unwrap())
            } else {
                Ok(hyper::Response::builder()
                    .status(hyper::StatusCode::NOT_FOUND)
                    .header("content-type", "application/json")
                    .body(hyper::Body::from(r#"{"error": "Stream not found"}"#))
                    .unwrap())
                }
        }

        // POST /api/streams/{id}/references - Add a reference
        (&hyper::Method::POST, path) if path.starts_with("streams/") && path.ends_with("/references") => {
            let id = path.trim_start_matches("streams/").trim_end_matches("/references").to_string();
            let body_bytes = hyper::body::to_bytes(req.into_body()).await.unwrap_or_default();
            let body_str = String::from_utf8(body_bytes.to_vec()).unwrap_or_default();

            match serde_json::from_str::<serde_json::Value>(&body_str) {
                Ok(data) => {
                    let reference = StreamReference {
                        id: format!("ref-{}", chrono::Utc::now().timestamp_millis()),
                        target_stream_id: data.get("targetStreamId").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        reference_type: data.get("referenceType").and_then(|v| v.as_str()).unwrap_or("reference").to_string(),
                        context: data.get("context").and_then(|v| v.as_str()).map(|s| s.to_string()),
                        created_at: chrono::Utc::now().to_rfc3339(),
                        is_active: true,
                    };

                    match STREAMS_API.add_reference(&id, reference).await {
                        Some(updated_stream) => {
                            let json_response = serde_json::to_string(&updated_stream).unwrap_or_else(|_| "{}".to_string());
                            Ok(hyper::Response::builder()
                                .status(hyper::StatusCode::OK)
                                .header("content-type", "application/json")
                                .body(hyper::Body::from(json_response))
                                .unwrap())
                        }
                        None => {
                            Ok(hyper::Response::builder()
                                .status(hyper::StatusCode::NOT_FOUND)
                                .header("content-type", "application/json")
                                .body(hyper::Body::from(r#"{"error": "Stream not found"}"#))
                                .unwrap())
                        }
                    }
                }
                Err(_) => {
                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::BAD_REQUEST)
                        .header("content-type", "application/json")
                        .body(hyper::Body::from(r#"{"error": "Invalid JSON"}"#))
                        .unwrap())
                }
            }
        }

        // Default API response
        _ => {
            let json_response = format!(r#"{{"message": "API endpoint: {}", "method": "{}"}}"#, path, method);
            Ok(hyper::Response::builder()
                .status(hyper::StatusCode::OK)
                .header("content-type", "application/json")
                .body(hyper::Body::from(json_response))
                .unwrap())
        }
    }
}

/// Handle page requests
async fn handle_page_request(path: &str) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    // Try to find corresponding files in order: .jsonnet, .kotoba, .kdl
    let jsonnet_path = if path == "/" {
        "app/page.jsonnet"
    } else {
        &format!("app{}/page.jsonnet", path)
    };

    let kotoba_path = if path == "/" {
        "app/page.kotoba"
    } else {
        &format!("app{}/page.kotoba", path)
    };

    let kdl_path = if path == "/" {
        "app/page.kdl"
    } else {
        &format!("app{}/page.kdl", path)
    };

    // Try JSONnet first
    if std::path::Path::new(jsonnet_path).exists() {
        return process_jsonnet_page(jsonnet_path).await;
    }

    // Try Kotoba next
    if std::path::Path::new(kotoba_path).exists() {
        return process_kotoba_page(kotoba_path).await;
    }

    // Fall back to KDL if others not found
    if std::path::Path::new(kdl_path).exists() {
        return process_template_file(kdl_path, "KDL").await;
    }

    not_found_response()
}

/// Process JSONnet page template
async fn process_jsonnet_page(jsonnet_path: &str) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    process_template_file(jsonnet_path, "JSONnet").await
}

/// Process Kotoba page template
async fn process_kotoba_page(kotoba_path: &str) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    process_template_file(kotoba_path, "Kotoba").await
}

/// Generic template file processor
async fn process_template_file(template_path: &str, template_type: &str) -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
    match tokio::fs::read_to_string(template_path).await {
        Ok(content) => {
            let transpiler = densha::transpiler::KotobaTranspiler::new();

            let html_result: Result<String, Box<dyn std::error::Error + Send + Sync>> = match template_type {
                "JSONnet" => transpiler.process_jsonnet_template(&content, None).await,
                "Kotoba" => transpiler.process_kotoba_template(&content, None).await,
                "KDL" => transpiler.process_kdl_template(&content, None).await,
                _ => Err(Box::new(densha::error::DenshaError::Template("Unknown template type".to_string()))),
            };

            match html_result {
                Ok(html_content) => {
                    // Parse the HTML content to separate head and body
                    let (head_content, body_content) = parse_head_body(&html_content);

                    // Wrap the rendered content in a basic HTML structure
                    let full_html = format!(r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link href="/public/globals.css" rel="stylesheet">
    {}
</head>
<body>
    {}
</body>
</html>"#, head_content, body_content);

                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::OK)
                        .header("content-type", "text/html")
                        .body(hyper::Body::from(full_html))
                        .unwrap())
                }
                Err(e) => {
                    let error_html = format!(r#"<!DOCTYPE html>
<html>
<head>
    <title>Error - Densha</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link href="/public/globals.css" rel="stylesheet">
</head>
<body class="bg-gray-50">
    <div class="container mx-auto py-8">
        <div class="max-w-4xl mx-auto bg-white rounded-lg shadow-lg p-8 text-center">
            <h1 class="text-4xl font-bold text-red-600 mb-4">Template Error</h1>
            <p class="text-gray-600">Failed to process {} template: {}</p>
            <p class="text-sm text-gray-500 mt-4">File: {}</p>
        </div>
    </div>
</body>
</html>"#, template_type, e, template_path);

                    Ok(hyper::Response::builder()
                        .status(hyper::StatusCode::INTERNAL_SERVER_ERROR)
                        .header("content-type", "text/html")
                        .body(hyper::Body::from(error_html))
                        .unwrap())
                }
            }
        }
        Err(_) => not_found_response(),
    }
}

/// Parse HTML content to separate head and body parts
fn parse_head_body(html_content: &str) -> (String, String) {
    // For now, assume all content goes to body
    // TODO: Implement proper head/body separation
    (String::new(), html_content.to_string())
}

/// Return 404 Not Found response
fn not_found_response() -> Result<hyper::Response<hyper::Body>, std::convert::Infallible> {
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
                The requested Kotoba component could not be found.
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

/// Build the application for production
async fn run_build(output: &str, verbose: bool) -> anyhow::Result<()> {
    println!("🔨 Building for production...");

    if verbose {
        println!("📂 Output directory: {}", output);
        println!("🔧 Building with verbose output enabled");
    }

    // Create output directory
    let output_dir = PathBuf::from(output);
    std::fs::create_dir_all(&output_dir)?;

    if verbose {
        println!("📁 Created output directory: {}", output_dir.display());
    }

    // Initialize transpiler for build process
    let transpiler = KotobaTranspiler::new();

    if verbose {
        println!("🔧 Initialized Kotoba transpiler");
    }

    // Build static pages
    println!("📄 Building pages...");
    build_pages(&transpiler, &output_dir, verbose).await?;

    // Copy public files
    println!("📦 Copying public files...");
    copy_public_files(&output_dir, verbose).await?;

    // Build API routes (if any)
    println!("🔌 Building API routes...");
    build_api_routes(&output_dir, verbose).await?;

    println!("✅ Build complete!");
    if verbose {
        println!("📂 Build artifacts saved to: {}", output_dir.display());
    } else {
        println!("📂 Build artifacts saved to {}", output);
    }

    Ok(())
}

/// Build static pages from app directory
async fn build_pages(transpiler: &KotobaTranspiler, output_dir: &PathBuf, verbose: bool) -> anyhow::Result<()> {
    use walkdir::WalkDir;

    let app_dir = PathBuf::from("app");
    if !app_dir.exists() {
        if verbose {
            println!("⚠️  App directory not found, skipping page build");
        }
        return Ok(());
    }

    for entry in WalkDir::new(&app_dir).into_iter().filter_map(|e| e.ok()) {
        let path = entry.path();

        if let Some(extension) = path.extension() {
            if extension == "kotoba" || extension == "kdl" || extension == "jsonnet" {
                let relative_path = path.strip_prefix(&app_dir)?;
                if verbose {
                    println!("📄 Processing: {}", relative_path.display());
                }

                // Read and process the template
                match tokio::fs::read_to_string(path).await {
                    Ok(content) => {
                        let html_result = match extension.to_str() {
                            Some("kotoba") => transpiler.process_kotoba_template(&content, None).await,
                            Some("kdl") => transpiler.process_kdl_template(&content, None).await,
                            Some("jsonnet") => transpiler.process_jsonnet_template(&content, None).await,
                            _ => continue,
                        };

                        match html_result {
                            Ok(html) => {
                                // Determine output path
                                let output_path = if relative_path.file_stem().unwrap_or_default() == "page" {
                                    // This is a page component
                                    let parent = relative_path.parent().unwrap_or_else(|| std::path::Path::new(""));
                                    if parent == std::path::Path::new("") {
                                        output_dir.join("index.html")
                                    } else {
                                        output_dir.join(parent).join("index.html")
                                    }
                                } else {
                                    // This is a component, skip for now
                                    continue;
                                };

                                // Create directory if it doesn't exist
                                if let Some(parent) = output_path.parent() {
                                    std::fs::create_dir_all(parent)?;
                                }

                                // Write HTML file
                                tokio::fs::write(&output_path, html).await?;
                                if verbose {
                                    println!("✅ Generated: {}", output_path.strip_prefix(output_dir)?.display());
                                }
                            }
                            Err(e) => {
                                println!("❌ Failed to process {}: {}", relative_path.display(), e);
                            }
                        }
                    }
                    Err(e) => {
                        println!("❌ Failed to read {}: {}", path.display(), e);
                    }
                }
            }
        }
    }

    Ok(())
}

/// Copy public files to output directory
async fn copy_public_files(output_dir: &PathBuf, verbose: bool) -> anyhow::Result<()> {
    let public_dir = PathBuf::from("public");

    if !public_dir.exists() {
        if verbose {
            println!("⚠️  Public directory not found, skipping public file copy");
        }
        return Ok(());
    }

    let output_public = output_dir.clone();

    // Copy all files from public directory
    copy_dir_recursive(public_dir.clone(), output_public.clone(), verbose).await?;

    Ok(())
}

/// Copy directory recursively
async fn copy_dir_recursive(src: PathBuf, dst: PathBuf, verbose: bool) -> anyhow::Result<()> {
    use tokio::fs;

    if !src.exists() {
        return Ok(());
    }

    fs::create_dir_all(&dst).await?;

    let mut entries = fs::read_dir(&src).await?;
    while let Some(entry) = entries.next_entry().await? {
        let entry_path = entry.path();
        let file_name = entry_path.file_name().unwrap();
        let dst_path = dst.join(file_name);

        if entry_path.is_dir() {
            Box::pin(copy_dir_recursive(entry_path, dst_path, verbose)).await?;
        } else {
            fs::copy(&entry_path, &dst_path).await?;
            if verbose {
                println!("📋 Copied: {}", entry_path.display());
            }
        }
    }

    Ok(())
}

/// Build API routes
async fn build_api_routes(_output_dir: &PathBuf, _verbose: bool) -> anyhow::Result<()> {
    // TODO: Implement API route building for static generation
    // For now, API routes are handled dynamically
    Ok(())
}

/// Run production server
async fn run_production_server(port: u16, host: &str, dir: &str) -> anyhow::Result<()> {
    println!("🌐 Starting production server...");
    println!("📂 Serving from: {}", dir);

    // Check if build directory exists
    let build_dir = PathBuf::from(dir);
    if !build_dir.exists() {
        println!("❌ Build directory '{}' not found. Run 'densha build' first.", dir);
        std::process::exit(1);
    }

    // Create config for production server
    let config = Config {
        server: densha::config::ServerConfig {
            host: host.to_string(),
            port,
            ..Default::default()
        },
        ..Default::default()
    };

    println!("🚀 Server starting on http://{}:{}", host, port);

    let server = Server::new(config).await.map_err(|e| anyhow::anyhow!("{}", e))?;

    server.start().await.map_err(|e| anyhow::anyhow!("{}", e))?;
    Ok(())
}

/// Initialize a new Densha project
async fn run_init(name: Option<String>, template: &str, skip_install: bool) -> anyhow::Result<()> {
    println!("🚀 Creating new Densha project...");

    let project_name = name.unwrap_or_else(|| {
        println!("📝 What would you like to name your project? (densha-app)");
        // For now, use default name since we can't interactively ask
        "densha-app".to_string()
    });

    println!("📁 Creating project: {}", project_name);
    println!("🎨 Using template: {}", template);

    // Create project directory
    let project_dir = PathBuf::from(&project_name);
    if project_dir.exists() {
        println!("❌ Directory '{}' already exists!", project_name);
        std::process::exit(1);
    }

    std::fs::create_dir_all(&project_dir)?;

    // Create basic project structure
    create_project_structure(&project_dir, template).await?;

    println!("✅ Project created successfully!");
    println!();
    println!("🚀 To get started:");
    println!("    cd {}", project_name);
    if !skip_install {
        println!("    densha dev");
    }
    println!();
    println!("📖 Documentation: https://densha.dev");

    Ok(())
}

/// Create project structure
async fn create_project_structure(project_dir: &PathBuf, template: &str) -> anyhow::Result<()> {
    // Create directories
    let dirs = ["app", "api", "public", "styles"];
    for dir in &dirs {
        std::fs::create_dir_all(project_dir.join(dir))?;
    }

    // Create package.json-like file (for future use)
    let package_json = serde_json::json!({
        "name": project_dir.file_name().unwrap().to_str().unwrap(),
        "version": "0.1.0",
        "description": "A Densha project",
        "scripts": {
            "dev": "densha dev",
            "build": "densha build",
            "start": "densha start"
        }
    });

    tokio::fs::write(
        project_dir.join("densha.json"),
        serde_json::to_string_pretty(&package_json)?
    ).await?;

    // Create basic files
    create_basic_files(project_dir, template).await?;

    Ok(())
}

/// Create basic project files
async fn create_basic_files(project_dir: &PathBuf, _template: &str) -> anyhow::Result<()> {
    // Create main page
    let page_content = r#"// Welcome to Densha!
export default function Home() {
    return (
        <div className="container mx-auto py-8">
            <h1 className="text-4xl font-bold mb-4">
                Welcome to Densha! 🚀
            </h1>
            <p className="text-gray-600 mb-8">
                This is your first Densha application.
            </p>
            <div className="space-x-4">
                <a href="/about"
                   className="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors">
                    About
                </a>
                <a href="/api/hello"
                   className="bg-green-600 text-white px-6 py-3 rounded-lg hover:bg-green-700 transition-colors">
                    API Test
                </a>
            </div>
        </div>
    );
}"#;

    tokio::fs::write(project_dir.join("app/page.kotoba"), page_content).await?;

    // Create layout
    let layout_content = r#"export default function Layout({ children }) {
    return (
        <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Densha App</title>
                <link href="/globals.css" rel="stylesheet" />
            </head>
            <body className="bg-gray-50">
                {children}
            </body>
        </html>
    );
}"#;

    tokio::fs::write(project_dir.join("app/layout.kotoba"), layout_content).await?;

    // Create about page
    let about_content = r#"export default function About() {
    return (
        <div className="container mx-auto py-8">
            <h1 className="text-4xl font-bold mb-4">About</h1>
            <p className="text-gray-600 mb-8">
                This is the about page of your Densha application.
            </p>
            <a href="/"
               className="bg-gray-600 text-white px-6 py-3 rounded-lg hover:bg-gray-700 transition-colors">
                ← Back Home
            </a>
        </div>
    );
}"#;

    std::fs::create_dir_all(project_dir.join("app/about"))?;
    tokio::fs::write(project_dir.join("app/about/page.kotoba"), about_content).await?;

    // Create API route
    let api_content = r#"export default function handler(req, res) {
    res.status(200).json({
        message: "Hello from Densha API!",
        method: req.method,
        timestamp: new Date().toISOString()
    });
}"#;

    std::fs::create_dir_all(project_dir.join("api/hello"))?;
    tokio::fs::write(project_dir.join("api/hello/route.rs"), api_content).await?;

    // Create CSS file
    let css_content = r#"@tailwind base;
@tailwind components;
@tailwind utilities;

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}"#;

    tokio::fs::write(project_dir.join("public/globals.css"), css_content).await?;

    // Create README
    let readme_content = format!(r#"# {}

Welcome to your new Densha project! 🚀

## Getting Started

```bash
# Start development server
densha dev

# Build for production
densha build

# Start production server
densha start
```

## Project Structure

```
{}/
├── app/                 # Page components (file-based routing)
│   ├── page.kotoba     # Home page
│   ├── layout.kotoba   # Root layout
│   └── about/
│       └── page.kotoba # About page
├── api/                 # API routes
│   └── hello/
│       └── route.rs     # API endpoint
├── public/              # Static files
│   └── globals.css      # Global styles
├── styles/              # Additional styles
└── densha.json          # Project configuration
```

## Documentation

Visit [https://densha.dev](https://densha.dev) for full documentation.

## Next Steps

- Edit `app/page.kotoba` to customize your home page
- Add new pages in the `app/` directory
- Create API routes in the `api/` directory
- Add static files to the `public/` directory

Happy coding! 🎉
"#, project_dir.file_name().unwrap().to_str().unwrap(), project_dir.file_name().unwrap().to_str().unwrap());

    tokio::fs::write(project_dir.join("README.md"), readme_content).await?;

    Ok(())
}

/// Export the application to static files
async fn run_export(output: &str, verbose: bool) -> anyhow::Result<()> {
    println!("📤 Exporting application to static files...");

    // First build the application
    run_build(".densha", verbose).await?;

    // Then copy to export directory
    let build_dir = PathBuf::from(".densha");
    let export_dir = PathBuf::from(output);

    if export_dir.exists() {
        std::fs::remove_dir_all(&export_dir)?;
    }

    if verbose {
        println!("📂 Copying from .densha to {}", output);
    }

    copy_dir_recursive(build_dir.clone(), export_dir.clone(), verbose).await?;

    println!("✅ Export complete!");
    println!("📂 Static files exported to: {}", output);

    Ok(())
}

/// Run linting on the codebase
async fn run_lint(_fix: bool, _path: &str) -> anyhow::Result<()> {
    println!("🔍 Running linter...");

    // TODO: Implement linting functionality
    // For now, just show that linting is not yet implemented
    println!("⚠️  Linting is not yet implemented in this version");
    println!("📝 This feature will be available in a future release");

    Ok(())
}

/// Manage telemetry settings
async fn run_telemetry(enable: bool, disable: bool, status: bool) -> anyhow::Result<()> {
    if enable && disable {
        println!("❌ Cannot enable and disable telemetry at the same time");
        std::process::exit(1);
    }

    if enable {
        println!("📊 Telemetry enabled");
        // TODO: Actually enable telemetry
    } else if disable {
        println!("📊 Telemetry disabled");
        // TODO: Actually disable telemetry
    } else if status {
        println!("📊 Telemetry status: disabled (default)");
        println!("💡 Densha collects anonymous usage data to improve the framework");
        println!("   Run 'densha telemetry --enable' to opt in");
    } else {
        println!("📊 Telemetry management");
        println!();
        println!("USAGE:");
        println!("    densha telemetry --enable     Enable telemetry");
        println!("    densha telemetry --disable    Disable telemetry");
        println!("    densha telemetry --status     Show telemetry status");
    }

    Ok(())
}

/// Show information about the project
async fn run_info() -> anyhow::Result<()> {
    println!("ℹ️  Densha Project Information");
    println!("================================");

    // Check if we're in a Densha project
    let is_densha_project = PathBuf::from("densha.json").exists() ||
                           PathBuf::from("app").exists() ||
                           PathBuf::from("api").exists();

    if !is_densha_project {
        println!("❌ Not a Densha project (no densha.json or app/ directory found)");
        println!("💡 Run 'densha init' to create a new project");
        return Ok(());
    }

    // Show project info
    if let Ok(content) = tokio::fs::read_to_string("densha.json").await {
        if let Ok(package) = serde_json::from_str::<serde_json::Value>(&content) {
            if let Some(name) = package.get("name") {
                println!("📁 Project: {}", name.as_str().unwrap_or("Unknown"));
            }
            if let Some(version) = package.get("version") {
                println!("📦 Version: {}", version.as_str().unwrap_or("Unknown"));
            }
            if let Some(description) = package.get("description") {
                println!("📝 Description: {}", description.as_str().unwrap_or(""));
            }
        }
    }

    // Show directory structure
    println!();
    println!("📂 Directory Structure:");
    let dirs = ["app", "api", "public", "styles"];
    for dir in &dirs {
        let path = PathBuf::from(dir);
        let exists = path.exists();
        let count = if exists {
            std::fs::read_dir(&path).map(|entries| entries.count()).unwrap_or(0)
        } else {
            0
        };
        println!("  {}/ {} ({})", dir, if exists { "✅" } else { "❌" }, count);
    }

    // Show system info
    println!();
    println!("🖥️  System Information:");
    println!("  Densha Version: {}", env!("CARGO_PKG_VERSION"));
    println!("  Rust Version: {}", rustc_version::version()?);
    println!("  Platform: {}", std::env::consts::OS);

    Ok(())
}
