# ai-be-to-fe-spring-boot-starter

> **Automatically generate React / TypeScript frontend code whenever your backend changes.**
>
> Add this starter to any Spring Boot project and it will listen for GitHub push/merge events,
> send the diff to an LLM, and open a Pull Request in your frontend repository with the
> generated TypeScript types, RTK Query API slices, and hooks — all without any manual steps.

---

## Table of Contents

1. [How It Works](#1-how-it-works)  
2. [Architecture Deep-Dive](#2-architecture-deep-dive)  
3. [Project Structure](#3-project-structure)  
4. [Quick Start](#4-quick-start)  
5. [Step-by-Step Setup Guide](#5-step-by-step-setup-guide)  
   - 5.1 [Add the Dependency](#51-add-the-dependency)  
   - 5.2 [Create a GitHub Personal Access Token](#52-create-a-github-personal-access-token)  
   - 5.3 [Configure Environment Variables](#53-configure-environment-variables)  
   - 5.4 [Configure application.yml](#54-configure-applicationyml)  
   - 5.5 [Register the GitHub Webhook](#55-register-the-github-webhook)  
   - 5.6 [Add an OpenAI API Key](#56-add-an-openai-api-key)  
   - 5.7 [(Optional) Add a Frontend Project Template](#57-optional-add-a-frontend-project-template)  
6. [On-Demand Generation API](#6-on-demand-generation-api)  
7. [Configuration Reference](#7-configuration-reference)  
8. [AI Generation Logic](#8-ai-generation-logic)  
9. [Generated Pull Request Format](#9-generated-pull-request-format)  
10. [Disabling the Starter](#10-disabling-the-starter)  
11. [Troubleshooting](#11-troubleshooting)  

---

## 1. How It Works

```
  Backend Repository                  ai-be-to-fe-spring-boot-starter         Frontend Repository
  ──────────────────                  ────────────────────────────────         ───────────────────
  Developer pushes                                                    
  a commit / merges ──── GitHub ────► POST /github/webhook                     
  a PR to main          Webhook       │                                         
                                      │ 1. Validate HMAC-SHA256 signature       
                                      │ 2. Extract commit SHA                   
                                      │                                         
                                      │ 3. Fetch commit diff via GitHub API     
                                      │    (changed files + patches)            
                                      │                                         
                                      │ 4. Load .ai-fe-template.txt             
                                      │    (project conventions, optional)      
                                      │                                         
                                      │ 5. Send diff + template to LLM  ──────► OpenAI GPT-4o
                                      │    (agentic loop: LLM can call           │
                                      │     fetchFrontendFile() tool to          │ reads existing
                                      │     read existing FE files)  ◄────────── FE files via
                                      │                                          GitHub API
                                      │ 6. Parse structured JSON response        │
                                      │    { summary, files: [{op, path,  ◄─────┘
                                      │      content}] }                         
                                      │                                         
                                      │ 7. Create branch  ai/fe-sync-<sha> ────► new branch
                                      │ 8. Commit generated TS files ──────────► commit files
                                      │ 9. Open Pull Request ──────────────────► PR for review
                                      │
                                      └── Return HTTP 202 immediately
                                          (pipeline runs asynchronously)
```

The entire pipeline runs **asynchronously** — the webhook endpoint immediately returns `HTTP 202 Accepted` and the AI generation + GitHub operations happen in a background thread, so GitHub never times out waiting for a response.

---

## 2. Architecture Deep-Dive

### A. Webhook Receiver — `GitHubWebhookController`

**Endpoint:** `POST /github/webhook`

Handles two GitHub event types (set via the `X-GitHub-Event` header):

| Event | Trigger condition |
|-------|-------------------|
| `push` | Commit pushed directly to the configured `backend-default-branch` |
| `pull_request` | PR with `action: closed` and `merged: true` targeting the configured branch |

**Security — HMAC-SHA256 signature verification**

Every incoming request is validated before any processing occurs. GitHub computes an
`HMAC-SHA256` hash of the raw request body using the shared `webhook-secret` and sends
it in the `X-Hub-Signature-256` header. The starter recomputes the same hash and uses
a **constant-time comparison** (preventing timing attacks) to verify authenticity.
Any request that fails this check is rejected with `HTTP 401`.

```
X-Hub-Signature-256: sha256=<hmac-hex>
                              └── HMAC-SHA256(rawBody, webhookSecret)
```

---

### B. GitHub Integration Service — `GitHubIntegrationService`

Uses the `org.kohsuke:github-api` library to talk to the GitHub REST API.  
All operations use the configured `github-token` for authentication.

| Operation | Description |
|-----------|-------------|
| `fetchCommitDiff(sha)` | Reads all changed files and their patches from the backend repo for the given commit SHA |
| `fetchFileContent(path)` | Reads a single file from the frontend repo's default branch (used by the AI tool) |
| `fetchFETemplate()` | Looks for `.ai-fe-template.txt` at the root of the frontend repo |
| `createBranch(name)` | Creates `ai/fe-sync-<sha>` branching off the frontend repo's default branch |
| `commitFiles(branch, files)` | Iterates the AI-generated file list and performs `CREATE` / `UPDATE` / `DELETE` operations via the GitHub Contents API |
| `createPullRequest(branch, title, body)` | Opens a PR from the new branch into the frontend repo's default branch |

For **UPDATE** operations, the starter always writes the **complete file content** supplied by the AI — never a patch. This is intentional: applying text patches programmatically is fragile; a full overwrite via the GitHub API is reliable and atomic.

---

### C. AI Code Generator Service — `AiCodeGeneratorService`

This is the intelligence layer. It uses **Spring AI's `ChatClient`** to communicate with OpenAI GPT-4o (or any other configured model).

#### Agentic Tool Calling

The AI is given a **tool** — `fetchFrontendFile(filePath)` — that it can invoke autonomously
during its reasoning process. When the model detects that a backend change might affect an
existing frontend file, it pauses, calls the tool, receives the file content, and
incorporates that context before producing the final output.

This means the AI:
1. Sees the backend diff
2. **Actively decides** which frontend files might be affected
3. **Reads those files** before writing its answer
4. Produces output that is aware of existing imports, exports, and patterns

#### Structured Output

A `BeanOutputConverter<AiFrontendResponse>` automatically injects the JSON schema of the
expected response directly into the prompt. This forces the model to return a valid,
parseable JSON object rather than free-form text.

#### Markdown Sanitisation

Some models wrap JSON output in markdown code fences (` ```json ... ``` `). The sanitiser
strips these before parsing, so the commit never contains syntax errors.

---

### D. Webhook Orchestration Service — `WebhookOrchestrationService`

The central coordinator. Wires A, B, and C together and runs the full pipeline:

```
fetchCommitDiff → fetchFETemplate → generateFrontendCode
    → (if files not empty) createBranch → commitFiles → createPullRequest
    → (if files empty) log "no FE changes needed" and stop
```

Annotated with `@Async` — runs in a Spring-managed thread pool, never blocking the
HTTP response.

---

### E. Auto-Configuration — `AiFeGeneratorAutoConfiguration`

Registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
so it is picked up automatically by any project that adds this starter as a Maven/Gradle
dependency. All beans are created conditionally:

- `@ConditionalOnProperty(prefix = "ai.fe-generator", name = "enabled", havingValue = "true", matchIfMissing = true)` — the entire starter can be disabled with a single property.
- `@ConditionalOnMissingBean` on every bean — consumers can override any individual component by declaring their own bean.

---

## 3. Project Structure

```
src/main/java/com/example/ai_be_to_fe_spring_boot_starter/
│
├── autoconfigure/
│   └── AiFeGeneratorAutoConfiguration.java     # Spring Boot auto-config entry point
│
├── config/
│   └── AiFeGeneratorProperties.java            # @ConfigurationProperties(prefix="ai.fe-generator")
│
├── controller/
│   └── GitHubWebhookController.java            # POST /github/webhook
│
├── service/
│   ├── GitHubIntegrationService.java           # All GitHub API operations
│   ├── AiCodeGeneratorService.java             # LLM prompting + structured output parsing
│   └── WebhookOrchestrationService.java        # Pipeline coordinator (@Async)
│
├── model/
│   ├── AiFrontendResponse.java                 # { summary, files[] }
│   ├── FileModification.java                   # { operation, path, content }
│   ├── OperationType.java                      # CREATE | UPDATE | DELETE
│   ├── PushEventPayload.java                   # GitHub push event JSON model
│   └── PullRequestEventPayload.java            # GitHub pull_request event JSON model
│
└── tools/
    └── GitHubFileTools.java                    # Spring AI @Tool — lets LLM read FE files

src/main/resources/
├── application.yaml                            # Sample configuration
└── META-INF/spring/
    └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## 4. Quick Start

```bash
# 1. Clone and install the starter into your local Maven repository
git clone https://github.com/your-org/ai-be-to-fe-spring-boot-starter.git
cd ai-be-to-fe-spring-boot-starter
./mvnw clean install -DskipTests

# 2. Copy the env template and fill in your secrets
cp .env.example .env
# edit .env

# 3. Run (for local webhook testing use ngrok or similar)
./mvnw spring-boot:run
```

---

## 5. Step-by-Step Setup Guide

### 5.1 Add the Dependency

Add the starter to the project that will **host the webhook** (this can be a dedicated
micro-service or your existing backend application).

**Maven:**
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>ai-be-to-fe-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Gradle (Kotlin DSL):**
```kotlin
implementation("com.example:ai-be-to-fe-spring-boot-starter:0.0.1-SNAPSHOT")
```

> The starter auto-configures itself. No `@EnableXxx` annotation is needed.

---

### 5.2 Create a GitHub Personal Access Token

The token needs permission to read the backend repo and read/write the frontend repo.

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**
2. Click **Generate new token**
3. Set **Repository access** to your backend and frontend repositories
4. Grant the following permissions:

   | Permission | Level |
   |------------|-------|
   | **Contents** | Read and Write |
   | **Pull requests** | Read and Write |
   | **Metadata** | Read (auto-selected) |

5. Copy the generated token — you will need it as `AI_FE_GENERATOR_GITHUB_TOKEN`.

---

### 5.3 Configure Environment Variables

Copy `.env.example` to `.env` (never commit `.env`):

```bash
cp .env.example .env
```

Fill in all values:

```dotenv
# OpenAI
SPRING_AI_OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o                          # optional, defaults to gpt-4o

# GitHub authentication
AI_FE_GENERATOR_GITHUB_TOKEN=ghp_...

# GitHub webhook shared secret (you will create this in step 5.5)
AI_FE_GENERATOR_WEBHOOK_SECRET=your-webhook-secret

# Backend repository
AI_FE_GENERATOR_BACKEND_REPO=my-org/my-backend
AI_FE_GENERATOR_BACKEND_BRANCH=main          # optional, defaults to main

# Frontend repository
AI_FE_GENERATOR_FRONTEND_REPO=my-org/my-react-app
AI_FE_GENERATOR_FRONTEND_PATH=src/api        # optional, defaults to src/api
```

**Loading `.env` at runtime:**

Spring Boot does not load `.env` files natively. Use one of:

- **Docker / Docker Compose:** `env_file: .env`
- **IntelliJ IDEA:** EnvFile plugin, or set variables in Run Configuration → Environment Variables
- **Shell:**
  ```bash
  export $(grep -v '^#' .env | xargs) && ./mvnw spring-boot:run
  ```
- **dotenv-java** (add to `pom.xml` and call `Dotenv.load()` in your main class)

---

### 5.4 Configure `application.yml`

If you are adding the starter to **your own project**, add this block to your existing
`application.yml`. All values that contain `${...}` are read from environment variables
(with defaults after the colon):

```yaml
spring:
  ai:
    openai:
      api-key: ${SPRING_AI_OPENAI_API_KEY:}
      chat:
        options:
          model: ${OPENAI_MODEL:gpt-4o}
          temperature: 0.2

ai:
  fe-generator:
    enabled: true
    github-token: ${AI_FE_GENERATOR_GITHUB_TOKEN:}
    webhook-secret: ${AI_FE_GENERATOR_WEBHOOK_SECRET:}
    backend-repo-name: ${AI_FE_GENERATOR_BACKEND_REPO:my-org/my-backend}
    backend-default-branch: ${AI_FE_GENERATOR_BACKEND_BRANCH:main}
    frontend-repo-name: ${AI_FE_GENERATOR_FRONTEND_REPO:my-org/my-react-app}
    frontend-source-path: ${AI_FE_GENERATOR_FRONTEND_PATH:src/api}

    # Optional: override the AI system prompt to match your FE stack exactly
    # ai-system-prompt: |
    #   You are an expert React TypeScript developer.
    #   Use RTK Query createApi / fetchBaseQuery for data fetching.
    #   Use Zod for runtime validation.
```

---

### 5.5 Register the GitHub Webhook

GitHub must be able to reach your running application over the public internet.
For local development use [ngrok](https://ngrok.com/) or [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/).

```bash
# Example with ngrok
ngrok http 8080
# Forwarding: https://abc123.ngrok.io -> http://localhost:8080
```

**Register the webhook on the backend repository:**

1. Go to your **backend** GitHub repository
2. Navigate to **Settings → Webhooks → Add webhook**
3. Fill in the form:

   | Field | Value |
   |-------|-------|
   | **Payload URL** | `https://your-domain.com/github/webhook` |
   | **Content type** | `application/json` |
   | **Secret** | A strong random string — this becomes `AI_FE_GENERATOR_WEBHOOK_SECRET` |
   | **Which events?** | Select **Let me select individual events**, then tick **Pushes** and **Pull requests** |
   | **Active** | ✅ checked |

4. Click **Add webhook**
5. GitHub will send a `ping` event — you should see `HTTP 202` in the webhook delivery log.

> 💡 **Tip:** Generate a strong secret with:
> ```bash
> openssl rand -hex 32
> ```

---

### 5.6 Add an OpenAI API Key

1. Go to [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. Create a new secret key
3. Set it as `SPRING_AI_OPENAI_API_KEY` in your `.env` / environment

The starter uses **GPT-4o** by default (`temperature: 0.2` for deterministic output).
You can change the model via the `OPENAI_MODEL` environment variable — any model supported
by Spring AI's OpenAI adapter works (e.g. `gpt-4-turbo`, `gpt-4o-mini`).

---

### 5.7 (Optional) Add a Frontend Project Template

Create a file named **`.ai-fe-template.txt`** at the **root of your frontend repository**.
The starter automatically reads this file and injects its content into every AI prompt,
giving the LLM precise instructions about your project's conventions.

**Example `.ai-fe-template.txt`:**

```text
Frontend project conventions
────────────────────────────
Framework   : React 18 with TypeScript (strict mode)
Data layer  : RTK Query (Redux Toolkit) — createApi / fetchBaseQuery
HTTP client : built-in RTK Query fetchBaseQuery (NOT Axios)
Validation  : Zod schemas for all API response types
File naming : kebab-case for files, PascalCase for components & interfaces
API base URL: use the constant BASE_URL from src/api/config.ts

Directory layout:
  src/api/
    config.ts          ← shared API config (BASE_URL, etc.)
    types/             ← TypeScript interfaces / Zod schemas
    services/          ← RTK Query createApi slices
    hooks/             ← re-exported generated hooks (if split from slice)

Conventions:
  - Each createApi slice file is named <resource>.api.ts (e.g. user.api.ts)
  - Each type file is named <resource>.types.ts (e.g. user.types.ts)
  - All interfaces must be exported
  - Do NOT import from node_modules directly in type files
  - Prefer interface over type alias for object shapes
```

Without this file the starter falls back to sensible RTK Query defaults, but providing it
dramatically improves output quality, especially for large or non-standard codebases.

---

## 6. On-Demand Generation API

In addition to the automatic GitHub webhook trigger, the starter exposes a second endpoint
that lets you **request FE code generation at any time** — from a CI step, a developer
tool, a Slack bot, or a simple `curl` command.

### Endpoint

```
POST /api/fe-generator/generate
Content-Type: application/json
```

### Request body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `commitSha` | `string` | at least one | SHA of a backend commit to base generation on |
| `prompt` | `string` | at least one | Free-text instructions or natural-language description |

Both fields can be provided at the same time — the diff supplies structural context while
the prompt adds extra instructions on top.

### Usage scenarios

**1 — Commit SHA only** (equivalent to the webhook trigger, but synchronous):
```bash
curl -X POST http://localhost:8080/api/fe-generator/generate \
  -H "Content-Type: application/json" \
  -d '{ "commitSha": "abc12345def67890" }'
```

**2 — Free-text prompt only** (no real commit needed):
```bash
curl -X POST http://localhost:8080/api/fe-generator/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Add a POST /api/orders endpoint that accepts OrderRequest { itemId, quantity } and returns OrderResponse { orderId, status }. Generate the TypeScript types and RTK Query slice."
  }'
```

**3 — Both together** (diff as context + extra instruction):
```bash
curl -X POST http://localhost:8080/api/fe-generator/generate \
  -H "Content-Type: application/json" \
  -d '{
    "commitSha": "abc12345",
    "prompt": "Also add a loading skeleton component for the user list page."
  }'
```

### Response — `SUCCESS`

```json
HTTP 200 OK
{
  "status": "SUCCESS",
  "summary": "Created UserDto type and RTK Query user API slice.",
  "pullRequestUrl": "https://github.com/my-org/my-react-app/pull/42",
  "branchName": "ai/fe-sync-abc12345",
  "filesChanged": 2,
  "files": [
    {
      "operation": "CREATE",
      "path": "src/api/types/user.types.ts",
      "content": "export interface UserDto { ... }"
    },
    {
      "operation": "CREATE",
      "path": "src/api/services/user.api.ts",
      "content": "import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'; ..."
    }
  ]
}
```

### Response — `NO_CHANGES`

```json
HTTP 200 OK
{
  "status": "NO_CHANGES",
  "summary": "The backend change only affected test files. No frontend code needs updating.",
  "filesChanged": 0,
  "files": []
}
```

### Response — validation error

```json
HTTP 400 Bad Request
{
  "status": "ERROR",
  "summary": "At least one of 'commitSha' or 'prompt' must be provided.",
  "filesChanged": 0,
  "files": []
}
```

### Key differences from the webhook route

| | GitHub Webhook | On-Demand API |
|-|----------------|--------------|
| Trigger | Automatic on push / PR merge | Manual — called by you |
| Execution | **Async** (fire-and-forget, HTTP 202) | **Sync** (waits for result, HTTP 200) |
| Input | Commit SHA from GitHub event | Commit SHA + / or free-text prompt |
| Branch name | `ai/fe-sync-<sha>` | `ai/fe-sync-<sha>` or `ai/fe-on-demand-<ts>` |
| Response | No body | Full JSON result including PR URL and file list |

---

## 7. Configuration Reference

All properties live under the prefix `ai.fe-generator`.

| Property | Env variable | Default | Description |
|----------|-------------|---------|-------------|
| `enabled` | — | `true` | Set to `false` to disable the entire starter |
| `github-token` | `AI_FE_GENERATOR_GITHUB_TOKEN` | _(required)_ | GitHub PAT with Contents + PR write access |
| `webhook-secret` | `AI_FE_GENERATOR_WEBHOOK_SECRET` | _(required)_ | Shared secret for HMAC-SHA256 webhook signature verification |
| `backend-repo-name` | `AI_FE_GENERATOR_BACKEND_REPO` | `my-org/my-backend` | Full name of the backend GitHub repository |
| `backend-default-branch` | `AI_FE_GENERATOR_BACKEND_BRANCH` | `main` | Only events targeting this branch trigger the pipeline |
| `frontend-repo-name` | `AI_FE_GENERATOR_FRONTEND_REPO` | `my-org/my-react-app` | Full name of the frontend GitHub repository |
| `frontend-source-path` | `AI_FE_GENERATOR_FRONTEND_PATH` | `src/api` | Root directory for generated TypeScript files |
| `ai-system-prompt` | — | _(see below)_ | System instruction injected into every AI prompt |

**Spring AI / OpenAI properties:**

| Property | Env variable | Default | Description |
|----------|-------------|---------|-------------|
| `spring.ai.openai.api-key` | `SPRING_AI_OPENAI_API_KEY` | _(required)_ | OpenAI API key |
| `spring.ai.openai.chat.options.model` | `OPENAI_MODEL` | `gpt-4o` | LLM model name |
| `spring.ai.openai.chat.options.temperature` | — | `0.2` | Lower = more deterministic output |

---

## 8. AI Generation Logic

### Prompt Structure

Every AI call is built from three layers:

```
┌──────────────────────────────────────────────────────────────┐
│  SYSTEM PROMPT                                               │
│  ─────────────────────────────────────────────────────────   │
│  ai-system-prompt property value                             │
│  + .ai-fe-template.txt content (if present)                  │
│  + tool usage guidance (fetchFrontendFile instructions)      │
│  + generation rules (complete files, relative paths, etc.)   │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│  USER PROMPT                                                 │
│  ─────────────────────────────────────────────────────────   │
│  Backend commit diff (full patch text)                       │
│  + JSON schema of AiFrontendResponse (injected by            │
│    BeanOutputConverter — forces the model to return          │
│    valid, parseable JSON)                                    │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│  TOOLS (available to the model during generation)            │
│  ─────────────────────────────────────────────────────────   │
│  fetchFrontendFile(filePath: String): String                 │
│    → calls GitHub API to read an existing FE file            │
│    → returns file content, or FILE_NOT_FOUND message         │
└──────────────────────────────────────────────────────────────┘
```

### Agentic Loop

The model does not have to answer in a single pass. A typical generation sequence looks like:

```
1. [LLM] reads BE diff → "UserController was added with CRUD endpoints"
2. [LLM] thinks: "I should check if src/api/types/user.types.ts exists"
3. [LLM] calls tool: fetchFrontendFile("src/api/types/user.types.ts")
4. [Spring AI] executes the Java method → reads file from GitHub → returns content
5. [LLM] incorporates existing content → generates updated version preserving existing code
6. [LLM] thinks: "I should also check the user API slice"
7. [LLM] calls tool: fetchFrontendFile("src/api/services/user.api.ts")
8. [Spring AI] executes → returns FILE_NOT_FOUND
9. [LLM] generates a new CREATE operation for that file
10.[LLM] returns final JSON response
```

### Structured JSON Output

The AI is required to return a single JSON object matching this schema:

```json
{
  "summary": "Added User types and RTK Query API slice based on the new UserController.",
  "files": [
    {
      "operation": "CREATE",
      "path": "src/api/types/user.types.ts",
      "content": "export interface UserDto {\n  id: number;\n  name: string;\n  email: string;\n}\n"
    },
    {
      "operation": "UPDATE",
      "path": "src/api/services/user.api.ts",
      "content": "import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';\n..."
    }
  ]
}
```

**Operation types:**

| Operation | GitHub API action |
|-----------|------------------|
| `CREATE` | Create new file at the specified path |
| `UPDATE` | Overwrite existing file with the complete new content |
| `DELETE` | Remove the file |

When the AI determines that **no frontend changes are needed** (e.g. the backend commit only changed a `README` or a deployment script), it returns an empty `files` array and a descriptive `summary`. The starter logs this and exits without touching the frontend repository.

---

## 9. Generated Pull Request Format

Each successful pipeline run creates a Pull Request in the frontend repository with:

- **Branch name:** `ai/fe-sync-<first-8-chars-of-commit-sha>`  
  e.g. `ai/fe-sync-abc12345`

- **PR title:** `AI FE Sync – abc12345`

- **PR body:**
  ```markdown
  ## 🤖 AI-Generated Frontend Sync

  **Triggered by backend commit:** `abc12345def67890...`

  **Summary:** Added User types and RTK Query API slice based on the new UserController.

  ### Changed files

  | Operation | Path |
  |-----------|------|
  | `CREATE`  | `src/api/types/user.types.ts` |
  | `UPDATE`  | `src/api/services/user.api.ts` |

  ---
  _Generated by ai-be-to-fe-spring-boot-starter. Please review carefully before merging._
  ```

The PR is always opened as a **draft-ready** PR targeting the frontend repo's default branch.
The frontend team reviews, adjusts if needed, and merges.

---

## 10. Disabling the Starter

**Disable completely** (no beans created, no endpoint registered):
```yaml
ai:
  fe-generator:
    enabled: false
```

**Disable per-environment** using Spring profiles:
```yaml
# application-prod.yml
ai:
  fe-generator:
    enabled: true

# application-dev.yml
ai:
  fe-generator:
    enabled: false
```

**Override individual beans** — because all beans are declared with `@ConditionalOnMissingBean`,
you can replace any component by declaring your own bean of the same type:

```java
@Bean
public AiCodeGeneratorService aiCodeGeneratorService(...) {
    // your custom implementation
}
```

---

## 11. Troubleshooting

### Webhook returns `401 Unauthorized`

The HMAC signature does not match. Common causes:

- `AI_FE_GENERATOR_WEBHOOK_SECRET` does not match the secret set in GitHub webhook settings
- The raw request body was modified in transit (e.g. by a reverse proxy re-encoding JSON)
- Make sure `Content-Type: application/json` is set in the webhook settings (not `application/x-www-form-urlencoded`)

### Webhook returns `200` but pipeline never runs

- Check that the branch name in `X-GitHub-Event: push` matches `backend-default-branch`  
- Check that the PR event has `action: closed` and `merged: true`  
- Check application logs for `"Ignoring unsupported event type"` or `"Push to '...' ignored"` messages

### AI returns an empty `files` array unexpectedly

- The model correctly determined no FE changes are needed — check the `summary` field in the logs
- If this is incorrect, improve the `.ai-fe-template.txt` with more context about your FE architecture
- Consider lowering the model temperature further or switching to a more capable model

### GitHub API errors on branch creation

- Ensure the PAT has **Contents: Write** permission on the frontend repository
- Ensure the branch `ai/fe-sync-<sha>` does not already exist (a previous pipeline run for the same commit may have been interrupted)

### `IllegalStateException: Failed to initialise GitHub client`

- `AI_FE_GENERATOR_GITHUB_TOKEN` is empty or not loaded from the environment
- Verify with: `echo $AI_FE_GENERATOR_GITHUB_TOKEN`

### AI output contains a Java parse error

- The model returned malformed JSON or non-JSON text. Check `DEBUG` logs for the raw AI response.
- Try a more capable model (e.g. `gpt-4o` instead of `gpt-4o-mini`)
- Ensure `temperature` is set to `0.2` or lower

---

## License

MIT

