import React from 'react';
import { useNavigate } from 'react-router-dom';
import ConversationList from '../components/Chat/ConversationList';
import MessageList from '../components/Chat/MessageList';
import MessageInput from '../components/Chat/MessageInput';
import { useApp } from '../context/AppContext';

export const ChatPage: React.FC = () => {
  const { currentUser, logout, wsConnected } = useApp();
  const navigate = useNavigate();

  return (
    <div style={styles.container}>
      {/* Top Bar */}
      <div style={styles.topBar}>
        <div style={styles.topBarLeft}>
          <span style={styles.logo}>💬 ChatApp</span>
          <span style={{
            ...styles.connectionDot,
            backgroundColor: wsConnected ? '#00b894' : '#d63031',
          }} title={wsConnected ? 'Connected' : 'Disconnected'} />
        </div>
        <div style={styles.topBarRight}>
          <button style={styles.configBtn} onClick={() => navigate('/config')}>⚙️ Config</button>
          <span style={styles.userEmail}>{currentUser?.email}</span>
          <button style={styles.logoutBtn} onClick={logout}>Logout</button>
        </div>
      </div>

      {/* Main Content */}
      <div style={styles.main}>
        {/* Sidebar: Conversations */}
        <ConversationList />

        {/* Chat Area */}
        <div style={styles.chatArea}>
          <MessageList />
          <MessageInput />
        </div>
      </div>
    </div>
  );
};

const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
    backgroundColor: '#fff',
  },
  topBar: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '10px 20px',
    backgroundColor: '#1a1a2e',
    color: '#fff',
  },
  topBarLeft: { display: 'flex', alignItems: 'center', gap: '8px' },
  logo: { fontSize: '18px', fontWeight: 'bold' },
  connectionDot: {
    width: '10px',
    height: '10px',
    borderRadius: '50%',
    display: 'inline-block',
  },
  topBarRight: { display: 'flex', alignItems: 'center', gap: '16px' },
  userEmail: { fontSize: '13px', opacity: 0.8 },
  configBtn: {
    padding: '6px 16px',
    backgroundColor: 'transparent',
    border: '1px solid rgba(255,255,255,0.3)',
    color: '#fff',
    borderRadius: '6px',
    fontSize: '13px',
    cursor: 'pointer',
  },
  logoutBtn: {
    padding: '6px 16px',
    backgroundColor: 'transparent',
    border: '1px solid rgba(255,255,255,0.3)',
    color: '#fff',
    borderRadius: '6px',
    fontSize: '13px',
    cursor: 'pointer',
  },
  main: {
    display: 'flex',
    flex: 1,
    overflow: 'hidden',
  },
  chatArea: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minWidth: 0,
  },
};

export default ChatPage;
