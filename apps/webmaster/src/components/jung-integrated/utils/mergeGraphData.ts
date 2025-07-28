import { type GraphData } from "@/components/kawasaki-model/utils/generateGraphData";

/**
 * Merges multiple GraphData objects into a single combined visualization.
 * Useful for showing relationships between different types of assessments.
 */
export function mergeGraphData(...graphDataSources: GraphData[]): GraphData {
  // Initialize with empty arrays
  const result: GraphData = {
    nodes: [],
    links: []
  };
  
  // Simple merge of all nodes and links
  graphDataSources.forEach(source => {
    if (source && source.nodes && source.links) {
      result.nodes.push(...source.nodes);
      result.links.push(...source.links);
    }
  });
  
  // Optional: Create connections between different assessment fields
  const fieldNodes = result.nodes.filter(node => 
    node.id === "field" || node.id === "voice_field"
  );
  
  // If we have multiple field nodes, connect them
  if (fieldNodes.length > 1) {
    for (let i = 0; i < fieldNodes.length; i++) {
      for (let j = i + 1; j < fieldNodes.length; j++) {
        result.links.push({
          source: fieldNodes[i].id,
          target: fieldNodes[j].id,
          strength: 1.0,
          name: `${fieldNodes[i].name} ↔ ${fieldNodes[j].name} Connection`
        });
      }
    }
  }
  
  // Connect identical words from different assessments
  const wordMap = new Map<string, string[]>();
  
  // Group node IDs by the word they represent
  result.nodes.forEach(node => {
    const nodeId = node.id;
    // Extract the word part after the prefix (word_ or voice_)
    if (nodeId.startsWith("word_") || nodeId.startsWith("voice_")) {
      const word = nodeId.includes("_") ? nodeId.split("_")[1] : nodeId;
      
      if (!wordMap.has(word)) {
        wordMap.set(word, []);
      }
      wordMap.get(word)?.push(nodeId);
    }
  });
  
  // Connect nodes representing the same word across different assessments
  wordMap.forEach((nodeIds) => {
    if (nodeIds.length > 1) {
      // Different node types for the same word, connect them
      for (let i = 0; i < nodeIds.length; i++) {
        for (let j = i + 1; j < nodeIds.length; j++) {
          // Only connect if the prefixes are different (word_ vs voice_)
          const prefix1 = nodeIds[i].split("_")[0];
          const prefix2 = nodeIds[j].split("_")[0];
          
          if (prefix1 !== prefix2) {
            result.links.push({
              source: nodeIds[i],
              target: nodeIds[j],
              strength: 0.7, // Medium strength for cross-assessment connections
              name: `Cross-Assessment: ${nodeIds[i]} ↔ ${nodeIds[j]}`
            });
          }
        }
      }
    }
  });
  
  return result;
} 