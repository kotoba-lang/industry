import { serve } from "https://deno.land/std@0.155.0/http/server.ts";
import { serveDir } from "https://deno.land/std@0.155.0/http/file_server.ts";

serve(async (req) => {
  const url = new URL(req.url);

  // API endpoint for session data processing
  if (url.pathname === "/api/spirit-data") {
    try {
      // Load session data from the analytics project
      const sessionPath = "/Users/junkawasaki/jun784/root/procs/250904-SIP-analytics/data/2a0d7a69-f953-4c29-87a5-8a8e4e8bd413/session_data.json";
      const sessionData = JSON.parse(await Deno.readTextFile(sessionPath));

      // Process the session data to extract Spirit vectors
      const spiritVectors = processSessionData(sessionData);

      return new Response(JSON.stringify(spiritVectors), {
        headers: { "Content-Type": "application/json" },
      });
    } catch (error) {
      console.error("Error processing session data:", error);
      return new Response(JSON.stringify({ error: error.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }
  }

  return serveDir(req, {
    fsRoot: "public",
    urlRoot: "",
    showDirListing: true,
    enableCors: true,
  });
});

function processSessionData(sessionData: any) {
  const events = sessionData.events;
  const wordAssociations = new Map();
  const reactionTimes = new Map();

  // Extract word pairs and reaction times
  let currentWord = null;
  let displayTime = null;

  for (const event of events) {
    if (event.type === "word_displayed") {
      currentWord = event.payload.word;
      displayTime = event.timestamp;
    } else if (event.type === "speech_detected" && currentWord) {
      const reactionTime = event.timestamp - displayTime;
      const responseWord = event.payload.word || "unknown";

      // Store reaction time
      if (!reactionTimes.has(currentWord)) {
        reactionTimes.set(currentWord, []);
      }
      reactionTimes.get(currentWord).push(reactionTime);

      // Store association
      const key = `${currentWord}->${responseWord}`;
      wordAssociations.set(key, (wordAssociations.get(key) || 0) + 1);

      currentWord = null;
      displayTime = null;
    }
  }

  // Generate Spirit vectors based on the model
  const words = Array.from(new Set([
    ...Array.from(reactionTimes.keys()),
    ...Array.from(wordAssociations.keys()).flatMap(k => k.split('->'))
  ]));

  const vectors = words.map(word => {
    const times = reactionTimes.get(word) || [];
    const avgReactionTime = times.length > 0 ? times.reduce((a, b) => a + b, 0) / times.length : 1000;

    // Calculate Spirit vector components based on Kawasaki Model
    const energy = -Math.log(avgReactionTime / 1000 + 0.001); // E = -ln P
    const associations = Array.from(wordAssociations.entries())
      .filter(([key]) => key.startsWith(`${word}->`))
      .length;

    return {
      word,
      vector: [
        Math.random() * 2 - 1, // x: semantic similarity proxy
        energy, // y: energy component
        associations * 0.1 // z: association strength
      ],
      reactionTime: avgReactionTime,
      associationCount: associations
    };
  });

  return { vectors, associations: Array.from(wordAssociations.entries()) };
}

console.log("Spirit in Physics 3D Visualizer running on http://localhost:8000");
