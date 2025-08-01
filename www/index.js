async function main() {
    const wasm = await import("../crates/kotoba-wasm-frontend/pkg");

    const result = wasm.greet("World");
    console.log(result);
    document.getElementById("log").textContent = result;
}

main();