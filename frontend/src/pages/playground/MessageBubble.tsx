import { AlertCircle, Check, CheckCheck, Clock, CornerUpLeft, Download, FileText, Pencil, RotateCcw, SmilePlus, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { resolveBackendUrl } from '../../lib/config'
import { formatBytes, formatTime } from '../../lib/format'
import { cx } from '../../lib/util'
import type { Attachment, Message, MessageType } from '../../types'
import { previewOf, type PendingMessage, type TickState } from './chatState'

const QUICK_REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🙏'] as const

const TICK_LABEL: Record<TickState, string> = {
  pending: 'Sending',
  failed: 'Not sent',
  sent: 'Sent',
  delivered: 'Delivered',
  read: 'Read',
}

export function Ticks({ state }: { state: TickState }) {
  const Icon = state === 'pending' ? Clock : state === 'failed' ? AlertCircle : state === 'sent' ? Check : CheckCheck
  return (
    <span className={cx('ticks', `ticks--${state}`)} title={TICK_LABEL[state]} aria-label={TICK_LABEL[state]} role="img">
      <Icon size={state === 'pending' ? 12 : 15} strokeWidth={state === 'pending' ? 2 : 2.25} aria-hidden />
    </span>
  )
}

function AttachmentView({ type, attachment, localPreviewUrl, fileName, fileSize }: { type: MessageType; attachment: Attachment | null; localPreviewUrl?: string | null; fileName?: string | null; fileSize?: number | null }) {
  const url = attachment?.url ? resolveBackendUrl(attachment.url) : null
  const src = localPreviewUrl ?? url
  const name = attachment?.fileName ?? fileName ?? 'attachment'
  const size = attachment?.size ?? fileSize ?? null
  if (type === 'IMAGE' && src) {
    const img = <img src={src} alt={name} loading="lazy" className="msg__image" width={attachment?.width ?? undefined} height={attachment?.height ?? undefined} />
    return url ? (
      <a href={url} target="_blank" rel="noreferrer" className="msg__media" aria-label={`Open ${name}`}>
        {img}
      </a>
    ) : (
      <span className="msg__media">{img}</span>
    )
  }
  if (type === 'VIDEO' && src) return <video className="msg__video" src={src} controls preload="metadata" />
  if (type === 'AUDIO' && url) return <audio className="msg__audio" src={url} controls preload="none" />
  return (
    <span className="msg__file">
      <FileText size={20} aria-hidden />
      <span className="msg__file-text">
        <span className="msg__file-name">{name}</span>
        {size !== null ? <span className="msg__file-size">{formatBytes(size)}</span> : null}
      </span>
      {url ? (
        <a href={url} target="_blank" rel="noreferrer" className="msg__file-dl" aria-label={`Download ${name}`} download>
          <Download size={16} aria-hidden />
        </a>
      ) : null}
    </span>
  )
}

function ReplyQuote({ quoted, senderName, onJump }: { quoted: Message | null; senderName: string | null; onJump?: () => void }) {
  return (
    <button type="button" className="msg__quote" onClick={onJump} disabled={!quoted || !onJump}>
      <span className="msg__quote-sender">{quoted ? senderName : 'Original message'}</span>
      <span className="msg__quote-text">{quoted ? previewOf(quoted) || 'Message' : 'Not loaded. Scroll up to load older messages.'}</span>
    </button>
  )
}

export interface BubbleCapabilities {
  reply: boolean
  react: boolean
  edit: boolean
  delete: boolean
}

export interface MessageBubbleProps {
  message: Message
  own: boolean
  me: string
  senderName: string | null
  /** First bubble of a run from the same sender: shows the sender name (groups) and the tail. */
  firstInRun: boolean
  showSender: boolean
  tick: TickState | null
  quoted: Message | null
  quotedSenderName: string | null
  capabilities: BubbleCapabilities
  onReply: () => void
  onReact: (emoji: string) => void
  onEdit: () => void
  onDelete: () => void
  onJumpTo?: (id: string) => void
  highlighted?: boolean
}

export function MessageBubble({
  message,
  own,
  me,
  senderName,
  firstInRun,
  showSender,
  tick,
  quoted,
  quotedSenderName,
  capabilities,
  onReply,
  onReact,
  onEdit,
  onDelete,
  onJumpTo,
  highlighted,
}: MessageBubbleProps) {
  const [picker, setPicker] = useState(false)

  if (message.type === 'SYSTEM') {
    return (
      <div className="msg-system" id={`msg-${message.id}`}>
        <span>{message.content}</span>
      </div>
    )
  }

  const reactions = Object.entries(message.reactions ?? {}).filter(([, users]) => users.length > 0)
  const hasActions = !message.deleted && (capabilities.reply || capabilities.react || capabilities.edit || capabilities.delete)

  return (
    <div
      className={cx('msg', own ? 'msg--out' : 'msg--in', firstInRun && 'is-first', highlighted && 'is-highlighted')}
      id={`msg-${message.id}`}
      data-seq={message.seq}
    >
      <div className="msg__bubble">
        {showSender && senderName ? <span className="msg__sender">{senderName}</span> : null}
        {message.deleted ? (
          <span className="msg__deleted">{own ? 'You deleted this message' : 'This message was deleted'}</span>
        ) : (
          <>
            {message.forwarded ? <span className="msg__forwarded">Forwarded</span> : null}
            {message.replyToId ? <ReplyQuote quoted={quoted} senderName={quotedSenderName} onJump={quoted && onJumpTo ? () => onJumpTo(quoted.id) : undefined} /> : null}
            {message.attachment || message.type !== 'TEXT' ? <AttachmentView type={message.type} attachment={message.attachment} /> : null}
            {message.content ? <span className="msg__text">{message.content}</span> : null}
          </>
        )}
        <span className="msg__meta">
          {message.edited && !message.deleted ? <span className="msg__edited">edited</span> : null}
          <time dateTime={message.createdAt}>{formatTime(message.createdAt)}</time>
          {own && tick && !message.deleted ? <Ticks state={tick} /> : null}
        </span>
      </div>

      {reactions.length > 0 ? (
        <div className="msg__reactions">
          {reactions.map(([emoji, users]) => {
            const mine = users.includes(me)
            return (
              <button
                key={emoji}
                type="button"
                className={cx('reaction', mine && 'is-mine')}
                onClick={() => onReact(emoji)}
                disabled={!capabilities.react}
                title={users.join(', ')}
                aria-label={`${emoji} ${users.length}${mine ? ', including you' : ''}`}
              >
                <span aria-hidden>{emoji}</span>
                {users.length > 1 ? <span className="reaction__count">{users.length}</span> : null}
              </button>
            )
          })}
        </div>
      ) : null}

      {hasActions ? (
        <div className={cx('msg__actions', picker && 'is-open')}>
          {capabilities.reply ? (
            <button type="button" className="msg__action" onClick={onReply} aria-label="Reply" title="Reply">
              <CornerUpLeft size={14} aria-hidden />
            </button>
          ) : null}
          {capabilities.react ? (
            <span className="msg__react">
              <button type="button" className="msg__action" onClick={() => setPicker((p) => !p)} aria-label="React" aria-expanded={picker} title="React">
                <SmilePlus size={14} aria-hidden />
              </button>
              {picker ? (
                <span className="emoji-picker" role="menu" aria-label="Reactions">
                  {QUICK_REACTIONS.map((emoji) => (
                    <button
                      key={emoji}
                      type="button"
                      role="menuitem"
                      className="emoji-picker__item"
                      onClick={() => {
                        setPicker(false)
                        onReact(emoji)
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Escape') setPicker(false)
                      }}
                    >
                      {emoji}
                    </button>
                  ))}
                </span>
              ) : null}
            </span>
          ) : null}
          {capabilities.edit ? (
            <button type="button" className="msg__action" onClick={onEdit} aria-label="Edit" title="Edit">
              <Pencil size={14} aria-hidden />
            </button>
          ) : null}
          {capabilities.delete ? (
            <button type="button" className="msg__action msg__action--danger" onClick={onDelete} aria-label="Delete for everyone" title="Delete for everyone">
              <Trash2 size={14} aria-hidden />
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

/** Optimistic bubble for a message the server has not acknowledged yet. */
export function PendingBubble({ pending, quoted, quotedSenderName, onRetry, onDiscard }: { pending: PendingMessage; quoted: Message | null; quotedSenderName: string | null; onRetry: () => void; onDiscard: () => void }) {
  const failed = pending.status === 'failed'
  return (
    <div className={cx('msg', 'msg--out', 'is-first', 'is-pending', failed && 'is-failed')}>
      <div className="msg__bubble">
        {pending.replyToId ? <ReplyQuote quoted={quoted} senderName={quotedSenderName} /> : null}
        {pending.type !== 'TEXT' ? (
          <AttachmentView type={pending.type} attachment={pending.attachment} localPreviewUrl={pending.localPreviewUrl} fileName={pending.fileName} fileSize={pending.fileSize} />
        ) : null}
        {pending.content ? <span className="msg__text">{pending.content}</span> : null}
        {pending.status === 'uploading' ? (
          <span className="msg__progress" role="progressbar" aria-label="Upload progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round((pending.progress ?? 0) * 100)}>
            <span style={{ width: `${Math.round((pending.progress ?? 0) * 100)}%` }} />
          </span>
        ) : null}
        <span className="msg__meta">
          <time dateTime={pending.createdAt}>{formatTime(pending.createdAt)}</time>
          <Ticks state={failed ? 'failed' : 'pending'} />
        </span>
      </div>
      {failed ? (
        <div className="msg__failure" role="alert">
          <span>{pending.error ?? 'Not sent.'}</span>
          <button type="button" className="msg__failure-btn" onClick={onRetry}>
            <RotateCcw size={12} aria-hidden /> Retry
          </button>
          <button type="button" className="msg__failure-btn" onClick={onDiscard} aria-label="Discard message">
            <X size={12} aria-hidden />
          </button>
        </div>
      ) : null}
    </div>
  )
}
