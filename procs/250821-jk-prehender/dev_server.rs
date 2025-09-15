//! Development server with hot reload functionality

use densha::config::Config;
use densha::server::Server;
/// Development server (simplified for now)
pub struct DevServer {
    config: Config,
}

impl DevServer {
    /// Create a new development server
    pub fn new(config: Config) -> Self {
        Self { config }
    }

    /// Start the development server
    pub async fn start(mut self) -> Result<(), Box<dyn std::error::Error>> {
        println!("🔥 Development server starting...");
        println!("📁 Watching directories: {:?}", self.config.dev.watch_dirs);

        let server = Server::new(self.config.clone()).await?;
        let server_handle = tokio::spawn(async move {
            server.start().await
        });

        println!("✅ Server ready at http://{}:{}", self.config.server.host, self.config.server.port);

        // File watching loop
        println!("📝 File watching is not fully implemented yet.");
        println!("   The server is running but file changes won't trigger rebuilds.");

        // For now, just keep the server running
        // In a real implementation, you'd use a proper event loop with notify
        std::future::pending::<()>().await;

        // This won't be reached in the current implementation
        // server_handle.await??;
        // Ok(())
    }
}

/// Auto-restart utilities for development
pub struct AutoRestarter {
    /// Command to restart
    restart_command: Vec<String>,
    /// Current process handle
    current_process: Option<tokio::process::Child>,
}

impl AutoRestarter {
    /// Create a new auto-restarter
    pub fn new(command: Vec<String>) -> Self {
        Self {
            restart_command: command,
            current_process: None,
        }
    }

    /// Start the initial process
    pub async fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        self.restart().await
    }

    /// Restart the process
    pub async fn restart(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        // Kill current process if running
        if let Some(mut process) = self.current_process.take() {
            process.kill().await?;
            process.wait().await?;
        }

        // Start new process
        let mut command = tokio::process::Command::new(&self.restart_command[0]);
        command.args(&self.restart_command[1..]);

        let child = command.spawn()?;
        self.current_process = Some(child);

        println!("🔄 Process restarted");
        Ok(())
    }

    /// Wait for the process to finish
    pub async fn wait(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(mut process) = self.current_process.take() {
            process.wait().await?;
        }
        Ok(())
    }
}

/// Development helpers
pub mod helpers {
    use std::path::Path;

    /// Check if a file change should trigger a rebuild
    pub fn should_trigger_rebuild(path: &Path) -> bool {
        let extensions = ["rs", "html", "css", "js", "ts", "json"];

        if let Some(ext) = path.extension().and_then(|s| s.to_str()) {
            extensions.contains(&ext)
        } else {
            false
        }
    }

    /// Get file type for logging
    pub fn get_file_type(path: &Path) -> &'static str {
        match path.extension().and_then(|s| s.to_str()) {
            Some("rs") => "Rust source",
            Some("html") => "HTML template",
            Some("css") => "Stylesheet",
            Some("js") => "JavaScript",
            Some("ts") => "TypeScript",
            Some("json") => "JSON config",
            _ => "Unknown",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::helpers::*;

    #[test]
    fn test_should_trigger_rebuild() {
        assert!(should_trigger_rebuild(std::path::Path::new("main.rs")));
        assert!(should_trigger_rebuild(std::path::Path::new("style.css")));
        assert!(should_trigger_rebuild(std::path::Path::new("script.js")));
        assert!(!should_trigger_rebuild(std::path::Path::new("image.png")));
        assert!(!should_trigger_rebuild(std::path::Path::new("document.pdf")));
    }

    #[test]
    fn test_get_file_type() {
        assert_eq!(get_file_type(std::path::Path::new("main.rs")), "Rust source");
        assert_eq!(get_file_type(std::path::Path::new("style.css")), "Stylesheet");
        assert_eq!(get_file_type(std::path::Path::new("unknown.xyz")), "Unknown");
    }

    #[test]
    fn test_dev_server_creation() {
        let config = Config::default();
        let dev_server = DevServer::new(config);
        // Basic test to ensure DevServer can be created
        assert!(true); // Placeholder test
    }
}
