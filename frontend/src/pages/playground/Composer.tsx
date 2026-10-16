import { FileText, Paperclip, Pencil, Reply, SendHorizontal, X } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { formatBytes } from '../../lib/format'
import { cx } from '../../lib/util'
import type { Message } from '../../types'
import { previewOf } from './chatState'

export interface ComposerProps {
  disabled?: boolean
  maxLength: number
  allowMedia: boolean
  allowFiles: boolean
  replyTo: { message: Message; senderName: string } | null
  editing: Message | null
  onCancelReply: () => void
  onCancelEdit: () => void
  onSend: (text: string) => void
  onSendFile: (file: File, caption: string) => void
  onEdit: (message: Message, content: string) => void
  onTyping: () => void
  onStopTyping: () => void
  onReject: (message: string) => void
}

function isMediaFile(file: File): boolean {
  return /^(image|video|audio)\//.test(file.type)
}

/** Message input: Enter sends, Shift+Enter adds a line, Escape cancels reply/edit. */
export function Composer({
  disabled,
  maxLength,
  allowMedia,
  allowFiles,
  replyTo,
  editing,
  onCancelReply,
  onCancelEdit,
  onSend,
  onSendFile,
  onEdit,
  onTyping,
  onStopTyping,
  onReject,
}: ComposerProps) {
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [filePreview, setFilePreview] = useState<string | null>(null)
  const areaRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const editingId = useRef<string | null>(null)

  // Prefill when entering edit mode.
  useEffect(() => {
    if (editing && editingId.current !== editing.id) {
      editingId.current = editing.id
      setText(editing.content ?? '')
      areaRef.current?.focus()
    } else if (!editing && editingId.current) {
      editingId.current = null
      setText('')
    }
  }, [editing])

  useEffect(() => {
    if (replyTo) areaRef.current?.focus()
  }, [replyTo])

  useEffect(() => () => {
    if (filePreview) URL.revokeObjectURL(filePreview)
  }, [filePreview])

  // Auto-grow up to ~6 lines.
  useLayoutEffect(() => {
    const el = areaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`
  }, [text])

  const trimmed = text.trim()
  const over = text.length > maxLength
  const canSend = !disabled && !over && (editing ? trimmed.length > 0 && trimmed !== (editing.content ?? '').trim() : trimmed.length > 0 || file !== null)

  const clearFile = () => {
    setFile(null)
    setFilePreview(null)
    if (fileRef.current) fileRef.current.value = ''
  }

  const submit = () => {
    if (!canSend) return
    if (editing) {
      onEdit(editing, trimmed)
      onCancelEdit()
      setText('')
      return
    }
    if (file) {
      onSendFile(file, trimmed)
      clearFile()
    } else {
      onSend(trimmed)
    }
    setText('')
    onStopTyping()
    areaRef.current?.focus()
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    } else if (e.key === 'Escape') {
      if (editing) {
        e.preventDefault()
        onCancelEdit()
      } else if (replyTo) {
        e.preventDefault()
        onCancelReply()
      } else if (file) {
        e.preventDefault()
        clearFile()
      }
    }
  }

  const pickFile = (picked: File | undefined) => {
    if (!picked) return
    const media = isMediaFile(picked)
    if (media && !allowMedia) {
      onReject('Photo, video and audio sharing is turned off for this tenant.')
      return
    }
    if (!media && !allowFiles) {
      onReject('File sharing is turned off for this tenant.')
      return
    }
    setFile(picked)
    setFilePreview(picked.type.startsWith('image/') ? URL.createObjectURL(picked) : null)
    areaRef.current?.focus()
  }

  const canAttach = (allowMedia || allowFiles) && !editing
  const accept = allowMedia && !allowFiles ? 'image/*,video/*,audio/*' : undefined

  return (
    <div className="composer">
      {editing ? (
        <div className="composer__context">
          <Pencil size={14} aria-hidden />
          <span className="composer__context-text">
            <span className="composer__context-title">Editing message</span>
            <span className="composer__context-sub">{previewOf(editing)}</span>
          </span>
          <button type="button" className="composer__context-close" onClick={onCancelEdit} aria-label="Cancel editing">
            <X size={14} aria-hidden />
          </button>
        </div>
      ) : replyTo ? (
        <div className="composer__context">
          <Reply size={14} aria-hidden />
          <span className="composer__context-text">
            <span className="composer__context-title">Replying to {replyTo.senderName}</span>
            <span className="composer__context-sub">{previewOf(replyTo.message) || 'Message'}</span>
          </span>
          <button type="button" className="composer__context-close" onClick={onCancelReply} aria-label="Cancel reply">
            <X size={14} aria-hidden />
          </button>
        </div>
      ) : null}
      {file ? (
        <div className="composer__file">
          {filePreview ? <img src={filePreview} alt="" className="composer__file-thumb" /> : <FileText size={22} aria-hidden />}
          <span className="composer__context-text">
            <span className="composer__context-title">{file.name}</span>
            <span className="composer__context-sub">{formatBytes(file.size)}. Add a caption or press Enter to send.</span>
          </span>
          <button type="button" className="composer__context-close" onClick={clearFile} aria-label="Remove attachment">
            <X size={14} aria-hidden />
          </button>
        </div>
      ) : null}
      <div className="composer__row">
        {canAttach ? (
          <>
            <input ref={fileRef} type="file" className="sr-only" tabIndex={-1} accept={accept} onChange={(e) => pickFile(e.target.files?.[0])} aria-hidden />
            <button type="button" className="composer__icon" onClick={() => fileRef.current?.click()} disabled={disabled} aria-label="Attach a file" title="Attach a file">
              <Paperclip size={18} aria-hidden />
            </button>
          </>
        ) : null}
        <textarea
          ref={areaRef}
          className={cx('composer__input', over && 'is-over')}
          rows={1}
          value={text}
          placeholder={disabled ? 'Connecting…' : file ? 'Add a caption' : 'Message'}
          aria-label="Message"
          disabled={disabled}
          onChange={(e) => {
            setText(e.target.value)
            if (e.target.value && !editing) onTyping()
          }}
          onKeyDown={onKeyDown}
          onBlur={onStopTyping}
        />
        {text.length > maxLength * 0.8 ? (
          <span className={cx('composer__count', over && 'is-over')} aria-live="polite">
            {maxLength - text.length}
          </span>
        ) : null}
        <button type="button" className="composer__send" onClick={submit} disabled={!canSend} aria-label={editing ? 'Save edit' : 'Send message'} title={editing ? 'Save edit' : 'Send'}>
          <SendHorizontal size={18} aria-hidden />
        </button>
      </div>
    </div>
  )
}
