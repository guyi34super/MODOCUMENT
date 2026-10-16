import { useId, useMemo, useRef, useState } from 'react';
import { Tag, inputClass } from '../ui';
import { KIND_COLORS, KIND_LABELS, REGION_KINDS } from './geometry';

const NO_GROUP = '__none';

const byTabOrder = (a, b) => a.page - b.page || (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.fy - b.fy || a.fx - b.fx;

const nameOf = (r) => r.label?.trim() || `Unlabelled field (${r.key})`;

/**
 * The fields of one page in a new order: their `sortOrder` (the tab order)
 * is rewritten as consecutive numbers from the page's lowest, in the order
 * of `orderedUids`. Other pages keep theirs.
 */
export function reorderPage(regions, page, orderedUids) {
  const onPage = regions.filter((r) => r.page === page);
  const start = onPage.length ? Math.min(...onPage.map((r) => r.sortOrder ?? 0)) : 0;
  const position = new Map(orderedUids.map((uid, i) => [uid, i]));
  return regions.map((r) =>
    r.page === page && position.has(r._uid) ? { ...r, sortOrder: start + position.get(r._uid) } : r
  );
}

/**
 * The template's fields, by page and tab order: search by label or key,
 * filter by kind and group, "required" and "N rules" badges, and reordering
 * by drag and drop or the Move up / Move down buttons (keyboard). A new
 * order goes to `onReorder(page, orderedUids)`.
 */
export default function FieldList({
  regions,
  isSelected,
  onSelect,
  onReorder,
  rulesCount,
  isRequired = (r) => Boolean(r.required),
  isLow,
  readOnly = false,
  testId = 'field-list',
  emptyText = 'No fields yet.',
}) {
  const baseId = useId();
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState('');
  const [group, setGroup] = useState('');
  const dragged = useRef(null);
  const [dropTarget, setDropTarget] = useState(null);

  const kinds = useMemo(() => REGION_KINDS.filter((k) => regions.some((r) => r.kind === k)), [regions]);
  const groups = useMemo(
    () => [...new Set(regions.map((r) => r.group?.trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [regions]
  );
  const filtering = Boolean(query.trim() || kind || group);
  const sorted = useMemo(() => [...regions].sort(byTabOrder), [regions]);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return sorted.filter(
      (r) =>
        (!q || (r.label || '').toLowerCase().includes(q) || (r.key || '').toLowerCase().includes(q)) &&
        (!kind || r.kind === kind) &&
        (!group || (group === NO_GROUP ? !r.group?.trim() : r.group?.trim() === group))
    );
  }, [sorted, query, kind, group]);
  const pages = useMemo(() => {
    const map = new Map();
    for (const r of shown) {
      if (!map.has(r.page)) map.set(r.page, []);
      map.get(r.page).push(r);
    }
    return [...map.entries()];
  }, [shown]);

  const canReorder = Boolean(onReorder) && !readOnly && !filtering;

  const move = (page, uid, delta) => {
    const ids = sorted.filter((r) => r.page === page).map((r) => r._uid);
    const from = ids.indexOf(uid);
    const to = from + delta;
    if (from < 0 || to < 0 || to >= ids.length) return;
    [ids[from], ids[to]] = [ids[to], ids[from]];
    onReorder(page, ids);
  };

  const dropOn = (target) => {
    const source = dragged.current;
    dragged.current = null;
    setDropTarget(null);
    if (!source || source.page !== target.page || source._uid === target._uid) return;
    const ids = sorted.filter((r) => r.page === target.page && r._uid !== source._uid).map((r) => r._uid);
    ids.splice(ids.indexOf(target._uid), 0, source._uid);
    onReorder(target.page, ids);
  };

  if (regions.length === 0) return <p className="text-xs text-muted">{emptyText}</p>;

  return (
    <div>
      <div className="mb-2 space-y-1.5">
        <label className="block text-xs">
          <span className="sr-only">Search fields</span>
          <input
            type="search"
            className={`${inputClass} !py-1 text-xs`}
            placeholder="Search by label or key"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <div className="grid grid-cols-2 gap-1.5">
          <label className="block text-xs">
            <span className="sr-only">Filter by kind</span>
            <select className={`${inputClass} !py-1 text-xs`} value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="">All kinds</option>
              {kinds.map((k) => (
                <option key={k} value={k}>
                  {KIND_LABELS[k] || k}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-xs">
            <span className="sr-only">Filter by group</span>
            <select className={`${inputClass} !py-1 text-xs`} value={group} onChange={(e) => setGroup(e.target.value)}>
              <option value="">All groups</option>
              {groups.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
              <option value={NO_GROUP}>No group</option>
            </select>
          </label>
        </div>
        {filtering && (
          <p className="text-xs text-muted" role="status">
            Showing {shown.length} of {regions.length} fields · clear the search and filters to reorder
          </p>
        )}
      </div>
      {shown.length === 0 ? (
        <p className="text-xs text-muted">No field matches.</p>
      ) : (
        <div className="max-h-96 space-y-2 overflow-y-auto">
          {pages.map(([pageNo, list]) => (
            <div key={pageNo}>
              <h3 className="text-xs font-medium text-muted">Page {pageNo + 1}</h3>
              <ul className="space-y-0.5" data-testid={testId}>
                {list.map((region, index) => {
                  const selected = isSelected?.(region._uid) ?? false;
                  const low = isLow?.(region) ?? false;
                  const rules = rulesCount?.(region.key) ?? 0;
                  const nameId = `${baseId}-${region._uid}`;
                  return (
                    <li
                      key={region._uid}
                      data-testid={`field-row-${region.key}`}
                      draggable={canReorder}
                      onDragStart={(event) => {
                        dragged.current = region;
                        if (event.dataTransfer) {
                          event.dataTransfer.effectAllowed = 'move';
                          event.dataTransfer.setData('text/plain', region.key);
                        }
                      }}
                      onDragOver={(event) => {
                        if (!canReorder || !dragged.current || dragged.current.page !== region.page) return;
                        event.preventDefault();
                        setDropTarget(region._uid);
                      }}
                      onDragLeave={() => setDropTarget((t) => (t === region._uid ? null : t))}
                      onDrop={(event) => {
                        event.preventDefault();
                        if (canReorder) dropOn(region);
                      }}
                      className={`flex items-center gap-1 rounded ${dropTarget === region._uid ? 'border-t-2 border-accent' : ''}`}
                    >
                      <button
                        type="button"
                        aria-pressed={selected}
                        onClick={(event) => onSelect(region, event)}
                        className={`flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left text-xs hover:bg-pill/50 ${
                          selected ? 'bg-pill' : ''
                        } ${low ? 'ring-1 ring-amber-500' : ''}`}
                      >
                        <span
                          aria-hidden="true"
                          className="inline-block size-2.5 shrink-0 rounded-sm"
                          style={{ background: KIND_COLORS[region.kind] }}
                        />
                        <span id={nameId} className="flex-1 truncate">
                          {nameOf(region)}
                        </span>
                        <span className="text-muted">{KIND_LABELS[region.kind] || region.kind}</span>
                        {isRequired(region) && <Tag tone="danger">required</Tag>}
                        {rules > 0 && (
                          <Tag tone="info">
                            {rules} rule{rules === 1 ? '' : 's'}
                          </Tag>
                        )}
                        {low && <Tag tone="warning">check</Tag>}
                      </button>
                      {canReorder && (
                        <span className="flex shrink-0">
                          <button
                            type="button"
                            aria-label="Move up"
                            aria-describedby={nameId}
                            title="Move up in the tab order"
                            disabled={index === 0}
                            onClick={() => move(region.page, region._uid, -1)}
                            className="rounded px-1 text-xs text-accent hover:bg-pill/50 disabled:opacity-30"
                          >
                            <span aria-hidden="true">↑</span>
                          </button>
                          <button
                            type="button"
                            aria-label="Move down"
                            aria-describedby={nameId}
                            title="Move down in the tab order"
                            disabled={index === list.length - 1}
                            onClick={() => move(region.page, region._uid, 1)}
                            className="rounded px-1 text-xs text-accent hover:bg-pill/50 disabled:opacity-30"
                          >
                            <span aria-hidden="true">↓</span>
                          </button>
                        </span>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
