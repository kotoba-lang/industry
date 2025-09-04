# Prehender - Tauri Desktop Application

A modern desktop application built with Tauri, Next.js, and React. This application provides a rich interface for note-taking, event management, and projection visualization.

[![Built with Tauri](https://img.shields.io/badge/Built%20with-Tauri-24C8DB?style=for-the-badge&logo=tauri)](https://tauri.app/)
[![Next.js](https://img.shields.io/badge/Next.js-000000?style=for-the-badge&logo=next.js)](https://nextjs.org/)
[![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react)](https://react.dev/)

## Overview

Prehender is a cross-platform desktop application that combines the power of Rust (via Tauri) with modern web technologies and Apache Kafka for real-time data streaming. It features:

- **Note Management**: Advanced note-taking with sidebar organization
- **Event Composition**: Create and manage events
- **Projection Visualization**: Interactive projection panels
- **Stream Views**: Real-time data streaming and visualization via Kafka
- **Topic Management**: Organize content by topics
- **Viewpoint Panels**: Multiple perspective management
- **Kafka Integration**: Real-time data synchronization using Confluent Cloud

## Technology Stack

- **Frontend**: Next.js 15, React 19, TypeScript
- **Backend**: Tauri (Rust)
- **Data Streaming**: Apache Kafka (Confluent Cloud)
- **UI Components**: Radix UI, Tailwind CSS
- **State Management**: React Hooks
- **Build Tool**: pnpm

## Prerequisites

- **Node.js**: v18+ (recommended via [Volta](https://volta.sh/))
- **Rust**: Latest stable version
- **pnpm**: Package manager

## Quick Start

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd prehender.junkawasaki.com-1
   ```

2. **Install dependencies**
   ```bash
   make install
   # or
   pnpm install
   ```

3. **Start development mode**
   ```bash
   make tauri-dev
   # or
   pnpm tauri:dev
   ```

## Development

### Available Commands

Use `make help` to see all available commands:

```bash
# Development
make dev          # Start Next.js development server
make tauri-dev    # Start Tauri development mode

# Building
make build        # Build Next.js for production
make tauri-build  # Build Tauri application

# Maintenance
make clean        # Clean build artifacts
make lint         # Run linting
make test         # Run tests
make format       # Format code

# Check everything
make check        # Run build, lint, and tests
```

### Project Structure

```
├── app/                    # Next.js app directory
├── components/             # React components
│   ├── ui/                # Reusable UI components
│   └── *.tsx              # Feature components
├── hooks/                 # Custom React hooks
├── lib/                   # Utility libraries
├── public/                # Static assets
├── src-tauri/             # Tauri/Rust backend
│   ├── src/               # Rust source code
│   ├── Cargo.toml         # Rust dependencies
│   └── tauri.conf.json    # Tauri configuration
├── styles/                # Global styles
├── types/                 # TypeScript type definitions
└── Makefile              # Build automation
```

## Building for Production

### Desktop Application

Build the desktop application for your platform:

```bash
make tauri-build
```

The built application will be available in `src-tauri/target/release/bundle/`.

### Web Application

Build the web version:

```bash
make build
```

Static files will be generated in the `out/` directory.

## Configuration

### Kafka Configuration

The application uses Confluent Cloud for Kafka streaming. Configuration is set in `lib/kafka-rest-client.ts`:

```typescript
// Kafka設定（Confluent Cloud）
const KAFKA_REST_ENDPOINT = 'https://pkc-921jm.us-east-2.aws.confluent.cloud:443';
const KAFKA_API_KEY = 'your-api-key';
const KAFKA_API_SECRET = 'your-api-secret';
```

**Topics used:**
- `prehender-notes`: Note data and updates
- `prehender-users`: User information
- `prehender-events`: Application events
- `prehender-sync`: Synchronization status

### Tauri Configuration

Edit `src-tauri/tauri.conf.json` to customize:
- Window properties (size, title, etc.)
- Application identifier
- Build settings
- Security policies

### Next.js Configuration

Edit `next.config.mjs` for:
- Build optimization
- Asset handling
- Export settings

## Troubleshooting

### Common Issues

1. **Rust compilation errors**: Ensure you have the latest Rust toolchain
2. **Node.js version**: Use Node.js 18+ (check with `node --version`)
3. **Build failures**: Try cleaning with `make clean` and rebuilding

### Getting Help

- Check the [Tauri documentation](https://tauri.app/v1/guides/)
- Review [Next.js documentation](https://nextjs.org/docs)
- Open an issue in this repository

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.