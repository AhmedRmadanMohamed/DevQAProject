import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const feature = 'frontend/services/service-01-project-brain/';

test('Standalone React workspace contains Service 01 only', () => {
  const pkg = JSON.parse(read('frontend/app/package.json'));
  const ws = JSON.parse(read('package.json'));
  assert.deepEqual(ws.workspaces, ['frontend/app']);
  assert.equal(pkg.dependencies.react, pkg.dependencies['react-dom']);
  assert.match(pkg.scripts.build, /vite build/);
  assert.match(read('frontend/app/vite.config.ts'), /formats: \['iife'\]/);
  const services = fs.readdirSync(path.join(root, 'frontend/services'))
    .filter((name) => fs.existsSync(path.join(root, 'frontend/services', name, 'ProjectBrainHost.tsx')));
  assert.deepEqual(services, ['service-01-project-brain']);
});

test('All seven views render JSX and are routed inside React, not classic', () => {
  const host = read(feature + 'ProjectBrainHost.tsx');
  for (const name of ['Dashboard', 'Overview', 'Components', 'Files', 'Diagram', 'Frameworks', 'Settings']) {
    const src = read(feature + 'pages/' + name + '.tsx');
    assert.match(src, /data-react-view=/, name);
    assert.match(src, /<div|<section|<header/, name);
    assert.doesNotMatch(src, /dangerouslySetInnerHTML/);
    assert.match(host, new RegExp('<' + name + '\\b'), name);
  }
  for (const route of ['dashboard', 'overview', 'components', 'files', 'diagram', 'frameworks', 'settings'])
    assert.match(read(feature + 'model.ts'), new RegExp("'" + route + "'"));
  assert.doesNotMatch(host, /DevQAReactPreview|openClassic|Classic View/);
  assert.doesNotMatch(read('frontend/app/src/main.tsx'), /feature-root|devqa-react-switch/);
});

test('Kotlin JCEF packages React only with strict offline CSP and callback handshake', () => {
  const gradle = read('build.gradle.kts');
  const kotlin = read('backend/services/service-01-project-brain/src/main/kotlin/com/devqa/feature/projectbrain/presentation/web/ProjectBrainWebAssets.kt');
  const main = read('frontend/app/src/main.tsx');
  assert.match(gradle, /dependsOn\(buildDevqaReact\)/);
  assert.match(gradle, /include\("app.js"\)/);
  assert.match(gradle, /include\("style.css", "overrides.css"\)/);
  assert.doesNotMatch(gradle, /reactFrontendSelected|\/legacy|workspace\.js/);
  assert.match(kotlin, /resource\("app.js"\)/);
  assert.match(kotlin, /resource\("style.css"\)/);
  assert.match(kotlin, /resource\("overrides.css"\)/);
  assert.match(kotlin, /connect-src 'none'/);
  assert.match(kotlin, /frame-src 'none'/);
  assert.doesNotMatch(kotlin, /workspace\.js|backend-adapter\.js|feature-root/);
  assert.match(main, /installProjectBrainAdapter/);
  assert.match(main, /flushSync/);
  assert.match(main, /adapter\.ready\(\)/);
  assert.ok(fs.existsSync(path.join(root, feature + 'styles/style.css')));
});

test('Native bridge validates schemas, bounded responses and model versions', () => {
  const src = read('core/frontend/jcef-bridge/src/bridge.ts');
  for (const token of ['receiveDevQAProjectBrainSnapshot', 'receiveDevQAProjectBrainError',
    'receiveDevQAProjectBrainProgress', 'receiveDevQASourceNavigationError',
    'schemaVersion', 'classificationCoverage', 'frontendReady', 'openSource'])
    assert.ok(src.includes(token), 'bridge missing ' + token);
  assert.match(src, /reply\.requestId !== request\.requestId/);
  assert.match(src, /nodes\.length > 80/);
  assert.match(src, /edges\.length > 240/);
  assert.match(src, /reply\.items\.length > 100/);
});

test('React Components retains scope, bounded pages, advanced Boolean and numeric rules', () => {
  const src = read(feature + 'pages/Components.tsx');
  for (const token of ["kind: 'components'", "kind: 'component-options'", "kind: 'component-detail'",
    'const PAGE_SIZE = 40', 'normalizeFilters', 'sortNameDirection',
    'roleSummary', 'incoming', 'outgoing', 'BETWEEN', 'openSource'])
    assert.ok(src.includes(token), 'components missing ' + token);
  assert.match(src, /narrowed\.length \? narrowed : \[\.\.\.scope\]/);
});

test('React Files/Frameworks have paginated queries and evidence details', () => {
  const files = read(feature + 'pages/Files.tsx');
  const frameworks = read(feature + 'pages/Frameworks.tsx');
  for (const token of ["'files'", "'file-options'", "'file-detail'", 'openSource', 'FacetControls'])
    assert.ok(files.includes(token), 'files missing ' + token);
  for (const token of ["'frameworks'", "'framework-detail'", 'directDependencies',
    'transitiveDependencies', 'versionStatus', 'openSource'])
    assert.ok(frameworks.includes(token), 'frameworks missing ' + token);
  const hooks = read(feature + 'hooks/query-hooks.ts');
  assert.match(hooks, /limit: 40/);
  assert.match(hooks, /adapter\.latest\(\)\?\.modelVersion/);
});

test('React diagram and settings use Kotlin project APIs', () => {
  const diagram = read(feature + 'pages/Diagram.tsx');
  const settings = read(feature + 'pages/Settings.tsx');
  assert.match(diagram, /adapter\.query<ProjectGraphReply>/);
  assert.match(diagram, /<svg /);
  for (const token of ["'modules'", "'layers'", "'expanded'", "'dependency'", "'impact'", 'groupType', 'groupModule'])
    assert.ok(diagram.includes(token), 'diagram missing ' + token);
  assert.match(settings, /readSettings<ScanSettings>/);
  assert.match(settings, /writeSettings\(next\)/);
  assert.match(settings, /excludedDirectories/);
  assert.match(settings, /includedModules/);
});
