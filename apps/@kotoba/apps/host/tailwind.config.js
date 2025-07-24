import sharedConfig from '../../packages/shared/tailwind.config.js';

/** @type {import('tailwindcss').Config} */
export default {
  ...sharedConfig,
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
    // BitDev components
    "../../kotoba/components/**/*.{js,ts,jsx,tsx}",
  ],
} 