import { serve } from "https://deno.land/std@0.155.0/http/server.ts";
import { serveDir } from "https://deno.land/std@0.155.0/http/file_server.ts";

serve(async (req) => {
  const url = new URL(req.url);
  if (url.pathname.startsWith("/api/completions")) {
    const prompt = url.searchParams.get("prompt") || "Intelligence is";
    const completions = generateDummyCompletions(prompt, 3, 5, 3);
    return new Response(JSON.stringify(completions), {
      headers: { "Content-Type": "application/json" },
    });
  }

  return serveDir(req, {
    fsRoot: "public",
    urlRoot: "",
    showDirListing: true,
    enableCors: true,
  });
});

function generateDummyCompletions(prompt: string, depth: number, branches: number, seed: number) {
  const words = ["the", "a", "is", "in", "to", "and", "of", "that", "it", "for", "with", "as", "was", "on", "at", "by", "an"];
  let idCounter = 0;
  
  function randomWord() {
    return words[Math.floor(Math.random() * words.length)];
  }

  function createNode(word: string, x: number, y: number, z: number) {
    return { id: idCounter++, word, x, y, z, children: [] };
  }

  function generateChildren(node, currentDepth) {
    if (currentDepth >= depth) return;

    for (let i = 0; i < branches; i++) {
      const word = randomWord();
      const x = node.x + (Math.random() - 0.5) * 5;
      const y = node.y + (Math.random() - 0.5) * 5;
      const z = node.z - 5; // Move forward in z-axis
      const childNode = createNode(word, x, y, z);
      node.children.push(childNode);
      generateChildren(childNode, currentDepth + 1);
    }
  }

  const root = createNode(prompt, 0, 0, 0);
  generateChildren(root, 0);

  return { prompt, tree: root };
}


console.log("Listening on http://localhost:8000");
