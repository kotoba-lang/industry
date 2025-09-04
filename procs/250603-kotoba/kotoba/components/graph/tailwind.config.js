import { tailwindConfig } from '@junkawasaki/kotoba.shared';

/** @type {import('tailwindcss').Config} */
export default {
  ...tailwindConfig,
  content: [
    "./**/*.{js,ts,jsx,tsx}",
    "../../packages/shared/**/*.{js,ts,jsx,tsx}",
    "../editor/**/*.{js,ts,jsx,tsx}",
  ],
} 