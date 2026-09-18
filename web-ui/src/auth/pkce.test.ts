import { describe, expect, it } from 'vitest';
import { createCodeChallenge, createCodeVerifier, toBase64Url } from './pkce';

/**
 * Prueft die Rechenschritte von PKCE.
 *
 * Diese Tests brauchen weder Browser noch Keycloak: die Web Crypto API und
 * btoa gibt es in Node seit Version 18 genauso wie im Browser.
 */
describe('pkce', () => {
  it('erzeugt einen verifier in der von RFC 7636 erlaubten Laenge', () => {
    const verifier = createCodeVerifier();

    expect(verifier.length).toBe(64);
    expect(verifier.length).toBeGreaterThanOrEqual(43);
    expect(verifier.length).toBeLessThanOrEqual(128);
  });

  it('erzeugt jedes Mal einen anderen verifier', () => {
    const erster = createCodeVerifier();
    const zweiter = createCodeVerifier();

    expect(erster).not.toBe(zweiter);
  });

  it('benutzt nur Zeichen, die in einer URL unproblematisch sind', () => {
    const verifier = createCodeVerifier();

    expect(verifier).toMatch(/^[A-Za-z0-9\-._~]+$/);
  });

  it('schreibt base64url ohne +, / und Fuellzeichen', () => {
    // Diese Bytefolge ergibt in normalem Base64 "++//" und ist damit genau
    // der Fall, den base64url anders schreiben muss: "+" wird zu "-",
    // "/" wird zu "_".
    const bytes = new Uint8Array([0xfb, 0xef, 0xff]);

    const kodiert = toBase64Url(bytes);

    expect(kodiert).not.toContain('+');
    expect(kodiert).not.toContain('/');
    expect(kodiert).not.toContain('=');
    expect(kodiert).toBe('--__');
  });

  it('berechnet die challenge so, wie RFC 7636 sie als Beispiel angibt', async () => {
    // Das Beispiel aus RFC 7636, Anhang B. Wenn unsere Rechnung mit dem
    // Beispiel der Norm uebereinstimmt, versteht Keycloak sie auch.
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';

    const challenge = await createCodeChallenge(verifier);

    expect(challenge).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('liefert zum selben verifier immer dieselbe challenge', async () => {
    const verifier = createCodeVerifier();

    const erste = await createCodeChallenge(verifier);
    const zweite = await createCodeChallenge(verifier);

    expect(erste).toBe(zweite);
  });
});
