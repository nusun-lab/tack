# Model sampling configuration

Tack supports global model-default sampling values plus per-lane overrides.

Global defaults:

| Environment variable | Default |
| --- | --- |
| `LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE` | `0` |
| `LLM_MODEL_DEFAULT_OPENAI_TOP_P` | `1.0` |
| `LLM_MODEL_DEFAULT_OPENAI_PRESENCE_PENALTY` | `0.0` |
| `LLM_MODEL_DEFAULT_OPENAI_TOP_K` | `-1` |
| `LLM_MODEL_DEFAULT_OPENAI_MIN_P` | `0.0` |
| `LLM_MODEL_DEFAULT_OPENAI_REPETITION_PENALTY` | `1.0` |

High-pass overrides:

```bash
LLM_HIGH_TEMPERATURE=0.7
LLM_HIGH_TOP_P=0.8
LLM_HIGH_PRESENCE_PENALTY=1.0
LLM_HIGH_TOP_K=20
LLM_HIGH_MIN_P=0.0
LLM_HIGH_REPETITION_PENALTY=1.05
```

Low-pass equivalents use the `LLM_LOW_` prefix. Any unset lane value falls back to the matching global model-default value.

`temperature`, `top_p`, and `presence_penalty` are sent as standard OpenAI-compatible request parameters. `top_k`, `min_p`, and `repetition_penalty` are sent as custom request parameters supported by vLLM.

## Qwen 3.8 high-pass profile

The high-pass profile above resolved repeated equivalent tool searches observed with Qwen 3.8 under deterministic sampling. These are runtime tuning values, not framework invariants.

## Local `.env`

Add only the high-pass values when tuning Qwen 3.8 independently:

```bash
LLM_HIGH_TEMPERATURE=0.7
LLM_HIGH_TOP_P=0.8
LLM_HIGH_PRESENCE_PENALTY=1.0
LLM_HIGH_TOP_K=20
LLM_HIGH_MIN_P=0.0
LLM_HIGH_REPETITION_PENALTY=1.05
```

Then export them before starting Tack:

```bash
set -a
source .env
set +a
```

`.env` is intentionally gitignored.

## OpenShift Secret

The Tack deployment imports all environment variables from Secret `tack` through `envFrom`. Patch only the high-pass sampling keys so the low-pass model retains its existing sampling profile:

```bash
oc -n tack patch secret tack --type=merge -p '{
  "stringData": {
    "LLM_HIGH_TEMPERATURE": "0.7",
    "LLM_HIGH_TOP_P": "0.8",
    "LLM_HIGH_PRESENCE_PENALTY": "1.0",
    "LLM_HIGH_TOP_K": "20",
    "LLM_HIGH_MIN_P": "0.0",
    "LLM_HIGH_REPETITION_PENALTY": "1.05"
  }
}'
oc -n tack rollout restart deployment/tack
oc -n tack rollout status deployment/tack
```

Patching `stringData` preserves unrelated Secret keys. Existing pods must be restarted to receive changed environment variables.
