import React, { useState } from 'react';
import { useApp } from '../../context/AppContext';
import type { StorageType, DatabaseConfig, SqlConfig, MongoDbConfig, FirebaseConfig, SupabaseConfig } from '../../types';

const STORAGE_TYPES: { type: StorageType; label: string; icon: string; description: string }[] = [
  { type: 'h2', label: 'H2 Database', icon: '💾', description: 'In-memory/embedded SQL (dev/testing)' },
  { type: 'mysql', label: 'MySQL', icon: '🐬', description: 'Relational SQL database' },
  { type: 'postgresql', label: 'PostgreSQL', icon: '🐘', description: 'Advanced relational SQL database' },
  { type: 'mongodb', label: 'MongoDB', icon: '🍃', description: 'Document-oriented NoSQL database' },
  { type: 'firebase', label: 'Firebase Firestore', icon: '🔥', description: "Google's serverless NoSQL database" },
  { type: 'supabase', label: 'Supabase', icon: '⚡', description: 'Open source Firebase alternative' },
];

export const DatabaseConfigPanel: React.FC = () => {
  const { storageType, dbConfig, dbConfigLoading, dbConfigError, setStorageType, saveDbConfig, testDbConnection } = useApp();
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);

  const handleTypeChange = (type: StorageType) => {
    setStorageType(type);
    setTestResult(null);
  };

  const handleSave = async () => {
    try {
      await saveDbConfig(dbConfig);
    } catch {
      // Error is handled in context
    }
  };

  const handleTest = async () => {
    const healthy = await testDbConnection();
    setTestResult({
      success: healthy,
      message: healthy ? 'Connection successful!' : 'Connection failed. Check your credentials.',
    });
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>🗄️ Database Configuration</h2>
      <p style={styles.subtitle}>Select and configure the storage backend for chat data</p>

      {/* Storage Type Selector */}
      <div style={styles.typeGrid}>
        {STORAGE_TYPES.map((st) => (
          <div
            key={st.type}
            style={{
              ...styles.typeCard,
              ...(storageType === st.type ? styles.typeCardActive : {}),
            }}
            onClick={() => handleTypeChange(st.type)}
          >
            <span style={styles.typeIcon}>{st.icon}</span>
            <span style={styles.typeLabel}>{st.label}</span>
            <span style={styles.typeDesc}>{st.description}</span>
          </div>
        ))}
      </div>

      {/* Config Form */}
      <div style={styles.formContainer}>
        <h3 style={styles.formTitle}>
          {STORAGE_TYPES.find((s) => s.type === storageType)?.icon}{' '}
          {STORAGE_TYPES.find((s) => s.type === storageType)?.label} Configuration
        </h3>

        {storageType === 'h2' && <H2Form />}
        {(storageType === 'mysql' || storageType === 'postgresql') && <SqlForm />}
        {storageType === 'mongodb' && <MongoForm />}
        {storageType === 'firebase' && <FirebaseForm />}
        {storageType === 'supabase' && <SupabaseForm />}

        {/* Test Result */}
        {testResult && (
          <div style={{
            ...styles.testResult,
            backgroundColor: testResult.success ? '#d4edda' : '#f8d7da',
            color: testResult.success ? '#155724' : '#721c24',
          }}>
            {testResult.success ? '✅' : '❌'} {testResult.message}
          </div>
        )}

        {/* Error */}
        {dbConfigError && (
          <div style={{ ...styles.testResult, backgroundColor: '#f8d7da', color: '#721c24' }}>
            ❌ {dbConfigError}
          </div>
        )}

        {/* Action Buttons */}
        <div style={styles.actions}>
          <button
            style={styles.testBtn}
            onClick={handleTest}
            disabled={dbConfigLoading}
          >
            {dbConfigLoading ? 'Testing...' : '🔌 Test Connection'}
          </button>
          <button
            style={styles.saveBtn}
            onClick={handleSave}
            disabled={dbConfigLoading}
          >
            {dbConfigLoading ? 'Saving...' : '💾 Save Configuration'}
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================================
// H2 Form
// ============================================================

const H2Form: React.FC = () => {
  const { dbConfig } = useApp();
  return (
    <div style={styles.form}>
      <div style={styles.field}>
        <label style={styles.label}>Database URL</label>
        <input
          style={styles.input}
          value={dbConfig.sql?.url || 'jdbc:h2:mem:chat_storage'}
          readOnly
        />
      </div>
      <p style={styles.hint}>H2 is an in-memory database ideal for development and testing.</p>
    </div>
  );
};

// ============================================================
// SQL Form (MySQL / PostgreSQL)
// ============================================================

const SqlForm: React.FC = () => {
  const { dbConfig, saveDbConfig } = useApp();
  const sql = dbConfig.sql || { url: '', username: '', password: '', driverClassName: '', poolSize: 20 };

  const update = (field: keyof SqlConfig, value: string | number) => {
    const newConfig: DatabaseConfig = {
      ...dbConfig,
      sql: { ...sql, [field]: value },
    };
    saveDbConfig(newConfig);
  };

  return (
    <div style={styles.form}>
      <div style={styles.field}>
        <label style={styles.label}>JDBC URL</label>
        <input
          style={styles.input}
          value={sql.url}
          onChange={(e) => update('url', e.target.value)}
          placeholder={dbConfig.type === 'mysql' ? 'jdbc:mysql://localhost:3306/chat_db' : 'jdbc:postgresql://localhost:5432/chat_db'}
        />
      </div>
      <div style={styles.row}>
        <div style={styles.field}>
          <label style={styles.label}>Username</label>
          <input style={styles.input} value={sql.username} onChange={(e) => update('username', e.target.value)} placeholder="root" />
        </div>
        <div style={styles.field}>
          <label style={styles.label}>Password</label>
          <input style={styles.input} type="password" value={sql.password} onChange={(e) => update('password', e.target.value)} placeholder="••••••" />
        </div>
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Pool Size</label>
        <input
          style={styles.input}
          type="number"
          value={sql.poolSize}
          onChange={(e) => update('poolSize', parseInt(e.target.value) || 20)}
        />
      </div>
    </div>
  );
};

// ============================================================
// MongoDB Form
// ============================================================

const MongoForm: React.FC = () => {
  const { dbConfig, saveDbConfig } = useApp();
  const mongo = dbConfig.mongodb || { host: 'localhost', port: 27017, database: 'chat_db' };

  const update = (field: keyof MongoDbConfig, value: string | number) => {
    const newConfig: DatabaseConfig = {
      ...dbConfig,
      mongodb: { ...mongo, [field]: value },
    };
    saveDbConfig(newConfig);
  };

  return (
    <div style={styles.form}>
      <div style={styles.field}>
        <label style={styles.label}>Connection URI (optional — overrides host/port)</label>
        <input
          style={styles.input}
          value={mongo.uri || ''}
          onChange={(e) => update('uri', e.target.value)}
          placeholder="mongodb://localhost:27017/chat_db"
        />
      </div>
      <div style={styles.row}>
        <div style={styles.field}>
          <label style={styles.label}>Host</label>
          <input style={styles.input} value={mongo.host} onChange={(e) => update('host', e.target.value)} />
        </div>
        <div style={styles.field}>
          <label style={styles.label}>Port</label>
          <input style={styles.input} type="number" value={mongo.port} onChange={(e) => update('port', parseInt(e.target.value) || 27017)} />
        </div>
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Database Name</label>
        <input style={styles.input} value={mongo.database} onChange={(e) => update('database', e.target.value)} />
      </div>
      <div style={styles.row}>
        <div style={styles.field}>
          <label style={styles.label}>Username (optional)</label>
          <input style={styles.input} value={mongo.username || ''} onChange={(e) => update('username', e.target.value)} />
        </div>
        <div style={styles.field}>
          <label style={styles.label}>Password (optional)</label>
          <input style={styles.input} type="password" value={mongo.password || ''} onChange={(e) => update('password', e.target.value)} />
        </div>
      </div>
    </div>
  );
};

// ============================================================
// Firebase Form
// ============================================================

const FirebaseForm: React.FC = () => {
  const { dbConfig, saveDbConfig } = useApp();
  const fb = dbConfig.firebase || { projectId: '', credentialPath: 'firebase-service-account.json', firestoreDatabase: '(default)' };

  const update = (field: keyof FirebaseConfig, value: string) => {
    const newConfig: DatabaseConfig = {
      ...dbConfig,
      firebase: { ...fb, [field]: value },
    };
    saveDbConfig(newConfig);
  };

  return (
    <div style={styles.form}>
      <div style={styles.field}>
        <label style={styles.label}>Google Cloud Project ID</label>
        <input style={styles.input} value={fb.projectId} onChange={(e) => update('projectId', e.target.value)} placeholder="my-project-id" />
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Service Account JSON Path</label>
        <input style={styles.input} value={fb.credentialPath} onChange={(e) => update('credentialPath', e.target.value)} />
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Firestore Database</label>
        <input style={styles.input} value={fb.firestoreDatabase} onChange={(e) => update('firestoreDatabase', e.target.value)} />
      </div>
    </div>
  );
};

// ============================================================
// Supabase Form
// ============================================================

const SupabaseForm: React.FC = () => {
  const { dbConfig, saveDbConfig } = useApp();
  const sup = dbConfig.supabase || { url: '', apiKey: '', schema: 'public' };

  const update = (field: keyof SupabaseConfig, value: string) => {
    const newConfig: DatabaseConfig = {
      ...dbConfig,
      supabase: { ...sup, [field]: value },
    };
    saveDbConfig(newConfig);
  };

  return (
    <div style={styles.form}>
      <div style={styles.field}>
        <label style={styles.label}>Supabase Project URL</label>
        <input style={styles.input} value={sup.url} onChange={(e) => update('url', e.target.value)} placeholder="https://xyzcompany.supabase.co" />
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Service Role API Key</label>
        <input style={styles.input} type="password" value={sup.apiKey} onChange={(e) => update('apiKey', e.target.value)} placeholder="eyJhbGciOi..." />
      </div>
      <div style={styles.field}>
        <label style={styles.label}>Schema</label>
        <input style={styles.input} value={sup.schema} onChange={(e) => update('schema', e.target.value)} />
      </div>
    </div>
  );
};

// ============================================================
// Styles
// ============================================================

const styles: Record<string, React.CSSProperties> = {
  container: {
    padding: '24px',
    maxWidth: '900px',
    margin: '0 auto',
  },
  title: {
    fontSize: '24px',
    fontWeight: 'bold',
    marginBottom: '4px',
    color: '#1a1a2e',
  },
  subtitle: {
    color: '#666',
    marginBottom: '24px',
  },
  typeGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(250px, 1fr))',
    gap: '12px',
    marginBottom: '24px',
  },
  typeCard: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    padding: '16px',
    border: '2px solid #e0e0e0',
    borderRadius: '12px',
    cursor: 'pointer',
    transition: 'all 0.2s',
    backgroundColor: '#fff',
  },
  typeCardActive: {
    borderColor: '#6c5ce7',
    backgroundColor: '#f0edff',
    boxShadow: '0 0 0 3px rgba(108, 92, 231, 0.2)',
  },
  typeIcon: { fontSize: '32px', marginBottom: '8px' },
  typeLabel: { fontWeight: 'bold', fontSize: '14px', marginBottom: '4px' },
  typeDesc: { fontSize: '12px', color: '#666', textAlign: 'center' },
  formContainer: {
    backgroundColor: '#fff',
    border: '1px solid #e0e0e0',
    borderRadius: '12px',
    padding: '24px',
  },
  formTitle: { fontSize: '18px', fontWeight: 'bold', marginBottom: '16px' },
  form: { display: 'flex', flexDirection: 'column', gap: '12px' },
  row: { display: 'flex', gap: '12px' },
  field: { flex: 1, display: 'flex', flexDirection: 'column', gap: '4px' },
  label: { fontSize: '13px', fontWeight: '600', color: '#333' },
  input: {
    padding: '10px 12px',
    border: '1px solid #d0d0d0',
    borderRadius: '8px',
    fontSize: '14px',
    outline: 'none',
    transition: 'border-color 0.2s',
  },
  hint: { fontSize: '13px', color: '#888', fontStyle: 'italic' },
  testResult: {
    padding: '12px',
    borderRadius: '8px',
    fontSize: '14px',
    marginTop: '12px',
  },
  actions: {
    display: 'flex',
    gap: '12px',
    marginTop: '16px',
  },
  testBtn: {
    padding: '10px 20px',
    backgroundColor: '#fff',
    border: '2px solid #6c5ce7',
    color: '#6c5ce7',
    borderRadius: '8px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'pointer',
  },
  saveBtn: {
    padding: '10px 20px',
    backgroundColor: '#6c5ce7',
    border: 'none',
    color: '#fff',
    borderRadius: '8px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'pointer',
  },
};

export default DatabaseConfigPanel;
