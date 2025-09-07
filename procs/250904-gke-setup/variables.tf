variable "project_id" {
  description = "The GCP project ID to deploy to."
  type        = string
  default     = "jun784"
}

variable "region" {
  description = "The GCP region to deploy to."
  type        = string
  default     = "asia-northeast1"
}

variable "cluster_name" {
  description = "The name for the GKE cluster."
  type        = string
  default     = "temporal-dgraph-cluster"
}
