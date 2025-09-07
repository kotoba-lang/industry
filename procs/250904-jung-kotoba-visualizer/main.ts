import { serve } from "https://deno.land/std@0.155.0/http/server.ts";
import { serveDir } from "https://deno.land/std@0.155.0/http/file_server.ts";

serve(async (req) => {
  const url = new URL(req.url);
  if (url.pathname.startsWith("/api/completions")) {
    const prompt = url.searchParams.get("prompt") || "Intelligence is";
    const completions = await generateLlamaCompletions(prompt);
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

async function generateLlamaCompletions(prompt: string) {
  const llamaPath = "./llama.cpp";
  const modelPath = `${llamaPath}/models/tinyllama-1.1b-chat-v1.0.Q2_K.gguf`;
  const llamaCliPath = `${llamaPath}/build/bin/llama-cli`;

  const N_GENERATIONS = 5;
  const tokensToGenerate = "16";

  const generationPromises = [];
  for (let i = 0; i < N_GENERATIONS; i++) {
    const cmd = new Deno.Command(llamaCliPath, {
      args: [
        "-m",
        modelPath,
        "-p",
        prompt,
        "-n",
        tokensToGenerate,
        "--temp",
        "0.9",
        "-s",
        Math.floor(Math.random() * 1000).toString(), // random seed
      ],
    });
    generationPromises.push(cmd.output());
  }

  const results = await Promise.all(generationPromises);

  let idCounter = 0;
  function createNode(word: string, x: number, y: number, z: number) {
    return { id: idCounter++, word, x, y, z, children: [] };
  }
  const root = createNode(prompt, 0, 0, 0);

  for (const result of results) {
    if (result.code !== 0) {
      console.error(new TextDecoder().decode(result.stderr));
      continue; // Skip failed generations
    }
    const output = new TextDecoder().decode(result.stdout);
    const lines = output.trim().split("\n").filter((line) =>
      line.trim() !== "" && !line.startsWith("Log ")
    );
    const generatedText = lines.join(" ").replace(prompt, "").trim();
    const words = generatedText.split(/\s+/).filter((w) => w);

    let currentNode = root;
    for (let i = 0; i < words.length; i++) {
      const word = words[i];
      const z = currentNode.z - 2;

      // Check if a child with the same word already exists
      let childNode = currentNode.children.find((c) =>
        c.word === word && c.z === z
      );

      if (!childNode) {
        const x = currentNode.x + (Math.random() - 0.5) * 5;
        const y = currentNode.y + (Math.random() - 0.5) * 5;
        childNode = createNode(word, x, y, z);
        currentNode.children.push(childNode);
      }
      currentNode = childNode;
    }
  }

  return { prompt, tree: root };
}

console.log("Listening on http://localhost:8000");
