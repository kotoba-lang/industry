# ADR-2607081500: CommonCrawl Ingestion to Datom-log with LLM Extraction and Vector Search Integration

## Status
Implemented & Active

## Context
The `net-kotobase-commoncrawler` project processes CommonCrawl WARC and HTML data, extracting structured content via the `murakumo` LLM component (integrated with `cloud-murakumo` API), and persists it to **kotobase.net** as kotoba Datom-log (EAVT) representation.

While the EAVT representation enables symbolic Datalog-style queries via kotoba's `:db-api` (`{:q :transact! :db :pull :entid}`), it does not natively support fast vector search or semantic similarity search (e.g., LLM embedding-based search or Google-style semantic search).

To enable both symbolic EAVT queries and fast vector/semantic search, we integrated embedding generation and vector store capabilities into the `net-kotobase-commoncrawler` architecture, and realized the system as a **kotoba actor with `langgraph-clj StateGraph` durable outer loop**, continuously running as a resident service.

## Decision
1. **EAVT for Symbolic Data**: Continue using `datom.core/entity` and `datom.core/eavt` for structured, symbolic data representation (URL, title, content, links, images, metadata).
2. **LLM Extraction via `cloud-murakumo`**: Use `net-kotobase.commoncrawler.murakumo` for LLM-based content extraction from WARC/HTML to structured EDN, calling the `cloud-murakumo` API (murakumo loopback / WASM lattice fleet endpoint).
3. **Vector Search Integration via `langchain-clj` / `embeddings.cljc`**: Integrate embedding models and vector store capabilities to generate and store text embeddings.
4. **Hybrid EAVT + Vector Store Architecture**:
   - Generate embedding vectors for extracted content using `embeddings.cljc` embedding models.
   - Store embedding vectors in a dedicated vector store or kotobase.net vector index, linking them to the EAVT entity ID (e.g., `"html-1"`).
   - Enable hybrid queries: use vector similarity search to find semantically similar content, then use kotoba `:db-api :pull` to retrieve the full EAVT entity data.
5. **kotoba Actor Durable Outer Loop for Resident Service**:
   - Implement the ingestion pipeline as a **kotoba actor** using `langgraph-clj StateGraph`.
   - Use the durable outer loop pattern (tick / lease / budget / governor / crash recovery) to ensure continuous, state-persistent execution.
   - State is persisted via kotobase.net `:db-api` (`:transact!`) to `:checkpoint/*`, `:agent.loop/*`, `:agent.tick/*`, enabling crash recovery and logical permanent residence on the kotobase.net / murakumo lattice fleet without dedicated VM or `systemd` services.

## Consequences
- **Dual Query Capabilities**: The system supports both symbolic Datalog queries (via `:db-api :q`) and fast semantic similarity search (via vector embeddings).
- **Extended Data Pipeline**: The ingestion pipeline includes an embedding generation step after LLM content extraction.
- **Storage Overhead**: Vector embeddings require additional storage, but are necessary for semantic search capabilities.
- **Integration Complexity**: Requires coordination between `net-kotobase-commoncrawler` (EAVT + murakumo + embeddings), `langchain-clj` (embeddings + vector store), and kotobase.net's vector index or XRPC endpoints.
- **Resident Service Realized**: The persistent worker is now actively running as a kotoba actor durable loop, processing CommonCrawl captures in a continuous tick/lease/budget cycle, with `cloud-murakumo` for inference and kotobase.net for state and data persistence.

## References
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/core.cljc` (EAVT representation)
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/murakumo.cljc` (LLM extraction via cloud-murakumo)
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/embeddings.cljc` (Embedding generation and vector search integration)
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/actor/state.cljc` (kotoba actor State definition)
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/actor/nodes.cljc` (kotoba actor Graph nodes)
- `net-kotobase-commoncrawler/src/net_kotobase/commoncrawler/worker.cljc` (Resident worker entry point)
- `langgraph-clj` (Embedding models and vector store capabilities, StateGraph durable outer loop)
- kotobase.net `:db-api` (`{:q :transact! :db :pull :entid}`)
