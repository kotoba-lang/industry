const assert = require('assert');

describe('Kawa WASM Module - storage.rs', () => {
    before(async () => {
        await browser.url('http://localhost:8080/test/index.html');
        await browser.waitUntil(
            async () => (await browser.execute(() => document.body.getAttribute('data-wasm-loaded'))) === 'true',
            {
                timeout: 10000,
                timeoutMsg: 'WASM module did not load in time'
            }
        );
    });

    describe('Memory Storage', () => {
        it('should perform basic CRUD operations', async () => {
            const result = await browser.execute(() => {
                const { WasmStorage, StorageType } = window.wasm;
                const storage = new WasmStorage(StorageType.Memory);
                storage.clear();

                const key = "mem_key";
                const value = "mem_value";

                storage.set_item(key, value);
                const retrieved = storage.get_item(key);
                const hasKey = storage.keys().includes(key);
                storage.remove_item(key);
                const removed = storage.get_item(key);

                return {
                    retrieved,
                    hasKey,
                    removed,
                    keys_after_remove: storage.keys(),
                };
            });

            assert.strictEqual(result.retrieved, "mem_value");
            assert.strictEqual(result.hasKey, true);
            assert.strictEqual(result.removed, undefined);
            assert.strictEqual(result.keys_after_remove.length, 0);
        });
    });

    describe('Local Storage', () => {
        beforeEach(async () => {
            // Clear local storage before each test
            await browser.execute(() => window.localStorage.clear());
        });

        it('should perform basic CRUD operations', async () => {
            const key = "local_key";
            const value = "local_value";

            // Set item using Wasm
            await browser.execute((k, v) => {
                const { WasmStorage, StorageType } = window.wasm;
                const storage = new WasmStorage(StorageType.LocalStorage);
                storage.set_item(k, v);
            }, key, value);

            // Get item directly from browser's localStorage to verify
            const retrievedDirectly = await browser.execute((k) => window.localStorage.getItem(k), key);
            assert.strictEqual(retrievedDirectly, value, "Value should be set in localStorage");
            
            // Get item using Wasm
            const retrievedWasm = await browser.execute((k) => {
                 const { WasmStorage, StorageType } = window.wasm;
                const storage = new WasmStorage(StorageType.LocalStorage);
                return storage.get_item(k);
            }, key);
            assert.strictEqual(retrievedWasm, value, "Wasm get_item should retrieve the value");

            // Remove item using Wasm
            await browser.execute((k) => {
                 const { WasmStorage, StorageType } = window.wasm;
                const storage = new WasmStorage(StorageType.LocalStorage);
                storage.remove_item(k);
            }, key);

            // Verify item is removed
            const retrievedAfterRemove = await browser.execute((k) => window.localStorage.getItem(k), key);
            assert.strictEqual(retrievedAfterRemove, null, "Value should be removed from localStorage");
        });
    });
}); 