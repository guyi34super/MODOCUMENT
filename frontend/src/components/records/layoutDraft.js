// Helpers for the builder's "Edit layout" mode (flow-records contract §4,
// "Layout from the builder"): the document's regions become an editable
// draft that is saved to the blueprint through PUT /blueprints/{id}/overlay.

import { uniqueKey } from '../pdf/geometry';
import { cleanProps } from '../pdf/RegionPropertiesPanel';

/** The RegionDto fields the overlay endpoint takes (no sampleValue / suggested). */
export const REGION_DTO_FIELDS = [
  'key', 'label', 'group', 'kind', 'page', 'fx', 'fy', 'fw', 'fh', 'fontName', 'fontSize', 'color',
  'align', 'maxLines', 'acroFieldName', 'required', 'sortOrder', 'source', 'confidence', 'props',
];

/** A draft region as the contract's RegionDto (UI-only fields and review-only values stripped). */
export function toRegionDto(region) {
  const dto = Object.fromEntries(REGION_DTO_FIELDS.map((f) => [f, region[f] ?? null]));
  dto.props = cleanProps(region.props);
  return dto;
}

let uidCounter = 0;
/** A copy of `region` with a stable client-side id (`_uid`) that survives key edits. */
export function withUid(region) {
  uidCounter += 1;
  return { ...region, _uid: `l${uidCounter}` };
}

/** A new TEXT field drawn on `page` at `box`, with a unique key and the next sort order. */
export function newRegion(box, page, regions) {
  const taken = new Set(regions.map((r) => r.key));
  return withUid({
    key: uniqueKey('field', taken),
    label: '',
    group: null,
    kind: 'TEXT',
    page,
    ...box,
    fontName: 'Helvetica',
    fontSize: 0,
    color: '#000000',
    align: 'LEFT',
    maxLines: 1,
    acroFieldName: null,
    required: false,
    sortOrder: regions.reduce((max, r) => Math.max(max, r.sortOrder ?? 0), -1) + 1,
    source: 'MANUAL',
    confidence: 1,
  });
}
