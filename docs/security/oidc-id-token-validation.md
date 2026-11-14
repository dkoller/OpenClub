# R05 / C3 — Verified OIDC ID-token identity

**Status:** Internal verification component only; no HTTP login, callback, OAuth token exchange or browser sessions are enabled. Parent issue [#14](https://github.com/dkoller/OpenClub/issues/14).

The Kotlin backend pins `com.nimbusds:oauth2-oidc-sdk:11.39` (Apache-2.0), the selected maintained OIDC validator. `OidcIdTokenVerifier` uses Nimbus `IDTokenValidator` with the configured issuer/client, **RS256 only**, a trusted public JWK set supplied by backend configuration/discovery (never a URL from the token header), and a 30-second clock-skew allowance. It requires a valid signed JWT, expected stored nonce, a single audience equal to the client ID and nonblank subject. It returns only `issuer + subject`, **not an authenticated member or club role**. Any verifier exception must cause authentication to fail closed.

Unit tests generate ephemeral RSA keys and verify acceptance with the trusted public key, rejection of a forged signature, untrusted key, wrong issuer, audience, nonce, expired token, unsupported algorithm, unsecured JWT, malformed JWT, missing subject and missing trusted keys. No real identity-provider credentials are involved.

**Still blocked before wiring a browser login:** trust and safely refresh issuer-pinned OIDC discovery/JWKS; resolve public localhost versus container-internal Keycloak routing without weakening issuer matching; confidential authorization-code + PKCE token exchange; atomically consume the browser-bound transaction before validating the ID token; securely create/revoke persistent HttpOnly sessions with CSRF enforcement. Do not configure unauthenticated club/member endpoints. No OpenAPI changes because there is still no HTTP auth route.

The validator deliberately supports only single-audience RS256 ID tokens for now. If a provider requires multiple audiences or a different vetted algorithm, update the policy and add explicit tests before allowing it. See [security design](oidc-bff-design.md) and [login transactions](oidc-login-transactions.md). Review dependency updates and their transitive security advisories.
