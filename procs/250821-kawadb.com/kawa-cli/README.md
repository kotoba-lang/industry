# ⚙️ Kawa CLI - Advanced Broker Management Tool

**kawa-cli** is a comprehensive management and operation tool for Kawa broker. With beautiful UI, rich features, and intuitive command structure, it enables efficient broker operations.

## 🎯 Key Features

- **🖥️ Server Management**: Start, stop, and status monitoring
- **📋 Topic Management**: Create, delete, list, and detailed display
- **📨 Message Operations**: Send/receive and batch processing
- **⚙️ Configuration Management**: Display, validation, and generation
- **📊 Monitoring & Statistics**: Metrics and performance measurement
- **🏥 Health Checks**: System health verification

## 🚀 Installation & Setup

### Build

```bash
# Clone repository
git clone https://github.com/your-org/kawa.git
cd kawa

# Build CLI
cargo build --release --bin kawa

# Add to system path (optional)
sudo cp target/release/kawa /usr/local/bin/
```

### Basic Verification

```bash
# Check version
kawa --version

# Show help
kawa --help

# Subcommand help
kawa server --help
```

## 🖥️ Server Management

### Basic Operations

```bash
# Start server with default settings
kawa server start

# Start with custom port
kawa server start --port 9092

# Start with configuration file
kawa server start --config ./custom-config.toml

# Start in background
kawa server start --daemon

# Stop server
kawa server stop

# Check server status
kawa server status
```

### Example Output

```bash
$ kawa server start
🚀 Starting Kawa broker server...
✅ Server started successfully!
   📡 Listening on: 127.0.0.1:9092
   🔧 Data directory: ./data

ℹ️ Press Ctrl+C to stop the server
```

## 📋 Topic Management

### Topic Operations

```bash
# List topics (brief display)
kawa topic list

# List topics (detailed display)
kawa topic list --verbose

# Create new topic
kawa topic create my-topic --partitions 3 --replication 1

# Delete topic (with confirmation)
kawa topic delete my-topic

# Delete topic (force execution)
kawa topic delete my-topic --force

# Show topic details
kawa topic describe my-topic
```

### Example Output

```bash
$ kawa topic list --verbose
📋 Listing topics...

┌─────────────┬────────────┬────────┐
│ Topic Name  │ Partitions │ Status │
├─────────────┼────────────┼────────┤
│ user-events │ 3          │ Active │
│ log-stream  │ 1          │ Active │
│ metrics     │ 5          │ Active │
└─────────────┴────────────┴────────┘

ℹ️ Found 3 topic(s)
```

## 📨 Message Operations

### Producer (Message Sending)

```bash
# Send message directly
kawa message produce my-topic --message "Hello, Kawa!"

# Send to specific partition
kawa message produce my-topic --message "Hello" --partition 2

# Send from file
kawa message produce my-topic --file ./message.txt

# Send from stdin
echo "Test message" | kawa message produce my-topic
```

### Consumer (Message Receiving)

```bash
# Receive from latest messages
kawa message consume my-topic

# Receive from specific offset
kawa message consume my-topic --offset 100

# Specify maximum receive count
kawa message consume my-topic --max 50

# Receive from specific partition
kawa message consume my-topic --partition 1 --offset 0 --max 10
```

### Example Output

```bash
$ kawa message consume user-events --max 3
📥 Consuming messages from topic 'user-events'...
   📊 Partition: 0
   📍 Starting offset: 0
   📏 Max messages: 3

📨 Received 3 message(s):

📋 Message 1:
   Offset: 0
   Timestamp: 2024-01-15 10:30:15 UTC
   Size: 28 bytes
   Content: {"user": "alice", "action": "login"}

📋 Message 2:
   Offset: 1
   Timestamp: 2024-01-15 10:30:16 UTC
   Size: 32 bytes
   Content: {"user": "bob", "action": "purchase"}
```

## ⚙️ Configuration Management

### Configuration Operations

```bash
# Display current configuration
kawa config show

# Validate configuration file
kawa config validate

# Generate default configuration
kawa config generate

# Output configuration to file
kawa config generate --output kawa-config.toml
```

### Configuration Example

```toml
[network]
bind_host = "0.0.0.0"
bind_port = 9092
max_connections = 1000

[storage]
data_dir = "./data"
segment_size = 1073741824  # 1GB
sync_interval_ms = 1000

[cluster]
node_id = 0
enable_replication = false
```

## 📊 Monitoring & Metrics

### Metrics Display

```bash
# Display system metrics
kawa metrics show

# Display data statistics
kawa data stats
```

### Example Output

```bash
$ kawa metrics show
📊 Fetching metrics...

┌──────────────────┬───────┐
│ Metric           │ Value │
├──────────────────┼───────┤
│ Active Sessions  │ 5     │
│ Total Topics     │ 3     │
│ Consumer Groups  │ 2     │
│ Total Offsets    │ 1,234 │
│ Uptime           │ 3,600s│
└──────────────────┴───────┘
```

## 🏥 Health Checks

### System Health Verification

```bash
# Comprehensive health check
kawa health check
```

### Example Output

```bash
$ kawa health check
🏥 Performing health check...

⠁ [00:00:02] ████████████████████████████████████████ 4/4 ✅ Health check passed

🎉 All systems are healthy!
```

## 🎨 CLI Features

### Beautiful UI

- **🎨 Colorful Output**: Color-coded information display
- **📊 Table Format**: Readable display of structured data
- **📈 Progress Bars**: Progress indication for long-running processes
- **🎯 Emoji Icons**: Intuitive operation guides

### Interactive Features

```bash
# Deletion confirmation
$ kawa topic delete important-topic
⚠️ Are you sure you want to delete topic 'important-topic'? [y/N]: n
ℹ️ Deletion cancelled

# Message from stdin
$ kawa message produce my-topic
📝 Enter message (Ctrl+D to finish):
This is a multi-line
message from stdin
^D
✅ Message produced successfully!
```

## 🔧 Advanced Usage

### Batch Scripts

```bash
#!/bin/bash
# Kawa management script example

# Start server
kawa server start --daemon

# Create topics
kawa topic create logs --partitions 5
kawa topic create events --partitions 3

# Validate configuration
kawa config validate

# Health check
kawa health check

echo "✅ Kawa setup completed!"
```

### Configuration File Integration

```bash
# Production environment configuration
kawa server start --config /etc/kawa/production.toml

# Development environment configuration
kawa server start --config ./dev-config.toml
```

### Monitoring Scripts

```bash
#!/bin/bash
# Periodic monitoring script

while true; do
    echo "=== $(date) ==="
    kawa metrics show
    kawa health check
    sleep 60
done
```

## 📝 Command Reference

### Global Options

```bash
kawa [OPTIONS] <SUBCOMMAND>

OPTIONS:
    -h, --help       Display help
    -V, --version    Display version
    -v, --verbose    Enable verbose output
    -q, --quiet      Quiet output mode
```

### Subcommand List

| Command | Description | Example |
|---------|-------------|---------|
| **server** | Server management | `kawa server start` |
| **topic** | Topic management | `kawa topic create my-topic` |
| **message** | Message operations | `kawa message produce my-topic` |
| **config** | Configuration management | `kawa config show` |
| **metrics** | Metrics display | `kawa metrics show` |
| **data** | Data statistics | `kawa data stats` |
| **health** | Health checks | `kawa health check` |

## 🚧 Future Features

### v0.2.0 - Enhanced Management Features
- [ ] **Interactive Mode**: TUI support
- [ ] **Configuration Wizard**: Guided configuration creation
- [ ] **Log Viewer**: Real-time log display
- [ ] **Performance Analysis**: Detailed benchmarking

### v0.3.0 - Operation Support
- [ ] **Cluster Management**: Multi-node support
- [ ] **Alert Configuration**: Threshold monitoring & notifications
- [ ] **Backup**: Data backup & restore
- [ ] **Plugin System**: Custom extensions

### v0.4.0 - Enterprise Features
- [ ] **Web UI Integration**: Browser-based management
- [ ] **API Gateway**: REST/GraphQL management API
- [ ] **Audit Logs**: Operation history tracking
- [ ] **RBAC**: Role-based access control

## 🔧 For Developers

### Adding Custom Commands

```rust
// src/commands/custom.rs
use clap::Subcommand;

#[derive(Subcommand, Clone)]
pub enum CustomAction {
    /// Custom feature
    Custom {
        #[arg(short, long)]
        option: String,
    },
}
```

### Plugin Feature (Future)

```rust
// Plugin interface
pub trait KawaPlugin {
    fn name(&self) -> &str;
    fn execute(&self, args: &[String]) -> Result<()>;
}
```

## 🔗 Related Links

- [kawa-broker](../kawa-broker/README.md)
- [kawa-storage](../kawa-storage/README.md)
- [CLI Design Patterns](https://clig.dev/)

---

**kawa-cli** - Beautiful, user-friendly, high-functionality management tool! ⚙️🚀 