import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import {
  NUDGE_STEPS,
  SNAP_GRID,
  SNAP_THRESHOLD,
  alignBoxes,
  clampBox,
  distribute,
  matchSize,
  moveBox,
  resizeBox,
  snapBox,
  uniqueKey,
} from './geometry';

/** Undo keeps this many steps. */
export const HISTORY_LIMIT = 100;
/** Zoom levels of the page (1 = fit the width). */
export const ZOOM_LEVELS = [0.5, 0.75, 1, 1.25, 1.5, 2];
/** Where a pasted or duplicated field lands: this far right and down of the original. */
export const PASTE_OFFSET = 0.02;

const BOX_KEYS = ['fx', 'fy', 'fw', 'fh'];

let uidCounter = 0;
const defaultWithUid = (item) => {
  uidCounter += 1;
  return { ...item, _uid: `e${uidCounter}` };
};

function historyReducer(state, action) {
  switch (action.type) {
    case 'reset':
      return { items: action.items, past: [], future: [], chain: null };
    case 'quiet':
      // A change that mirrors saved state (not a user edit): no history step.
      return { ...state, items: action.fn(state.items) };
    case 'apply': {
      const next = action.fn(state.items);
      if (next === state.items) return state;
      // Consecutive changes of one kind (typing a label, holding an arrow) are one step.
      if (action.chain && action.chain === state.chain && state.past.length) {
        return { ...state, items: next, future: [] };
      }
      const past = [...state.past, state.items].slice(-HISTORY_LIMIT);
      return { items: next, past, future: [], chain: action.chain || null };
    }
    case 'undo': {
      if (!state.past.length) return state;
      const previous = state.past[state.past.length - 1];
      return {
        items: previous,
        past: state.past.slice(0, -1),
        future: [state.items, ...state.future],
        chain: null,
      };
    }
    case 'redo': {
      if (!state.future.length) return state;
      const [next, ...rest] = state.future;
      return { items: next, past: [...state.past, state.items].slice(-HISTORY_LIMIT), future: rest, chain: null };
    }
    default:
      return state;
  }
}

/** True when keyboard shortcuts should leave the event alone (typing in a field, a dialog). */
function inTextEntry(target) {
  if (!target || typeof target.closest !== 'function') return false;
  if (target.closest('[role="dialog"]')) return true;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

/**
 * The shared editing model of the template editor (BlueprintDetailPage) and
 * the builder's "Edit layout" mode: a list of fields (`items`, each with a
 * client `_uid`, `key`, `page` and a top-left fraction box) plus
 *
 * - undo / redo (Ctrl/Cmd+Z, Ctrl+Shift+Z, Ctrl+Y), 100 steps, one step per
 *   completed drag or per run of the same edit (typing, holding an arrow);
 * - a multi-selection (Shift-click adds, a marquee, Ctrl+A on the page);
 * - duplicate (Ctrl+D) and copy / paste (Ctrl+C / Ctrl+V) with an offset and
 *   unique keys;
 * - delete with a confirmation (undoable);
 * - arrow nudges (Alt = fine, Shift = coarse; Ctrl/Cmd resizes);
 * - align / distribute / same size, snapping with guides and zoom.
 *
 * Options: `page` (the page shown), `enabled` (shortcuts listen on the
 * document while true), `onChange()` (after every user change),
 * `touch(item)` (marks an item the user changed), `withUid(item)`.
 */
export default function useOverlayEditor({
  page = 0,
  enabled = true,
  onChange,
  touch,
  withUid = defaultWithUid,
} = {}) {
  const [history, dispatch] = useReducer(historyReducer, { items: [], past: [], future: [], chain: null });
  const [selectedRaw, setSelectedRaw] = useState([]);
  const [clipboard, setClipboard] = useState([]);
  const [snapOn, setSnapOn] = useState(false);
  const [guides, setGuides] = useState([]);
  const [zoom, setZoomRaw] = useState(1);
  const [pendingDelete, setPendingDelete] = useState(null);

  const items = history.items;
  const latest = useRef({});
  latest.current = { items, onChange, touch, withUid, page };

  const byUid = useMemo(() => new Map(items.map((item) => [item._uid, item])), [items]);
  const selected = useMemo(() => selectedRaw.filter((uid) => byUid.has(uid)), [selectedRaw, byUid]);
  const selectedItems = useMemo(() => selected.map((uid) => byUid.get(uid)), [selected, byUid]);

  // --------------------------------------------------------------- changes

  /** Replaces the list without history (a load); clears the selection. */
  const reset = useCallback((list) => {
    dispatch({ type: 'reset', items: list });
    setSelectedRaw([]);
    setPendingDelete(null);
  }, []);

  /**
   * Applies `fn(items) → items` as one undoable step (or as part of the
   * running `chain`). Changed items go through `touch`.
   */
  const apply = useCallback((fn, { chain } = {}) => {
    const { touch: mark } = latest.current;
    dispatch({
      type: 'apply',
      chain,
      fn: (current) => {
        const next = fn(current);
        if (!mark || next === current) return next;
        const before = new Map(current.map((item) => [item._uid, item]));
        return next.map((item) => (before.has(item._uid) && before.get(item._uid) !== item ? mark(item) : item));
      },
    });
    latest.current.onChange?.();
  }, []);

  /** Changes items to mirror something already saved: no undo step, no onChange. */
  const quietly = useCallback((fn) => dispatch({ type: 'quiet', fn }), []);

  const update = useCallback(
    (uid, patch, { chain } = {}) => {
      apply((list) => list.map((item) => (item._uid === uid ? { ...item, ...patch } : item)), {
        chain: chain === true ? `update:${uid}:${Object.keys(patch).sort().join(',')}` : chain,
      });
    },
    [apply]
  );

  /** One step that patches several items: `patchFor(item)` returns the patch (or null). */
  const updateMany = useCallback(
    (uids, patchFor, { chain } = {}) => {
      const set = new Set(uids);
      apply(
        (list) =>
          list.map((item) => {
            if (!set.has(item._uid)) return item;
            const patch = typeof patchFor === 'function' ? patchFor(item) : patchFor;
            return patch ? { ...item, ...patch } : item;
          }),
        { chain }
      );
    },
    [apply]
  );

  const add = useCallback(
    (item, { select = true } = {}) => {
      apply((list) => [...list, item]);
      if (select) setSelectedRaw([item._uid]);
    },
    [apply]
  );

  const remove = useCallback(
    (uids) => {
      const set = new Set(uids);
      apply((list) => list.filter((item) => !set.has(item._uid)));
      setSelectedRaw((current) => current.filter((uid) => !set.has(uid)));
    },
    [apply]
  );

  const undo = useCallback(() => {
    dispatch({ type: 'undo' });
    latest.current.onChange?.();
  }, []);
  const redo = useCallback(() => {
    dispatch({ type: 'redo' });
    latest.current.onChange?.();
  }, []);

  // ------------------------------------------------------------- selection

  /**
   * Selects one item. `additive` (Shift) toggles it in the selection;
   * `keep` leaves a multi-selection alone when the item is already in it
   * (pressing on a selected box to drag the group).
   */
  const select = useCallback((uid, { additive = false, keep = false } = {}) => {
    setSelectedRaw((current) => {
      if (additive) return current.includes(uid) ? current.filter((u) => u !== uid) : [...current, uid];
      if (keep && current.includes(uid)) return current;
      return [uid];
    });
  }, []);

  const setSelection = useCallback((uids) => setSelectedRaw([...uids]), []);
  const clearSelection = useCallback(() => setSelectedRaw([]), []);

  /** Selects every item on the shown page. */
  const selectAll = useCallback(() => {
    const { items: list, page: shown } = latest.current;
    setSelectedRaw(list.filter((item) => item.page === shown).map((item) => item._uid));
  }, []);

  /** Selects the items on the shown page that intersect `rect` (fractions); `additive` adds to the selection. */
  const selectInRect = useCallback((rect, { additive = false } = {}) => {
    const { items: list, page: shown } = latest.current;
    const hits = list
      .filter(
        (item) =>
          item.page === shown &&
          item.fx < rect.fx + rect.fw &&
          item.fx + item.fw > rect.fx &&
          item.fy < rect.fy + rect.fh &&
          item.fy + item.fh > rect.fy
      )
      .map((item) => item._uid);
    setSelectedRaw((current) => (additive ? [...new Set([...current, ...hits])] : hits));
  }, []);

  // ------------------------------------------------------- copy and paste

  const copies = useCallback((sources, targetPage) => {
    const { items: list, withUid: makeUid } = latest.current;
    const taken = new Set(list.map((item) => item.key));
    let order = list.reduce((max, item) => Math.max(max, item.sortOrder ?? 0), -1);
    return sources.map((source) => {
      const key = uniqueKey(source.key, taken);
      taken.add(key);
      order += 1;
      const copy = { ...source };
      delete copy._uid;
      delete copy._edited;
      delete copy.suggested;
      delete copy.sampleValue;
      return makeUid({
        ...copy,
        key,
        label: source.label?.trim() ? `${source.label.trim()} (copy)`.slice(0, 200) : source.label,
        page: targetPage ?? source.page,
        ...moveBox(source, PASTE_OFFSET, PASTE_OFFSET),
        sortOrder: order,
        source: 'MANUAL',
        confidence: 1,
      });
    });
  }, []);

  const insertCopies = useCallback(
    (sources, targetPage) => {
      if (!sources.length) return;
      const made = copies(sources, targetPage);
      apply((list) => [...list, ...made]);
      setSelectedRaw(made.map((item) => item._uid));
    },
    [apply, copies]
  );

  const duplicate = useCallback(() => insertCopies(selectedItems), [insertCopies, selectedItems]);
  const copy = useCallback(() => {
    if (selectedItems.length) setClipboard(selectedItems.map((item) => ({ ...item })));
  }, [selectedItems]);
  const paste = useCallback(() => insertCopies(clipboard, latest.current.page), [insertCopies, clipboard]);

  // -------------------------------------------------------------- deleting

  /** Asks to delete `uids` (default: the selection); confirmDelete() does it. */
  const requestDelete = useCallback(
    (uids) => {
      const list = (uids && uids.length ? uids : selected).filter((uid) => byUid.has(uid));
      if (list.length) setPendingDelete(list);
    },
    [selected, byUid]
  );
  const confirmDelete = useCallback(() => {
    if (pendingDelete) remove(pendingDelete);
    setPendingDelete(null);
  }, [pendingDelete, remove]);
  const cancelDelete = useCallback(() => setPendingDelete(null), []);

  // ------------------------------------------------------------- arranging

  const arrange = useCallback(
    (transform) => {
      if (selectedItems.length < 2) return;
      const moved = new Map(transform(selectedItems).map((item) => [item._uid, item]));
      apply((list) => list.map((item) => (moved.has(item._uid) ? { ...item, ...pickBox(moved.get(item._uid)) } : item)));
    },
    [apply, selectedItems]
  );

  const align = useCallback((edge) => arrange((boxes) => alignBoxes(boxes, edge)), [arrange]);
  const distributeSelection = useCallback(
    (axis) => {
      if (selectedItems.length < 3) return;
      arrange((boxes) => distribute(boxes, axis));
    },
    [arrange, selectedItems.length]
  );
  /** The selection given the first-selected item's width, height or both. */
  const sameSize = useCallback((dim) => arrange((boxes) => matchSize(boxes, dim, boxes[0])), [arrange]);

  /**
   * Moves (or, with `resize`, grows from the bottom-right) `uids` by
   * (dx, dy). Holding the key is one undo step.
   */
  const nudge = useCallback(
    (uids, dx, dy, { resize = false } = {}) => {
      const set = new Set(uids);
      apply(
        (list) =>
          list.map((item) =>
            set.has(item._uid)
              ? { ...item, ...(resize ? resizeBox(item, 'se', dx, dy) : moveBox(item, dx, dy)) }
              : item
          ),
        { chain: `nudge:${resize ? 'r' : 'm'}:${[...set].sort().join(',')}` }
      );
    },
    [apply]
  );

  /** The arrow key of `event` on item `uid`: nudges it, or the whole selection when it is part of it. */
  const nudgeKey = useCallback(
    (event, uid) => {
      const arrows = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
      const arrow = arrows[event.key];
      if (!arrow) return false;
      const step = event.altKey ? NUDGE_STEPS.fine : event.shiftKey ? NUDGE_STEPS.coarse : NUDGE_STEPS.normal;
      const targets = uid && !selected.includes(uid) ? [uid] : selected;
      if (!targets.length) return false;
      nudge(targets, arrow[0] * step, arrow[1] * step, { resize: event.ctrlKey || event.metaKey });
      return true;
    },
    [nudge, selected]
  );

  /**
   * A drag of item `uid` ended at `box`: a moved box takes the rest of the
   * selection along by the same distance; a resized one changes alone.
   */
  const dragEnd = useCallback(
    (uid, box) => {
      const item = byUid.get(uid);
      if (!item) return;
      const moved = box.fw === item.fw && box.fh === item.fh;
      if (moved && selected.length > 1 && selected.includes(uid)) {
        const dx = box.fx - item.fx;
        const dy = box.fy - item.fy;
        const set = new Set(selected);
        apply((list) =>
          list.map((other) =>
            set.has(other._uid) ? { ...other, ...(other._uid === uid ? clampBox(box) : moveBox(other, dx, dy)) } : other
          )
        );
        return;
      }
      update(uid, pickBox(box));
    },
    [apply, byUid, selected, update]
  );

  // ------------------------------------------------------------ snap, zoom

  /** A snap function for RegionBox while item `uid` is dragged (null when Snap is off). */
  const snapFor = useCallback(
    (uid) => {
      if (!snapOn) return null;
      const self = byUid.get(uid);
      const skip = new Set(selected.includes(uid) ? selected : [uid]);
      const others = items.filter((item) => item.page === self?.page && !skip.has(item._uid));
      return (box, mode) => snapBox(box, others, { grid: SNAP_GRID, threshold: SNAP_THRESHOLD, mode });
    },
    [snapOn, byUid, items, selected]
  );

  const setZoom = useCallback((value) => {
    const n = Number(value);
    setZoomRaw(Math.min(2, Math.max(0.5, Number.isFinite(n) ? n : 1)));
  }, []);
  const zoomIn = useCallback(() => setZoomRaw((z) => ZOOM_LEVELS.find((l) => l > z + 1e-9) ?? z), []);
  const zoomOut = useCallback(() => setZoomRaw((z) => [...ZOOM_LEVELS].reverse().find((l) => l < z - 1e-9) ?? z), []);

  // ------------------------------------------------------------- shortcuts

  const handlers = useRef({});
  handlers.current = { undo, redo, selectAll, duplicate, copy, paste, requestDelete, clearSelection, nudgeKey, selected };

  useEffect(() => {
    if (!enabled) return undefined;
    const onKeyDown = (event) => {
      if (event.defaultPrevented || inTextEntry(event.target)) return;
      const h = handlers.current;
      const mod = event.ctrlKey || event.metaKey;
      const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
      let handled = true;
      if (mod && key === 'z' && !event.shiftKey) h.undo();
      else if (mod && ((key === 'z' && event.shiftKey) || key === 'y')) h.redo();
      else if (mod && key === 'a') h.selectAll();
      else if (mod && key === 'd') h.duplicate();
      else if (mod && key === 'c' && h.selected.length) h.copy();
      else if (mod && key === 'v') h.paste();
      else if ((key === 'Delete' || key === 'Backspace') && h.selected.length) h.requestDelete();
      else if (key === 'Escape' && h.selected.length) h.clearSelection();
      else if (key.startsWith('Arrow') && h.selected.length) handled = h.nudgeKey(event, null);
      else handled = false;
      if (handled) event.preventDefault();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [enabled]);

  return {
    items,
    reset,
    apply,
    quietly,
    update,
    updateMany,
    add,
    remove,
    undo,
    redo,
    canUndo: history.past.length > 0,
    canRedo: history.future.length > 0,
    selected,
    selectedItems,
    isSelected: (uid) => selected.includes(uid),
    select,
    setSelection,
    clearSelection,
    selectAll,
    selectInRect,
    duplicate,
    copy,
    paste,
    canPaste: clipboard.length > 0,
    pendingDelete,
    pendingDeleteItems: (pendingDelete || []).map((uid) => byUid.get(uid)).filter(Boolean),
    requestDelete,
    confirmDelete,
    cancelDelete,
    align,
    distribute: distributeSelection,
    sameSize,
    nudge,
    nudgeKey,
    dragEnd,
    snapOn,
    setSnapOn,
    snapFor,
    guides,
    setGuides,
    zoom,
    setZoom,
    zoomIn,
    zoomOut,
  };
}

function pickBox(item) {
  return Object.fromEntries(BOX_KEYS.map((k) => [k, item[k]]));
}
