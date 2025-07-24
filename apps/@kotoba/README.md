
### Component Breakdown

1.  **Editor Core (ProseMirror)**
    - Manages the document as a structured, editable object based on a defined schema.
    - The schema will include standard nodes (`paragraph`, `heading`, etc.) and a custom `graphView` node.
    - User edits generate transactions, which are the primary trigger for all updates.

2.  **Parsing & Transformation Layer**
    - On each editor transaction, the content of the ProseMirror document is fed into `remark`.
    - It parses the Markdown/MDX into an AST.
    - A custom `unified` plugin traverses the AST to:
        - Identify each document/file as a **node**.
        - Extract all links (`[[wikilinks]]`, `[markdown](links.md)`, etc.) as **edges**.
        - Parse frontmatter or special code blocks (e.g., `mermaid`) for additional metadata.

3.  **Graph Data Abstraction**
    - The extracted data is transformed into a simple, renderer-agnostic format:
      ```typescript
      interface GraphNode {
        id: string; // Unique identifier (e.g., file path)
        label: string;
        // ...other metadata
      }
      interface GraphEdge {
        id: string;
        source: string; // Source Node ID
        target: string; // Target Node ID
      }
      ```
    - This structure decouples the parsing logic from the visualization library.

4.  **Graph Visualization (Cytoscape.js)**
    - Implemented as a ProseMirror `NodeView`. This allows the graph to be treated as an immutable part of the document structure, managed by ProseMirror's state.
    - The `NodeView` receives the abstracted graph data (nodes and edges).
    - It initializes or updates a Cytoscape.js instance within its DOM element.
    - User interactions with the graph (e.g., clicking a node) can dispatch ProseMirror transactions to affect the editor state (e.g., focusing on the corresponding document).

## 4. Implementation Details

### ProseMirror Schema & `GraphView` Node

A custom node will be created to host the Cytoscape canvas.

```typescript
import { Node } from '@tiptap/core';
import cytoscape from 'cytoscape';
import { parseDocumentToGraphData } from './parser'; // To be implemented

export const GraphView = Node.create({
  name: 'graphView',

  // ...schema definition (group, content, etc.)

  addNodeView() {
    return ({ editor, getPos }) => {
      const dom = document.createElement('div');
      dom.style.height = '500px'; // Example styling

      const updateGraph = () => {
        // Get the entire document state from the editor
        const graphData = parseDocumentToGraphData(editor.state.doc);
        // Render or update the Cytoscape graph
        cytoscape({
          container: dom,
          elements: graphData,
          layout: { name: 'cose' },
          // ...other options
        });
      };

      // Initial render
      updateGraph();

      // Subscribe to editor updates
      // This is a simplified view; a real implementation would be more performant
      editor.on('update', updateGraph);

      return {
        dom,
        destroy: () => {
          editor.off('update', updateGraph);
        },
      };
    };
  },
});
```

### Parsing Logic (`parser.ts`)

This module will contain the core logic for converting ProseMirror documents or text into a graph data structure.

-   **Input:** ProseMirror `doc` object or raw Markdown/MDX string.
-   **Processing:**
    1.  Serialize the ProseMirror `doc` to a string.
    2.  Use `unified` with `remark-parse`, `remark-wiki-link`, and a custom plugin.
    3.  The custom plugin will walk the AST, collect all link targets, and build the node/edge list.
-   **Output:** An array of elements compatible with Cytoscape.js.

### Reactivity and Performance

-   Parsing the entire document on every keystroke is inefficient.
-   **Optimization Strategy:** Debounce updates. The parsing and graph re-rendering process will only run after a short period of user inactivity (e.g., 300ms) or when the `graphView` becomes visible.
-   For larger documents, a web worker could be used to offload the parsing process from the main thread, preventing UI freezes.

## 5. Roadmap & Future Enhancements

-   **Bidirectional Linking:** Analyze not just outgoing links but also incoming "backlinks".
-   **Advanced Graph Analysis:** Integrate more Cytoscape.js extensions for centrality analysis, cycle detection, etc., and display the results.
-   **Interactive MDX:** Allow MDX components within the editor to query and display data from the graph.
-   **Persistence:** Integrate with a backend or local storage (e.g., IndexedDB) to save and load documents.
-   **Mermaid Integration:** Support rendering of `mermaid` code blocks as inline diagrams, treating them as part of the overall graph.