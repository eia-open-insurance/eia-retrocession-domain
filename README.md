# eia-retrocession-domain

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-retrocession-domain
- Type: Spring Boot service
- Deployable: Yes
- Owner: Retrocession
- Runtime: Java 22 and Spring Boot
- Data store: eia_retrocession (tx schema)
- Depends on: eia-retrocession-expert, eia-contracts, eia-platform/libs

## Purpose

Retrocession business rules, transaction catalog, orchestration, approvals, and APIs. Assumed-book retrocession contracts, retro rules, FS-RI hand-off, and recoverables.

## Implemented slice

- Spring Boot 3.4.0 on Java 22, port `8094`, calling the expert with `RestClient`.
- Public API under `/api/v1/retrocessions` (list, get, create, status transition).
- Requires `X-EIA-Tenant` (UUID) and `X-EIA-Book: ASSUMED`; all other books return a structured `400 BOOK_SCOPE_VIOLATION`.
- Enforces non-negative ceded amounts, three-letter currency, and the retro-case lifecycle from SPEC 11.10.3 before calling the expert.
- Structured 400 and 404 errors; expert outages return `503 EXPERT_UNAVAILABLE`.
- JUnit controller and service tests.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
.\gradlew.bat bootRun
```

Start the expert on port `8093` first. Override `eia.retrocession.expert-url` to point at another expert instance.

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
