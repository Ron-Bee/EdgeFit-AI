// Public evidence endpoint configuration boundary (V4-02 G).
//
// null = no authorized public source exists: the dashboard uses
// EmptyProvider and renders truthful unavailable-data states.
//
// Activation (separate owner authorization AFTER the server-side
// publication controls pass): set EVIDENCE_BASE to the exact
// authorized https base URL string. Rollback: set it back to
// null. One tracked line either way.
//
// NEVER a staging URL, NEVER credentials, NEVER a hypothetical
// placeholder presented as live. HttpProvider itself refuses
// non-https URLs and the staging host (defense in depth).
export const EVIDENCE_BASE = null;
