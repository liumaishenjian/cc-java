# AGENTS.md

This file defines repository-wide instructions for human and AI contributors. It applies to every file under the repository root.

## 1. Read before changing anything

Read these documents in order:

1. [README.md](./README.md)
2. [Reference Architecture](./docs/reference-architecture.md)
3. [Feature Parity Matrix](./docs/feature-parity-matrix.md)
4. [Product Requirements](./docs/product-requirements.md)
5. [Technical Design](./docs/technical-design.md)
6. This file

The reference architecture defines the long-term study target. The parity matrix is the authoritative record of current gaps and target levels. The PRD defines product behavior, and the technical design defines the current implementation approach. Do not silently change one through another.

## 2. Current project phase

The repository is currently in **S00: reference architecture and learning map**.

Until the maintainer explicitly starts S01:

- do not create Maven modules;
- do not add production or test code;
- do not add framework dependencies;
- do not claim that the project is runnable;
- improve the reference baseline, capability matrix, requirements and design instead.

When S01 starts, update this section in the same change that creates the initial skeleton.

## 3. Project intent

`cc-java` is a reference-driven, independently designed Java reimplementation of a general Coding Agent Runtime and CLI.

The learning loop is:

```text
observe public behavior
→ explain the responsible subsystem
→ specify an independent Java contract
→ implement and test it
→ compare against the reference baseline
→ record the remaining gap
→ innovate only after understanding
```

The first runnable coding loop is a checkpoint, not the final scope. FixBug, review and test generation are future Skills or examples, not core domain models.

## 4. Traceability and learning evidence

Every implementation task must identify:

1. its Stage (`S01` through `S15`);
2. one or more Feature IDs from `docs/feature-parity-matrix.md`;
3. the current and target level (`L0` through `L4`);
4. the public behavior or project requirement being reproduced;
5. the test, demo or measurement that proves the new level;
6. the design decision the maintainer should be able to explain afterward.

When a capability advances, update the parity matrix in the same change. A Stage is complete only when it has:

- a design explanation or ADR;
- deterministic tests where feasible;
- a reproducible demonstration;
- a comparison with the reference behavior;
- a short gap report naming what is deliberately still missing.

Do not add features merely because they are interesting. Close the current Stage gap or record an explicit independent-innovation hypothesis and evaluation plan.

## 5. Clean-room and provenance rules

These rules are non-negotiable:

- do not copy or translate leaked, decompiled or otherwise restricted source code;
- do not copy internal Prompt text, comments, errors, private type names, file layout or implementation-specific constants;
- do not use a restricted-source repository as a dependency, submodule, fixture or golden-output source;
- do not reconstruct source expression from memory after inspecting restricted code;
- derive behavior from public documentation, public interfaces and independently created black-box scenarios;
- use independent names and Java-native design justified by this repository's requirements;
- record important third-party inspiration and applicable license obligations;
- do not imply affiliation through product names or trademarks.

“Learning only,” “noncommercial,” and owning a GitHub copy do not grant redistribution rights. If provenance is unclear, stop using that material and retain only independently expressible behavioral requirements.

The repository license remains open. Do not add a `LICENSE` file or accept external code contributions without maintainer confirmation.

## 6. Core architecture invariants

- The model proposes actions; deterministic application code decides whether they execute.
- The Agent Runtime owns the model/tool loop, budgets, cancellation and terminal states.
- A `ModelGateway` represents one model turn and returns raw Tool Calls; Spring AI must not run the whole loop behind the core.
- Every built-in, MCP and plugin Tool uses the same `ToolExecutionPipeline`.
- The pipeline owns validation, lifecycle events, permission, approval, execution, truncation, redaction and result conversion.
- Tool Call IDs and corresponding Tool Result IDs remain exact and ordered.
- CLI, future desktop clients and SDKs consume events; they do not contain Agent decisions.
- Core and domain types do not depend on Spring AI, Reactor, Picocli, JLine, filesystem or persistence types.
- Permission rules are not described as an OS Sandbox.
- README capability claims must match code and parity levels that actually exist.

The initial dependency direction is:

```text
cc-java-domain
        ↑
cc-java-core
    ↑           ↑
cc-java-model-spring-ai   cc-java-tools-local
             \             /
                 cc-java-cli
```

Rules:

- `cc-java-domain` contains framework-free immutable protocols and value objects.
- `cc-java-core` owns the Runtime, Agent Loop, ports, Context, limits, lifecycle and permission pipeline.
- `cc-java-model-spring-ai` only translates between project protocols and Spring AI.
- `cc-java-tools-local` implements project Tool contracts and local execution safety.
- `cc-java-cli` is the Composition Root and terminal adapter.
- Create additional modules only when the active Stage needs them.

## 7. Stage discipline

Stages are learning slices, not declarations that later capabilities are out of scope.

- S01-S04: Runtime kernel, model streaming, repository reading, controlled patch and command loop.
- S05-S08: permission depth, sessions, checkpoints, context, instructions and settings.
- S09-S11: hooks, MCP, skills and plugins.
- S12-S13: subagents, worktrees, background execution and sandboxing.
- S14-S15: production harness and evaluated independent innovation.

Do not prematurely implement a later Stage inside an earlier one. Preserve the documented extension seam, write down the deferred gap and continue through the matrix rather than treating the first working version as completion.

## 8. Security rules

- Treat user input, repository files, model output, Tool arguments, command output and external integrations as untrusted.
- Never rely on a Prompt for access control.
- Resolve and verify real paths before file operations.
- Reject traversal, absolute-path misuse, symlink escape and Windows Junction escape.
- Protect sensitive files and cap file size, result count, output bytes, turns, calls and time.
- Never interpolate model or user text into a Shell string.
- Execute approved commands with structured arguments where possible, a fixed working directory, timeout, output cap and cancellation.
- Never log API keys, complete Prompts, full source files or raw sensitive Tool output by default.
- Secrets come from environment variables or external secret stores, never committed configuration.
- Never add company endpoints, credentials, schemas, logs, tickets, code or unredacted business data.
- Commit, push, merge, release, deployment and external-system writes require separate, explicit user authorization.

If convenience conflicts with a safety boundary, preserve the boundary and record the trade-off.

## 9. Change workflow

Before implementation:

1. identify the Stage, Feature IDs and target level;
2. read the associated reference and acceptance criteria;
3. list affected module boundaries, protocols and security invariants;
4. record a new architectural choice as an ADR before hiding it in code;
5. design the smallest experiment that can falsify the proposed understanding.

While implementing:

- preserve unrelated user changes;
- keep adapters at the edges;
- avoid speculative abstractions and empty future modules;
- use structured errors and explicit terminal states;
- propagate budgets, cancellation and lifecycle events through every new path;
- prefer deterministic fake-model and fake-tool tests before a real provider;
- do not add a dependency when the JDK or an existing dependency is sufficient.

Before completion:

1. run the smallest relevant tests and the broader module tests when practical;
2. run the Stage demonstration or behavioral comparison;
3. verify no secret, private data or restricted-source expression entered the diff;
4. update the matrix level and evidence links;
5. update capability claims and record remaining differences.

## 10. Test expectations

The Agent Loop must be testable without network access or API keys through a scripted fake `ModelGateway`.

At minimum, preserve coverage for:

- direct final responses and streaming aggregation;
- one and multiple Tool rounds;
- multiple Tool Calls in one model turn;
- exact Tool Call/Tool Result ID matching;
- invalid arguments, unknown tools, refusal and Tool failure;
- model failure, empty response and partial streams;
- turn, Tool, token, output and time limits;
- user steering, cancellation and child-process cleanup;
- resume, incomplete side-effect detection and context compaction when their Stages begin.

Tool tests must cover traversal, absolute paths, symlink/Junction escape, sensitive files, size caps, output truncation and dirty-worktree preservation.

Real-model tests are opt-in, are not ordinary CI prerequisites, must not assert exact prose and must not expose credentials or private repositories.

Behavioral comparison uses independently authored tasks and observable results. Never use restricted source text as expected test output.

## 11. Multiple Tool Call protocol

When one model turn contains multiple Tool Calls:

1. append the Assistant Message containing all calls exactly once;
2. execute calls according to the active Stage's ordering policy;
3. append exactly one Tool Result for every call ID;
4. request the next model turn only after the batch reaches a defined terminal state.

Do not append the same Assistant Message once per Tool.

## 12. Documentation conventions

- Keep documents in UTF-8 Markdown.
- Use `FR-*`, `NFR-*` and Feature IDs when describing behavior.
- Mark decisions as `Proposed`, `Accepted`, `Open` or `Superseded`.
- Add a date or baseline ID to version-sensitive reference claims.
- Prefer primary public documentation for framework and product behavior.
- Distinguish observed behavior, inference and independent design.
- Update PRD, technical design and parity matrix together when scope or capability changes.
- Use Mermaid only when it materially clarifies flow, state or dependencies.

## 13. Dependency and version policy

The provisional baseline is Java 21, Spring Boot 4.1.x, Spring AI 2.0.x, Picocli, JLine and JUnit 5. Confirm exact versions in an ADR at S01.

When dependencies are introduced:

- use the Spring Boot parent or BOM for Boot-managed dependencies;
- import the Spring AI BOM separately;
- use Maven Wrapper;
- prefer stable releases from Maven Central;
- begin with one real model Provider plus a fake gateway;
- explain every non-test dependency in the change description.

## 14. Git conventions

- Use focused commits with Conventional Commit-style subjects such as `docs:`, `feat:`, `fix:`, `test:` and `refactor:`.
- Do not rewrite shared history unless the maintainer explicitly asks.
- Do not commit generated build output, IDE state, secrets or local model configuration.
- Do not push, merge, create releases or alter external systems unless explicitly requested.

## 15. Definition of done

A change is done only when:

- it reaches the declared Feature ID target level;
- its behavior can be explained from public requirements and independent design;
- module dependencies and execution pipelines preserve documented boundaries;
- relevant offline tests and Stage evidence pass;
- the parity matrix and capability claims are accurate;
- remaining gaps and risks are stated rather than hidden.
