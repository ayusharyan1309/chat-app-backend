import React, { useState } from 'react';
import { useApp } from '../context/AppContext';

export const LoginPage: React.FC = () => {
  const { login } = useApp();
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !token.trim()) {
      setError('Please enter both email and token');
      return;
    }
    login(email.trim(), token.trim());
  };

  return (
    <div style={styles.container}>
      <div style={styles.card}>
        <div style={styles.header}>
          <span style={styles.logo}>💬</span>
          <h1 style={styles.title}>ChatApp</h1>
          <p style={styles.subtitle}>Sign in to start chatting</p>
        </div>

        <form onSubmit={handleSubmit} style={styles.form}>
          <div style={styles.field}>
            <label style={styles.label}>Email</label>
            <input
              style={styles.input}
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="your@email.com"
              autoFocus
            />
          </div>

          <div style={styles.field}>
            <label style={styles.label}>Auth Token (Firebase ID Token)</label>
            <input
              style={styles.input}
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="Paste your Firebase ID token"
            />
          </div>

          {error && <div style={styles.error}>{error}</div>}

          <button type="submit" style={styles.loginBtn}>
            Sign In
          </button>
        </form>

        <div style={styles.footer}>
          <button
            style={styles.devBtn}
            onClick={() => login('dev@test.com', 'dev-token')}
          >
            🚀 Quick Dev Login (no auth)
          </button>
          <p style={styles.hint}>
            Use your Firebase ID token for authentication.
            <br />
            Or use Dev Login for testing.
          </p>
        </div>
      </div>
    </div>
  );
};

const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    backgroundColor: '#f5f6fa',
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: '16px',
    padding: '40px',
    width: '400px',
    boxShadow: '0 4px 20px rgba(0,0,0,0.1)',
  },
  header: {
    textAlign: 'center',
    marginBottom: '32px',
  },
  logo: { fontSize: '48px' },
  title: { fontSize: '28px', fontWeight: 'bold', margin: '8px 0 4px', color: '#1a1a2e' },
  subtitle: { color: '#666', fontSize: '14px' },
  form: { display: 'flex', flexDirection: 'column', gap: '16px' },
  field: { display: 'flex', flexDirection: 'column', gap: '4px' },
  label: { fontSize: '13px', fontWeight: '600', color: '#333' },
  input: {
    padding: '12px 14px',
    border: '1px solid #d0d0d0',
    borderRadius: '10px',
    fontSize: '14px',
    outline: 'none',
    transition: 'border-color 0.2s',
  },
  error: {
    padding: '10px',
    backgroundColor: '#f8d7da',
    color: '#721c24',
    borderRadius: '8px',
    fontSize: '13px',
    textAlign: 'center',
  },
  loginBtn: {
    padding: '14px',
    backgroundColor: '#6c5ce7',
    color: '#fff',
    border: 'none',
    borderRadius: '10px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer',
    marginTop: '8px',
  },
  footer: { marginTop: '24px', textAlign: 'center' },
  devBtn: {
    width: '100%',
    padding: '12px',
    backgroundColor: '#00b894',
    color: '#fff',
    border: 'none',
    borderRadius: '10px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'pointer',
    marginBottom: '16px',
  },
  hint: { fontSize: '12px', color: '#888', lineHeight: '1.6' },
};

export default LoginPage;
