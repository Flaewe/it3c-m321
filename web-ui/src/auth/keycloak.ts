import { createCodeChallenge, createCodeVerifier, createState } from './pkce';

/**
 * Der Login-Ablauf gegen Keycloak, Schritt fuer Schritt.
 *
 * Alle Adressen sind relativ. Das ist kein Zufall, sondern die Folge der
 * Vorgabe "nur die Web-App ist ueber localhost erreichbar": Web-App, API und
 * Keycloak liegen alle unter derselben Herkunft, weil das Gateway /auth
 * durchreicht. Deshalb steht hier nirgends ein Hostname.
 */

/** So heisst der Client im Realm chat (siehe keycloak/realm-chat.json). */
const CLIENT_ID = 'chat-web';

/** Der Realm-Pfad, so wie ihn das Gateway durchreicht. */
const REALM_PFAD = '/auth/realms/chat/protocol/openid-connect';

/** Schluessel, unter denen wir uns Werte ueber die Weiterleitung hinweg merken. */
const SPEICHER_VERIFIER = 'pkce_verifier';
const SPEICHER_STATE = 'pkce_state';

/**
 * Startet den Login: Browser zu Keycloak schicken.
 *
 * Vorher legen wir verifier und state im sessionStorage ab. Sie muessen die
 * Weiterleitung ueberleben, denn beim Rueckweg braucht die Anwendung sie
 * wieder -- und nach dem Sprung zu Keycloak und zurueck ist der Speicher im
 * Arbeitsspeicher weg.
 */
export async function startLogin(): Promise<void> {
  const verifier = createCodeVerifier();
  const challenge = await createCodeChallenge(verifier);
  const state = createState();

  sessionStorage.setItem(SPEICHER_VERIFIER, verifier);
  sessionStorage.setItem(SPEICHER_STATE, state);

  const parameter = new URLSearchParams({
    client_id: CLIENT_ID,
    response_type: 'code',
    scope: 'openid profile',
    redirect_uri: redirectUri(),
    state: state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });

  window.location.assign(`${REALM_PFAD}/auth?${parameter.toString()}`);
}

/**
 * Loest den Code aus der Rueckleitung gegen ein Token ein.
 *
 * Gibt null zurueck, wenn in der Adresse gar kein Code steht -- das ist der
 * Normalfall beim ersten Aufruf der Seite und kein Fehler.
 */
export async function finishLogin(): Promise<string | null> {
  const parameter = new URLSearchParams(window.location.search);
  const code = parameter.get('code');
  if (code === null) {
    return null;
  }

  const zurueckgegebenerState = parameter.get('state');
  const erwarteterState = sessionStorage.getItem(SPEICHER_STATE);
  if (zurueckgegebenerState !== erwarteterState) {
    throw new Error('State does not match, aborting login');
  }

  const verifier = sessionStorage.getItem(SPEICHER_VERIFIER);
  if (verifier === null) {
    throw new Error('Code verifier missing, cannot redeem code');
  }

  const koerper = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: CLIENT_ID,
    code: code,
    redirect_uri: redirectUri(),
    code_verifier: verifier,
  });

  const antwort = await fetch(`${REALM_PFAD}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: koerper.toString(),
  });

  if (!antwort.ok) {
    throw new Error(`Token request failed with status ${antwort.status}`);
  }

  const daten = await antwort.json();

  sessionStorage.removeItem(SPEICHER_VERIFIER);
  sessionStorage.removeItem(SPEICHER_STATE);

  // Den Code aus der Adresszeile entfernen. Er ist verbraucht, und niemand
  // soll ihn aus dem Verlauf oder einem Lesezeichen wieder hervorholen.
  window.history.replaceState({}, '', window.location.pathname);

  return daten.access_token;
}

/**
 * Fragt das Gateway, wer gerade angemeldet ist.
 *
 * Das Token wandert als Authorization-Header mit. Der Browser schickt einen
 * solchen Header nie von allein mit -- genau deshalb braucht es hier keinen
 * CSRF-Schutz.
 */
export async function ladeBenutzer(token: string): Promise<CurrentUser> {
  const antwort = await fetch('/api/me', {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!antwort.ok) {
    throw new Error(`Could not load user, status ${antwort.status}`);
  }

  return antwort.json();
}

/**
 * Wohin Keycloak zuruecksenden soll.
 *
 * Immer die Seite ohne Query, sonst haengt beim naechsten Login der alte Code
 * noch daran. Die Adresse muss zu redirectUris im Realm passen.
 */
function redirectUri(): string {
  return `${window.location.origin}${window.location.pathname}`;
}

/** Was /api/me zurueckgibt -- dasselbe wie der record CurrentUser im Gateway. */
export interface CurrentUser {
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
}
