/**
 * Die Rechenschritte von PKCE.
 *
 * PKCE loest ein Problem, das eine Anwendung im Browser hat: sie kann kein
 * Geheimnis speichern. Alles, was sie kennt, kennt auch jeder, der den
 * Quelltext oeffnet. Wie beweist sie dann beim Einloesen des Codes, dass sie
 * dieselbe Anwendung ist, die den Login gestartet hat?
 *
 * Die Antwort: sie denkt sich beim Start eine Zufallszahl aus (den "verifier"),
 * schickt aber nur deren Pruefsumme mit (die "challenge"). Erst beim Einloesen
 * zeigt sie die Zufallszahl selbst. Wer den Code unterwegs abfaengt, hat nur
 * die Pruefsumme -- und aus einer SHA-256-Pruefsumme laesst sich der
 * Ausgangswert nicht zurueckrechnen.
 *
 * Diese Datei enthaelt nur reine Funktionen. Sie laesst sich deshalb testen,
 * ohne dass ein Browser oder ein Keycloak laeuft.
 */

/** Zeichenvorrat fuer den verifier. Alle Zeichen sind in URLs unproblematisch. */
const VERIFIER_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

/**
 * Wuerfelt einen neuen code_verifier.
 *
 * Die Laenge von 64 Zeichen liegt in dem von RFC 7636 erlaubten Bereich von
 * 43 bis 128. Gezogen wird aus crypto.getRandomValues und nicht aus
 * Math.random -- letzteres ist vorhersagbar und waere hier wertlos.
 */
export function createCodeVerifier(): string {
  const zufall = new Uint8Array(64);
  crypto.getRandomValues(zufall);

  let verifier = '';
  for (let i = 0; i < zufall.length; i++) {
    const position = zufall[i] % VERIFIER_ALPHABET.length;
    verifier = verifier + VERIFIER_ALPHABET.charAt(position);
  }
  return verifier;
}

/**
 * Wandelt Bytes in base64url um.
 *
 * Normales Base64 benutzt "+", "/" und "=" -- alle drei haben in einer URL
 * eine eigene Bedeutung und muessten kodiert werden. base64url ersetzt sie.
 */
export function toBase64Url(bytes: Uint8Array): string {
  let binaer = '';
  for (let i = 0; i < bytes.length; i++) {
    binaer = binaer + String.fromCharCode(bytes[i]);
  }

  const base64 = btoa(binaer);
  const ohnePlus = base64.replace(/\+/g, '-');
  const ohneSchraegstrich = ohnePlus.replace(/\//g, '_');
  const ohneFuellzeichen = ohneSchraegstrich.replace(/=+$/, '');
  return ohneFuellzeichen;
}

/**
 * Berechnet die code_challenge zu einem verifier.
 *
 * Das ist die SHA-256-Pruefsumme, in base64url geschrieben. Keycloak kennt das
 * als Methode "S256" -- so ist der Client im Realm auch eingetragen.
 */
export async function createCodeChallenge(verifier: string): Promise<string> {
  const kodiert = new TextEncoder().encode(verifier);
  const pruefsumme = await crypto.subtle.digest('SHA-256', kodiert);
  const bytes = new Uint8Array(pruefsumme);
  return toBase64Url(bytes);
}

/**
 * Wuerfelt einen state-Wert.
 *
 * Der state hat mit PKCE nichts zu tun, gehoert aber zum selben Schutzgedanken:
 * wir merken uns den Wert beim Start und vergleichen ihn beim Rueckweg. Kommt
 * eine Antwort mit einem anderen state, hat sie jemand anders losgeschickt.
 */
export function createState(): string {
  return createCodeVerifier();
}
