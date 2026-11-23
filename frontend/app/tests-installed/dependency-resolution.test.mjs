import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { existsSync, realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const contexts = [
  'frontend/app/src/App.tsx',
  'frontend/services/service-01-project-brain/ProjectBrainHost.tsx',
  'core/frontend/ui-components/src/ErrorBoundary.tsx'
];

// These are live Node resolution checks, not static source-pattern checks.
// Run only AFTER npm ci from the monorepo root.
test('React and JSX runtime resolve from app, feature module, and shared library', () => {
  const resolved = contexts.map((source) => {
    const requireFromSource = createRequire(path.join(repo, source));
    const react = requireFromSource.resolve('react');
    const runtime = requireFromSource.resolve('react/jsx-runtime');
    const reactTypes = requireFromSource.resolve('@types/react/package.json');
    const domTypes = requireFromSource.resolve('@types/react-dom/package.json');
    for (const resolvedFile of [react, runtime, reactTypes, domTypes]) {
      assert.ok(existsSync(resolvedFile), `Missing dependency from ${source}: ${resolvedFile}`);
    }
    return { react: realpathSync(react), runtime: realpathSync(runtime) };
  });
  assert.equal(resolved[0].react, resolved[1].react, 'app and feature must share one React install');
  assert.equal(resolved[0].react, resolved[2].react, 'app and shared UI must share one React install');
  assert.equal(resolved[0].runtime, resolved[2].runtime, 'JSX runtime must be the same everywhere');
});
