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

  const cmd = new Deno.Command(llamaCliPath, {
    args: [
      "-m",
      modelPath,
      "-p",
      prompt,
      "-n",
      "16", // number of tokens to generate
      "--n-beams",
      "5", // generate 5 alternative sequences
      "--temp",
      "0.7",
    ],
  });

  const { code, stdout, stderr } = await cmd.output();

  if (code !== 0) {
    console.error(new TextDecoder().decode(stderr));
    throw new Error("Failed to run llama.cpp");
  }

  const output = new TextDecoder().decode(stdout);

  // NOTE: This parsing is a simplified example.
  // llama.cpp with beam search outputs multiple sequences. We need to parse them.
  // The actual output format needs to be checked to parse correctly.
  // For this example, we'll simulate the tree structure from the output.

  const lines = output.trim().split("\n").filter((line) => line.trim() !== "");
  const generatedText = lines.join(" ").replace(prompt, "").trim();
  const words = generatedText.split(/\s+/);

  let idCounter = 0;
  function createNode(word: string, x: number, y: number, z: number) {
    return { id: idCounter++, word, x, y, z, children: [] };
  }

  const root = createNode(prompt, 0, 0, 0);
  let currentNode = root;

  for (let i = 0; i < words.length; i++) {
    const word = words[i];
    // Simple linear path for now
    const x = currentNode.x + (Math.random() - 0.5) * 2;
    const y = currentNode.y + (Math.random() - 0.5) * 2;
    const z = currentNode.z - 2;
    const childNode = createNode(word, x, y, z);
    currentNode.children.push(childNode);
    currentNode = childNode;
  }

  return { prompt, tree: root };
}

console.log("Listening on http://localhost:8000");
