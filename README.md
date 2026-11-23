# DevQAProject

IntelliJ IDEA plugin, with Kotlin backend and React + TypeScript frontend.

## Repository structure

```text
backend/
  plugin-host/                      IntelliJ plugin entry point, plugin.xml and icons
  services/
    service-01-project-brain/       Service 01 Kotlin backend and tests
    service-02/ ... service-30/     Reserved for future services
frontend/
  app/                              React/Vite host, TypeScript build and tests
  services/
    service-01-project-brain/
      ProjectBrainHost.tsx
      model.ts
      pages/                       Seven React pages
      components/
      hooks/
      styles/
    service-02/ ... service-30/     Reserved for future services
core/
  backend/
    code-analysis/                  Project analyzers, models and file intelligence
    classifier/                     Shared component/file classification
    scanner/                        Shared project scanners
    intellij-core/                  Reserved shared library
    logging/                        Reserved shared library
  frontend/
    jcef-bridge/                    Native IntelliJ <-> React adapter
    state-management/
    ui-components/
    utils/                          Reserved shared library
contracts/
  service-01-project-brain/         Project Brain TypeScript request/response contracts
    requests/
    responses/
  service-02/ ... service-30/       Reserved future contracts
  shared/
```

Service 01 is the only implemented service. The remaining service folders are intentionally empty.
React is the sole production frontend; no legacy UI is shipped.

## Build

Requires JDK 21, Node.js 22.12+ and Gradle 9.9.1.

```bash
npm install --no-audit --no-fund
npm run frontend:test
npm run frontend:build
gradle --no-daemon check buildPlugin
```

The build embeds the bundled React IIFE and CSS in IntelliJ JCEF. No network server is needed at runtime.

**Gradle wrapper note:** This migration could not copy the binary `gradle/wrapper/gradle-wrapper.jar`
through the connected GitHub text-only file API. The textual wrapper scripts and
distribution settings are preserved; use an installed Gradle 9.9.1 until the
official Gradle wrapper JAR is regenerated and committed. To regenerate it from
a trusted local Gradle installation, run `gradle wrapper --gradle-version 9.9.1`.

## Source and scope

Migrated from `AhmedRmadanMohamed/DevQA`, branch
`feature/devqa-monorepo-scaffold`, commit `29b4703`.
The source repository remains untouched. Existing implementation code is grouped
by ownership; the Kotlin package names and IntelliJ plugin ID are unchanged.
Shared Kotlin code currently compiles into one plugin artifact using Gradle source
sets. It is not yet published as a separate JAR dependency.
