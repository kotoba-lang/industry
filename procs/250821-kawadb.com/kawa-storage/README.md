# 💾 Kawa Storage - High-Performance Event Storage Engine

**kawa-storage** is the core high-performance storage engine of the Kawa project. It implements Write-Ahead Log (WAL) and segmented persistence, along with event sourcing patterns.

## 🎯 Design Goals

- **⚡ High Performance**: 274K+ writes/sec, 1.2M+ reads/sec
- **🔒 Consistency**: Data integrity guarantee through CRC32 checksums
- **📈 Scalability**: Efficient data management through segmentation
- **🛡️ Reliability**: Fault tolerance and data recovery capabilities

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Kawa Storage Engine                         │
├─────────────────────────────────────────────────────────────┤
│  📤 Public API                                             │
│   ├── StorageEngine                                        │
│   ├── append_event() / read_events()                       │
│   └── get_latest_offset()                                  │
├─────────────────────────────────────────────────────────────┤
│  📝 Write-Ahead Log (WAL)                                  │
│   ├── Per-partition offset management                      │
│   ├── Automatic segment rotation                           │
│   └── Asynchronous write & read                            │
├─────────────────────────────────────────────────────────────┤
│  📦 Segment Management                                      │
│   ├── Fixed-size segments (1GB default)                    │
│   ├── Memory-mapped I/O                                    │
│   ├── CRC32 integrity checks                               │
│   └── Concurrent access control                            │
├─────────────────────────────────────────────────────────────┤
│  🔍 Event Management                                        │
│   ├── EventId (UUID v4)                                    │
│   ├── Event metadata                                       │
│   ├── JSON & binary data support                           │
│   └── Timestamp management                                 │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Basic Usage

```rust
use kawa_storage::{
    StorageEngine, StorageConfig, 
    Topic, Partition, EventData, Offset
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize storage engine
    let config = StorageConfig {
        data_dir: "./kawa-data".into(),
        segment_size: 1024 * 1024 * 1024, // 1GB
        sync_interval_ms: 1000,
        enable_compression: false,
    };
    
    let storage = StorageEngine::new(config).await?;
    
    // Append event
    let topic = Topic::new("user-events");
    let partition = Partition::new(0);
    let event_data = EventData::from_json(r#"{"user_id": "123", "action": "login"}"#);
    
    let (event_id, offset) = storage.append_event(&topic, partition, event_data).await?;
    println!("Event {} stored at offset {}", event_id, offset);
    
    // Read events
    let events = storage.read_events(&topic, partition, Offset::new(0), 10).await?;
    for event in events {
        println!("Event: {:?}", event);
    }
    
    // Get latest offset
    if let Some(latest) = storage.get_latest_offset(&topic, partition).await? {
        println!("Latest offset: {}", latest);
    }
    
    Ok(())
}
```

### Performance Optimization

```rust
// Efficient writing of large data
for i in 0..10000 {
    let event_data = EventData::from_bytes(format!("Event {}", i).into_bytes());
    storage.append_event(&topic, partition, event_data).await?;
}

// Batch reading
let events = storage.read_events(&topic, partition, Offset::new(0), 1000).await?;
```

## 📊 Performance Characteristics

### Benchmark Results

| Operation | Performance | Conditions |
|-----------|-------------|------------|
| **Write** | 274,816 events/sec | Release build, 1000 events |
| **Read** | 1,190,890 events/sec | Release build, 1000 events |
| **Startup Time** | < 100ms | Empty data directory |
| **Memory Usage** | < 50MB | Basic operations |

### Scalability

- **Maximum Segment Size**: No limit (1GB recommended)
- **Maximum Partitions**: No limit
- **Maximum Topics**: No limit
- **Concurrent Access**: Fully supported

## 🔧 Configuration Options

```rust
use kawa_storage::StorageConfig;

let config = StorageConfig {
    // Data storage directory
    data_dir: PathBuf::from("./kawa-data"),
    
    // Segment file size (bytes)
    segment_size: 1024 * 1024 * 1024, // 1GB
    
    // Sync interval (milliseconds)
    sync_interval_ms: 1000,
    
    // Enable compression (future implementation)
    enable_compression: false,
};
```

## 🗃️ Data Structures

### Event

```rust
pub struct Event {
    pub id: EventId,           // UUID v4
    pub topic: Topic,          // Topic name
    pub partition: Partition,  // Partition number
    pub data: EventData,       // Event data
    pub timestamp: Timestamp,  // Creation time
    pub offset: Option<Offset>, // Offset (set on read)
}
```

### EventData

```rust
pub enum EventData {
    Json(String),      // JSON string
    Binary(Vec<u8>),   // Binary data
    Text(String),      // Plain text
}

// Usage examples
let json_data = EventData::from_json(r#"{"key": "value"}"#);
let binary_data = EventData::from_bytes(vec![1, 2, 3, 4]);
let text_data = EventData::from_text("Hello, World!");
```

## 🧪 Testing

### Run Tests

```bash
# Run all tests
cargo test

# Integration tests
cargo test --test integration_tests

# Benchmarks
cargo test bench_ --release -- --nocapture

# Coverage
cargo tarpaulin --out Html
```

### Test Configuration

- **Unit Tests**: Basic functionality of each module
- **Integration Tests**: End-to-end scenarios
- **Benchmark Tests**: Performance measurement
- **Property Tests**: Random data validation

## 🚧 Future Features

### v0.2.0
- [ ] **Compression Support**: LZ4/Snappy compression
- [ ] **Index Building**: Fast search functionality
- [ ] **Memory Pool**: Allocation optimization
- [ ] **Batch API**: Bulk event processing

### v0.3.0
- [ ] **Replication**: Multi-node support
- [ ] **Encryption**: Encryption at rest
- [ ] **Schema Validation**: Event structure validation
- [ ] **Automatic Compaction**: Old segment cleanup

## ⚠️ Limitations

- **Transactions**: Currently not supported
- **Delete Operations**: Event deletion not supported
- **Distributed Storage**: Single node only
- **Schema Evolution**: Currently not supported

## 🔗 Related Documentation

- [API Documentation](https://docs.rs/kawa-storage)
- [Storage Design](./docs/storage-design.md)
- [Performance Tuning](./docs/performance.md)
- [Error Handling](./docs/error-handling.md)

## 📝 Error Handling

```rust
use kawa_storage::{StorageError, StorageResult};

match storage.append_event(&topic, partition, event_data).await {
    Ok((event_id, offset)) => {
        println!("Success: {} at {}", event_id, offset);
    }
    Err(StorageError::Io { source, .. }) => {
        eprintln!("I/O Error: {}", source);
    }
    Err(StorageError::Serialization { source, .. }) => {
        eprintln!("Serialization Error: {}", source);
    }
    Err(e) => {
        eprintln!("Other Error: {}", e);
    }
}
```

---

**kawa-storage** - High-performance event storage implemented in Rust 🚀 