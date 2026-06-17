# Architecture Notes

## Problem

A local LLM is a scarce resource. Agent teams create bursty demand: planners, builders, reviewers, fixers, and summarizers all want the model at once. If every agent calls vLLM directly, the system loses control over concurrency, priority, observability, and failure handling.

## Hypothesis

Kafka plus Camel is a strong fit for LLM sharing because it separates submission from execution.

- Kafka absorbs bursts.
- Camel makes the orchestration explicit and inspectable.
- The LLM worker route becomes the single concurrency gate.
- Results become events that other agents can consume.

## Initial Concurrency Model

The first version uses one worker consumer and one record per poll.

```properties
llm.worker.consumers-count=1
llm.worker.max-poll-records=1
```

That makes the worker effectively single-flight.

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
