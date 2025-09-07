const assert = require('assert');

describe('Kawa WASM Module - event_store.rs', () => {
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

    it('should append and get events correctly', async () => {
        const result = await browser.execute(async () => {
            const { KawaBrowserDB, create_default_config, StorageType } = window.wasm;
            const config = create_default_config();
            config.set_storage_type(StorageType.Memory);
            const db = await KawaBrowserDB.createDB(config);
            await db.clear_data();

            await db.add_event("user_created", JSON.stringify({ userId: "user1", name: "Alice" }));
            await db.add_event("user_created", JSON.stringify({ userId: "user2", name: "Bob" }));

            const eventsJson = await db.get_events(null);
            const events = JSON.parse(eventsJson);
            
            return {
                eventCount: events.length,
                firstEventType: events[0] ? events[0].event_type : null,
            };
        });

        assert.strictEqual(result.eventCount, 2);
        assert.strictEqual(result.firstEventType, "user_created");
    });

    it('should reconstruct the current state correctly', async () => {
        const stateJson = await browser.execute(async () => {
            const { KawaBrowserDB, create_default_config, StorageType } = window.wasm;
            const config = create_default_config();
            config.set_storage_type(StorageType.Memory);
            const db = await KawaBrowserDB.createDB(config);
            await db.clear_data();

            await db.add_event(
                "user_created", 
                JSON.stringify({ user_id: "user1", user_data: { name: "Alice" } })
            );
            await db.add_event(
                "user_updated", 
                JSON.stringify({ user_id: "user1", updates: { age: 30 } })
            );
            await db.add_event(
                "data_inserted", 
                JSON.stringify({ collection: "messages", item: { text: "Hello" } })
            );

            return await db.get_current_state();
        });
        
        const state = JSON.parse(stateJson);
        
        assert.ok(state.user_user1, "User state for user1 should exist");
        assert.strictEqual(state.user_user1.user_data.name, "Alice");
        assert.strictEqual(state.user_user1.user_data.age, 30);
        assert.ok(state.collection_messages, "Message collection should exist");
        assert.strictEqual(state.collection_messages[0].text, "Hello");
    });
}); 