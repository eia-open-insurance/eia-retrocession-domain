# eia-retrocession-domain

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-retrocession-domain
- Type: Spring Boot service
- Deployable: Yes
- Owner: Retrocession
- Runtime: Java 22 and Spring Boot
- Data store: eia_retrocession (tx schema)
- Depends on: eia-retrocession-expert, eia-contracts, eia-open-insurance-libs

## Purpose

Retrocession business rules, transaction catalog, orchestration, approvals, and APIs. Reinsurance-book retrocession contracts, retro rules, FS-RI hand-off, and recoverables.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Owns the business logic for this vertical.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
