//! # GraphSON v3 Serialization
//!
//! Provides serialization and deserialization for the GraphSON v3 format,
//! compatible with Apache TinkerPop.

use serde::{Serialize, Deserialize};
use serde_json::Value;
use std::collections::HashMap;
use crate::error::SerializationError;
// Assuming a general Message trait exists that can be converted to a graph element
// pub use crate::message::Message;

/// Represents a GraphSON v3 typed value.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct TypedValue {
    #[serde(rename = "@type")]
    pub type_name: String,
    #[serde(rename = "@value")]
    pub value: Value,
}

/// Represents a GraphSON v3 Vertex.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct Vertex {
    #[serde(rename = "@type")]
    type_name: String,
    #[serde(rename = "@value")]
    value: VertexValue,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
struct VertexValue {
    id: TypedValue,
    label: String,
    properties: HashMap<String, Vec<VertexProperty>>,
}

/// Represents a GraphSON v3 VertexProperty.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct VertexProperty {
    #[serde(rename = "@type")]
    type_name: String,
    #[serde(rename = "@value")]
    value: VertexPropertyValue,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
struct VertexPropertyValue {
    id: TypedValue,
    value: Value,
    label: String,
}

/// Represents a GraphSON v3 Edge.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct Edge {
    #[serde(rename = "@type")]
    type_name: String,
    #[serde(rename = "@value")]
    value: EdgeValue,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
struct EdgeValue {
    id: TypedValue,
    label: String,
    #[serde(rename = "inV")]
    in_v: TypedValue,
    #[serde(rename = "inVLabel")]
    in_v_label: String,
    #[serde(rename = "outV")]
    out_v: TypedValue,
    #[serde(rename = "outVLabel")]
    out_v_label: String,
    properties: HashMap<String, Value>,
}

pub struct GraphSONv3Serializer;

impl GraphSONv3Serializer {
    pub fn new() -> Self {
        GraphSONv3Serializer
    }

    /// Serializes a generic message into a GraphSON Vertex.
    pub fn serialize_to_vertex(id: &str, label: &str, data: &HashMap<String, Value>) -> Result<Vec<u8>, SerializationError> {
        let mut properties = HashMap::new();
        for (k, v) in data {
            let vp = VertexProperty {
                type_name: "g:VertexProperty".to_string(),
                value: VertexPropertyValue {
                    id: Self::value_to_typed_value(&Value::String(uuid::Uuid::new_v4().to_string())),
                    value: v.clone(),
                    label: k.clone(),
                },
            };
            properties.insert(k.clone(), vec![vp]);
        }

        let vertex = Vertex {
            type_name: "g:Vertex".to_string(),
            value: VertexValue {
                id: Self::value_to_typed_value(&Value::String(id.to_string())),
                label: label.to_string(),
                properties,
            },
        };

        serde_json::to_vec(&vertex).map_err(SerializationError::from)
    }

    /// Converts a serde_json::Value to a GraphSON TypedValue.
    fn value_to_typed_value(value: &Value) -> TypedValue {
        match value {
            Value::String(s) => TypedValue {
                type_name: "g:String".to_string(),
                value: Value::String(s.clone()),
            },
            Value::Number(n) if n.is_i64() => TypedValue {
                type_name: "g:Int64".to_string(),
                value: Value::Number(n.clone()),
            },
            Value::Number(n) if n.is_f64() => TypedValue {
                type_name: "g:Double".to_string(),
                value: Value::Number(n.clone()),
            },
            Value::Bool(b) => TypedValue {
                type_name: "g:Boolean".to_string(),
                value: Value::Bool(*b),
            },
            // Add other type mappings as needed
            _ => TypedValue { // Default to string representation
                type_name: "g:String".to_string(),
                value: Value::String(value.to_string()),
            }
        }
    }
} 