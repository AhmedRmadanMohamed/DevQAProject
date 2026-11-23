import { defineConfig } from 'vite';

// Bundle React + ReactDOM in one IIFE. No external runtime URL in IntelliJ JCEF.
export default defineConfig({
  // Shared library sources live outside frontend/app; always resolve one React copy.
  resolve: { dedupe: ['react', 'react-dom'] },
  define: {
    'process.env.NODE_ENV': JSON.stringify('production')
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    cssCodeSplit: false,
    lib: {
      entry: 'src/main.tsx',
      name: 'DevQAReactShell',
      formats: ['iife'],
      fileName: () => 'app.js'
    }
  },
  server: { host: '127.0.0.1', strictPort: true }
});
