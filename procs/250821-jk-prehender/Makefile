# Makefile for Tauri + Next.js Application

.PHONY: install dev build clean test lint deploy setup-deps tauri-dev tauri-build help

# Default target
help:
	@echo "Available commands:"
	@echo "  install      - Install dependencies"
	@echo "  dev          - Start Next.js development server"
	@echo "  build        - Build Next.js for production"
	@echo "  tauri-dev    - Start Tauri development mode"
	@echo "  tauri-build  - Build Tauri application"
	@echo "  clean        - Clean build artifacts"
	@echo "  test         - Run tests"
	@echo "  lint         - Run linting"
	@echo "  deploy       - Deploy application"
	@echo "  setup-deps   - Install system dependencies"

# Install dependencies
install:
	pnpm install

# Development
dev:
	pnpm dev

# Build Next.js app
build:
	pnpm build

# Tauri development
tauri-dev:
	pnpm tauri:dev

# Tauri production build
tauri-build:
	pnpm tauri:build

# Clean build artifacts
clean:
	pnpm exec next clean
	rm -rf out
	rm -rf .next
	rm -rf node_modules/.cache
	cd src-tauri && cargo clean

# Run tests
test:
	pnpm test
	cd src-tauri && cargo test

# Linting
lint:
	pnpm lint
	cd src-tauri && cargo clippy

# Setup system dependencies (macOS)
setup-deps:
	@echo "Installing Rust..."
	curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
	@echo "Installing Node.js via Volta..."
	curl https://get.volta.sh | bash
	@echo "Please restart your terminal and run 'make install'"

# Deploy (example for different platforms)
deploy:
	make tauri-build
	@echo "Build completed. Distributable files are in src-tauri/target/release/bundle/"

# Quick setup for new development environment
setup: setup-deps install build

# Watch for changes (cargo-watch for Rust)
watch:
	cd src-tauri && cargo watch -x check

# Format code
format:
	pnpm prettier --write "**/*.{js,jsx,ts,tsx,json,md}"
	cd src-tauri && cargo fmt

# Check everything
check:
	pnpm build
	pnpm lint
	cd src-tauri && cargo check
	cd src-tauri && cargo clippy
	cd src-tauri && cargo test 