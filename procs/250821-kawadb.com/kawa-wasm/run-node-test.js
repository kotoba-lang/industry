const assert = require('assert');
const kawa = require('./pkg/kawa_wasm.js');

async function runTests() {
    console.log("Running kawa-wasm Node.js tests...");

    // Test version()
    try {
        const ver = kawa.version();
        assert(ver.includes("browser"), "version() test failed");
        console.log("✅ version() passed");
    } catch (e) {
        console.error("❌ version() failed:", e);
    }

    // Test config_creation()
    try {
        const config = kawa.create_default_config();
        // In node, we can't check storage type, but we can check if it exists.
        assert(config !== null, "config_creation() test failed");
        console.log("✅ config_creation() passed");
    } catch (e) {
        console.error("❌ config_creation() failed:", e);
    }

    // Test event_creation()
    try {
        const event = kawa.create_sample_event();
        assert.strictEqual(event.event_type, "user_action", "event_creation() event_type test failed");
        assert(event.data.includes("login"), "event_creation() data test failed");
        console.log("✅ event_creation() passed");
    } catch (e) {
        console.error("❌ event_creation() failed:", e);
    }

    // Test storage operations
    try {
        const storage = new kawa.WasmStorage(kawa.StorageType.Memory);
        storage.clear(); // Clear from previous tests

        const key = "my_key";
        const value = "my_value";

        assert.strictEqual(storage.set_item(key, value), true, "set_item should return true");
        assert.strictEqual(storage.get_item(key), value, "get_item should return the value");
        
        const keys = storage.keys();
        assert.strictEqual(keys.length, 1, "keys() should return one key");
        assert.strictEqual(keys[0], key, "the key should be correct");

        assert.strictEqual(storage.remove_item(key), true, "remove_item should return true");
        assert.strictEqual(storage.get_item(key), undefined, "get_item should return undefined after removal");
        
        console.log("✅ storage operations passed");
    } catch (e) {
        console.error("❌ storage operations failed:", e);
    }

    // Test event_store operations
    try {
        const config = new kawa.BrowserDBConfig();
        config.set_storage_type(kawa.StorageType.Memory);
        const db = await kawa.KawaBrowserDB.createDB(config);
        await db.clear_data();

        const eventId1 = await db.add_event("user_created", JSON.stringify({ name: "Alice" }));
        assert(eventId1.startsWith("event_"), "add_event should return an event ID");

        const eventsJson = await db.get_events(10);
        const events = JSON.parse(eventsJson);
        assert.strictEqual(events.length, 1, "get_events should return one event");
        assert.strictEqual(events[0].event_type, "user_created", "event_type should be correct");

        console.log("✅ event_store operations passed");
    } catch (e) {
        console.error("❌ event_store operations failed:", e);
    }

    // Test database_engine operations
    try {
        const config = new kawa.DbConfig();
        const engine = new kawa.DatabaseEngine(config);
        const result = engine.executeQuery("SELECT * FROM users");
        assert.strictEqual(result.success, true, "executeQuery should succeed");
        assert(result.data.includes("SELECT * FROM users"), "executeQuery should return query string");
        console.log("✅ database_engine operations passed");
    } catch (e) {
        console.error("❌ database_engine operations failed:", e);
    }

    // Test ksql_engine operations
    try {
        const engine = new kawa.WasmKsqlEngine();
        const createResult = engine.execute_ksql("CREATE STREAM my_stream (id INT, name VARCHAR) WITH (KAFKA_TOPIC='users', VALUE_FORMAT='JSON');");
        assert.strictEqual(createResult.success, true, "CREATE STREAM should succeed");
        assert(createResult.query_id !== null, "CREATE STREAM should return a query ID");

        const showResult = engine.execute_ksql("SHOW STREAMS;");
        assert.strictEqual(showResult.success, true, "SHOW STREAMS should succeed");
        assert(showResult.data.includes("my_stream"), "SHOW STREAMS should list the new stream");

        console.log("✅ ksql_engine operations passed");
    } catch (e) {
        console.error("❌ ksql_engine operations failed:", e);
    }

    // Test query_parser operations
    try {
        const parser = new kawa.QueryParser();
        assert.strictEqual(parser.parse_query_type("SELECT * FROM users"), kawa.QueryType.Select, "parse_query_type should detect SELECT");
        assert.strictEqual(parser.validate_sql("DROP DATABASE test"), false, "validate_sql should detect dangerous queries");
        assert.strictEqual(parser.extract_table_name("SELECT * FROM users"), "users", "extract_table_name should find table name");

        console.log("✅ query_parser operations passed");
    } catch (e) {
        console.error("❌ query_parser operations failed:", e);
    }
    
    console.log("All tests completed.");
}

runTests().catch(e => {
    console.error("An unexpected error occurred:", e);
    process.exit(1);
}); 