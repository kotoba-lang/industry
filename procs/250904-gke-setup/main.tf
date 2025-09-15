# --- GCP Provider Configuration ---
provider "google" {
  project = var.project_id
  region  = var.region
}

# --- GKE Cluster ---
resource "google_container_cluster" "primary" {
  name     = var.cluster_name
  location = var.region

  # Start with a small cluster to avoid quota issues
  initial_node_count = 1
  remove_default_node_pool = true

  # Networking
  networking_mode = "VPC_NATIVE"
}

resource "google_container_node_pool" "primary_nodes" {
  name       = "default-node-pool"
  cluster    = google_container_cluster.primary.name
  location   = google_container_cluster.primary.location
  node_count = 1

  node_config {
    machine_type = "e2-medium"
    disk_size_gb = 30
    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform"
    ]
  }
}


# --- Kubernetes Provider Configuration ---
# The Kubernetes provider uses the GKE cluster credentials
data "google_client_config" "default" {}

provider "kubernetes" {
  host                   = "https://${google_container_cluster.primary.endpoint}"
  token                  = data.google_client_config.default.access_token
  cluster_ca_certificate = base64decode(google_container_cluster.primary.master_auth[0].cluster_ca_certificate)
}

# --- Kubernetes Resources ---
resource "kubernetes_namespace" "temporal_ns" {
  metadata {
    name = "temporal-ns"
  }
  depends_on = [google_container_node_pool.primary_nodes]
}

resource "kubernetes_namespace" "dgraph_ns" {
  metadata {
    name = "dgraph-ns"
  }
  depends_on = [google_container_node_pool.primary_nodes]
}

resource "kubernetes_namespace" "llm_ns" {
  metadata {
    name = "llm-ns"
  }
  depends_on = [google_container_node_pool.primary_nodes]
}

resource "kubernetes_deployment" "llm_activity" {
  metadata {
    name      = "llm-activity"
    namespace = kubernetes_namespace.llm_ns.metadata[0].name
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "llm-activity"
      }
    }

    template {
      metadata {
        labels = {
          app = "llm-activity"
        }
      }

      spec {
        container {
          image = "gcr.io/google-samples/hello-app:1.0"
          name  = "llm-activity-container"
          
          port {
            container_port = 8080
          }
        }
      }
    }
  }
  depends_on = [kubernetes_namespace.llm_ns]
}
