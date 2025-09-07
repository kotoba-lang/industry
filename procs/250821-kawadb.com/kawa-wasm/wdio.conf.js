const path = require('path');
const { spawn, execSync } = require('child_process');

let serveProcess;

exports.config = {
    runner: 'local',
    specs: [
        './test/specs/**/*.js'
    ],
    exclude: [],
    maxInstances: 10,
    capabilities: [{
        maxInstances: 5,
        browserName: 'chrome',
        'goog:chromeOptions': {
            args: ['--headless', '--disable-gpu', '--no-sandbox']
        },
    }, {
        maxInstances: 5,
        browserName: 'firefox',
        'moz:firefoxOptions': {
            args: ['-headless']
        }
    }],
    logLevel: 'info',
    bail: 0,
    baseUrl: 'http://localhost',
    waitforTimeout: 10000,
    connectionRetryTimeout: 120000,
    connectionRetryCount: 3,
    services: [],
    framework: 'mocha',
    reporters: ['spec'],
    mochaOpts: {
        ui: 'bdd',
        timeout: 60000
    },

    async onPrepare(config, capabilities) {
        console.log('Building Wasm package...');
        try {
            execSync('wasm-pack build --dev --target web', { stdio: 'inherit' });
        } catch (e) {
            console.error('Wasm build failed:', e);
            process.exit(1);
        }

        console.log('Starting static server...');
        const serverPath = path.resolve(__dirname, 'node_modules/.bin/serve');
        const rootPath = path.resolve(__dirname); // Serve from the project root
        serveProcess = spawn(serverPath, [rootPath, '-l', '8080'], { stdio: 'inherit' });
        
        // Give server time to start
        await new Promise(resolve => setTimeout(resolve, 3000));
    },

    async onComplete(exitCode, config, capabilities, results) {
        console.log('Stopping static server...');
        if (serveProcess) {
            serveProcess.kill();
        }
    }
} 