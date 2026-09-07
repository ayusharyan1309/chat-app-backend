import React, { useEffect, useRef } from 'react';
import { useApp } from '../../context/AppContext';
import type { ChatMessage } from '../../types';

export const MessageList: React.FC = () => {
  const { messages, messagesLoading, currentUser, activeConversation, typingUsers, loadMoreMessages } = useApp();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const isOwnMessage = (msg: ChatMessage) => {
    return currentUser?.email === msg.senderEmail;
  };

  const formatTime = (dateStr: string) => {
    return new Date(dateStr).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
    if (diffDays === 0) return 'Today';
    if (diffDays === 1) return 'Yesterday';
    return date.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
  };

  // Group messages by date
  const groupedMessages: { date: string; messages: ChatMessage[] }[] = [];
  let currentDate = '';
  for (const msg of messages) {
    const msgDate = formatDate(msg.createdAt);
    if (msgDate !== currentDate) {
      currentDate = msgDate;
      groupedMessages.push({ date: msgDate, messages: [] });
    }
    groupedMessages[groupedMessages.length - 1].messages.push(msg);
  }

  // Get typing indicator text
  const typingUser = activeConversation && currentUser
    ? (currentUser.email === activeConversation.user1Email
        ? activeConversation.user2Email
        : activeConversation.user1Email)
    : null;
  const isTyping = typingUser && typingUsers.has(typingUser);

  if (!activeConversation) {
    return (
      <div style={styles.emptyState}>
        <div style={styles.emptyIcon}>💬</div>
        <h3 style={styles.emptyTitle}>Select a conversation</h3>
        <p style={styles.emptyDesc}>Choose a conversation from the sidebar to start chatting</p>
      </div>
    );
  }

  return (
    <div ref={containerRef} style={styles.container}>
      {/* Load More Button */}
      {messages.length > 0 && (
        <div style={styles.loadMoreContainer}>
          <button style={styles.loadMoreBtn} onClick={loadMoreMessages}>
            ↑ Load older messages
          </button>
        </div>
      )}

      {/* Messages */}
      {groupedMessages.map((group) => (
        <div key={group.date}>
          {/* Date Separator */}
          <div style={styles.dateSeparator}>
            <span style={styles.dateText}>{group.date}</span>
          </div>

          {/* Messages for this date */}
          {group.messages.map((msg, index) => {
            const own = isOwnMessage(msg);
            const showAvatar = index === 0 ||
              group.messages[index - 1].senderEmail !== msg.senderEmail;

            return (
              <div
                key={msg.id}
                style={{
                  ...styles.messageRow,
                  justifyContent: own ? 'flex-end' : 'flex-start',
                }}
              >
                {/* Other user avatar */}
                {!own && (
                  <div style={{
                    ...styles.msgAvatar,
                    visibility: showAvatar ? 'visible' : 'hidden',
                  }}>
                    {msg.senderEmail.charAt(0).toUpperCase()}
                  </div>
                )}

                {/* Message bubble */}
                <div style={{
                  ...styles.bubble,
                  ...(own ? styles.bubbleOwn : styles.bubbleOther),
                }}>
                  {!own && showAvatar && (
                    <div style={styles.senderName}>{msg.senderName || msg.senderEmail}</div>
                  )}
                  <div style={styles.messageContent}>{msg.content}</div>
                  <div style={styles.messageMeta}>
                    <span style={styles.messageTime}>{formatTime(msg.createdAt)}</span>
                    {own && (
                      <span style={styles.messageStatus}>
                        {msg.status === 'READ' ? '✓✓' : msg.status === 'DELIVERED' ? '✓✓' : '✓'}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ))}

      {/* Typing Indicator */}
      {isTyping && (
        <div style={styles.typingIndicator}>
          <div style={styles.typingDots}>
            <span style={styles.dot}>●</span>
            <span style={{ ...styles.dot, animationDelay: '0.2s' }}>●</span>
            <span style={{ ...styles.dot, animationDelay: '0.4s' }}>●</span>
          </div>
          <span style={styles.typingText}>{typingUser} is typing...</span>
        </div>
      )}

      {/* Loading */}
      {messagesLoading && (
        <div style={styles.loading}>Loading messages...</div>
      )}

      <div ref={messagesEndRef} />
    </div>
  );
};

// ============================================================
// Styles
// ============================================================

const styles: Record<string, React.CSSProperties> = {
  container: {
    flex: 1,
    overflowY: 'auto',
    padding: '16px',
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
  },
  emptyState: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#888',
  },
  emptyIcon: { fontSize: '64px', marginBottom: '16px' },
  emptyTitle: { fontSize: '20px', fontWeight: 'bold', marginBottom: '8px', color: '#333' },
  emptyDesc: { fontSize: '14px' },
  loadMoreContainer: { textAlign: 'center', marginBottom: '12px' },
  loadMoreBtn: {
    padding: '6px 16px',
    backgroundColor: '#f0f0f0',
    border: 'none',
    borderRadius: '16px',
    fontSize: '13px',
    color: '#666',
    cursor: 'pointer',
  },
  dateSeparator: {
    textAlign: 'center',
    margin: '16px 0',
    position: 'relative',
  },
  dateText: {
    backgroundColor: '#f0f0f0',
    padding: '4px 12px',
    borderRadius: '12px',
    fontSize: '12px',
    color: '#666',
  },
  messageRow: {
    display: 'flex',
    alignItems: 'flex-end',
    gap: '8px',
    marginBottom: '4px',
  },
  msgAvatar: {
    width: '32px',
    height: '32px',
    borderRadius: '50%',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '14px',
    fontWeight: 'bold',
    flexShrink: 0,
  },
  bubble: {
    maxWidth: '65%',
    padding: '8px 14px',
    borderRadius: '18px',
    fontSize: '14px',
    lineHeight: '1.4',
    wordBreak: 'break-word',
  },
  bubbleOwn: {
    backgroundColor: '#6c5ce7',
    color: '#fff',
    borderBottomRightRadius: '4px',
  },
  bubbleOther: {
    backgroundColor: '#f0f0f0',
    color: '#333',
    borderBottomLeftRadius: '4px',
  },
  senderName: { fontSize: '11px', fontWeight: '600', color: '#6c5ce7', marginBottom: '2px' },
  messageContent: {},
  messageMeta: {
    display: 'flex',
    justifyContent: 'flex-end',
    alignItems: 'center',
    gap: '4px',
    marginTop: '2px',
  },
  messageTime: { fontSize: '11px', opacity: 0.7 },
  messageStatus: { fontSize: '12px' },
  typingIndicator: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 16px',
  },
  typingDots: { display: 'flex', gap: '2px' },
  dot: { fontSize: '12px', color: '#999', animation: 'pulse 1.4s infinite' },
  typingText: { fontSize: '13px', color: '#999', fontStyle: 'italic' },
  loading: { textAlign: 'center', padding: '16px', color: '#888' },
};

export default MessageList;
