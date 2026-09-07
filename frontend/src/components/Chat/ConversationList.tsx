import React, { useState, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import type { Conversation } from '../../types';

export const ConversationList: React.FC = () => {
  const {
    conversations,
    activeConversation,
    selectConversation,
    currentUser,
    users,
    searchUsers,
    usersLoading,
  } = useApp();
  const [searchQuery, setSearchQuery] = useState('');
  const [showUserSearch, setShowUserSearch] = useState(false);

  useEffect(() => {
    if (showUserSearch && searchQuery.length > 0) {
      const timer = setTimeout(() => searchUsers(searchQuery), 300);
      return () => clearTimeout(timer);
    }
  }, [searchQuery, showUserSearch, searchUsers]);

  const formatTime = (dateStr?: string) => {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    const now = new Date();
    const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
    if (diffDays === 0) return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    if (diffDays === 1) return 'Yesterday';
    if (diffDays < 7) return date.toLocaleDateString([], { weekday: 'short' });
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  };

  const getOtherUser = (conv: Conversation) => {
    if (!currentUser) return { name: 'Unknown', email: '' };
    const isUser1 = currentUser.email === conv.user1Email;
    return {
      name: isUser1 ? conv.user2Email : conv.user1Email,
      email: isUser1 ? conv.user2Email : conv.user1Email,
    };
  };

  return (
    <div style={styles.container}>
      {/* Header */}
      <div style={styles.header}>
        <h3 style={styles.title}>💬 Chats</h3>
        <button
          style={styles.newChatBtn}
          onClick={() => setShowUserSearch(!showUserSearch)}
        >
          ✏️
        </button>
      </div>

      {/* Search Bar */}
      <div style={styles.searchContainer}>
        <input
          style={styles.searchInput}
          placeholder={showUserSearch ? 'Search users to start chat...' : 'Search conversations...'}
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          onFocus={() => setShowUserSearch(true)}
        />
      </div>

      {/* User Search Results */}
      {showUserSearch && searchQuery && (
        <div style={styles.userResults}>
          {usersLoading && <div style={styles.loading}>Searching...</div>}
          {!usersLoading && users.length === 0 && (
            <div style={styles.noResults}>No users found</div>
          )}
          {users.map((user) => (
            <div
              key={user.id}
              style={styles.userItem}
              onClick={() => {
                // TODO: Create conversation with this user
                setShowUserSearch(false);
                setSearchQuery('');
              }}
            >
              <div style={styles.avatar}>
                {user.fullName?.charAt(0)?.toUpperCase() || user.email.charAt(0).toUpperCase()}
              </div>
              <div style={styles.userInfo}>
                <span style={styles.userName}>{user.fullName || user.email}</span>
                <span style={styles.userEmail}>{user.email}</span>
                {user.source && (
                  <span style={styles.userSource}>via {user.source}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Conversation List */}
      {!showUserSearch && (
        <div style={styles.conversationList}>
          {conversations.length === 0 && (
            <div style={styles.noResults}>No conversations yet</div>
          )}
          {conversations.map((conv) => {
            const other = getOtherUser(conv);
            const isActive = activeConversation?.id === conv.id;
            return (
              <div
                key={conv.id}
                style={{
                  ...styles.conversationItem,
                  ...(isActive ? styles.conversationItemActive : {}),
                }}
                onClick={() => selectConversation(conv)}
              >
                <div style={styles.avatar}>
                  {other.name.charAt(0).toUpperCase()}
                </div>
                <div style={styles.convInfo}>
                  <div style={styles.convTop}>
                    <span style={styles.convName}>{other.name}</span>
                    <span style={styles.convTime}>{formatTime(conv.lastMessageTime)}</span>
                  </div>
                  <div style={styles.convBottom}>
                    <span style={styles.convLastMsg}>
                      {conv.lastMessage || 'No messages yet'}
                    </span>
                    {conv.status === 'INIT' && (
                      <span style={styles.newBadge}>NEW</span>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

// ============================================================
// Styles
// ============================================================

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '320px',
    borderRight: '1px solid #e0e0e0',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#fafafa',
    height: '100%',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '16px',
    borderBottom: '1px solid #e0e0e0',
  },
  title: { fontSize: '18px', fontWeight: 'bold', margin: 0 },
  newChatBtn: {
    width: '36px',
    height: '36px',
    borderRadius: '50%',
    border: 'none',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    fontSize: '16px',
    cursor: 'pointer',
  },
  searchContainer: { padding: '12px 16px' },
  searchInput: {
    width: '100%',
    padding: '10px 14px',
    border: '1px solid #d0d0d0',
    borderRadius: '20px',
    fontSize: '14px',
    outline: 'none',
    boxSizing: 'border-box',
  },
  userResults: {
    maxHeight: '250px',
    overflowY: 'auto',
    borderBottom: '1px solid #e0e0e0',
  },
  userItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '10px 16px',
    cursor: 'pointer',
    transition: 'background 0.2s',
  },
  conversationList: { flex: 1, overflowY: 'auto' },
  conversationItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '12px 16px',
    cursor: 'pointer',
    transition: 'background 0.2s',
    borderBottom: '1px solid #f0f0f0',
  },
  conversationItemActive: {
    backgroundColor: '#e8e4ff',
    borderLeft: '3px solid #6c5ce7',
  },
  avatar: {
    width: '44px',
    height: '44px',
    borderRadius: '50%',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontWeight: 'bold',
    fontSize: '18px',
    flexShrink: 0,
  },
  userInfo: { display: 'flex', flexDirection: 'column' },
  userName: { fontWeight: '600', fontSize: '14px' },
  userEmail: { fontSize: '12px', color: '#666' },
  userSource: { fontSize: '11px', color: '#999', fontStyle: 'italic' },
  convInfo: { flex: 1, minWidth: 0 },
  convTop: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  convName: { fontWeight: '600', fontSize: '14px' },
  convTime: { fontSize: '12px', color: '#999' },
  convBottom: { display: 'flex', alignItems: 'center', gap: '6px', marginTop: '2px' },
  convLastMsg: {
    fontSize: '13px', color: '#666', overflow: 'hidden',
    textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1,
  },
  newBadge: {
    backgroundColor: '#6c5ce7', color: '#fff', fontSize: '10px',
    padding: '2px 6px', borderRadius: '4px', fontWeight: 'bold',
  },
  loading: { padding: '16px', textAlign: 'center', color: '#888' },
  noResults: { padding: '24px', textAlign: 'center', color: '#888', fontSize: '14px' },
};

export default ConversationList;
