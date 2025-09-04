/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './apps/host/src/**/*.{js,ts,jsx,tsx,mdx}',
    './kotoba/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  presets: [require('@junkawasaki/kotoba.shared').tailwindConfig],
  theme: {
    extend: {},
  },
  plugins: [],
} 