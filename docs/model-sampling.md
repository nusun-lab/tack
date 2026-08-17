# Model sampling configuration

Tack exposes the model-default sampling profile through environment-backed application properties.

| Environment variable | Property | Default |
| --- | --- | --- |
| `LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE` | `llm.model-default.openai.temperature` | `0` |
| `LLM_MODEL_DEFAULT_OPENAI_TOP_P` | `llm.model-default.openai.top-p` | `1.0` |
| `LLM_MODEL_DEFAULT_OPENAI_PRESENCE_PENALTY` | `llm.model-default.openai.presence-penalty` | `0.0` |
| `LLM_MODEL_DEFAULT_OPENAI_TOP_K` | `llm.model-default.openai.top-k` | `-1` |
| `LLM_MODEL_DEFAULT_OPENAI_MIN_P` | `llm.model-default.openai.min-p` | `0.0` |
| `LLM_MODEL_DEFAULT_OPENAI_REPETITION_PENALTY` | `llm.model-default.openai.repetition-penalty` | `1.0` |

`temperature`, `top_p`, and `presence_penalty` are sent as standard OpenAI-compatible request parameters. `top_k`, `min_p`, and `repetition_penalty` are sent as custom request parameters supported by vLLM.

## Qwen 3.8 agent-tooling profile

This profile resolved repeated equivalent tool searches observed with Qwen 3.8 under deterministic sampling:

```bash
LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE=0.7
LLM_MODEL_DEFAULT_OPENAI_TOP_P=0.8
LLM_MODEL_DEFAULT_OPENAI_PRESENCE_PENALTY=1.0
LLM_MODEL_DEFAULT_OPENAI_TOP_K=20
LLM_MODEL_DEFAULT_OPENAI_MIN_P=0.0
LLM_MODEL_DEFAULT_OPENAI_REPETITION_PENALTY=1.05
```

These are runtime tuning values, not framework invariants. Different models may require different sampling profiles.

## Local `.env`

Add the values above to `.env`, then export them before starting Tack:

```bash
set -a
source .env
set +a
```

`.env` is intentionally gitignored.

## OpenShift Secret

The Tack deployment imports all environment variables from Secret `tack` through `envFrom`. Update only the sampling keys so existing credentials and endpoint settings remain untouched:

```bash
oc -n tack patch secret tack --type=merge -p '{
  "stringData": {
    "LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE": "0.7",
    "LLM_MODEL_DEFAULT_OPENAI_TOP_P": "0.8",
    "LLM_MODEL_DEFAULT_OPENAI_PRESENCE_PENALTY": "1.0",
    "LLM_MODEL_DEFAULT_OPENAI_TOP_K": "20",
    "LLM_MODEL_DEFAULT_OPENAI_MIN_P": "0.0",
    "LLM_MODEL_DEFAULT_OPENAI_REPETITION_PENALTY": "1.05"
  }
}'
oc -n tack rollout restart deployment/tack
oc -n tack rollout status deployment/tack
```

Patching `stringData` avoids reconstructing the full Secret and therefore preserves unrelated keys.
