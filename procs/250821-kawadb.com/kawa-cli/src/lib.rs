//! # Kawa CLI Library
//!
//! Kawaブローカーの管理用コマンドラインインターフェース。
//! ブローカーの起動、設定、モニタリング、管理機能を提供。

use anyhow::Result;
use clap::{Parser, Subcommand};
use std::path::PathBuf;
use tracing::{info, error};

mod commands;
mod config;
mod display;

use commands::*;

/// Kawa - 高性能Kafka互換メッセージブローカー
/// 
/// # 主要機能
/// - ブローカーの起動・停止
/// - 設定管理
/// - トピック・パーティション管理  
/// - メッセージ送受信
/// - メトリクス・モニタリング
/// - データ管理・統計
#[derive(Parser)]
#[command(
    name = "kawa",
    version = env!("CARGO_PKG_VERSION"),
    about = "高性能Kafka互換メッセージブローカー",
    long_about = "Kawa は Kafka互換プロトコルを処理する高性能メッセージブローカーです。\n\
                  イベントソーシング、リアルタイム配信、高可用性を提供します。"
)]
pub struct Cli {
    /// 設定ファイルのパス
    #[arg(short, long, global = true)]
    pub config: Option<PathBuf>,
    
    /// ログレベル
    #[arg(short, long, global = true, default_value = "info")]
    pub log_level: String,
    
    /// JSON形式でのログ出力
    #[arg(long, global = true)]
    pub json_logs: bool,
    
    /// 詳細モード
    #[arg(short, long, global = true)]
    pub verbose: bool,
    
    /// サブコマンド
    #[command(subcommand)]
    pub command: Commands,
}

/// 利用可能なコマンド
#[derive(Subcommand)]
pub enum Commands {
    /// ブローカーサーバーを開始
    /// 
    /// # Example
    /// ```bash
    /// kawa server start --config /etc/kawa/broker.toml
    /// ```
    Server {
        #[command(subcommand)]
        action: ServerAction,
    },
    
    /// トピック管理
    /// 
    /// # Example
    /// ```bash
    /// kawa topic create user-events --partitions 3
    /// kawa topic list --verbose
    /// ```
    Topic {
        #[command(subcommand)]
        action: TopicAction,
    },
    
    /// メッセージ操作
    /// 
    /// # Example
    /// ```bash
    /// kawa message produce my-topic --message "Hello, World!"
    /// kawa message consume my-topic --offset 0 --max 10
    /// ```
    Message {
        #[command(subcommand)]
        action: MessageAction,
    },
    
    /// 設定管理
    /// 
    /// # Example
    /// ```bash
    /// kawa config show
    /// kawa config validate
    /// ```
    Config {
        #[command(subcommand)]
        action: ConfigAction,
    },
    
    /// メトリクス・モニタリング
    /// 
    /// # Example
    /// ```bash
    /// kawa metrics show
    /// ```
    Metrics {
        #[command(subcommand)]
        action: MetricsAction,
    },
    
    /// データ管理
    /// 
    /// # Example  
    /// ```bash
    /// kawa data stats
    /// ```
    Data {
        #[command(subcommand)]
        action: DataAction,
    },
    
    /// ヘルスチェック
    /// 
    /// # Example
    /// ```bash
    /// kawa health check
    /// ```
    Health {
        #[command(subcommand)]
        action: HealthAction,
    },
}

/// Public main function for library usage
pub async fn main() -> Result<()> {
    let cli = Cli::parse();
    
    // ログ初期化
    setup_logging(&cli)?;
    
    info!("Kawa CLI v{} starting", env!("CARGO_PKG_VERSION"));
    
    // コマンド実行
    let result = match &cli.command {
        Commands::Server { action } => {
            server_command(action.clone(), &cli).await
        },
        Commands::Topic { action } => {
            topic_command(action.clone(), &cli).await
        },
        Commands::Message { action } => {
            message_command(action.clone(), &cli).await
        },
        Commands::Config { action } => {
            config_command(action.clone(), &cli).await
        },
        Commands::Metrics { action } => {
            metrics_command(action.clone(), &cli).await
        },
        Commands::Data { action } => {
            data_command(action.clone(), &cli).await
        },
        Commands::Health { action } => {
            health_command(action.clone(), &cli).await
        },
    };
    
    match result {
        Ok(_) => {
            info!("Command completed successfully");
            Ok(())
        },
        Err(e) => {
            error!("Command failed: {}", e);
            std::process::exit(1);
        }
    }
}

/// ログ設定を初期化
/// 
/// # Arguments
/// * `cli` - CLI設定
/// 
/// # Returns
/// * `Result<()>` - 初期化結果
/// 
/// # @todo
/// - [ ] ファイル出力サポート
/// - [ ] ログローテーション
/// - [ ] 構造化ログ
fn setup_logging(cli: &Cli) -> Result<()> {
    use tracing_subscriber::{filter::LevelFilter, fmt, prelude::*, EnvFilter};
    
    let level = match cli.log_level.to_lowercase().as_str() {
        "trace" => LevelFilter::TRACE,
        "debug" => LevelFilter::DEBUG,
        "info" => LevelFilter::INFO,
        "warn" => LevelFilter::WARN,
        "error" => LevelFilter::ERROR,
        _ => LevelFilter::INFO,
    };
    
    let fmt_layer = if cli.json_logs {
        fmt::layer().with_ansi(false).boxed()
    } else {
        fmt::layer().pretty().boxed()
    };
    
    tracing_subscriber::registry()
        .with(EnvFilter::from_default_env().add_directive(level.into()))
        .with(fmt_layer)
        .init();
    
    Ok(())
}

/// デフォルト設定ファイルのパスを取得
/// 
/// # Returns
/// * `PathBuf` - 設定ファイルのパス
/// 
/// # @todo
/// - [ ] XDG Base Directory 対応
/// - [ ] Windows対応
fn default_config_path() -> PathBuf {
    if let Some(config_dir) = dirs::config_dir() {
        config_dir.join("kawa").join("broker.toml")
    } else {
        PathBuf::from("./kawa-broker.toml")
    }
}

/// 設定ファイルのパスを解決
/// 
/// # Arguments
/// * `cli` - CLI設定
/// 
/// # Returns
/// * `PathBuf` - 解決された設定ファイルのパス
pub fn resolve_config_path(cli: &Cli) -> PathBuf {
    cli.config.clone().unwrap_or_else(default_config_path)
} 