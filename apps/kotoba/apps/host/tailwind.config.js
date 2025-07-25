import { tailwindConfig } from '@junkawasaki/kotoba.shared';

/** @type {import('tailwindcss').Config} */
export default {
  ...tailwindConfig,
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
    "../../kotoba/components/**/*.{js,ts,jsx,tsx}",
  ],
} 