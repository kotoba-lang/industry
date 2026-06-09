import csv
import json
import os
import sys
import networkx as nx
import matplotlib.pyplot as plt
from networkx.algorithms import community


def read_edge_list_csv(path):
    edges = []
    with open(path, newline='') as f:
        reader = csv.DictReader(f)
        for r in reader:
            u = r['source'].strip()
            v = r['target'].strip()
            try:
                w = float(r.get('weight', 1.0))
            except Exception:
                w = 1.0
            edges.append((u, v, w))
    return edges


def build_graph(edges, directed=True):
    G = nx.DiGraph() if directed else nx.Graph()
    for u, v, w in edges:
        G.add_edge(u, v, weight=w)
    return G


def analyze(G):
    metrics = {}
    metrics['num_nodes'] = G.number_of_nodes()
    metrics['num_edges'] = G.number_of_edges()
    metrics['degree'] = dict(G.degree(weight='weight'))
    metrics['in_degree'] = dict(G.in_degree(weight='weight')) if G.is_directed() else {}
    metrics['out_degree'] = dict(G.out_degree(weight='weight')) if G.is_directed() else {}
    metrics['degree_centrality'] = nx.degree_centrality(G)
    metrics['betweenness'] = nx.betweenness_centrality(G, weight='weight')
    try:
        metrics['closeness'] = nx.closeness_centrality(G)
    except Exception:
        metrics['closeness'] = {}
    try:
        metrics['eigenvector'] = nx.eigenvector_centrality_numpy(G.to_undirected())
    except Exception:
        metrics['eigenvector'] = {}
    return metrics


def detect_communities(G):
    H = G.to_undirected() if G.is_directed() else G
    comms = list(community.greedy_modularity_communities(H, weight='weight'))
    # convert to list of lists
    return [list(c) for c in comms]


def plot_graph(G, path='graph.png'):
    pos = nx.spring_layout(G, seed=42)
    plt.figure(figsize=(10, 8))
    # node sizes by degree
    deg = dict(G.degree())
    sizes = [300 + 300 * deg.get(n, 0) for n in G.nodes()]
    nx.draw_networkx_nodes(G, pos, node_size=sizes, node_color='#ffb3c6')
    nx.draw_networkx_edges(G, pos, arrowstyle='-|>' ,arrowsize=12, edge_color='#3b5bdb')
    nx.draw_networkx_labels(G, pos, font_size=9)
    plt.axis('off')
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()


def save_metrics(metrics, path='metrics.json'):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)


def save_communities(comms, path='communities.json'):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(comms, f, ensure_ascii=False, indent=2)


def main(edge_csv=None):
    edge_csv = edge_csv or os.path.join(os.path.dirname(__file__), 'edges.csv')
    edges = read_edge_list_csv(edge_csv)
    G = build_graph(edges, directed=True)
    metrics = analyze(G)
    comms = detect_communities(G)
    out_dir = os.path.dirname(edge_csv)
    plot_graph(G, os.path.join(out_dir, 'graph.png'))
    save_metrics(metrics, os.path.join(out_dir, 'metrics.json'))
    save_communities(comms, os.path.join(out_dir, 'communities.json'))
    # print short summary
    print(f"Nodes: {metrics['num_nodes']} Edges: {metrics['num_edges']}")
    top_deg = sorted(metrics['degree'].items(), key=lambda x: -x[1])[:10]
    print('Top degree nodes:')
    for n, d in top_deg:
        print(f"  {n}: {d}")
    print(f"Communities found: {len(comms)}")


if __name__ == '__main__':
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    main(arg)
