# Manual import evidence

See [the validation record](../../manual-import-validation.md) for scope, failed attempts,
source boundaries and unverified real-network/recovery gates.

- `2026-10-04/` retains the initial implementation snapshot: focused JVM 170/170,
  full Windows JVM 991/999 (eight POSIX environment failures), OPPO UI 32/32,
  four distinct physical-platform paths, and baseline/upgrade/rollback validation.
  Failed earlier UI/camera/permission attempts are included separately.
- `2026-10-05/` records the final account-source controller follow-up: two behavioral
  RED cases, focused JVM 172/172 GREEN, rebuilt APKs/Lint and OPPO Host/account 7/7.
  Source inputs include new files and the isolated build overlay; artifacts are
  explicitly working-tree builds, not clean-source-commit builds.
- Cleanup receipts verify only owned synthetic fixtures. The normal application
  remains on its earlier APK; no normal-app feature upgrade is claimed.

These JSON files contain fixed test identifiers/results, hashes and metadata. They do
not contain raw configurations, subscription links, account data, logs or camera images.
Local binary artifacts and raw build logs remain under `.build-output/` and are not
part of the repository evidence. Repeated denial or UI runs are not additional unique
coverage. Earlier failed hosted CI runs are not converted into passing results.
