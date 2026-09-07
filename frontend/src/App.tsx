import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppProvider, useApp } from './context/AppContext';
import LoginPage from './pages/LoginPage';
import ConfigPage from './pages/ConfigPage';
import ChatPage from './pages/ChatPage';

/**
 * Protected route wrapper — redirects to login if not authenticated.
 */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isLoggedIn } = useApp();
  if (!isLoggedIn) return <Navigate to="/login" replace />;
  return <>{children}</>;
};

/**
 * App Routes — defines the navigation structure:
 * /login    → LoginPage
 * /config   → ConfigPage (database + platform configuration)
 * /chat     → ChatPage (main chat interface)
 * /         → Redirects to /chat if logged in, /login otherwise
 */
const AppRoutes: React.FC = () => {
  const { isLoggedIn } = useApp();

  return (
    <Routes>
      <Route path="/login" element={
        isLoggedIn ? <Navigate to="/chat" replace /> : <LoginPage />
      } />
      <Route path="/config" element={
        <ProtectedRoute><ConfigPage /></ProtectedRoute>
      } />
      <Route path="/chat" element={
        <ProtectedRoute><ChatPage /></ProtectedRoute>
      } />
      <Route path="*" element={
        <Navigate to={isLoggedIn ? '/chat' : '/login'} replace />
      } />
    </Routes>
  );
};

/**
 * Root App Component
 */
const App: React.FC = () => {
  return (
    <BrowserRouter>
      <AppProvider>
        <AppRoutes />
      </AppProvider>
    </BrowserRouter>
  );
};

export default App;
