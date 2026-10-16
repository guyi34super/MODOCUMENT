import { useEffect, useState } from 'react';
import { instancePagePath } from '../../api/doccreation';
import AuthedImage from '../AuthedImage';
import Modal from '../Modal';
import { Button } from '../ui';

/** The preview: the server's rendering of each page with the values in place. */
export default function PreviewDialog({ open, onClose, instanceId, pages }) {
  const [index, setIndex] = useState(0);
  useEffect(() => {
    if (open) setIndex(0);
  }, [open]);
  const count = pages.length;
  return (
    <Modal open={open} onClose={onClose} title="Preview" size="lg" description="The pages exactly as they will be generated.">
      {open && count > 0 && (
        <div>
          <div className="mb-2 flex items-center justify-center gap-3">
            <Button size="sm" variant="secondary" onClick={() => setIndex(index - 1)} disabled={index === 0}>
              Previous page
            </Button>
            <span aria-live="polite">
              Page {index + 1} of {count}
            </span>
            <Button size="sm" variant="secondary" onClick={() => setIndex(index + 1)} disabled={index >= count - 1}>
              Next page
            </Button>
          </div>
          <AuthedImage
            key={index}
            path={instancePagePath(instanceId, index, 110)}
            alt={`Preview of page ${index + 1}`}
            className="mx-auto max-h-[70vh] w-auto border border-line"
          />
        </div>
      )}
    </Modal>
  );
}
