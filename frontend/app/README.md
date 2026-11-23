# DevQA React frontend

This workspace builds the only production frontend for Project Brain. It contains
seven React + TypeScript views: Dashboard, Overview, Components, Project Files,
Live Diagram, Framework Inventory, and Scan Settings.

From the repository root:

```bash
npm install --no-audit --no-fund
npm run frontend:test
npm run frontend:build
```

Vite emits `frontend/app/dist/app.js`. Gradle packages that file with the feature
CSS under `web/project-brain/react`. IntelliJ injects the local JCEF bridge before
the bundle starts; there is no external server and no legacy JavaScript fallback.
