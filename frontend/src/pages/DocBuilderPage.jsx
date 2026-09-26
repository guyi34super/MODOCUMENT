import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  addBlock,
  assetContentPath,
  assignAsset,
  deleteAsset,
  deleteBlock,
  downloadInstance,
  generateInstance,
  getInstance,
  reorderBlocks,
  updateBlock,
  uploadAssets,
} from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import AuthedImage from '../components/AuthedImage';
import { Button, Callout, Card, ErrorBanner, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

/** A paragraph block: click to edit in place, save/cancel while dirty. */
function ParagraphBlock({ block, onChanged }) {
  const toast = useToast();
  const [text, setText] = useState(block.text || '');
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    try {
      await toast.promise(() => updateBlock(block.blockId, { text }), {
        loading: 'Saving…',
        success: 'Paragraph saved',
        error: (err) => err.message,
      });
      setEditing(false);
      onChanged();
    } catch {
      // The error toast is already shown.
    } finally {
      setSaving(false);
    }
  };

  if (!editing) {
    return (
      <button
        type="button"
        className="block w-full whitespace-pre-wrap rounded border border-transparent px-2 py-1 text-left text-sm hover:border-line hover:bg-sidebar/60"
        title="Click to edit"
        onClick={() => setEditing(true)}
      >
        {block.text?.trim() ? block.text : <span className="text-muted">Empty paragraph — click to write…</span>}
      </button>
    );
  }
  return (
    <div>
      <textarea
        autoFocus
        rows={Math.min(10, Math.max(3, text.split('\n').length + 1))}
        className={`${inputClass} font-normal`}
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      <div className="mt-1 flex gap-2">
        <Button size="sm" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save'}
        </Button>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => {
            setText(block.text || '');
            setEditing(false);
          }}
        >
          Cancel
        </Button>
      </div>
    </div>
  );
}

/** An image block: thumbnail, caption editor, and "move to Unsorted". */
function ImageBlock({ block, onChanged }) {
  const toast = useToast();
  const [caption, setCaption] = useState(block.caption || '');
  const dirty = caption !== (block.caption || '');

  const saveCaption = async () => {
    try {
      await toast.promise(() => updateBlock(block.blockId, { caption }), {
        loading: 'Saving caption…',
        success: 'Caption saved',
        error: (err) => err.message,
      });
      onChanged();
    } catch {
      // The error toast is already shown.
    }
  };

  const unplace = async () => {
    try {
      await toast.promise(() => deleteBlock(block.blockId), {
        loading: 'Moving to Unsorted…',
        success: 'Image moved to Unsorted',
        error: (err) => err.message,
      });
      onChanged();
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div className="flex flex-wrap items-start gap-3">
      <AuthedImage
        path={assetContentPath(block.assetId)}
        alt={block.assetFileName || 'image'}
        className="h-24 w-32 rounded border border-line object-cover"
      />
      <div className="min-w-52 flex-1">
        <p className="mb-1 truncate text-xs text-muted">{block.assetFileName}</p>
        <input
          className={`${inputClass} text-sm`}
          placeholder="Caption (optional)"
          value={caption}
          onChange={(e) => setCaption(e.target.value)}
        />
        <div className="mt-1 flex gap-2">
          {dirty && (
            <Button size="sm" onClick={saveCaption}>
              Save caption
            </Button>
          )}
          <Button size="sm" variant="secondary" onClick={unplace}>
            Move to Unsorted
          </Button>
        </div>
      </div>
    </div>
  );
}

/** One blueprint section with its ordered blocks and an "add paragraph" action. */
function SectionCard({ instanceId, section, onChanged }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  const addParagraph = async () => {
    setBusy(true);
    try {
      await toast.promise(
        () => addBlock(instanceId, section.sectionId, { blockType: 'PARAGRAPH', text: '' }),
        { loading: 'Adding paragraph…', success: 'Paragraph added', error: (err) => err.message }
      );
      onChanged();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const move = async (index, delta) => {
    const ids = section.blocks.map((b) => b.blockId);
    const target = index + delta;
    if (target < 0 || target >= ids.length) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    try {
      await reorderBlocks(instanceId, section.sectionId, ids);
      onChanged();
    } catch (err) {
      toast.error(err.message);
    }
  };

  return (
    <Card className="mb-4">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="rounded bg-pill px-2 py-0.5 text-xs text-muted">{section.orderIndex + 1}</span>
        <h3 className="text-base font-medium">{section.title}</h3>
        {section.keywords.length > 0 && (
          <span className="text-xs text-muted">keywords: {section.keywords.join(', ')}</span>
        )}
      </div>
      {section.blocks.length === 0 && (
        <p className="mb-2 text-sm text-muted">Nothing added to this section yet.</p>
      )}
      <ul className="space-y-3">
        {section.blocks.map((block, index) => (
          <li key={block.blockId} className="flex items-start gap-2">
            <div className="flex flex-col gap-0.5 pt-1">
              <button
                type="button"
                aria-label="Move up"
                className="rounded px-1 text-xs text-muted hover:bg-pill disabled:opacity-30"
                disabled={index === 0}
                onClick={() => move(index, -1)}
              >
                ▲
              </button>
              <button
                type="button"
                aria-label="Move down"
                className="rounded px-1 text-xs text-muted hover:bg-pill disabled:opacity-30"
                disabled={index === section.blocks.length - 1}
                onClick={() => move(index, 1)}
              >
                ▼
              </button>
            </div>
            <div className="min-w-0 flex-1">
              {block.blockType === 'PARAGRAPH' ? (
                <ParagraphBlock block={block} onChanged={onChanged} />
              ) : (
                <ImageBlock block={block} onChanged={onChanged} />
              )}
            </div>
            {block.blockType === 'PARAGRAPH' && (
              <button
                type="button"
                aria-label="Delete block"
                className="pt-1 text-sm text-muted hover:text-danger-600"
                onClick={async () => {
                  try {
                    await deleteBlock(block.blockId);
                    onChanged();
                  } catch (err) {
                    toast.error(err.message);
                  }
                }}
              >
                ✕
              </button>
            )}
          </li>
        ))}
      </ul>
      <div className="mt-3">
        <Button size="sm" variant="secondary" onClick={addParagraph} disabled={busy}>
          + Add paragraph
        </Button>
      </div>
    </Card>
  );
}

export default function DocBuilderPage() {
  const { id } = useParams();
  const toast = useToast();
  const fileInputRef = useRef(null);
  const [doc, setDoc] = useState(null);
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [generating, setGenerating] = useState(false);

  const load = useCallback(async () => {
    try {
      setDoc(await getInstance(id));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  const bulkUpload = async (files) => {
    if (!files || files.length === 0) return;
    setUploading(true);
    try {
      await toast.promise(() => uploadAssets(id, Array.from(files), true), {
        loading: `Uploading ${files.length} image${files.length === 1 ? '' : 's'}…`,
        success: (result) => {
          const parts = [];
          if (result.assigned.length) parts.push(`${result.assigned.length} auto-assigned`);
          if (result.unsorted.length) parts.push(`${result.unsorted.length} in Unsorted`);
          if (result.rejected.length) parts.push(`${result.rejected.length} rejected`);
          return parts.join(', ') || 'Nothing uploaded';
        },
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const generate = async () => {
    setGenerating(true);
    try {
      await toast.promise(() => generateInstance(id), {
        loading: 'Generating the document…',
        success: (r) =>
          `Document generated (${r.pdfReady ? '.docx + PDF' : '.docx only'})${
            r.composedBy === 'AI' ? ' · composed by AI' : ''
          }`,
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setGenerating(false);
    }
  };

  const download = async (format) => {
    try {
      await toast.promise(
        () => downloadInstance(id, format, `${doc?.title || 'document'}.${format}`),
        { loading: 'Preparing download…', success: 'Download started', error: (err) => err.message }
      );
    } catch {
      // The error toast is already shown.
    }
  };

  const reassign = async (assetId, sectionId) => {
    try {
      await toast.promise(() => assignAsset(assetId, sectionId ? Number(sectionId) : null), {
        loading: 'Placing image…',
        success: 'Image placed',
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  const removeAsset = async (asset) => {
    if (!window.confirm(`Delete image "${asset.originalFileName}"?`)) return;
    try {
      await toast.promise(() => deleteAsset(asset.assetId), {
        loading: 'Deleting image…',
        success: 'Image deleted',
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'My Documents', to: '/creation/documents' }, { label: doc?.title || `#${id}` }]}
        title={doc?.title || 'Document'}
        subtitle={doc ? `Blueprint: ${doc.blueprintName}` : undefined}
        actions={
          doc && (
            <>
              <Button onClick={generate} disabled={generating}>
                {generating ? 'Generating…' : 'Generate'}
              </Button>
              <Button variant="secondary" onClick={() => download('docx')}>
                Download .docx
              </Button>
              <Button
                variant="secondary"
                onClick={() => download('pdf')}
                disabled={!doc.pdfAvailable}
                title={doc.pdfAvailable ? undefined : 'PDF conversion is not available on this server'}
              >
                Download PDF
              </Button>
            </>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!doc ? (
        <Spinner />
      ) : (
        <>
          <Card className="mb-4">
            <div className="flex flex-wrap items-center gap-3">
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept=".png,.jpg,.jpeg,.gif,.bmp"
                className="block text-sm text-muted file:mr-3 file:cursor-pointer file:rounded file:border-0 file:bg-pill file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-accent"
                onChange={(e) => bulkUpload(e.target.files)}
                disabled={uploading}
              />
              <p className="text-xs text-muted">
                Drop all your photos at once — file names matching a section's keywords are placed
                automatically; the rest wait in Unsorted.
              </p>
            </div>
          </Card>

          {doc.unsortedAssets.length > 0 && (
            <div className="mb-4">
              <Callout variant="info" title={`Unsorted images (${doc.unsortedAssets.length})`}>
                <ul className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {doc.unsortedAssets.map((asset) => (
                    <li key={asset.assetId} className="rounded border border-line bg-white p-2">
                      <AuthedImage
                        path={assetContentPath(asset.assetId)}
                        alt={asset.originalFileName}
                        className="mb-1 h-24 w-full rounded object-cover"
                      />
                      <p className="mb-1 truncate text-xs text-muted" title={asset.originalFileName}>
                        {asset.originalFileName}
                      </p>
                      <div className="flex items-center gap-2">
                        <select
                          aria-label={`Assign ${asset.originalFileName} to a section`}
                          className={`${inputClass} !py-1 text-xs`}
                          defaultValue=""
                          onChange={(e) => e.target.value && reassign(asset.assetId, e.target.value)}
                        >
                          <option value="">Place in section…</option>
                          {doc.sections.map((s) => (
                            <option key={s.sectionId} value={s.sectionId}>
                              {s.title}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          aria-label={`Delete ${asset.originalFileName}`}
                          className="text-sm text-muted hover:text-danger-600"
                          onClick={() => removeAsset(asset)}
                        >
                          ✕
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              </Callout>
            </div>
          )}

          {doc.sections.map((section) => (
            <SectionCard
              key={section.sectionId}
              instanceId={Number(id)}
              section={section}
              onChanged={load}
            />
          ))}
        </>
      )}
    </div>
  );
}
