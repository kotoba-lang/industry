//! # Graph Traversal Engine
//!
//! Provides a Gremlin-like traversal engine for KawaDB.
//! This allows for graph-based querying of event-sourced data.

use serde_json::Value;
use std::collections::HashMap;

/// Represents a node (Vertex) in the graph projection.
#[derive(Debug, Clone)]
pub struct GraphVertex {
    pub id: String,
    pub label: String,
    pub properties: HashMap<String, Value>,
}

/// Represents a relationship (Edge) in the graph projection.
#[derive(Debug, Clone)]
pub struct GraphEdge {
    pub id: String,
    pub label: String,
    pub out_v: String,
    pub in_v: String,
    pub properties: HashMap<String, Value>,
}

/// Represents the entire projected graph.
#[derive(Debug, Clone)]
pub struct ProjectedGraph {
    pub vertices: HashMap<String, GraphVertex>,
    pub edges: HashMap<String, GraphEdge>,
}

impl ProjectedGraph {
    pub fn new() -> Self {
        ProjectedGraph {
            vertices: HashMap::new(),
            edges: HashMap::new(),
        }
    }
}

/// A Traverser holds the state of a traversal through the graph.
pub struct Traverser<'a> {
    graph: &'a ProjectedGraph,
    current_vertices: Vec<&'a GraphVertex>,
    // In a real implementation, this would be more complex,
    // holding paths, sacks, etc.
}

impl<'a> Traverser<'a> {
    pub fn new(graph: &'a ProjectedGraph) -> Self {
        Traverser {
            graph,
            current_vertices: graph.vertices.values().collect(),
        }
    }

    #[allow(non_snake_case)]
    pub fn V(&mut self) -> &mut Self {
        self.current_vertices = self.graph.vertices.values().collect();
        self
    }

    /// Moves to the adjacent vertices connected by outgoing edges with the given label.
    pub fn out(&mut self, edge_label: &str) -> &mut Self {
        let mut next_vertices = Vec::new();
        for vertex in &self.current_vertices {
            for edge in self.graph.edges.values() {
                if edge.out_v == vertex.id && edge.label == edge_label {
                    if let Some(in_vertex) = self.graph.vertices.get(&edge.in_v) {
                        next_vertices.push(in_vertex);
                    }
                }
            }
        }
        self.current_vertices = next_vertices;
        self
    }

    /// Filters the current vertices, keeping only those with a specific property value.
    pub fn has(&mut self, key: &str, value: &Value) -> &mut Self {
        self.current_vertices.retain(|v| {
            v.properties.get(key).map_or(false, |prop_val| prop_val == value)
        });
        self
    }

    /// Terminates the traversal and collects the property values of the current vertices.
    pub fn values(&self, key: &str) -> Vec<Value> {
        self.current_vertices
            .iter()
            .filter_map(|v| v.properties.get(key).cloned())
            .collect()
    }
} 