import { useEffect, useState } from 'react';
import { CurrentUser, finishLogin, ladeBenutzer, startLogin } from './auth/keycloak';

/**
 * Die Oberflaeche von Schritt 2.
 *
 * Sie kann genau eine Sache: zeigen, wer angemeldet ist. Das klingt nach
 * wenig, ist aber der Nachweis, dass die ganze Kette steht -- Browser,
 * Keycloak hinter dem Gateway, Signaturpruefung, /api/me. Der Chat selbst
 * kommt in Schritt 3 dazu.
 */
export default function App() {
  const [benutzer, setBenutzer] = useState<CurrentUser | null>(null);
  const [fehler, setFehler] = useState<string | null>(null);
  const [laedt, setLaedt] = useState(true);

  // Beim ersten Anzeigen pruefen, ob wir gerade von Keycloak zurueckkommen.
  useEffect(() => {
    async function pruefeRueckweg() {
      try {
        const token = await finishLogin();
        if (token === null) {
          setLaedt(false);
          return;
        }

        const geladen = await ladeBenutzer(token);
        setBenutzer(geladen);
      } catch (problem) {
        setFehler(String(problem));
      } finally {
        setLaedt(false);
      }
    }

    pruefeRueckweg();
  }, []);

  if (laedt) {
    return <p style={stil.seite}>Einen Moment …</p>;
  }

  if (fehler !== null) {
    return (
      <div style={stil.seite}>
        <h1>Anmeldung fehlgeschlagen</h1>
        <p style={stil.fehler}>{fehler}</p>
        <button style={stil.knopf} onClick={() => window.location.assign('/')}>
          Nochmal versuchen
        </button>
      </div>
    );
  }

  if (benutzer === null) {
    return (
      <div style={stil.seite}>
        <h1>M321 Chat-App</h1>
        <p>Noch nicht angemeldet.</p>
        <button style={stil.knopf} onClick={() => startLogin()}>
          Bei Keycloak anmelden
        </button>
      </div>
    );
  }

  return (
    <div style={stil.seite}>
      <h1>Angemeldet</h1>
      <p style={stil.name}>{benutzer.displayName}</p>
      <dl style={stil.liste}>
        <dt style={stil.begriff}>Benutzername</dt>
        <dd style={stil.wert}>{benutzer.username}</dd>
        <dt style={stil.begriff}>Kennung (sub)</dt>
        <dd style={stil.wert}>{benutzer.userId}</dd>
        <dt style={stil.begriff}>Rollen</dt>
        <dd style={stil.wert}>{benutzer.roles.join(', ') || '—'}</dd>
      </dl>
      <p style={stil.hinweis}>
        Diese Angaben kommen aus dem geprueften Token, nicht aus unserer
        Datenbank. Benutzer verwaltet Keycloak.
      </p>
    </div>
  );
}

/**
 * Ein paar Stile direkt im Bauteil.
 *
 * Fuer eine Seite mit vier Zeilen Inhalt waere eine eigene CSS-Datei mehr
 * Aufwand als Nutzen. Sobald der Chat dazukommt, zieht das hier aus.
 */
const stil = {
  seite: {
    fontFamily: 'system-ui, sans-serif',
    maxWidth: '36rem',
    margin: '4rem auto',
    padding: '0 1rem',
    lineHeight: 1.6,
  },
  knopf: {
    fontSize: '1rem',
    padding: '0.6rem 1.2rem',
    borderRadius: '0.5rem',
    border: '1px solid #0C6E48',
    background: '#0C6E48',
    color: '#fff',
    cursor: 'pointer',
  },
  name: { fontSize: '1.5rem', fontWeight: 600, margin: '0 0 1rem' },
  liste: { display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '0.4rem 1rem' },
  begriff: { color: '#5A625C', fontSize: '0.9rem' },
  wert: { margin: 0, fontFamily: 'ui-monospace, monospace' },
  fehler: { color: '#B23A3A' },
  hinweis: { marginTop: '2rem', color: '#5A625C', fontSize: '0.9rem' },
} as const;
