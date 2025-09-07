const assert = require('assert');

describe('Kawa WASM Module - lib.rs', () => {
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

    it('should return correct version string', async () => {
        const version = await browser.execute(() => window.wasm.version());
        assert.ok(version.includes('-browser'), 'Version string should contain "-browser"');
    });

    it('should create a default config object', async () => {
        const config = await browser.execute(() => window.wasm.create_default_config());
        // We can't easily inspect the complex object, but we can check it exists
        assert.ok(config, 'Default config object should be created');
    });

    it('should create a sample event', async () => {
        const eventDetails = await browser.execute(() => {
            const event = window.wasm.create_sample_event();
            // Accessing as properties, not methods
            const eventType = event.event_type;
            const eventData = event.data;
            return [eventType, eventData];
        });
        
        const [eventType, eventData] = eventDetails;
        
        assert.strictEqual(eventType, 'user_action');
        assert.ok(eventData.includes('login'));
    });

    it('should create a new KawaBrowserDB instance', async () => {
        const result = await browser.execute(async () => {
            try {
                const config = window.wasm.create_default_config();
                const db = await window.wasm.KawaBrowserDB.createDB(config);
                // Can't return the db instance itself, so return a success flag
                return db !== null && db !== undefined;
            } catch (e) {
                return e.toString();
            }
        });

        assert.strictEqual(result, true, `DB creation should succeed, but got: ${result}`);
    });
}); 