import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

/**
 * Haengt die Anwendung in die Seite ein.
 *
 * Mehr passiert hier nicht -- die ganze Arbeit steckt in App.tsx.
 */
const wurzel = document.getElementById('root');
if (wurzel === null) {
  throw new Error('Element with id "root" not found in index.html');
}

createRoot(wurzel).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
