output "cluster_name" {
  description = "The name of the GKE cluster."
  value       = google_container_cluster.primary.name
}

output "cluster_endpoint" {
  description = "The endpoint of the GKE cluster."
  value       = google_container_cluster.primary.endpoint
}

output "kubeconfig" {
  description = "The kubeconfig to access the GKE cluster. Use with `kubectl --kubeconfig <file>`"
  sensitive   = true
  value       = <<-EOT
    apiVersion: v1
    clusters:
    - name: ${google_container_cluster.primary.name}
      cluster:
        certificate-authority-data: ${google_container_cluster.primary.master_auth[0].cluster_ca_certificate}
        server: https://${google_container_cluster.primary.endpoint}
    contexts:
    - name: ${google_container_cluster.primary.name}
      context:
        cluster: ${google_container_cluster.primary.name}
        user: ${google_container_cluster.primary.name}
    current-context: ${google_container_cluster.primary.name}
    kind: Config
    users:
    - name: ${google_container_cluster.primary.name}
      user:
        exec:
          apiVersion: client.authentication.k8s.io/v1beta1
          command: gke-gcloud-auth-plugin
          installHint: Install gke-gcloud-auth-plugin for use with kubectl by following
            https://cloud.google.com/blog/products/containers-kubernetes/kubectl-auth-changes-in-gke
          provideClusterInfo: true
  EOT
}
