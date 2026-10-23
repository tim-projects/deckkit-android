// Playwright test for BBC dark mode issue
// 
// ISSUE: When BBC website is loaded in Android WebView with system dark mode,
// the page has <meta name="color-scheme" content="only light"> which prevents
// proper dark mode handling. This causes dark background with unreadable black text.
//
// ROOT CAUSE: 
// - BBC includes <meta name="color-scheme" content="only light"> in their HTML
// - Android WebView's FORCE_DARK can bypass this meta tag
// - BBC's dark mode JavaScript runs after 5 seconds and conflicts with WebView's forced dark
// - Result: dark background with black/unreadable foreground text
//
// This test:
// 1. Verifies BBC has the color-scheme meta tag
// 2. Simulates the WebView's forced dark mode by overriding the meta tag
// 3. Checks for dark text on dark background

const { test, expect } = require('@playwright/test');

test('BBC dark mode: detect color-scheme meta tag and dark text issue', async ({ page }) => {
  const url = 'https://www.bbc.co.uk/news';
  
  console.log('Navigating to BBC News...');
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {
    console.log('Navigation timeout, continuing anyway');
  });
  
  // Helper function to analyze colors
  const analyzeColor = (colorStr) => {
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
  
  // Check for the color-scheme meta tag
  const metaInfo = await page.evaluate(() => {
    const meta = document.querySelector('meta[name="color-scheme"]');
    return meta ? meta.content : 'not found';
  });
  console.log('color-scheme meta tag:', metaInfo);
  
  // This is the root cause we're checking for
  if (metaInfo === 'only light') {
    console.log('ROOT CAUSE FOUND: BBC has "color-scheme: only light" meta tag');
    console.log('This meta tag tells browsers NOT to apply automatic dark mode');
    console.log('When Android WebView FORCE_DARK bypasses this, it conflicts with BBC\'s JS dark mode');
  }
  
  // Wait for initial render
  console.log('Waiting 3 seconds for initial render...');
  await page.waitForTimeout(3000);
  
  // Get initial colors
  const initialColors = await page.evaluate(() => {
    const body = document.body;
    const style = window.getComputedStyle(body);
    return {
      bodyBg: style.backgroundColor,
      bodyColor: style.color
    };
  });
  console.log('Initial colors:', JSON.stringify(initialColors, null, 2));
  
  // Simulate Android WebView FORCE_DARK behavior:
  // Remove the color-scheme meta tag and set dark background
  await page.evaluate(() => {
    const meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) {
      console.log('[simulated] Removing color-scheme meta tag');
      meta.remove();
    }
    // Simulate forced dark mode background
    document.body.style.backgroundColor = '#121212';
  });
  
  // Wait for dark mode scripts to run (5 seconds as reported by user)
  console.log('Waiting 5 seconds for dark mode scripts...');
  await page.waitForTimeout(5000);
  
  // Get colors after simulated dark mode
  const afterColors = await page.evaluate(() => {
    const analyzeColorStr = (colorStr) => {
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
    
    // Check for dark text on dark background
    const elements = Array.from(document.querySelectorAll('h1, h2, h3, p, a, span'));
    const unreadableElements = [];
    
    for (const el of elements) {
      const style = window.getComputedStyle(el);
      const textInfo = analyzeColorStr(style.color);
      const bgInfo = analyzeColorStr(style.backgroundColor);
      
      const text = el.textContent?.trim();
      if (!text || text.length < 1) continue;
      
      const bothDark = textInfo.isDark === true && bgInfo.isDark === true;
      if (bothDark) {
        unreadableElements.push({
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
      unreadableElements: unreadableElements,
      bodyBg: analyzeColorStr(bodyStyle.backgroundColor),
      bodyColor: analyzeColorStr(bodyStyle.color),
      issueDetected: unreadableElements.length > 0
    };
  });
  
  console.log('After simulated dark mode:', JSON.stringify(afterColors, null, 2));
  console.log(`Total elements checked: ${afterColors.totalElementsChecked}`);
  console.log(`Unreadable elements: ${afterColors.unreadableElements.length}`);
  console.log(`Body bg isDark: ${afterColors.bodyBg.isDark}, Body text isDark: ${afterColors.bodyColor.isDark}`);
  
  // Check for the specific issue: dark background with dark text
  let issueDetected = false;
  
  // Body-level check: dark background with dark text
  if (afterColors.bodyBg.isDark === true && afterColors.bodyColor.isDark === true) {
    console.log('ISSUE DETECTED: Body has dark background with dark (unreadable) text!');
    issueDetected = true;
  }
  
  // Element-level check: any text element with dark color on dark background
  if (afterColors.unreadableElements.length > 0) {
    console.log(`ISSUE DETECTED: ${afterColors.unreadableElements.length} elements have dark text on dark background`);
    issueDetected = true;
  }
  
  if (!issueDetected) {
    console.log('No dark text on dark background issue detected');
  }
  
  console.log('Test completed');
});
