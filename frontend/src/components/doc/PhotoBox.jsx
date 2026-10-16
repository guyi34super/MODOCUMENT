import { useState } from 'react';
import { imageContentPath } from '../../api/doccreation';
import AuthedImage from '../AuthedImage';
import { fieldLabel } from './fields';

export const PHOTO_MIME = 'application/x-modoc-image';

/** The photo id carried by a drag (our own type, or plain text). */
export function photoIdFrom(event) {
  const data = event.dataTransfer;
  if (!data) return null;
  const raw = data.getData(PHOTO_MIME) || data.getData('text/plain');
  const idValue = Number(raw);
  return Number.isFinite(idValue) && raw !== '' ? idValue : null;
}

export function startPhotoDrag(event, image) {
  event.dataTransfer.setData(PHOTO_MIME, String(image.id));
  event.dataTransfer.setData('text/plain', String(image.id));
  event.dataTransfer.effectAllowed = 'move';
}

/** A drop target for a photo: the photo placed in the box, or a hint. */
export default function PhotoBox({ instanceId, region, image, locked, onPlace, onFocus, active }) {
  const [over, setOver] = useState(false);
  const label = fieldLabel(region);
  return (
    <div
      role="group"
      aria-label={`${label} — photo box`}
      data-testid={`photo-box-${region.key}`}
      className={`flex h-full w-full items-center justify-center overflow-hidden border-2 border-dashed text-center ${
        over ? 'border-accent bg-pill/70' : 'border-orange-500/70 bg-orange-50/40'
      } ${active ? 'ring-2 ring-accent' : ''}`}
      onDragOver={(event) => {
        if (locked) return;
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        event.preventDefault();
        setOver(false);
        if (locked) return;
        const imageId = photoIdFrom(event);
        if (imageId != null && imageId !== image?.id) onPlace(imageId, region.key);
      }}
      onFocus={onFocus}
    >
      {image ? (
        <div
          draggable={!locked}
          onDragStart={(event) => startPhotoDrag(event, image)}
          className="h-full w-full"
          title={`${image.originalName} — drag onto another photo box to swap`}
        >
          <AuthedImage
            path={imageContentPath(instanceId, image.id)}
            alt={`${label}: ${image.originalName}`}
            className="h-full w-full object-contain"
          />
        </div>
      ) : (
        <span className="px-1 text-[10px] leading-tight text-orange-900">Drop a photo here</span>
      )}
    </div>
  );
}
