import React, { useState } from 'react';
import { useApp } from '../../context/AppContext';
import type { PlatformDbConfig, PlatformDbType } from '../../types';

const PLATFORM_TYPES: { type: PlatformDbType; label: string; icon: string }[] = [
  { type: 'supabase', label: 'Supabase', icon: '⚡' },
  { type: 'mysql', label: 'MySQL', icon: '🐬' },
  { type: 'postgresql', label: 'PostgreSQL', icon: '🐘' },
  { type: 'mongodb', label: 'MongoDB', icon: '🍃' },
  { type: 'firebase', label: 'Firebase', icon: '🔥' },
  { type: 'custom', label: 'Custom API', icon: '🔌' },
];

export const PlatformDbPanel: React.FC = () => {
  const { platformConfigs, addPlatform, removePlatform } = useApp();
  const [showForm, setShowForm] = useState(false);
  const [editingPlatform, setEditingPlatform] = useState<PlatformDbConfig | null>(null);

  const handleAdd = () => {
    setEditingPlatform(null);
    setShowForm(true);
  };

  const handleEdit = (platform: PlatformDbConfig) => {
    setEditingPlatform(platform);
    setShowForm(true);
  };

  const handleSave = async (config: PlatformDbConfig) => {
    await addPlatform(config);
    setShowForm(false);
    setEditingPlatform(null);
  };

  const handleDelete = async (platformId: string) => {
    if (confirm('Remove this platform database?')) {
      await removePlatform(platformId);
    }
  };

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <div>
          <h2 style={styles.title}>🌐 Platform Databases</h2>
          <p style={styles.subtitle}>Connect external platforms to fetch user data (users are fetched from these sources)</p>
        </div>
        <button style={styles.addBtn} onClick={handleAdd}>+ Add Platform</button>
      </div>

      {/* Primary Supabase (always shown) */}
      <div style={styles.platformCard}>
        <div style={styles.platformHeader}>
          <span style={styles.platformIcon}>⚡</span>
          <div style={styles.platformInfo}>
            <span style={styles.platformName}>Supabase (Primary)</span>
            <span style={styles.platformDesc}>Default user data source — hardcoded</span>
          </div>
          <span style={{ ...styles.badge, backgroundColor: '#00b894' }}>Active</span>
        </div>
      </div>

      {/* External Platforms */}
      {platformConfigs.map((platform) => (
        <div key={platform.id} style={styles.platformCard}>
          <div style={styles.platformHeader}>
            <span style={styles.platformIcon}>
              {PLATFORM_TYPES.find((t) => t.type === platform.type)?.icon || '🔌'}
            </span>
            <div style={styles.platformInfo}>
              <span style={styles.platformName}>{platform.name}</span>
              <span style={styles.platformDesc}>
                {platform.type.toUpperCase()} — {platform.id}
              </span>
            </div>
            <span style={{
              ...styles.badge,
              backgroundColor: platform.isActive ? '#00b894' : '#d63031',
            }}>
              {platform.isActive ? 'Active' : 'Inactive'}
            </span>
          </div>
          <div style={styles.platformActions}>
            <button style={styles.editBtn} onClick={() => handleEdit(platform)}>Edit</button>
            <button style={styles.deleteBtn} onClick={() => handleDelete(platform.id)}>Remove</button>
          </div>
        </div>
      ))}

      {platformConfigs.length === 0 && (
        <div style={styles.empty}>
          No external platforms configured. Click "Add Platform" to connect a database for user data fetching.
        </div>
      )}

      {/* Add/Edit Form Modal */}
      {showForm && (
        <PlatformForm
          platform={editingPlatform}
          onSave={handleSave}
          onCancel={() => { setShowForm(false); setEditingPlatform(null); }}
        />
      )}
    </div>
  );
};

// ============================================================
// Platform Form Component
// ============================================================

interface PlatformFormProps {
  platform: PlatformDbConfig | null;
  onSave: (config: PlatformDbConfig) => Promise<void>;
  onCancel: () => void;
}

const PlatformForm: React.FC<PlatformFormProps> = ({ platform, onSave, onCancel }) => {
  const [name, setName] = useState(platform?.name || '');
  const [type, setType] = useState<PlatformDbType>(platform?.type || 'supabase');
  const [isActive, setIsActive] = useState(platform?.isActive ?? true);

  // Supabase fields
  const [supUrl, setSupUrl] = useState(platform?.supabase?.url || '');
  const [supApiKey, setSupApiKey] = useState(platform?.supabase?.apiKey || '');

  // SQL fields
  const [sqlUrl, setSqlUrl] = useState(platform?.sql?.url || '');
  const [sqlUser, setSqlUser] = useState(platform?.sql?.username || '');
  const [sqlPass, setSqlPass] = useState(platform?.sql?.password || '');

  // Mongo fields
  const [mongoUri, setMongoUri] = useState(platform?.mongodb?.uri || '');
  const [mongoDb, setMongoDb] = useState(platform?.mongodb?.database || '');

  // Firebase fields
  const [fbProject, setFbProject] = useState(platform?.firebase?.projectId || '');
  const [fbCred, setFbCred] = useState(platform?.firebase?.credentialPath || '');

  // Custom API fields
  const [customApi, setCustomApi] = useState(platform?.custom?.apiUrl || '');
  const [customKey, setCustomKey] = useState(platform?.custom?.apiKey || '');

  const handleSubmit = async () => {
    const id = platform?.id || name.toLowerCase().replace(/\s+/g, '-');
    const config: PlatformDbConfig = {
      id,
      name,
      type,
      isActive,
    };

    if (type === 'supabase') {
      config.supabase = { url: supUrl, apiKey: supApiKey };
    } else if (type === 'mysql' || type === 'postgresql') {
      const driver = type === 'mysql' ? 'com.mysql.cj.jdbc.Driver' : 'org.postgresql.Driver';
      config.sql = { url: sqlUrl, username: sqlUser, password: sqlPass, driverClassName: driver };
    } else if (type === 'mongodb') {
      config.mongodb = { uri: mongoUri, database: mongoDb };
    } else if (type === 'firebase') {
      config.firebase = { projectId: fbProject, credentialPath: fbCred };
    } else if (type === 'custom') {
      config.custom = { apiUrl: customApi, apiKey: customKey };
    }

    await onSave(config);
  };

  return (
    <div style={styles.modalOverlay}>
      <div style={styles.modal}>
        <h3 style={styles.modalTitle}>{platform ? 'Edit' : 'Add'} Platform Database</h3>

        <div style={styles.formField}>
          <label style={styles.label}>Platform Name</label>
          <input style={styles.input} value={name} onChange={(e) => setName(e.target.value)} placeholder="My MySQL Users" />
        </div>

        <div style={styles.formField}>
          <label style={styles.label}>Database Type</label>
          <select style={styles.input} value={type} onChange={(e) => setType(e.target.value as PlatformDbType)}>
            {PLATFORM_TYPES.map((pt) => (
              <option key={pt.type} value={pt.type}>{pt.icon} {pt.label}</option>
            ))}
          </select>
        </div>

        <div style={styles.formField}>
          <label style={styles.label}>
            <input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} style={{ marginRight: '8px' }} />
            Active
          </label>
        </div>

        {/* Type-specific fields */}
        {type === 'supabase' && (
          <>
            <div style={styles.formField}>
              <label style={styles.label}>Supabase URL</label>
              <input style={styles.input} value={supUrl} onChange={(e) => setSupUrl(e.target.value)} placeholder="https://xyz.supabase.co" />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>API Key</label>
              <input style={styles.input} type="password" value={supApiKey} onChange={(e) => setSupApiKey(e.target.value)} />
            </div>
          </>
        )}

        {(type === 'mysql' || type === 'postgresql') && (
          <>
            <div style={styles.formField}>
              <label style={styles.label}>JDBC URL</label>
              <input style={styles.input} value={sqlUrl} onChange={(e) => setSqlUrl(e.target.value)} placeholder={type === 'mysql' ? 'jdbc:mysql://host:3306/db' : 'jdbc:postgresql://host:5432/db'} />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>Username</label>
              <input style={styles.input} value={sqlUser} onChange={(e) => setSqlUser(e.target.value)} />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>Password</label>
              <input style={styles.input} type="password" value={sqlPass} onChange={(e) => setSqlPass(e.target.value)} />
            </div>
          </>
        )}

        {type === 'mongodb' && (
          <>
            <div style={styles.formField}>
              <label style={styles.label}>MongoDB URI</label>
              <input style={styles.input} value={mongoUri} onChange={(e) => setMongoUri(e.target.value)} placeholder="mongodb://host:27017/db" />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>Database</label>
              <input style={styles.input} value={mongoDb} onChange={(e) => setMongoDb(e.target.value)} />
            </div>
          </>
        )}

        {type === 'firebase' && (
          <>
            <div style={styles.formField}>
              <label style={styles.label}>Project ID</label>
              <input style={styles.input} value={fbProject} onChange={(e) => setFbProject(e.target.value)} />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>Credential Path</label>
              <input style={styles.input} value={fbCred} onChange={(e) => setFbCred(e.target.value)} />
            </div>
          </>
        )}

        {type === 'custom' && (
          <>
            <div style={styles.formField}>
              <label style={styles.label}>API URL</label>
              <input style={styles.input} value={customApi} onChange={(e) => setCustomApi(e.target.value)} placeholder="https://api.example.com" />
            </div>
            <div style={styles.formField}>
              <label style={styles.label}>API Key</label>
              <input style={styles.input} type="password" value={customKey} onChange={(e) => setCustomKey(e.target.value)} />
            </div>
          </>
        )}

        <div style={styles.modalActions}>
          <button style={styles.cancelBtn} onClick={onCancel}>Cancel</button>
          <button style={styles.saveBtn} onClick={handleSubmit}>{platform ? 'Update' : 'Add Platform'}</button>
        </div>
      </div>
    </div>
  );
};

// ============================================================
// Styles
// ============================================================

const styles: Record<string, React.CSSProperties> = {
  container: { padding: '24px', maxWidth: '900px', margin: '0 auto' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '24px' },
  title: { fontSize: '24px', fontWeight: 'bold', color: '#1a1a2e', marginBottom: '4px' },
  subtitle: { color: '#666' },
  addBtn: {
    padding: '10px 20px', backgroundColor: '#6c5ce7', color: '#fff', border: 'none',
    borderRadius: '8px', fontSize: '14px', fontWeight: '600', cursor: 'pointer',
  },
  platformCard: {
    backgroundColor: '#fff', border: '1px solid #e0e0e0', borderRadius: '12px',
    padding: '16px', marginBottom: '12px',
  },
  platformHeader: { display: 'flex', alignItems: 'center', gap: '12px' },
  platformIcon: { fontSize: '28px' },
  platformInfo: { flex: 1, display: 'flex', flexDirection: 'column' },
  platformName: { fontWeight: 'bold', fontSize: '16px' },
  platformDesc: { fontSize: '13px', color: '#666' },
  badge: {
    padding: '4px 12px', borderRadius: '12px', fontSize: '12px',
    fontWeight: '600', color: '#fff',
  },
  platformActions: { display: 'flex', gap: '8px', marginTop: '12px', justifyContent: 'flex-end' },
  editBtn: {
    padding: '6px 16px', backgroundColor: '#fff', border: '1px solid #6c5ce7',
    color: '#6c5ce7', borderRadius: '6px', fontSize: '13px', cursor: 'pointer',
  },
  deleteBtn: {
    padding: '6px 16px', backgroundColor: '#fff', border: '1px solid #d63031',
    color: '#d63031', borderRadius: '6px', fontSize: '13px', cursor: 'pointer',
  },
  empty: { textAlign: 'center', color: '#888', padding: '40px', fontSize: '14px' },
  modalOverlay: {
    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex',
    alignItems: 'center', justifyContent: 'center', zIndex: 1000,
  },
  modal: {
    backgroundColor: '#fff', borderRadius: '12px', padding: '24px',
    width: '500px', maxHeight: '80vh', overflowY: 'auto',
  },
  modalTitle: { fontSize: '20px', fontWeight: 'bold', marginBottom: '16px' },
  formField: { marginBottom: '12px' },
  label: { display: 'block', fontSize: '13px', fontWeight: '600', color: '#333', marginBottom: '4px' },
  input: {
    width: '100%', padding: '10px 12px', border: '1px solid #d0d0d0',
    borderRadius: '8px', fontSize: '14px', boxSizing: 'border-box',
  },
  modalActions: { display: 'flex', gap: '12px', justifyContent: 'flex-end', marginTop: '16px' },
  cancelBtn: {
    padding: '10px 20px', backgroundColor: '#fff', border: '1px solid #d0d0d0',
    borderRadius: '8px', fontSize: '14px', cursor: 'pointer',
  },
  saveBtn: {
    padding: '10px 20px', backgroundColor: '#6c5ce7', color: '#fff', border: 'none',
    borderRadius: '8px', fontSize: '14px', fontWeight: '600', cursor: 'pointer',
  },
};

export default PlatformDbPanel;
