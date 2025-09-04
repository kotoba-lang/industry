# 🌐 KawaDB Browser Edition - Makefile
# サーバレス・イベントソーシングライブラリ

.PHONY: help build build-wasm build-demo dev serve test clean install-deps check lint fmt

# デフォルトターゲット
help:
	@echo "🌐 KawaDB Browser Edition - Build Commands"
	@echo ""
	@echo "Build Commands:"
	@echo "  build          - フル（Rust + WASM）ビルド"
	@echo "  build-wasm     - WASMライブラリのビルド"
	@echo "  build-demo     - デモアプリケーションのビルド"
	@echo ""
	@echo "Development Commands:"
	@echo "  dev            - 開発環境でのビルド・テスト"
	@echo "  serve          - ローカルサーバーでデモを実行"
	@echo "  test           - 全テストの実行"
	@echo ""
	@echo "Maintenance Commands:"
	@echo "  clean          - ビルドファイルのクリア"
	@echo "  install-deps   - 依存関係のインストール"
	@echo "  check          - コードチェック"
	@echo "  lint           - リンティング"
	@echo "  fmt            - コードフォーマット"

# 依存関係のインストール
install-deps:
	@echo "📦 Installing Rust dependencies..."
	cargo install wasm-pack
	cargo install cargo-watch
	cargo install basic-http-server
	rustup target add wasm32-unknown-unknown

# フルビルド
build: build-wasm build-demo
	@echo "✅ KawaDB Browser Edition build completed!"

# WASMライブラリのビルド
build-wasm:
	@echo "🔧 Building WASM library..."
	cd kawa-wasm && wasm-pack build --target web --out-dir ../apps/event-sourcing-demo/pkg

# デモアプリケーションのビルド
build-demo: build-wasm
	@echo "🎨 Building demo application..."
	@echo "Demo app prepared at: apps/event-sourcing-demo/"

# 開発環境
dev: install-deps
	@echo "🚀 Starting development environment..."
	$(MAKE) build
	$(MAKE) test
	$(MAKE) serve

# ローカルサーバーでデモを実行
serve: build-demo
	@echo "🌐 Starting local server..."
	@echo "Demo will be available at: http://localhost:8080"
	cd apps/event-sourcing-demo && basic-http-server

# 全テストの実行
test:
	@echo "🧪 Running all tests..."
	cargo test --workspace
	cd kawa-wasm && wasm-pack test --headless --firefox

# Rustのビルドチェック
check:
	@echo "🔍 Checking Rust code..."
	cargo check --workspace
	cargo clippy --workspace -- -D warnings

# リンティング
lint:
	@echo "🔍 Running linting..."
	cargo clippy --workspace -- -D warnings
	cargo fmt --all -- --check

# コードフォーマット
fmt:
	@echo "🎨 Formatting code..."
	cargo fmt --all

# ビルドファイルのクリア
clean:
	@echo "🧹 Cleaning build files..."
	cargo clean
	rm -rf kawa-wasm/pkg/
	rm -rf apps/event-sourcing-demo/pkg/
	rm -rf target/

# パフォーマンステスト
perf-test: build-wasm
	@echo "⚡ Running performance tests..."
	cd kawa-wasm && wasm-pack test --headless --firefox -- --release

# プロダクションビルド
build-prod:
	@echo "🏗️ Building for production..."
	cd kawa-wasm && wasm-pack build --target web --out-dir ../apps/event-sourcing-demo/pkg --release

# 依存関係の更新
update-deps:
	@echo "📦 Updating dependencies..."
	cargo update

# ドキュメント生成
docs:
	@echo "📚 Generating documentation..."
	cargo doc --workspace --no-deps --open

# セキュリティ監査
audit:
	@echo "🔒 Running security audit..."
	cargo audit

# 複数ブラウザテスト
test-browsers: build-wasm
	@echo "🌐 Testing on multiple browsers..."
	cd kawa-wasm && wasm-pack test --headless --firefox
	cd kawa-wasm && wasm-pack test --headless --chrome
	cd kawa-wasm && wasm-pack test --headless --safari

# 最適化ビルド
build-opt:
	@echo "⚡ Building with optimizations..."
	cd kawa-wasm && wasm-pack build --target web --out-dir ../apps/event-sourcing-demo/pkg --release
	wasm-opt -Oz -o apps/event-sourcing-demo/pkg/kawa_wasm_bg.wasm apps/event-sourcing-demo/pkg/kawa_wasm_bg.wasm

# 統計情報表示
stats:
	@echo "📊 Project statistics:"
	@echo "Lines of code:"
	@find . -name "*.rs" -not -path "./target/*" | xargs wc -l | tail -n1
	@echo "WASM binary size:"
	@ls -lh apps/event-sourcing-demo/pkg/kawa_wasm_bg.wasm 2>/dev/null || echo "Not built yet"

# 全体検証
validate: clean install-deps build test lint
	@echo "✅ Full validation completed successfully!"

# 継続的インテグレーション
ci: validate test-browsers audit
	@echo "🎯 CI pipeline completed!" 