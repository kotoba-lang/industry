import * as pulumi from "@pulumi/pulumi";
import * as gcp from "@pulumi/gcp";
import * as k8s from "@pulumi/kubernetes";

// Create a GKE cluster
const cluster = new gcp.container.Cluster("temporal-cluster", {
    initialNodeCount: 3,
    location: "asia-northeast1",
    nodeConfig: {
        machineType: "e2-medium",
    },
});

// Export the kubeconfig
const kubeconfig = pulumi.
    all([cluster.name, cluster.endpoint, cluster.masterAuth]).
    apply(([name, endpoint, masterAuth]) => {
        const context = `${gcp.config.project}_${gcp.config.zone}_${name}`;
        return `apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: ${masterAuth.clusterCaCertificate}
    server: https://${endpoint}
  name: ${context}
contexts:
- context:
    cluster: ${context}
    user: ${context}
  name: ${context}
current-context: ${context}
kind: Config
preferences: {}
users:
- name: ${context}
  user:
    auth-provider:
      config:
        cmd-args: config config-helper --format=json
        cmd-path: gcloud
        expiry-key: '{.credential.token_expiry}'
        token-key: '{.credential.access_token}'
      name: gcp
`;
    });

// Create a Kubernetes provider
const k8sProvider = new k8s.Provider("gke-k8s", { kubeconfig });

// Namespaces
const temporalNs = new k8s.core.v1.Namespace("temporal-ns", {}, { provider: k8sProvider });
const dgraphNs = new k8s.core.v1.Namespace("dgraph-ns", {}, { provider: k8sProvider });
const llmNs = new k8s.core.v1.Namespace("llm-ns", {}, { provider: k8sProvider });

// Example Deployment (LLM Activity)
const llmDeployment = new k8s.apps.v1.Deployment("llm-activity", {
    metadata: { namespace: llmNs.metadata.name },
    spec: {
        replicas: 2,
        selector: { matchLabels: { app: "llm-activity" } },
        template: {
            metadata: { labels: { app: "llm-activity" } },
            spec: {
                containers: [{
                    name: "llm",
                    image: "your-repo/llm-activity:latest",
                    ports: [{ containerPort: 8080 }],
                    env: [{
                        name: "OPENAI_API_KEY",
                        valueFrom: { secretKeyRef: { name: "openai-secret", key: "api-key" } },
                    }],
                }],
            },
        },
    },
}, { provider: k8sProvider });

// Exports
export const clusterName = cluster.name;
export const kubeConfig = kubeconfig;
