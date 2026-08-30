# ADR 0001: Java platform foundation

- Decision status: Accepted
- Implementation status: Built — `pom.xml` sets `java.version` 25 on the
  Spring Boot 4.1.0 parent with the checked-in Maven Wrapper (`mvnw`,
  `.mvn/wrapper/`), Spring Modulith 2.1.0 with
  `spring.modulith.detection-strategy: explicitly-annotated`, and Camel 4.22.0
  through `camel-spring-boot-starter` plus `camel-resilience4j-starter`.
  Persistence is `spring-boot-starter-jdbc` and Flyway with no JPA dependency;
  `V0001__create_module_schemas.sql` creates the thirteen module schemas and no
  business tables. `SecurityConfiguration` is an OAuth2 resource server with
  `issuer-uri` and `audiences` bound in `application.yml`.
  `ModularArchitectureTests` runs `ApplicationModules.verify()` and asserts that
  no domain module imports `org.apache.camel`.
- Date proposed: 2026-08-18
- Date decided: 2026-08-18
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: none
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Qoida needs a foundation that supports capability-based growth, provider
adapters, durable messaging, tenant isolation, and incremental replacement of
the legacy Python system. The foundation must avoid committing the product to
premature microservices or to business tables before the canonical domain
model is approved.

## Decision

- Use Java 25 as the required language and runtime level.
- Use Spring Boot 4.1.0, the current stable Spring Boot release when this ADR
  was accepted.
- Use Maven 3.9 through the checked-in Maven Wrapper.
- Start as one deployable modular monolith with business capabilities as
  explicitly annotated Spring Modulith 2.1 modules.
- Use Apache Camel 4.22 for the integration boundary. Domain modules must not
  import Camel APIs.
- Use standard Spring Security OAuth 2.0 resource-server support with
  Keycloak, validating issuer and audience.
- Create only module-owned PostgreSQL schemas in the foundation migration.
  Business tables wait for the target domain model and invariants.
- Keep Kafka, provider, and object-storage integrations behind module ports.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Microservices from the start | No evidence yet exists for the boundaries. Splitting tenancy, ordering, and payments across processes before the domain model is proven would replace local transactions with distributed ones and make every early refactor a deployment problem | A module demonstrates independent team ownership, data boundary, scaling profile, and failure isolation, as ADR 0023 requires |
| Continue on Python/FastAPI and refactor in place | The defect is the data model and tenancy, not the language, so a rewrite is not automatically justified. Java was still chosen for typed domain modeling, first-class Camel/Kafka/Keycloak integration, and long-lived transactional workloads where static typing pays for itself across a 24-ADR program | Never for this program. A separate Python service for analytics or ML remains acceptable |
| Kotlin on Spring Boot | Smaller local hiring pool. Java 25 records, sealed types, and pattern matching close most of the ergonomic gap that motivated Kotlin | Team composition changes such that Kotlin experience dominates |
| Quarkus or Micronaut | Faster startup and lower memory, neither of which matters for a long-running monolith. Spring Modulith, Spring Security OAuth2, Spring Kafka, and Camel-Spring integration are the mainstream, best-documented path | Scale-to-zero or serverless deployment becomes a requirement |
| Java 21 LTS instead of Java 25 | Java 25 is itself an LTS release and is supported by Spring Boot 4. Choosing the older LTS would mean adopting a migration debt on day one | Never |
| Gradle instead of Maven | A single-artifact modulith has few build customizations. Maven's declarative model plus the checked-in wrapper is the lower-variance choice | Build times become a measured bottleneck, or the Java and TypeScript workspaces need one build graph |

## Consequences

- Every developer and CI image needs JDK 25.
- The Java deployment remains operationally simple while module tests expose
  accidental coupling.
- A module can be extracted later, but extraction is not a reason to add a
  network boundary now.
- Spring Boot, Spring Modulith, and Camel compatibility must be checked as a
  set during upgrades.
- The first business feature must add its model, constraints, migrations,
  authorization tests, and migration mapping together.

## References

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Modulith reference](https://docs.spring.io/spring-modulith/reference/)
- [Apache Camel downloads](https://camel.apache.org/download/)
