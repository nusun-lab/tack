# Architecture Notes

## Problem

A local LLM is a scarce resource. Agent teams create bursty demand: planners, builders, reviewers, fixers, and summarizers all want the model at once. If every agent calls vLLM directly, the system loses control over concurrency, priority, observability, and failure handling.

## Hypothesis

Kafka plus Camel is a strong fit for LLM sharing because it separates submission from execution.

- Kafka absorbs bursts.
- Camel makes the orchestration explicit and inspectable.
- The LLM worker route becomes the lane parallelism gate.
- Results become events that other agents can consume.

## Inference Lane Parallelism

An inference lane is a shared Kafka-backed model work pool. Its `parallelism` controls how many jobs that lane can work on concurrently in the current single-runtime-instance topology.

`parallelism = 1` keeps the lane serialized. `parallelism > 1` creates a partitioned work pool: the worker route uses `consumersCount=parallelism` with `maxPollRecords=1`.

When topic provisioning is enabled, missing lane request topics are created with partitions equal to lane parallelism. Provisioning does not silently increase partitions for existing topics; existing request topics must already have at least `lane.parallelism()` partitions or startup fails clearly. Reply topics remain single-partition in the current single-orchestrator topology.

Lane-wide FIFO is not guaranteed when `parallelism > 1`. Ordering is preserved only for jobs with the same Kafka partition key. The default partition key strategy is `AGENT_RUN`, with fallback `agentRunId -> missionId -> jobId`. `MISSION` uses `missionId -> agentRunId -> jobId`, and `JOB` uses `jobId`.

Replies remain simple in this mode: reply topics are single-partition, reply records are keyed by `jobId`, and `InFlightReplyManager` completes the local in-memory waiter by `jobId`.

The current request/reply implementation assumes one active orchestrator/runtime instance owns those in-memory waiters. Lane worker parallelism is supported inside that topology. Horizontally scaled orchestrator/API instances require reply-to routing or durable reply storage and are intentionally out of scope for this mode.

## Why not just use a Java BlockingQueue?

A local queue is fine for a toy. Kafka gives you the properties you will need once agents become separate services:

- durable queue
- replayable request/response log
- multi-producer fan-in
- worker isolation
- metrics
- future partitioning strategy
- OpenShift-friendly operational model

## Near-term decisions

### Priority

Kafka does not natively reorder records inside a partition by priority. Options:

1. Use separate topics: `llm.requests.high`, `llm.requests.normal`, `llm.requests.low`.
2. Use a scheduler service that reads submitted jobs from a DB and publishes to Kafka in priority order.
3. Use Kafka only for accepted execution jobs and keep priority in Postgres.

For the first proof, keep priority as metadata only.

### Persistence

The in-memory `JobStore` is intentionally disposable. Replace it with Postgres when you care about restarts.

### Result contracts

Eventually each request should specify a response contract:

- JSON schema
- maximum tokens
- retry policy
- timeout
- cacheability
- requester/role

The worker should reject invalid outputs instead of letting bad artifacts flow downstream.
