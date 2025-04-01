#!/usr/bin/env node

const { execSync } = require('child_process');
const path = require('path');

// Define colors for terminal output
const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  green: '\x1b[32m',
  cyan: '\x1b[36m',
  yellow: '\x1b[33m',
  red: '\x1b[31m'
};

console.log(`${colors.bright}${colors.cyan}Jung Word Assessment Browser Test Runner${colors.reset}\n`);

try {
  // Check if the dev server is running
  console.log(`${colors.yellow}Checking if development server is running...${colors.reset}`);
  
  try {
    // Just a simple fetch to check if the server responds
    execSync('curl -s http://localhost:3000 > /dev/null');
    console.log(`${colors.green}✓ Development server is running${colors.reset}`);
  } catch (error) {
    console.log(`${colors.yellow}! Development server is not running. Starting it...${colors.reset}`);
    
    // Start the development server in the background
    const devServer = execSync('pnpm dev > /dev/null 2>&1 &');
    
    // Wait for server to start up
    console.log(`${colors.yellow}Waiting for server to start...${colors.reset}`);
    execSync('sleep 5');
  }
  
  // Run the Playwright test
  console.log(`${colors.bright}${colors.yellow}Running Jung Word Assessment browser tests...${colors.reset}`);
  
  const testPath = path.join('src', 'components', 'jung-word-assessment', 'JungWordTest.e2e.test.ts');
  const command = `npx playwright test ${testPath} --headed`;
  
  console.log(`${colors.yellow}Executing: ${command}${colors.reset}`);
  execSync(command, { stdio: 'inherit' });
  
  console.log(`\n${colors.bright}${colors.green}✓ Browser tests completed${colors.reset}`);
  
} catch (error) {
  console.error(`\n${colors.bright}${colors.red}✗ Error running tests:${colors.reset}`, error.message);
  process.exit(1);
} 