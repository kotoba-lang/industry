# Jung Word Association Test Component

This component implements Carl Jung's 1910 Word Association Test, which presents stimulus words one at a time and records reaction times to analyze potential subconscious complexes.

## Component Structure

- `JungWordTest.tsx` - The core component that presents stimulus words and records responses
- `ResultAnalysis.tsx` - Analysis component based on Jung's theories
- `JungWordAssessment.tsx` - Wrapper component combining the test and analysis

## Usage

```tsx
// Import the component
import JungWordAssessment from '@/components/jung-word-assessment/JungWordAssessment';

// Use with default settings (100 words)
<JungWordAssessment />

// Or customize the number of words
<JungWordAssessment numberOfWords={30} />
```

## Features

- Presents Jung's original stimulus words one at a time
- Measures reaction time for each response
- Identifies "delayed" responses (>2 seconds) which Jung considered significant
- Provides analysis based on Jung's theories
- Categorizes responses into potential complex categories
- Fully responsive design

## Testing

### Unit Tests

The component includes unit tests that verify its basic functionality:

```bash
# Run unit tests
pnpm test
```

### Browser Tests (End-to-End)

We provide end-to-end tests using Playwright to verify the component works correctly in a browser environment:

```bash
# Install Playwright browsers (first time only)
npx playwright install

# Run all E2E tests
pnpm test:e2e

# Run only Jung Word Assessment tests
pnpm test:jung
```

The browser tests verify:
- Test initialization and word presentation
- Response entry and progression
- Test completion and results display
- Analysis functionality

### Test Script

A dedicated test script (`scripts/test-jung-word.js`) is provided to simplify browser testing:

```bash
# Make script executable (if needed)
chmod +x scripts/test-jung-word.js

# Run the test script
pnpm test:jung
```

This script will:
1. Check if a development server is running (and start one if needed)
2. Run the Playwright tests in headed mode (showing browser UI)
3. Display detailed test results

## Educational Purpose

This component is provided for educational purposes to demonstrate Jung's historical methodology and theories. It is not intended as a diagnostic tool. 