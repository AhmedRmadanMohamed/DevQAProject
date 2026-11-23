import { useState } from 'react';
import { pretty } from '../model';
export interface FacetControlsProps {
  readonly options: Readonly<Partial<Record<string, readonly string[]>>>;
  readonly labels: Readonly<Record<string, string>>;
  readonly selected: Readonly<Record<string, readonly string[]>>;
  readonly onChange: (field: string, values: readonly string[]) => void;
}
export function FacetControls({ options, labels, selected, onChange }: FacetControlsProps) {
  const [search, setSearch] = useState<Readonly<Record<string, string>>>({});
  return <div className="filter-grid" data-react-facets="true">
    {Object.entries(labels).map(([field, label]) => {
      const values = [...new Set([...(options[field] ?? []), ...(selected[field] ?? [])])];
      const visible = values.filter((value) => value.toLowerCase().includes((search[field] ?? '').toLowerCase()));
      return <fieldset className="cm-filter-group" key={field}>
        <legend>{label} ({(selected[field] ?? []).length})</legend>
        <input type="search" aria-label={'Search ' + label}
          placeholder={'Search ' + label} value={search[field] ?? ''}
          onChange={(event) => setSearch((current) => ({ ...current, [field]: event.target.value }))} />
        <div className="cm-filter-options" role="group" aria-label={label}>
          {visible.map((value) => <label key={value}>
            <input type="checkbox" value={value} checked={(selected[field] ?? []).includes(value)}
              onChange={(event) => {
                const values = new Set(selected[field] ?? []);
                if (event.target.checked) values.add(value); else values.delete(value);
                onChange(field, [...values].slice(0, 100));
              }} />
            {field === 'filename' || field === 'class' ? value : pretty(value)}
          </label>)}
          {!visible.length && <p>No matching options</p>}
        </div>
      </fieldset>;
    })}
  </div>;
}
