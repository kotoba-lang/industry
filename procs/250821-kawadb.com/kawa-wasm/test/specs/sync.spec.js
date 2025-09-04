const assert = require('assert');
const { mock } = require('@wdio/globals');

describe('Kawa WASM Module - sync.rs', () => {
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
    
    it('should upload events to the mock server', async () => {
        const MOCK_ENDPOINT = 'http://localhost:8081';
        const uploadMock = await browser.mock(`${MOCK_ENDPOINT}/upload`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        });

        const uploadResult = await browser.execute(async (endpoint) => {
            const { KawaBrowserDB, create_default_config, StorageType } = window.wasm;
            const config = create_default_config();
            config.set_storage_type(StorageType.Memory);
            config.enable_sync(true);
            config.set_sync_endpoint(endpoint);

            const db = await KawaBrowserDB.createDB(config);
            await db.add_event("local_event", JSON.stringify({ data: "some_data" }));
            
            return await db.sync_to_cloud();
        }, MOCK_ENDPOINT);
        
        assert.strictEqual(uploadResult, true, "Sync to cloud should return true on success");
        
        expect(uploadMock).toBeRequested();
        const requestBody = JSON.parse(uploadMock.calls[0].body);
        assert.strictEqual(requestBody.events.length, 1);
        assert.strictEqual(requestBody.events[0].event_type, "local_event");
    });

    it('should download events from the mock server', async () => {
        const MOCK_ENDPOINT = 'http://localhost:8081';
        const downloadMock = await browser.mock(`${MOCK_ENDPOINT}/download`);
        downloadMock.respondOnce({
            events: [{
                id: 'remote_event_1',
                event_type: 'user_created',
                data: JSON.stringify({ userId: 'remote_user' }),
                timestamp: Date.now(),
                metadata: {},
            }],
            server_timestamp: Date.now(),
        });
        
        const downloadResult = await browser.execute(async (endpoint) => {
            const { KawaBrowserDB, create_default_config, StorageType } = window.wasm;
            const config = create_default_config();
            config.set_storage_type(StorageType.Memory);
            config.enable_sync(true);
            config.set_sync_endpoint(endpoint);

            const db = await KawaBrowserDB.createDB(config);
            await db.sync_from_cloud();
            
            const eventsJson = await db.get_events(null);
            return JSON.parse(eventsJson);
        }, MOCK_ENDPOINT);
        
        assert.strictEqual(downloadResult.length, 1, "Should have downloaded one event");
        assert.strictEqual(downloadResult[0].id, "remote_event_1");
    });
}); 