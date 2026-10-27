# EdgeFit release and distribution architecture (PROPOSED)

How the public application ships without leaking secrets, evidence,
or trust. Nothing below is implemented; existing APKs are debuggable
benchmark builds and are explicitly NOT release candidates.

## Build variants

- `debug`: development installs (current default). Debuggable,
  unoptimized native unless configured otherwise. Never distributed.
- `benchmark`: owner testing with optimized native
  (`applicationIdSuffix .bench`). Debuggable. Never distributed.
- `public-preview` (new, PROPOSED): `minifyEnabled false`,
  `debuggable false`, `jniDebuggable false`, release signing,
  stable `applicationId ai.edgefit.diag`, versioned
  `versionCode`/`versionName`, secret scan in the release gate.
- Future Play track: same package and signing key as
  public-preview so direct installs upgrade cleanly (FR-DIST-02).

## Signing and identity

Release signing uses a dedicated upload/keystore held by the owner
off-repo (never in source, CI logs, or artifacts). The signing
certificate SHA-256 fingerprint is published with every release.
Package `ai.edgefit.diag` is frozen at first public release;
`versionCode` increases monotonically.

## GitHub Releases (authoritative direct channel)

Each release publishes: the signed APK, SHA-256 checksum file,
signing-certificate fingerprint, versioned release notes (features,
tested devices/ABIs, known limits), and a link to the matching
source tag. The company website links to the Releases page; it
never hosts APK bytes itself. Pre-release flags mark preview
builds until the compatibility matrix covers them.

## Release gates (all must pass)

1. Offline clean-checkout build (Gradle + NDK pinned) succeeds.
2. Full JVM + Python suites green; native ownership test green.
3. Secret scan over tree AND built APK (strings check) passes.
4. No debuggable flags, no `BenchConfig` backdoors, no embedded
   keys or tokens in the APK.
5. Contract suites validate exports from the release candidate.
6. Physical install + launch + profile export + benchmark + save
   verified on the S22 Ultra; upgrade install over the previous
   release verified.
7. Release notes state exactly what was tested and what was not.

## User-side guidance (shipped with each release)

- GGUF acquisition: source repo, expected SHA-256, license id,
  and owner-action wording per `bench-build.md`; the app never
  downloads models itself.
- Unknown-app install steps for the user's Android version.
- Tested ABI/API floor (`arm64-v8a`, minSdk 26) and the current
  compatibility matrix with untested combinations marked.
- BYOK cost disclosure: Meta calls are billed to the user's own
  key; every connected action states its bounds before sending.

## Google Play (future, PROPOSED)

Play listing requires: policy review (network disclosure, data
safety form matching FR-PRIV-01), staged rollout plan, crash
reporting decision (no telemetry without consent design), and the
FR-DIST-02 upgrade test. No Play availability or approval is
claimed before it occurs. Direct APK remains supported alongside.
