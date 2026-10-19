import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFileSync } from 'node:fs';

// Optional local-only development helper. Nothing is embedded in the production bundle.
function demoConnection(): Plugin {
  return { name: 'robolink-local-demo', configureServer(server) {
    server.middlewares.use(async (request, response, next) => {
      const path = request.url?.split('?')[0];
      const operation = path?.match(/^\/__demo\/robots\/(RB-00[123])\/(start|stop)$/);
      if (path !== '/__demo/available' && path !== '/__demo/session' && path !== '/__demo/evidence' && !operation) return next();
      response.setHeader('Content-Type', 'application/json'); response.setHeader('Cache-Control', 'no-store');
      const host = request.headers.host;
      const origin = request.headers.origin;
      if (!['127.0.0.1:5173', 'localhost:5173'].includes(host ?? '') || (origin && origin !== `http://${host}`)
          || (request.headers['sec-fetch-site'] && request.headers['sec-fetch-site'] !== 'same-origin')
          || request.method !== (operation ? 'POST' : 'GET') || (operation && origin !== `http://${host}`)) {
        response.statusCode = 403; response.end('{}'); return;
      }
      const file = process.env.ROBOLINK_DEMO_CONTROL_FILE;
      if (path === '/__demo/available') { response.end(JSON.stringify({ available: !!file })); return; }
      if (!file) { response.statusCode = 404; response.end('{}'); return; }
      try {
        if (path === '/__demo/evidence' || operation) {
          const key = process.env.ROBOLINK_DEMO_ADMIN_KEY;
          if (!key) { response.statusCode = 503; response.end('{}'); return; }
          const upstream = await fetch(`http://127.0.0.1:19006${operation ? `/robots/${operation[1]}/${operation[2]}` : '/evidence'}`,
            { method: operation ? 'POST' : 'GET', headers: { 'X-RoboLink-Demo-Key': key }, signal: AbortSignal.timeout(8000) });
          response.statusCode = upstream.status; response.end(await upstream.text()); return;
        }
        const config = Object.fromEntries(readFileSync(file, 'utf8').split(/\r?\n/).filter(line => line.includes('=')).map(line => {
          const separator = line.indexOf('='); return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
        }));
        if (!config['operator.token']) throw new Error();
        response.end(JSON.stringify({ id: 'operator', token: config['operator.token'], url: 'ws://127.0.0.1:9001/control' }));
      } catch { response.statusCode = 503; response.end('{}'); }
    });
  } };
}

export default defineConfig({ plugins: [vue(), demoConnection()], server: { host: '127.0.0.1', port: 5173, strictPort: true },
  build: { target: 'es2020' } });
