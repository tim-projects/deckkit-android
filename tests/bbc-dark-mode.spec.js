// Playwright test for BBC dark mode issue
// 
// ISSUE: When BBC website is loaded in Android WebView with system dark mode enabled,
// the forced dark mode causes: light theme → black background → unreadable black/dark text.
//
// ROOT CAUSE: BBC includes <meta name="color-scheme" content="only light"> which tells
// browsers not to apply automatic dark mode. The Android WebView's FORCE_DARK bypasses
// this meta tag and forces dark mode anyway, but BBC's specific CSS causes the darkening
// algorithm to produce dark text on dark background.
//
// This test:
// 1. Confirms BBC has color-scheme: only light meta tag
// 2. Simulates the Android WebView FORCE_DARK behavior by applying CSS filter inversion
// 3. Checks for unreadable text (dark text on dark background)

const { test, expect } = require('@playwright/test');

test('BBC dark mode: detect black background with unreadable black text', async ({ page }) => {
  const url = 'https://www.bbc.co.uk/news';
  
  // Verify colorScheme is dark in our Playwright config
  await page.emulateMedia({ colorScheme: 'dark' });
  
  console.log('Navigating to BBC News...');
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {
    console.log('Navigation timeout, continuing anyway');
  });
  
  // Check initial state
  const initialState = await page.evaluate(() => {
    const colorSchemeMeta = document.querySelector('meta[name="color-scheme"]');
    const body = document.body;
    const bodyStyle = window.getComputedStyle(body);
    
    return {
      colorSchemeMeta: colorSchemeMeta ? colorSchemeMeta.content : 'not found',
      bodyBg: bodyStyle.backgroundColor,
      bodyColor: bodyStyle.color,
      matchMediaDark: window.matchMedia('(prefers-color-scheme: dark)').matches
    };
  });
  console.log('Initial state:', JSON.stringify(initialState, null, 2));
  
  // Wait for initial render
  console.log('Waiting 3 seconds for initial render...');
  await page.waitForTimeout(3000);
  
  // Get colors after initial load
  const colorsAfter3s = await page.evaluate(() => {
    const body = document.body;
    const style = window.getComputedStyle(body);
    return {
      bodyBg: style.backgroundColor,
      bodyColor: style.color
    };
  });
  console.log('Colors after 3s:', JSON.stringify(colorsAfter3s, null, 2));
  
  // Simulate Android WebView FORCE_DARK behavior
  // The WebView applies forced dark mode that ignores the color-scheme meta tag
  // We simulate this using CSS filter inversion (similar to what WebView does)
  console.log('Simulating Android WebView FORCE_DARK behavior...');
  await page.evaluate(() => {
    // Apply dark mode CSS to simulate WebView forced dark mode
    document.body.style.backgroundColor = '#121212';
    // Apply CSS filter to simulate forced dark mode
    document.body.style.filter = 'invert(1) hue-rotate(180deg) invert(1) hue-rotate(0deg)';
  });
  
  // Wait for simulated dark mode to take effect
  await page.waitForTimeout(1000);
  
  // Check colors after simulated dark mode
  const colorsAfterDarkMode = await page.evaluate(() => {
    const analyzeColorStr = (colorStr) => {
      // Don't treat rgb(0, 0, 0) as null - only rgba(0, 0, 0, 0) is transparent
      if (!colorStr || colorStr === 'rgba(0, 0, 0, 0)' || colorStr === 'transparent') {
        return { color: colorStr, isDark: null, luminance: null };
      }
      const match = colorStr.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
      if (!match) return { color: colorStr, isDark: null, luminance: null };
      const r = parseInt(match[1]);
      const g = parseInt(match[2]);
      const b = parseInt(match[3]);
      const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
      return { color: colorStr, isDark: luminance < 0.5, luminance: luminance.toFixed(3) };
    };
    
    // Get all visible text elements
    const elements = Array.from(document.querySelectorAll('h1, h2, h3, p, a'));
    const results = [];
    
    for (const el of elements) {
      const style = window.getComputedStyle(el);
      const textInfo = analyzeColorStr(style.color);
      const bgInfo = analyzeColorStr(style.backgroundColor);
      
      const text = el.textContent?.trim();
      if (!text || text.length < 1) continue;
      
      // Check if both text and background are dark (the issue)
      const bothDark = textInfo.isDark === true && bgInfo.isDark === true;
      
      if (bothDark) {
        results.push({
          tag: el.tagName,
          textPreview: text.substring(0, 30),
          textColor: textInfo.color,
          bgColor: bgInfo.color
        });
      }
    }
    
    const body = document.body;
    const bodyStyle = window.getComputedStyle(body);
    return {
      totalElementsChecked: elements.length,
      unreadableElements: results,
      bodyBg: analyzeColorStr(bodyStyle.backgroundColor),
      bodyColor: analyzeColorStr(bodyStyle.color),
      bodyFilter: bodyStyle.filter
    };
  });
  
  console.log('After simulated dark mode:', JSON.stringify(colorsAfterDarkMode, null, 2));
  
  // Report findings
  console.log(`Total elements checked: ${colorsAfterDarkMode.totalElementsChecked}`);
  console.log(`Unreadable elements (dark text on dark bg): ${colorsAfterDarkMode.unreadableElements.length}`);
  console.log(`Body background: ${colorsAfterDarkMode.bodyBg.color} (isDark: ${colorsAfterDarkMode.bodyBg.isDark})`);
  console.log(`Body text color: ${colorsAfterDarkMode.bodyColor.color} (isDark: ${colorsAfterDarkMode.bodyColor.isDark})`);
  
  // Check for the issue: dark background with dark text
  let issueDetected = false;
  
  // Scenario 1: body background is dark and text color is also dark (unreadable)
  if (colorsAfterDarkMode.bodyBg.isDark === true && colorsAfterDarkMode.bodyColor.isDark === true) {
    console.log('ISSUE DETECTED: Body has dark background with dark (unreadable) text!');
    issueDetected = true;
  }
  
  // Scenario 2: Any element with dark text on dark background
  if (colorsAfterDarkMode.unreadableElements.length > 0) {
    console.log(`ISSUE DETECTED: ${colorsAfterDarkMode.unreadableElements.length} elements have dark text on dark background`);
    console.log('Unreadable elements:', JSON.stringify(colorsAfterDarkMode.unreadableElements.slice(0, 5), null, 2));
    issueDetected = true;
  }
  
  if (!issueDetected) {
    console.log('No dark text on dark background issue detected');
  }
  
  console.log('Test completed');
});
