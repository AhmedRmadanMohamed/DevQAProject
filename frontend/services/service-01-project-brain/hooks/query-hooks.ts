import { useEffect, useRef, useState } from 'react';
import type { ProjectBrainAdapter } from '../../../../core/frontend/jcef-bridge/src/bridge';
import type { ProjectBrainQueryKind, QueryDetail, QueryPage } from '../../../../contracts/service-01-project-brain/project-brain.v2';

export type FacetDraft = Readonly<Record<string, readonly string[]>>;
export interface AppliedFilters {
  readonly modelVersion: number;
  readonly search: string; readonly offset: number;
  readonly filters: FacetDraft;
}

/** Always bounded on Kotlin and isolated against model-version/query races. */
export function usePage<T>(
  adapter: ProjectBrainAdapter | null, modelVersion: number | undefined,
  kind: ProjectBrainQueryKind, applied: AppliedFilters
) {
  const [items, setItems] = useState<readonly T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const seq = useRef(0);
  useEffect(() => {
    setItems([]); setTotal(0); setError('');
    if (!adapter || modelVersion === undefined || applied.modelVersion !== modelVersion) return;
    let alive = true;
    const request = ++seq.current;
    setLoading(true);
    void adapter.query<QueryPage<T>>({ kind, search: applied.search.slice(0, 200),
      offset: applied.offset, limit: 40, filters: applied.filters })
      .then((reply) => {
        if (!alive || request !== seq.current || adapter.latest()?.modelVersion !== modelVersion) return;
        if (reply.modelVersion !== modelVersion || !Array.isArray(reply.items) ||
            !Number.isSafeInteger(reply.total) || reply.total < 0 || reply.items.length > 40)
          throw new Error('Invalid page returned by IntelliJ');
        setItems(reply.items); setTotal(reply.total);
      })
      .catch((cause: unknown) => {
        if (alive && request === seq.current) setError(cause instanceof Error ? cause.message : 'Query failed');
      })
      .finally(() => { if (alive && request === seq.current) setLoading(false); });
    return () => { alive = false; };
  }, [adapter, kind, modelVersion, applied]);
  return { items, total, loading, error };
}

export function useDetail<T>(
  adapter: ProjectBrainAdapter | null, modelVersion: number | undefined,
  kind: ProjectBrainQueryKind, identifier: Record<string, string> | null
) {
  const [detail, setDetail] = useState<T | null>(null);
  const [error, setError] = useState('');
  const seq = useRef(0);
  const key = JSON.stringify(identifier);
  useEffect(() => {
    setDetail(null); setError('');
    if (!adapter || !identifier || !modelVersion) return;
    let alive = true;
    const current = ++seq.current;
    void adapter.query<QueryDetail<T>>({ kind, ...identifier }).then((reply) => {
      if (alive && current === seq.current && adapter.latest()?.modelVersion === modelVersion &&
          reply.modelVersion === modelVersion) setDetail(reply.item);
    }).catch((cause: unknown) => {
      if (alive && current === seq.current) setError(cause instanceof Error ? cause.message : 'Detail query failed');
    });
    return () => { alive = false; };
  }, [adapter, modelVersion, kind, key]);
  return { detail, error };
}
