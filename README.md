# EdgeFit AI

Hackathon prototype: Meta-assisted, evidence-based Android inference experiments.

**Development started October 26, 2026.** This repository is intended for new hackathon work. Honey LLM and BeeBench are pre-existing independent projects; their code and results are not claimed as new work.

## Qualification scripts

- `scripts/Test-EF-META-04.ps1` — PowerShell local invalid tool-argument tests (no Meta API usage).
- `scripts/ef_data_01.py` — Python standard-library SQLite database fixture and persistence verification (no Meta API usage).

Synthetic fixture values are illustrative and not physical measurements.
Run from repository root:

```powershell
& .\scripts\Test-EF-META-04.ps1
py -3 .\scripts\ef_data_01.py
```

Credentials must not be committed. Database files in `data/` are intentionally ignored. Public repository publication and acceptance under official hackathon rules must be verified separately.
