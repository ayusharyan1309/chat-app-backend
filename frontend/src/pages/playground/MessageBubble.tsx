import { AlertCircle, Check, CheckCheck, Clock, CornerUpLeft, Download, FileText, Pencil, RotateCcw, SmilePlus, Trash2, X } from 'lucide-react'
import { memo, useState } from 'react'
import { Menu, type MenuItem } from '../../components/ui/Misc'
import { useBeforeDeadline } from '../../hooks/useNow'
import { formatBytes, formatTime } from '../../lib/format'
import { safeMediaUrl } from '../../lib/mediaUrl'
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
    <span className={cx('ticks', `ticks--${state}`)} title={TICK_LABEL[state]}>
      <Icon size={state === 'pending' ? 12 : 15} strokeWidth={state === 'pending' ? 2 : 2.25} aria-hidden />
      <span className="sr-only">{TICK_LABEL[state]}</span>
    </span>
  )
}

function AttachmentView({ type, attachment, localPreviewUrl, fileName, fileSize }: { type: MessageType; attachment: Attachment | null; localPreviewUrl?: string | null; fileName?: string | null; fileSize?: number | null }) {
  // Only the backend's signed media endpoint is rendered or linked. Anything else (another host,
  // `javascript:`, `data:`) is shown as plain text. `localPreviewUrl` is a blob: URL this tab made.
  const url = safeMediaUrl(attachment?.url, 'attachment')
  const blocked = Boolean(attachment?.url) && url === null
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
  // User-uploaded chat media has no caption tracks to offer; the file name is the accessible name.
  // oxlint-disable-next-line jsx-a11y/media-has-caption
  if (type === 'VIDEO' && src) return <video className="msg__video" src={src} controls preload="metadata" aria-label={name} />
  // oxlint-disable-next-line jsx-a11y/media-has-caption
  if (type === 'AUDIO' && url) return <audio className="msg__audio" src={url} controls preload="none" aria-label={name} />
  return (
    <span className="msg__file">
      <FileText size={20} aria-hidden />
      <span className="msg__file-text">
        <span className="msg__file-name">{name}</span>
        {size !== null ? <span className="msg__file-size">{formatBytes(size)}</span> : null}
        {blocked ? <span className="msg__file-size">Not served by this backend, so it is not linked.</span> : null}
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

/** What the viewer may do in this conversation; built once per conversation view, not per bubble. */
export interface BubblePermissions {
  reply: boolean
  react: boolean
  /** Own text messages may be edited for this long after sending; 0 = editing off. */
  editWindowMs: number
  deleteOwn: boolean
  /** Group admins may delete anyone's message. */
  deleteOthers: boolean
  /** Show delivered/read ticks (`read-receipts`); otherwise own messages only show "sent". */
  receipts: boolean
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
  permissions: BubblePermissions
  /** Handlers receive the message so one stable callback serves every bubble. */
  onReply: (message: Message) => void
  onReact: (message: Message, emoji: string) => void
  onEdit: (message: Message) => void
  onDelete: (message: Message) => void
  onJumpTo?: (id: string) => void
  highlighted?: boolean
}

function MessageBubbleImpl({
  message,
  own,
  me,
  senderName,
  firstInRun,
  showSender,
  tick,
  quoted,
  quotedSenderName,
  permissions,
  onReply,
  onReact,
  onEdit,
  onDelete,
  onJumpTo,
  highlighted,
}: MessageBubbleProps) {
  const [pickerOpen, setPickerOpen] = useState(false)
  const editable = own && message.type === 'TEXT' && !message.deleted && permissions.editWindowMs > 0
  // One timer per recently sent own message hides "Edit" when the window closes; none otherwise.
  const canEdit = useBeforeDeadline(editable ? Date.parse(message.createdAt) + permissions.editWindowMs : null)

  if (message.type === 'SYSTEM') {
    return (
      <div className="msg-system" data-msg-id={message.id}>
        <span>{message.content}</span>
      </div>
    )
  }

  const canDelete = own ? permissions.deleteOwn : permissions.deleteOthers
  const reactions = Object.entries(message.reactions ?? {}).filter(([, users]) => users.length > 0)
  const hasActions = !message.deleted && (permissions.reply || permissions.react || canEdit || canDelete)
  const reactionItems: MenuItem[] = QUICK_REACTIONS.map((emoji) => ({
    key: emoji,
    label: emoji,
    ariaLabel: `React with ${emoji}`,
    onSelect: () => onReact(message, emoji),
  }))

  return (
    <div className={cx('msg', own ? 'msg--out' : 'msg--in', firstInRun && 'is-first', highlighted && 'is-highlighted')} data-msg-id={message.id} data-seq={message.seq}>
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
                onClick={() => onReact(message, emoji)}
                disabled={!permissions.react}
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
        <div className={cx('msg__actions', pickerOpen && 'is-open')}>
          {permissions.reply ? (
            <button type="button" className="msg__action" onClick={() => onReply(message)} aria-label="Reply" title="Reply">
              <CornerUpLeft size={14} aria-hidden />
            </button>
          ) : null}
          {permissions.react ? (
            <Menu
              label="Reactions"
              orientation="horizontal"
              className="msg__react"
              listClassName="emoji-picker"
              itemClassName="emoji-picker__item"
              onOpenChange={setPickerOpen}
              items={reactionItems}
              trigger={(props) => (
                <button type="button" className="msg__action" aria-label="React" title="React" {...props}>
                  <SmilePlus size={14} aria-hidden />
                </button>
              )}
            />
          ) : null}
          {canEdit ? (
            <button type="button" className="msg__action" onClick={() => onEdit(message)} aria-label="Edit" title="Edit">
              <Pencil size={14} aria-hidden />
            </button>
          ) : null}
          {canDelete ? (
            <button type="button" className="msg__action msg__action--danger" onClick={() => onDelete(message)} aria-label="Delete for everyone" title="Delete for everyone">
              <Trash2 size={14} aria-hidden />
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

/** Memoised: with stable handlers, a new message or receipt re-renders only the bubbles it affects. */
export const MessageBubble = memo(MessageBubbleImpl)

export interface PendingBubbleProps {
  pending: PendingMessage
  quoted: Message | null
  quotedSenderName: string | null
  onRetry: (clientMsgId: string) => void
  onDiscard: (clientMsgId: string) => void
}

/** Optimistic bubble for a message the server has not acknowledged yet. */
function PendingBubbleImpl({ pending, quoted, quotedSenderName, onRetry, onDiscard }: PendingBubbleProps) {
  const failed = pending.status === 'failed'
  const percent = Math.round((pending.progress ?? 0) * 100)
  return (
    <div className={cx('msg', 'msg--out', 'is-first', 'is-pending', failed && 'is-failed')}>
      <div className="msg__bubble">
        {pending.replyToId ? <ReplyQuote quoted={quoted} senderName={quotedSenderName} /> : null}
        {pending.type !== 'TEXT' ? (
          <AttachmentView type={pending.type} attachment={pending.attachment} localPreviewUrl={pending.localPreviewUrl} fileName={pending.fileName} fileSize={pending.fileSize} />
        ) : null}
        {pending.content ? <span className="msg__text">{pending.content}</span> : null}
        {pending.status === 'uploading' ? <progress className="msg__progress" aria-label="Upload progress" max={100} value={percent} /> : null}
        <span className="msg__meta">
          <time dateTime={pending.createdAt}>{formatTime(pending.createdAt)}</time>
          <Ticks state={failed ? 'failed' : 'pending'} />
        </span>
      </div>
      {failed ? (
        <div className="msg__failure" role="alert">
          <span>{pending.error ?? 'Not sent.'}</span>
          <button type="button" className="msg__failure-btn" onClick={() => onRetry(pending.clientMsgId)}>
            <RotateCcw size={12} aria-hidden /> Retry
          </button>
          <button type="button" className="msg__failure-btn" onClick={() => onDiscard(pending.clientMsgId)} aria-label="Discard message">
            <X size={12} aria-hidden />
          </button>
        </div>
      ) : null}
    </div>
  )
}

export const PendingBubble = memo(PendingBubbleImpl)
