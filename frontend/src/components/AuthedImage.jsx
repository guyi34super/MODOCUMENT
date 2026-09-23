import { useEffect, useState } from 'react';
import { getToken } from '../api/client';

/**
 * An <img> for API-served files: plain image tags can't carry the Bearer
 * header, so the bytes are fetched with it into an object URL (revoked on
 * unmount so previews don't leak memory).
 */
export default function AuthedImage({ path, alt = '', className = '' }) {
  const [url, setUrl] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    let objectUrl = null;
    setUrl(null);
    setFailed(false);
    (async () => {
      try {
        const token = getToken();
        const res = await fetch(path, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        });
        if (!res.ok) throw new Error(`Request failed (${res.status})`);
        const blob = await res.blob();
        objectUrl = URL.createObjectURL(blob);
        if (active) setUrl(objectUrl);
      } catch {
        if (active) setFailed(true);
      }
    })();
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [path]);

  if (failed) {
    return (
      <div className={`flex items-center justify-center bg-pill text-xs text-muted ${className}`}>
        preview unavailable
      </div>
    );
  }
  if (!url) {
    return <div className={`animate-pulse bg-pill ${className}`} aria-label="loading image" />;
  }
  return <img src={url} alt={alt} className={className} />;
}
