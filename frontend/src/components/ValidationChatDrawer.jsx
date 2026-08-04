import { useEffect, useRef, useState } from 'react';
import { Button, inputClass } from './ui';

const BULK_PROMPT = 'Fill empty required fields';

function buildQuickPrompts(chatContext) {
  const prompts = [];
  if (chatContext?.row != null && chatContext?.column) {
    prompts.push('Fix this cell');
  }
  if (chatContext?.row != null) {
    prompts.push('Fix this row');
  }
  if (chatContext?.column) {
    prompts.push(`Fix ${chatContext.column} column`);
  }
  prompts.push(BULK_PROMPT);
  return prompts;
}

export default function ValidationChatDrawer({
  open,
  onOpenChange,
  messages,
  onSend,
  busy,
  disabledNote = '',
  showQuickPrompts,
  chatContext = null,
}) {
  const [draft, setDraft] = useState('');
  const listRef = useRef(null);
  const blocked = busy || Boolean(disabledNote);
  const quickPrompts = showQuickPrompts ? buildQuickPrompts(chatContext) : [];

  useEffect(() => {
    if (open && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [open, messages]);

  const submit = (text) => {
    const trimmed = text.trim();
    if (!trimmed || blocked) return;
    onSend(trimmed);
    setDraft('');
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    submit(draft);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit(draft);
    }
  };

  const contextLabel =
    chatContext?.row != null && chatContext?.column
      ? `Row ${chatContext.row} · ${chatContext.column}`
      : chatContext?.row != null
        ? `Row ${chatContext.row}`
        : chatContext?.column
          ? chatContext.column
          : null;

  return (
    <>
      {!open && (
        <button
          type="button"
          onClick={() => onOpenChange(true)}
          className="fixed bottom-6 right-6 z-40 flex items-center gap-2 rounded-full bg-accent px-4 py-2.5 text-sm font-medium text-white shadow-lg hover:bg-accent-hover"
          aria-label="Open AI assistant"
        >
          <span aria-hidden>💬</span>
          AI assistant
        </button>
      )}

      {open && (
        <div
          className="fixed inset-x-0 bottom-0 z-40 flex max-h-[70vh] flex-col rounded-t-xl border border-line bg-white shadow-2xl sm:inset-x-auto sm:right-6 sm:bottom-6 sm:w-[360px] sm:max-h-[45vh] sm:rounded-xl"
          role="dialog"
          aria-label="AI assistant"
        >
          <div className="flex items-center justify-between border-b border-line px-4 py-3">
            <h3 className="text-sm font-medium">AI assistant</h3>
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              className="rounded px-2 py-1 text-sm text-muted hover:bg-neutral-100"
              aria-label="Close AI assistant"
            >
              Close
            </button>
          </div>

          <div ref={listRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-3">
            {messages.length === 0 ? (
              <p className="text-sm text-muted">
                Ask the assistant to fix validation issues in your mapped data. After each edit,
                click Re-validate to refresh errors.
              </p>
            ) : (
              messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[85%] rounded-lg px-3 py-2 text-sm ${
                      msg.role === 'user'
                        ? 'bg-accent text-white'
                        : 'border border-line bg-sidebar text-ink'
                    }`}
                  >
                    {msg.text}
                  </div>
                </div>
              ))
            )}
            {busy && (
              <p className="text-xs text-muted">Applying your changes…</p>
            )}
          </div>

          {contextLabel && (
            <p className="border-t border-line px-4 py-2 text-xs text-muted">
              Context: {contextLabel}
            </p>
          )}

          {showQuickPrompts && quickPrompts.length > 0 && (
            <div className="flex flex-wrap gap-1 border-t border-line px-4 py-2">
              {quickPrompts.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  disabled={blocked}
                  onClick={() => submit(prompt)}
                  className="rounded-full bg-neutral-100 px-2.5 py-1 text-xs text-ink hover:bg-pill disabled:opacity-50"
                >
                  {prompt}
                </button>
              ))}
            </div>
          )}

          {disabledNote && (
            <p className="border-t border-line bg-amber-100/60 px-4 py-2 text-xs text-amber-800">
              {disabledNote}
            </p>
          )}

          <form onSubmit={handleSubmit} className="border-t border-line p-3">
            <div className="flex gap-2">
              <textarea
                rows={2}
                className={`${inputClass} min-h-[2.5rem] flex-1 resize-none`}
                placeholder='e.g. "fix this cell" or "trim Email column"'
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={blocked}
              />
              <Button type="submit" disabled={blocked || !draft.trim()}>
                Send
              </Button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
