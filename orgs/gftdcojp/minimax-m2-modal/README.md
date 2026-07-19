# minimax-m2-modal

This directory is now Clojure plus Kotoba EDN. The former Python scripts were
Modal SDK entrypoints and OpenAI client helpers; their model/runtime shape is
captured in `resources/kotoba.edn`, while the runnable client and bench logic is
under `src/minimax_m2_modal`.

Common commands:

```sh
clj -M:kotoba models
clj -M:kotoba vllm minimax-m27
LLM_URL=https://example.modal.run LLM_MODEL=MiniMaxAI/MiniMax-M2.7 LLM_KEY=... clj -M:client chat
LLM_URL=https://example.modal.run LLM_MODEL=MiniMaxAI/MiniMax-M2.7 LLM_KEY=... clj -M:bench
```

Modal deployment is intentionally data-first here: the vLLM command, GPU shape,
volume name, parser choices, and API key env are EDN values instead of Python
decorators.
