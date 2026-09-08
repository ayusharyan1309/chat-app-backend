import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import DatabaseConfigPanel from '../components/DatabaseConfig/DatabaseConfigPanel';
import PlatformDbPanel from '../components/DatabaseConfig/PlatformDbPanel';
import { useApp } from '../context/AppContext';

export const ConfigPage: React.FC = () => {
  const { logout, currentUser } = useApp();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<'database' | 'platforms'>('database');

  return (
    <div style={styles.container}>
      {/* Top Bar */}
      <div style={styles.topBar}>
        <div style={styles.topBarLeft}>
          <span style={styles.logo}>⚙️ Configuration</span>
        </div>
        <div style={styles.topBarRight}>
          <button style={styles.chatBtn} onClick={() => navigate('/chat')}>💬 Chat</button>
          <span style={styles.userEmail}>{currentUser?.email}</span>
          <button style={styles.logoutBtn} onClick={logout}>Logout</button>
        </div>
      </div>

      {/* Tab Navigation */}
      <div style={styles.tabBar}>
        <button
          style={{
            ...styles.tab,
            ...(activeTab === 'database' ? styles.tabActive : {}),
          }}
          onClick={() => setActiveTab('database')}
        >
          🗄️ Chat Database
        </button>
        <button
          style={{
            ...styles.tab,
            ...(activeTab === 'platforms' ? styles.tabActive : {}),
          }}
          onClick={() => setActiveTab('platforms')}
        >
          🌐 Platform User DBs
        </button>
      </div>

      {/* Content */}
      <div style={styles.content}>
        {activeTab === 'database' && <DatabaseConfigPanel />}
        {activeTab === 'platforms' && <PlatformDbPanel />}
      </div>
    </div>
  );
};

const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
    backgroundColor: '#f5f6fa',
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
  topBarRight: { display: 'flex', alignItems: 'center', gap: '16px' },
  userEmail: { fontSize: '13px', opacity: 0.8 },
  chatBtn: {
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
  tabBar: {
    display: 'flex',
    backgroundColor: '#fff',
    borderBottom: '1px solid #e0e0e0',
    padding: '0 20px',
  },
  tab: {
    padding: '14px 24px',
    border: 'none',
    backgroundColor: 'transparent',
    fontSize: '14px',
    fontWeight: '600',
    color: '#666',
    cursor: 'pointer',
    borderBottom: '2px solid transparent',
    transition: 'all 0.2s',
  },
  tabActive: {
    color: '#6c5ce7',
    borderBottom: '2px solid #6c5ce7',
  },
  content: {
    flex: 1,
    overflowY: 'auto',
  },
};

export default ConfigPage;
