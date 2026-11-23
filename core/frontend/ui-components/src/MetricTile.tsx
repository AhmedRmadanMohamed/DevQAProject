import type { ReactNode } from 'react';

export interface MetricTileProps {
  readonly title: string;
  readonly value: string;
  readonly subtitle: string;
  readonly icon: string;
  readonly tint: 'blue' | 'violet' | 'amber' | 'cyan' | 'green' | 'pink';
  readonly onOpen?: () => void;
  readonly children?: ReactNode;
}
/** Same design tokens as the existing DevQA Dashboard / Overview. */
export function MetricTile({ title, value, subtitle, icon, tint, onOpen }: MetricTileProps) {
  return <button type="button" className={'pdb-stat pdb-' + tint} onClick={onOpen} disabled={!onOpen}>
    <span className="pdb-icon" aria-hidden="true">{icon}</span>
    <span className="pdb-stat-name">{title}</span>
    <strong>{value}</strong><small>{subtitle}</small>
  </button>;
}
