import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const backend = process.env.VITE_BACKEND_URL || 'http://localhost:8080';

// The backend exposes two path families: newer endpoints under /api and
// legacy PascalCase endpoints at the root. Proxy both to Spring Boot.
const proxiedPaths = [
  '/api',
  '/Templates',
  '/TemplateFields',
  '/TemplateDataTypes',
  '/ValidationRules',
  '/Validation',
  '/ImportHistory',
  '/ImportDetails',
  '/ProcessStep',
];

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: Object.fromEntries(
      proxiedPaths.map((path) => [path, { target: backend, changeOrigin: true }])
    ),
  },
});
