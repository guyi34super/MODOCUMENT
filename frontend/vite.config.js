import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { readFileSync } from 'node:fs';

// ws24: the release version has ONE source per side — pom.xml for the API,
// package.json here (scripts/bump-version.sh keeps them equal). It is baked
// into the bundle as __APP_VERSION__ (read it via src/version.js) so the SPA
// can tell when the backend runs a different release.
const { version: appVersion } = JSON.parse(
  readFileSync(new URL('./package.json', import.meta.url), 'utf8')
);

const backend = process.env.VITE_BACKEND_URL || 'http://localhost:8080';

// The backend exposes two path families: newer endpoints under /api and
// legacy PascalCase endpoints at the root. Proxy both to Spring Boot.
const proxiedPaths = [
  '/api',
  // OIDC handshake endpoints (ws07): Spring Security's authorization
  // redirect + provider callback. '/login' itself stays a SPA route — only
  // the '/login/oauth2' prefix is proxied.
  '/oauth2',
  '/login/oauth2',
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
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
  },
  build: {
    rollupOptions: {
      output: {
        // v1.2.0: the framework changes far less often than the app, so it
        // gets its own long-cached chunk (nginx: /assets/ immutable). Route
        // pages are separate chunks via React.lazy (App.jsx).
        manualChunks(id) {
          if (
            /[\\/]node_modules[\\/](react|react-dom|scheduler|react-router|react-router-dom|cookie|set-cookie-parser)[\\/]/.test(
              id
            )
          ) {
            return 'vendor';
          }
          return undefined;
        },
      },
    },
  },
  server: {
    port: 3000,
    proxy: Object.fromEntries(
      proxiedPaths.map((path) => [
        path,
        {
          target: backend,
          changeOrigin: true,
          // Batched LLM extraction for large spreadsheets can exceed 5 minutes.
          timeout: 900_000,
          proxyTimeout: 900_000,
        },
      ])
    ),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    // The typing-heavy dialog tests take ~1 s alone; on a loaded shared
    // runner the 5 s default made them flaky.
    testTimeout: 15_000,
  },
});
