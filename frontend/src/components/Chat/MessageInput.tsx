import React, { useState, useRef, useCallback, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import wsService from '../../services/websocket';

export const MessageInput: React.FC = () => {
  const { activeConversation, sendMessage, currentUser } = useApp();
  const [text, setText] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const typingTimeoutRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Auto-focus input when conversation changes
  useEffect(() => {
    if (activeConversation) {
      inputRef.current?.focus();
    }
  }, [activeConversation]);

  const getRecipientEmail = useCallback(() => {
    if (!activeConversation || !currentUser) return '';
    return currentUser.email === activeConversation.user1Email
      ? activeConversation.user2Email
      : activeConversation.user1Email;
  }, [activeConversation, currentUser]);

  const handleTypingStart = useCallback(() => {
    if (!isTyping) {
      setIsTyping(true);
      const recipient = getRecipientEmail();
      if (recipient) {
        wsService.sendTypingIndicator(recipient, true);
      }
    }

    // Reset typing timeout
    if (typingTimeoutRef.current) {
      clearTimeout(typingTimeoutRef.current);
    }
    typingTimeoutRef.current = setTimeout(() => {
      setIsTyping(false);
      const recipient = getRecipientEmail();
      if (recipient) {
        wsService.sendTypingIndicator(recipient, false);
      }
    }, 2000);
  }, [isTyping, getRecipientEmail]);

  const handleSend = () => {
    const trimmed = text.trim();
    if (!trimmed || !activeConversation) return;

    sendMessage(trimmed);
    setText('');

    // Stop typing indicator
    setIsTyping(false);
    const recipient = getRecipientEmail();
    if (recipient) {
      wsService.sendTypingIndicator(recipient, false);
    }

    // Focus back on input
    inputRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  if (!activeConversation) return null;

  const recipientEmail = currentUser?.email === activeConversation.user1Email
    ? activeConversation.user2Email
    : activeConversation.user1Email;

  return (
    <div style={styles.container}>
      {/* Chat Header */}
      <div style={styles.header}>
        <div style={styles.avatar}>
          {recipientEmail.charAt(0).toUpperCase()}
        </div>
        <div style={styles.headerInfo}>
          <span style={styles.headerName}>{recipientEmail}</span>
          <span style={styles.headerStatus}>
            {activeConversation.status === 'ACTIVE' ? 'Online' : activeConversation.status}
          </span>
        </div>
      </div>

      {/* Input Area */}
      <div style={styles.inputArea}>
        <textarea
          ref={inputRef}
          style={styles.textarea}
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            handleTypingStart();
          }}
          onKeyDown={handleKeyDown}
          placeholder="Type a message..."
          rows={1}
        />
        <button
          style={{
            ...styles.sendBtn,
            opacity: text.trim() ? 1 : 0.5,
          }}
          onClick={handleSend}
          disabled={!text.trim()}
        >
          ➤
        </button>
      </div>
    </div>
  );
};

// ============================================================
// Styles
// ============================================================

const styles: Record<string, React.CSSProperties> = {
  container: {
    borderTop: '1px solid #e0e0e0',
    backgroundColor: '#fff',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '10px 16px',
    borderBottom: '1px solid #f0f0f0',
  },
  avatar: {
    width: '36px',
    height: '36px',
    borderRadius: '50%',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontWeight: 'bold',
    fontSize: '16px',
  },
  headerInfo: { display: 'flex', flexDirection: 'column' },
  headerName: { fontWeight: '600', fontSize: '14px' },
  headerStatus: { fontSize: '12px', color: '#00b894' },
  inputArea: {
    display: 'flex',
    alignItems: 'flex-end',
    gap: '8px',
    padding: '12px 16px',
  },
  textarea: {
    flex: 1,
    padding: '10px 14px',
    border: '1px solid #d0d0d0',
    borderRadius: '20px',
    fontSize: '14px',
    resize: 'none',
    outline: 'none',
    fontFamily: 'inherit',
    maxHeight: '120px',
    lineHeight: '1.4',
  },
  sendBtn: {
    width: '42px',
    height: '42px',
    borderRadius: '50%',
    border: 'none',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    fontSize: '18px',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  },
};

export default MessageInput;
