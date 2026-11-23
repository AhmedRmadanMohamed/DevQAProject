import type { ProjectBrainSnapshotV2 } from '../../../../contracts/service-01-project-brain/project-brain.v2';

export function createProjectBrainStore() {
  let snapshot: ProjectBrainSnapshotV2 | null = null;
  const listeners = new Set<() => void>();
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    publish(next: ProjectBrainSnapshotV2): boolean {
      if (snapshot && next.modelVersion <= snapshot.modelVersion) return false;
      snapshot = next;
      listeners.forEach((notify) => notify());
      return true;
    }
  };
}
