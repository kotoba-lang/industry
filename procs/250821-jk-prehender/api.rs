// Prehender Streams API - Rust Implementation
// Handles CRUD operations for streams with Kafka integration

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::sync::RwLock;

// Stream data structures
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamReference {
    pub id: String,
    pub target_stream_id: String,
    pub reference_type: String,
    pub context: Option<String>,
    pub created_at: String,
    pub is_active: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Stream {
    pub id: String,
    pub stream_id: String,
    pub title: String,
    pub content: String,
    pub tags: Vec<String>,
    pub created_at: String,
    pub updated_at: String,
    pub references: Vec<StreamReference>,
    pub subscribers: Vec<String>,
    pub stream_type: String,
    pub stream_state: String,
    pub user_id: String,
    pub device_id: String,
    pub version: i32,
    pub is_deleted: bool,
    pub sync_status: String,
}

// In-memory storage for demo purposes
// In production, this would be replaced with proper database integration
type StreamStore = Arc<RwLock<HashMap<String, Stream>>>;

pub struct StreamsAPI {
    store: StreamStore,
}

impl StreamsAPI {
    pub fn new() -> Self {
        let store = Arc::new(RwLock::new(HashMap::new()));
        Self { store }
    }

    // Initialize with some sample data
    pub async fn initialize_sample_data(&self) {
        let mut store = self.store.write().await;

        let welcome_stream = Stream {
            id: "stream-welcome".to_string(),
            stream_id: "stream-welcome".to_string(),
            title: "Welcome to Prehender".to_string(),
            content: "This is your first stream in Prehender! This application helps you manage streams of information, ideas, and knowledge.\n\nKey features:\n- Create and organize streams\n- Add references between streams\n- Multiple viewpoints for different perspectives\n- Real-time synchronization\n\nTry creating a new stream to get started!".to_string(),
            tags: vec!["welcome".to_string(), "tutorial".to_string()],
            created_at: chrono::Utc::now().to_rfc3339(),
            updated_at: chrono::Utc::now().to_rfc3339(),
            references: vec![],
            subscribers: vec![],
            stream_type: "note".to_string(),
            stream_state: "active".to_string(),
            user_id: "local-user".to_string(),
            device_id: "local-device".to_string(),
            version: 1,
            is_deleted: false,
            sync_status: "synced".to_string(),
        };

        store.insert(welcome_stream.id.clone(), welcome_stream);
    }

    // Get all streams
    pub async fn get_streams(&self) -> Vec<Stream> {
        let store = self.store.read().await;
        store.values()
            .filter(|stream| !stream.is_deleted)
            .cloned()
            .collect()
    }

    // Get a specific stream by ID
    pub async fn get_stream(&self, id: &str) -> Option<Stream> {
        let store = self.store.read().await;
        store.get(id)
            .filter(|stream| !stream.is_deleted)
            .cloned()
    }

    // Create a new stream
    pub async fn create_stream(&self, mut stream: Stream) -> Stream {
        let id = format!("stream-{}", chrono::Utc::now().timestamp_millis());
        stream.id = id.clone();
        stream.stream_id = id.clone();
        stream.created_at = chrono::Utc::now().to_rfc3339();
        stream.updated_at = stream.created_at.clone();
        stream.version = 1;
        stream.is_deleted = false;
        stream.sync_status = "pending".to_string();

        let mut store = self.store.write().await;
        store.insert(id, stream.clone());

        // Simulate Kafka event emission
        println!("📨 Emitted stream.created event to Kafka: {}", stream.id);

        stream
    }

    // Update a stream
    pub async fn update_stream(&self, id: &str, updates: StreamUpdate) -> Option<Stream> {
        let mut store = self.store.write().await;

        if let Some(stream) = store.get_mut(id) {
            if updates.title.is_some() {
                stream.title = updates.title.unwrap();
            }
            if updates.content.is_some() {
                stream.content = updates.content.unwrap();
            }
            if updates.tags.is_some() {
                stream.tags = updates.tags.unwrap();
            }

            stream.updated_at = chrono::Utc::now().to_rfc3339();
            stream.version += 1;
            stream.sync_status = "pending".to_string();

            // Simulate Kafka event emission
            println!("📨 Emitted stream.updated event to Kafka: {}", id);

            Some(stream.clone())
        } else {
            None
        }
    }

    // Delete a stream (soft delete)
    pub async fn delete_stream(&self, id: &str) -> bool {
        let mut store = self.store.write().await;

        if let Some(stream) = store.get_mut(id) {
            stream.is_deleted = true;
            stream.updated_at = chrono::Utc::now().to_rfc3339();
            stream.sync_status = "pending".to_string();

            // Simulate Kafka event emission
            println!("📨 Emitted stream.deleted event to Kafka: {}", id);

            true
        } else {
            false
        }
    }

    // Add a reference to a stream
    pub async fn add_reference(&self, stream_id: &str, reference: StreamReference) -> Option<Stream> {
        let mut store = self.store.write().await;

        if let Some(stream) = store.get_mut(stream_id) {
            stream.references.push(reference.clone());
            stream.updated_at = chrono::Utc::now().to_rfc3339();
            stream.version += 1;

            // Simulate Kafka event emission
            println!("📨 Emitted stream.referenced event to Kafka: {} -> {}", stream_id, reference.target_stream_id);

            Some(stream.clone())
        } else {
            None
        }
    }

    // Remove a reference from a stream
    pub async fn remove_reference(&self, stream_id: &str, reference_id: &str) -> Option<Stream> {
        let mut store = self.store.write().await;

        if let Some(stream) = store.get_mut(stream_id) {
            stream.references.retain(|ref_| ref_.id != reference_id);
            stream.updated_at = chrono::Utc::now().to_rfc3339();
            stream.version += 1;

            // Simulate Kafka event emission
            println!("📨 Emitted stream.unreferenced event to Kafka: {} - {}", stream_id, reference_id);

            Some(stream.clone())
        } else {
            None
        }
    }
}

// Update structure for partial updates
#[derive(Debug, Deserialize)]
pub struct StreamUpdate {
    pub title: Option<String>,
    pub content: Option<String>,
    pub tags: Option<Vec<String>>,
}

// Create a global instance of the Streams API
lazy_static::lazy_static! {
    pub static ref STREAMS_API: StreamsAPI = {
        let api = StreamsAPI::new();
        // Initialize sample data in a blocking manner for simplicity
        tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async {
                api.initialize_sample_data().await;
            });
        });
        api
    };
}
