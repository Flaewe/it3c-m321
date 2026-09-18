import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Der Entwicklungs-Server von Vite laeuft auf 5173 und leitet /api und /auth
// an das web-gateway auf 8080 weiter. Damit sieht der Browser waehrend der
// Entwicklung dieselbe eine Herkunft wie spaeter im Container -- und wir
// handeln uns kein CORS ein, das es in der fertigen Anwendung gar nicht gibt.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
    },
  },
  test: {
    globals: true,
    environment: 'node',
  },
});
