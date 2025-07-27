use tower_lsp::jsonrpc::Result;
use tower_lsp::lsp_types::*;
use tower_lsp::{Client, LanguageServer, LspService, Server};

#[derive(Debug)]
struct Backend {
    client: Client,
}

#[tower_lsp::async_trait]
impl LanguageServer for Backend {
    async fn initialize(&self, _: InitializeParams) -> Result<InitializeResult> {
        Ok(InitializeResult {
            server_info: Some(ServerInfo {
                name: "kotoba-lsp".to_string(),
                version: Some("0.1.0".to_string()),
            }),
            capabilities: ServerCapabilities {
                text_document_sync: Some(TextDocumentSyncCapability::Kind(
                    TextDocumentSyncKind::FULL,
                )),
                ..ServerCapabilities::default()
            },
        })
    }

    async fn initialized(&self, _: InitializedParams) {
        self.client
            .log_message(MessageType::INFO, "kotoba-lsp server initialized!")
            .await;
    }

    async fn shutdown(&self) -> Result<()> {
        Ok(())
    }

    async fn did_open(&self, params: DidOpenTextDocumentParams) {
        self.on_change(params.text_document).await
    }

    async fn did_change(&self, mut params: DidChangeTextDocumentParams) {
        let text_document = TextDocumentItem {
            uri: params.text_document.uri,
            language_id: "kotoba".to_string(), // Assuming "kotoba" is the language ID
            version: params.text_document.version,
            text: std::mem::take(&mut params.content_changes[0].text),
        };
        self.on_change(text_document).await
    }
}

impl Backend {
    async fn on_change(&self, text_document: TextDocumentItem) {
        let text = &text_document.text;
        let uri = text_document.uri.clone();

        let diagnostics = match kotoba_parser::parse_statement(text) {
            Ok(_) => vec![], // Parse successful, no diagnostics
            Err(e) => {
                // In a real implementation, you would convert the nom error into one or more
                // `Diagnostic` objects, with accurate range information.
                // For now, we'll just create a single, simple diagnostic.
                let diagnostic = Diagnostic {
                    range: Range::new(Position::new(0, 0), Position::new(0, 1)), // Placeholder range
                    severity: Some(DiagnosticSeverity::ERROR),
                    code: None,
                    code_description: None,
                    source: Some("kotoba-lsp".to_string()),
                    message: format!("Parse error: {:?}", e),
                    related_information: None,
                    tags: None,
                    data: None,
                };
                vec![diagnostic]
            }
        };

        self.client
            .publish_diagnostics(uri, diagnostics, Some(text_document.version))
            .await;
    }
}

#[tokio::main]
async fn main() {
    let stdin = tokio::io::stdin();
    let stdout = tokio::io::stdout();

    let (service, socket) = LspService::new(|client| Backend { client });
    Server::new(stdin, stdout, socket).serve(service).await;
}
