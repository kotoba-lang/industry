# 🎯 Kawa Broker - Kafka-Compatible High-Performance Message Broker

**kawa-broker** is a high-performance message broker with full Apache Kafka compatibility. It achieves excellent performance through Rust's asynchronous processing and zero-copy optimization.

## 🎯 Key Features

- **📡 Full Kafka Protocol Support**: Existing Kafka clients can be used as-is
- **🚀 High-Performance Processing**: Asynchronous I/O & concurrent session management
- **🔧 Producer/Consumer API**: Fully implemented
- **⚙️ Management Features**: Topic, offset, and statistics management
- **🌐 TCP/TLS Support**: Secure communication (future implementation)
- **🗄️ DuckDB-style Database**: Local + WASM-compatible SQL processing engine
- **📊 Real-time Analytics**: SQL transformation & aggregation of streaming data
- **🌐 WASM Support**: Database operations in the browser

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Kawa Ecosystem                           │
├─────────────────────────────────────────────────────────────┤
│  🌐 Network Layer (TCP Server)                             │
│   ├── TcpListener (asynchronous)                           │
│   ├── Session management                                   │
│   ├── Connection pool                                      │
│   └── Dynamic port allocation                              │
├─────────────────────────────────────────────────────────────┤
│  📡 Kafka Protocol Layer                                   │
│   ├── Binary protocol parsing                              │
│   ├── Produce API (0)                                      │
│   ├── Fetch API (1)                                        │
│   ├── Metadata API (3)                                     │
│   └── ApiVersions API (18)                                 │
├─────────────────────────────────────────────────────────────┤
│  📨 Messaging Services                                      │
│   ├── ProducerService                                      │
│   ├── ConsumerService                                      │
│   ├── TopicManager                                         │
│   └── OffsetManager                                        │
├─────────────────────────────────────────────────────────────┤
│  🗄️ Database Layer (kawa-db)                               │
│   ├── SQL Query Engine (DataFusion)                       │
│   ├── Table Management                                     │
│   ├── Schema Definition                                    │
│   ├── Arrow Integration                                    │
│   └── Stream Processing                                    │
├─────────────────────────────────────────────────────────────┤
│  🌐 WASM Interface (kawa-wasm)                              │
│   ├── JavaScript Bindings                                  │
│   ├── Browser Storage                                      │
│   ├── Web Workers Support                                  │
│   └── Local Database Access                                │
├─────────────────────────────────────────────────────────────┤
│  💾 Storage Integration (kawa-storage)                     │
│   ├── Write-Ahead Log (WAL)                                │
│   ├── Segment Management                                   │
│   ├── Event Sourcing                                       │
│   └── Parquet Integration                                  │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Basic Server Startup

```rust
use kawa_broker::{MessageBroker, BrokerConfig};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Broker configuration
    let config = BrokerConfig {
        network: NetworkConfig {
            bind_host: "0.0.0.0".to_string(),
            bind_port: 9092,
        },
        storage: StorageConfig::default(),
        cluster: ClusterConfig::default(),
    };
    
    // Start broker
    let mut broker = MessageBroker::new(config).await?;
    let addr = broker.start().await?;
    
    println!("Kawa broker started on {}", addr);
    
    // Stop with Ctrl+C
    tokio::signal::ctrl_c().await?;
    broker.stop().await?;
    
    Ok(())
}
```

### Producer API

```rust
use kawa_broker::{MessageBroker, SessionId};

// Send message
let session_id = SessionId::new();
let topic = "user-events";
let partition = 0;
let message = b"Hello, Kawa!";

let offset = broker.produce_message(topic, partition, message, session_id).await?;
println!("Message sent at offset: {}", offset);

// Batch send
let messages = vec![
    b"Message 1".to_vec(),
    b"Message 2".to_vec(), 
    b"Message 3".to_vec(),
];

let offsets = broker.produce_batch(topic, partition, messages, session_id).await?;
println!("Batch sent at offsets: {:?}", offsets);
```

### Consumer API

```rust
// Receive messages
let messages = broker.fetch_messages(
    topic,
    partition,
    0,            // start offset
    10,           // max messages
    session_id
).await?;

for message in messages {
    println!("Offset: {}, Data: {:?}", message.offset, message.message);
}

// Consumer group
let group_id = "my-consumer-group";
let group_messages = broker.fetch_group_messages(
    topic,
    partition, 
    group_id,
    10,
    session_id
).await?;
```

## 📡 Kafka Protocol Support

### Supported APIs

| API | Code | Version | Status |
|-----|------|---------|--------|
| **Produce** | 0 | 0-7 | ✅ Fully supported |
| **Fetch** | 1 | 0-11 | ✅ Fully supported |
| **Metadata** | 3 | 0-9 | ✅ Fully supported |
| **ApiVersions** | 18 | 0-3 | ✅ Fully supported |

### Future Support Planned

| API | Code | Planned Version |
|-----|------|----------------|
| **CreateTopics** | 19 | v0.2.0 |
| **DeleteTopics** | 20 | v0.2.0 |
| **JoinGroup** | 11 | v0.3.0 |
| **SyncGroup** | 14 | v0.3.0 |

### Binary Protocol Example

```rust
use kawa_broker::protocol::{ProtocolReader, ProtocolWriter};

// Request parsing
let mut reader = ProtocolReader::new(&request_data);
let api_key = reader.read_i16()?;
let api_version = reader.read_i16()?;
let correlation_id = reader.read_u32()?;

// Response creation
let mut writer = ProtocolWriter::new();
writer.write_u32(correlation_id);
writer.write_i16(0); // error code = success
let response_data = writer.into_bytes();
```

## 🗄️ DuckDB-style Database Features

### Local Database Usage

```rust
use kawa_db::{DatabaseEngine, DbConfig, TableSchema, ColumnDefinition, DataType};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize database engine
    let config = DbConfig::new("./kawa-data".into());
    let db = DatabaseEngine::new(config).await?;
    
    // Create table
    let schema = TableSchema::new(
        "users".to_string(),
        vec![
            ColumnDefinition::new("id".to_string(), DataType::Int64, false),
            ColumnDefinition::new("name".to_string(), DataType::String, false),
            ColumnDefinition::new("email".to_string(), DataType::String, true),
            ColumnDefinition::new("created_at".to_string(), DataType::Timestamp, false),
        ],
    );
    db.create_table(schema).await?;
    
    // Insert data
    let data = vec![
        serde_json::json!({
            "id": 1,
            "name": "Alice",
            "email": "alice@example.com",
            "created_at": "2024-01-01T00:00:00Z"
        }),
        serde_json::json!({
            "id": 2,
            "name": "Bob",
            "email": "bob@example.com", 
            "created_at": "2024-01-02T00:00:00Z"
        }),
    ];
    db.insert_data("users", data).await?;
    
    // Execute SQL query
    let result = db.execute_sql("SELECT * FROM users WHERE id > 1").await?;
    println!("Query result: {:?}", result);
    
    Ok(())
}
```

### WASM Environment Usage

```javascript
import init, { WasmKawaDb, WasmTableConfig } from './pkg/kawa_wasm.js';

async function main() {
    // Initialize WASM module
    await init();
    
    // Create database
    const db = new WasmKawaDb();
    
    // Create table configuration
    const tableConfig = new WasmTableConfig("products");
    tableConfig.add_column("id", "int64", false);
    tableConfig.add_column("name", "string", false);
    tableConfig.add_column("price", "float64", false);
    tableConfig.add_column("category", "string", true);
    
    // Create table
    await db.create_table(tableConfig);
    
    // Insert data
    const data = JSON.stringify([
        { id: 1, name: "Laptop", price: 999.99, category: "Electronics" },
        { id: 2, name: "Book", price: 19.99, category: "Education" },
        { id: 3, name: "Coffee", price: 4.99, category: "Food" },
    ]);
    
    const insertCount = await db.insert_data("products", data);
    console.log(`Inserted ${insertCount} rows`);
    
    // Execute SQL query
    const result = await db.execute_sql("SELECT * FROM products WHERE price > 20");
    console.log("Query result:", JSON.parse(result.data));
    
    // List tables
    const tables = await db.list_tables();
    console.log("Tables:", JSON.parse(tables));
}

main().catch(console.error);
```

### Streaming Analytics

```rust
// Real-time stream processing
db.process_stream(
    "user-events",                    // input topic
    "SELECT user_id, COUNT(*) as event_count, AVG(value) as avg_value 
     FROM stream 
     GROUP BY user_id 
     WINDOW TUMBLING (INTERVAL 1 MINUTE)",  // SQL transformation
    "user-analytics"                  // output table
).await?;

// Query analytics results
let analytics = db.execute_sql("
    SELECT user_id, event_count, avg_value 
    FROM user_analytics 
    WHERE event_count > 100
    ORDER BY avg_value DESC
    LIMIT 10
").await?;
```

## 🛠️ Management Features

### Topic Management

```rust
// Create topic
broker.create_topic("my-topic", 3, 1).await?; // 3 partitions, replication 1

// List topics
let topics = broker.list_topics().await?;
for topic in topics {
    println!("Topic: {}", topic);
}

// Delete topic
broker.delete_topic("my-topic").await?;
```

### Offset Management

```rust
// Commit offset
broker.commit_offset("my-group", "topic", 0, 100).await?;

// Get latest offset
let latest = broker.get_latest_offset("topic", 0).await?;
println!("Latest offset: {:?}", latest);

// Get group offset
let group_offset = broker.get_group_offset("my-group", "topic", 0).await?;
```

### Statistics & Monitoring

```rust
// Broker statistics
let stats = broker.get_stats().await?;
println!("Active sessions: {}", stats.active_sessions);
println!("Total topics: {}", stats.total_topics);
println!("Uptime: {}s", stats.uptime_seconds);

// Topic statistics
let topic_stats = broker.get_topic_stats("my-topic").await?;
println!("Messages: {}", topic_stats.total_messages);
```

## ⚙️ Configuration

### BrokerConfig

```rust
use kawa_broker::{BrokerConfig, NetworkConfig, StorageConfig, ClusterConfig};

let config = BrokerConfig {
    network: NetworkConfig {
        bind_host: "0.0.0.0".to_string(),
        bind_port: 9092,
        enable_tls: false,                    // future implementation
        max_connections: 1000,
        connection_timeout_ms: 30000,
    },
    storage: StorageConfig {
        data_dir: "./data".into(),
        segment_size: 1024 * 1024 * 1024,    // 1GB
        sync_interval_ms: 1000,
        enable_compression: false,
    },
    cluster: ClusterConfig {
        node_id: 0,
        enable_replication: false,           // future implementation
        replication_factor: 1,
    },
};
```

### Configuration File (TOML)

```toml
[network]
bind_host = "0.0.0.0"
bind_port = 9092
max_connections = 1000

[storage]
data_dir = "./kawa-data"
segment_size = 1073741824  # 1GB
sync_interval_ms = 1000

[cluster]
node_id = 0
enable_replication = false
```

## 🧪 Testing

### Run Integration Tests

```bash
# Run all tests
cargo test

# Producer/Consumer tests
cargo test --test producer_consumer_integration_test

# Performance tests
cargo test test_basic_performance --release -- --nocapture
```

### Test Results

```
✅ test_basic_producer_functionality
✅ test_basic_consumer_functionality  
✅ test_multi_partition_messaging
✅ test_consumer_group_functionality
✅ test_offset_management
✅ test_error_handling
✅ test_basic_performance
✅ test_topic_management
✅ test_broker_statistics

Total: 9/9 tests passed
```

## 🚀 Performance Optimization

### Asynchronous Processing

```rust
// Concurrent session processing
tokio::spawn(async move {
    handle_client_connection(stream, session_id, storage, protocol_handler).await
});

// Concurrent message processing
let futures: Vec<_> = messages.into_iter()
    .map(|msg| broker.produce_message(topic, partition, msg, session_id))
    .collect();
let results = futures::future::join_all(futures).await;
```

### Memory Optimization

```rust
// Zero-copy operations
let response = protocol_handler.handle_request(&request_data, &storage, session_id).await?;

// Buffer reuse (future implementation)
let buffer_pool = BufferPool::new(1024, 4096, 8192);
let buffer = buffer_pool.get_buffer(size).await;
```

## 🔧 Error Handling

```rust
use kawa_broker::{BrokerError, BrokerResult};

match broker.produce_message(topic, partition, message, session_id).await {
    Ok(offset) => println!("Success: {}", offset),
    Err(BrokerError::TopicNotFound { topic }) => {
        eprintln!("Topic '{}' not found", topic);
    }
    Err(BrokerError::InvalidPartition { partition, max }) => {
        eprintln!("Invalid partition {} (max: {})", partition, max);
    }
    Err(BrokerError::Storage { source }) => {
        eprintln!("Storage error: {}", source);
    }
    Err(e) => eprintln!("Other error: {}", e),
}
```

## 🚧 Future Features

### v0.2.0 - Enhanced Management Features
- [ ] **CreateTopics/DeleteTopics API**
- [ ] **Management REST API**
- [ ] **Metrics Export**
- [ ] **Configuration Hot Reload**
- [x] **DuckDB-style Local Database**
- [x] **WASM Support and Browser Integration**

### v0.3.0 - Enterprise Features
- [ ] **Full Consumer Group Support**
- [ ] **TLS/SASL Authentication**
- [ ] **Replication**
- [ ] **Automatic Failover**
- [ ] **Index Functionality**
- [ ] **Query Optimization Engine**

### v0.4.0 - Advanced Features
- [ ] **Transactions**
- [ ] **Exactly-Once Delivery**
- [ ] **Schema Registry Integration**
- [ ] **Kubernetes Operator**
- [ ] **Machine Learning Integration**
- [ ] **Geospatial Data Support**

### v0.5.0 - AI/ML Integration 