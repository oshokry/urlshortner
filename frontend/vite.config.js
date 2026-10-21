import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Relative API calls keep the browser on one origin; Java needs no CORS policy.
const target = process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080';
const proxy = Object.fromEntries(['/api', '/r', '/health'].map(path => [path, { target }]));
const headers = { 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' };

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy, headers },
  preview: {
    host: '127.0.0.1', port: 4173, strictPort: true, proxy,
    headers: { ...headers, 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'" },
  },
  test: { include: ['src/**/*.test.jsx'], environment: 'jsdom', setupFiles: './src/test-setup.js', clearMocks: true },
});
