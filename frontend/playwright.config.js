import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('..', import.meta.url));
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:4174', browserName: 'chromium' },
  webServer: [
    {
      command: process.platform === 'win32' ? 'powershell -ExecutionPolicy Bypass -File scripts/run.ps1' : 'bash scripts/run.sh',
      cwd: repo, url: 'http://127.0.0.1:18080/health', timeout: 60000,
      env: { PORT: '18080', HOST: '127.0.0.1', BASE_URL: 'http://127.0.0.1:18080', DATA_FILE: `build/e2e-${process.pid}.journal` },
    },
    {
      command: 'npm run build && npm run preview -- --port 4174',
      url: 'http://127.0.0.1:4174', timeout: 60000,
      env: { API_PROXY_TARGET: 'http://127.0.0.1:18080' },
    },
  ],
});
