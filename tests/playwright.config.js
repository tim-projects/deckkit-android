// Playwright config for BBC dark mode testing
// 
// The BBC website may use server-side dark mode detection based on
// the Sec-CH-Prefers-Color-Scheme header.

const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: "./",
  outputDir: "./test-results",
  timeout: 180000,
  use: {
    browserName: 'chromium',
    headless: true,
    screenshot: 'only-on-failure',
    viewport: { width: 375, height: 667 }, // Mobile viewport (iPhone-like)
    navigationTimeout: 60000,
    actionTimeout: 30000,
    // Use mobile User-Agent to match what BBC serves to mobile
    userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
    // Pass dark mode preference header to trigger server-side dark mode
    extraHTTPHeaders: {
      'Sec-CH-Prefers-Color-Scheme': 'dark',
    },
  },
  projects: [
    {
      name: 'dark-mode-bbc',
      use: { colorScheme: 'dark' },
    },
  ],
});
